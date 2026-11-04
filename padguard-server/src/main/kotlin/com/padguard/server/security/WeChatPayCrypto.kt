package com.padguard.server.security

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 微信支付 v3 密码学工具（无外部依赖，纯 JDK）。
 *
 * 三类能力：
 * 1. [decodeApiV3Key]：把配置的 32 字节 APIv3 密钥统一成字节数组；
 * 2. [verifySignature] / [signRSA]：回调验签 / 请求签名，均为 SHA256withRSA；
 * 3. [decryptResource]：用 APIv3 密钥 AES-256-GCM 解密回调 resource（与下单回包加密一致）。
 *
 * 注意微信回调的 resource.nonce / ciphertext 都是 **Base64 字符串**，nonce 必须先 Base64 解码成 12 字节 IV，
 * 切勿直接拿 nonce 字符串的 UTF-8 字节当 IV（这是最常见的对接坑）。
 */
object WeChatPayCrypto {

    private const val GCM_TAG_BITS = 128

    /** 把 APIv3 密钥解析为 32 字节；支持 Base64 / 十六进制 / 32 位明文字符串三种写法 */
    fun decodeApiV3Key(raw: String): ByteArray {
        val s = raw.trim()
        return when {
            s.length == 44 -> Base64.getDecoder().decode(s)
            s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } ->
                s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            s.length == 32 -> s.toByteArray(StandardCharsets.ISO_8859_1)
            else -> throw IllegalArgumentException("apiV3Key 格式不支持（需 32 字节：Base64/HEX/原文）")
        }
    }

    /** RSA-SHA256 验签：用微信平台证书公钥验证回调签名 */
    fun verifySignature(publicKey: PublicKey, message: String, signatureBase64: String): Boolean =
        runCatching {
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(publicKey)
            sig.update(message.toByteArray(StandardCharsets.UTF_8))
            sig.verify(Base64.getDecoder().decode(signatureBase64))
        }.getOrDefault(false)

    /** RSA-SHA256 签名：用商户私钥对请求/二次签名串签名，返回 Base64 */
    fun signRSA(privateKey: PrivateKey, message: String): String {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(message.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    /**
     * AES-256-GCM 解密微信 resource。
     * @param ciphertextBase64 resource.ciphertext（Base64）
     * @param nonceBase64 resource.nonce（Base64，解码为 12 字节 IV）
     * @param associatedData resource.associated_data（明文，可为空）
     */
    fun decryptResource(apiV3Key: ByteArray, ciphertextBase64: String, nonceBase64: String, associatedData: String?): String {
        val iv = Base64.getDecoder().decode(nonceBase64)
        val ciphertext = Base64.getDecoder().decode(ciphertextBase64)
        require(iv.size == 12) { "nonce 必须解码为 12 字节 IV，实际 ${iv.size}" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(apiV3Key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val aad = associatedData?.takeIf { it.isNotBlank() }?.toByteArray(StandardCharsets.UTF_8)
        if (aad != null) cipher.updateAAD(aad)
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    /** 解析 X.509 证书（BEGIN CERTIFICATE）为 [X509Certificate] */
    fun loadCertificates(pemText: String): List<X509Certificate> = runCatching {
        val cf = CertificateFactory.getInstance("X.509")
        cf.generateCertificates(ByteArrayInputStream(pemToDer(pemText))).map { it as X509Certificate }
    }.getOrDefault(emptyList())

    /** 解析商户 API 私钥（BEGIN PRIVATE KEY，PKCS#8） */
    fun loadPrivateKey(pemText: String): PrivateKey {
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePrivate(PKCS8EncodedKeySpec(pemToDer(pemText)))
    }

    /**
     * 构造微信支付 v3 请求/回调所需的 Authorization 头值。
     * 签名串：`$method\n$path\n$timestamp\n$nonce\n$body\n`（body 为空时末尾仍是 $body\n 即两个换行）。
     */
    fun buildAuthorization(
        method: String, path: String, body: String,
        mchId: String, serialNo: String, privateKey: PrivateKey
    ): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val message = "$method\n$path\n$timestamp\n$nonce\n$body\n"
        val signature = signRSA(privateKey, message)
        return """WECHATPAY2-SHA256-RSA2048 mchid="$mchId",nonce_str="$nonce",signature="$signature",timestamp="$timestamp",serial_no="$serialNo""""
    }

    /** 按序列号（十六进制）在已加载证书中查找 */
    fun findCertBySerial(certs: List<X509Certificate>, serial: String): X509Certificate? {
        val target = runCatching { BigInteger(serial, 16) }.getOrNull() ?: return null
        return certs.firstOrNull { it.serialNumber == target }
    }

    /** 拉取并解密微信平台证书（/v3/certificates），返回解密后的证书文本列表（PEM） */
    fun downloadPlatformCertificates(
        baseUrl: String, mchId: String, serialNo: String, privateKey: PrivateKey, apiV3Key: ByteArray
    ): List<String> = runCatching {
        val http = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build()
        val path = "/v3/certificates"
        val auth = buildAuthorization("GET", path, "", mchId, serialNo, privateKey)
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            // 与 WeChatPayClient 同口径：不设超时等于无限等待，证书拉取挂住会拖死整个验签初始化。
            .timeout(java.time.Duration.ofSeconds(15))
            .header("Authorization", auth)
            .header("Accept", "application/json")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) return emptyList()
        val node = ObjectMapper().readTree(resp.body())
        val out = mutableListOf<String>()
        node["data"]?.forEach { item ->
            val enc = item["encrypt_certificate"] ?: return@forEach
            val pem = decryptResource(apiV3Key, enc["ciphertext"].asText(), enc["nonce"].asText(), enc["associated_data"]?.asText())
            out.add(pem)
        }
        out
    }.getOrDefault(emptyList())

    /** 去掉 PEM 头尾与空白，得到 DER 的 Base64 */
    private fun pemToDer(pem: String): ByteArray {
        val b64 = pem
            .replace("-----BEGIN[A-Z ]*-----".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("-----END[A-Z ]*-----".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("\\s".toRegex(), "")
        return Base64.getDecoder().decode(b64)
    }
}
