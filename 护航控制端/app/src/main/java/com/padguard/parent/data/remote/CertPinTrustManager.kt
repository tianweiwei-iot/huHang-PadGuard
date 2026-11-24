package com.padguard.parent.data.remote

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/**
 * 证书固定（Certificate Pinning），阻断「导入自定义 CA 的主动中间人」（Fiddler / Charles 可解密观测流量）。
 *
 * 家长端同样连同一个服务端（HTTPS / WSS），此前只靠 [EndpointSafety] 在 URL 层面强制公网必须 HTTPS，
 * 但**链路层**仍未固定：具备自定义 CA 的主动 MITM 虽无法伪造指令（HMAC 保完整性），却可观测明文行为画像。
 * 本类把「对端证书」也锁死，使主动 MITM 直接握手失败、断链。与孩子在端 P0-1 同源、同口径。
 *
 * ## 为什么用 TOFU 而非静态 pin
 *
 * 家长端地址是**运行时可改**的（登录页「⚙ 高级设置」、LAN 私有化部署），无法在构建期预知对端证书，
 * 因此默认走 TOFU（Trust-On-First-Use）：首次成功握手（绑定发生在家长可控的可信网络）的证书公钥被记下；
 * 之后若证书更换但**签发者(DN)不变**（= 证书正常轮换）则自动重新记录；若签发者变了（= 可能的中间人）则拒绝连接。
 * 运维也可用 `-PcertPins=sha256/xxx` 注入生产证书 SPKI 哈希，切换到**严格静态固定**（最稳）。
 * pin 落盘于 `filesDir/tofu_pins.json`：应用更新 / 重启后仍在；清数据 / 卸载后失效（属预期）。
 *
 * ## 与 EndpointSafety 的分工
 *
 * [EndpointSafety] 管「能不能连这个地址」（公网必须 https，否则拒绝）；本类管「连上后对方是不是我信的那张证书」。
 * 两者互补，不互相替代。无论哪一层都**先过系统 CA 校验**——随机 / 过期 / 自签证书先被拒，
 * 只把「合法证书被替换」这一类交给 pin 兜底，因此不会因固定逻辑误伤正常公网证书，
 * 也不会破坏 LAN 内网的 http（明文）链路（TLS 不参与，本 TrustManager 不触发）。
 */
class CertPinTrustManager(
    private val staticPins: Set<String>,
    private val tofuFile: File
) : X509TrustManager {

    private val systemTm: X509TrustManager
    private val tofu = loadTofu(tofuFile)

    init {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        systemTm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.getAcceptedIssuers()

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        systemTm.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        // 1) 先过系统 CA：只接受真实有效的证书链，把无效证书挡在 pin 之外。
        systemTm.checkServerTrusted(chain, authType)

        // 2) 再做 pin 校验。
        val leaf = chain[0]
        val spki = spkiSha256(leaf)
        val issuer = leaf.issuerX500Principal?.name ?: ""

        if (staticPins.isNotEmpty()) {
            if (!staticPins.contains("sha256/$spki")) {
                throw CertificateException(
                    "certificate pin mismatch (static); none of ${staticPins.size} pinned certs matched"
                )
            }
            return
        }

        // 3) TOFU：首次信任 + 同签发者轮换豁免。
        synchronized(tofu) {
            val prev = tofu[issuer]
            when {
                prev == null && tofu.isEmpty() -> {
                    // 首次建立信任：记下该签发者维度的公钥。
                    tofu[issuer] = spki
                    saveTofu()
                    Log.i(TAG, "tofu: first trust captured for issuer=$issuer")
                }
                prev == null -> {
                    // 已信任过部分签发者，却出现陌生签发者 → 疑似中间人，拒绝。
                    throw CertificateException("tofu: unknown issuer=$issuer (possible MITM)")
                }
                prev != spki -> {
                    // 同签发者、公钥变了 = 证书轮换，重新记录（不阻断连接）。
                    tofu[issuer] = spki
                    saveTofu()
                    Log.i(TAG, "tofu: cert rotated for issuer=$issuer, re-pinned")
                }
                // prev == spki：完全一致，放行。
            }
        }
    }

    private fun spkiSha256(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun loadTofu(file: File): MutableMap<String, String> {
        if (!file.isFile) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(file.readText())
            obj.keys().asSequence().associateTo(mutableMapOf()) { it to obj.getString(it) }
        }.getOrDefault(mutableMapOf())
    }

    private fun saveTofu() {
        runCatching {
            val obj = JSONObject()
            tofu.forEach { (k, v) -> obj.put(k, v) }
            tofuFile.writeText(obj.toString())
        }.onFailure { Log.w(TAG, "tofu save failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "CertPin"

        fun parsePins(raw: String): Set<String> =
            raw.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()

        /** 构造「系统校验 + 固定」的 SSLContext，供 OkHttp / WebSocket 共用。 */
        fun sslContext(context: Context, staticPins: Set<String>): SSLContext {
            val tm = CertPinTrustManager(staticPins, File(context.filesDir, "tofu_pins.json"))
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            return ctx
        }

        /** OkHttp 要求同时传入 SocketFactory 与 TrustManager，故成对返回。 */
        fun forOkHttp(
            context: Context,
            staticPins: Set<String>
        ): Pair<javax.net.ssl.SSLSocketFactory, X509TrustManager> {
            val tm = CertPinTrustManager(staticPins, File(context.filesDir, "tofu_pins.json"))
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            return ctx.socketFactory to tm
        }
    }
}
