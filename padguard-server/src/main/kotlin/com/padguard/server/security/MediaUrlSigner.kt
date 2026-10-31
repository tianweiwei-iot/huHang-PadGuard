package com.padguard.server.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 敏感媒体 URL 签名（第八轮评估第三批 P1-2）。
 *
 * ## 为什么需要
 * `/v1/files/{id}` 是"能力 URL"：fileId 为随机 128 位不可穷举，安全性依赖其保密性。
 * 但 URL 天然会泄漏——出现在服务端访问日志、反代日志、客户端分享/转发、错误上报里。
 * 一旦泄漏，**截图/录屏/录音这类最敏感的个人数据将永久可被任何匿名者下载**，
 * 能力 URL 没有 TTL，无法止损。
 *
 * ## 机制
 * 服务端下发文件 URL 时统一附带 `?exp=<过期毫秒>&sig=HMAC_SHA256(secret, "file|{id}|{exp}")`：
 * - 带 sig 的请求：验签 fail-closed（参数缺失/格式错/过期/篡改一律 403）；
 * - 有效签名 = 授权凭证本身，泄漏的 URL 在 TTL 过期后自动失效，日志里的历史 URL 无法重放。
 *
 * ## 兼容性
 * 旧的未签名 URL 不受影响（按原归属校验逻辑走）；强制匿名签名由
 * `padguard.media.require-signature` 闸门控制（默认关），待客户端全面升级后开启，
 * 与短信登录闸门同一治理思路。
 *
 * ## 为什么与 JWT 共用密钥
 * JWT 密钥已有"配置注入 → 本地文件随机生成持久化"的完整生命周期（见 [JwtTokenProvider]），
 * 复用避免再维护一份密钥文件；签名域分离（"file|" 前缀）保证两处签名不可互换。
 */
@Component
class MediaUrlSigner(
    private val jwt: JwtTokenProvider,
    @Value("\${padguard.media.url-ttl-hours:168}") private val urlTtlHours: Long
) {

    private val log = LoggerFactory.getLogger(MediaUrlSigner::class.java)

    /** 给 /v1/files/{id} 形态的 URL 附加短期签名；非文件 URL 原样返回。 */
    fun sign(url: String, ttlMillis: Long = urlTtlHours * 3_600_000L): String {
        val id = fileIdOf(url) ?: return url
        val exp = System.currentTimeMillis() + ttlMillis
        val sep = if (url.contains('?')) "&" else "?"
        return "$url${sep}exp=$exp&sig=${hmac(id, exp)}"
    }

    /**
     * 校验签名。任何一点不满足即 false（fail-closed）：
     * 参数缺失、格式非法、已过期、签名不匹配。
     * 比较用 [MessageDigest.isEqual] 常数时间比较，防时序侧信道逐字节爆破。
     */
    fun verify(fileId: String, exp: String?, sig: String?, now: Long = System.currentTimeMillis()): Boolean {
        val expMs = exp?.toLongOrNull() ?: return false
        if (expMs < now) {
            log.info("media url expired: fileId={} exp={}", fileId, Instant.ofEpochMilli(expMs))
            return false
        }
        val expected = hmac(fileId, expMs)
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), (sig ?: "").toByteArray(Charsets.UTF_8))
    }

    private fun hmac(fileId: String, expMs: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(jwt.signingSecret().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("file|$fileId|$expMs".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** 从 URL 末段取 fileId（容忍已带查询串的输入）。非文件 URL 返回 null。 */
    private fun fileIdOf(url: String): String? {
        val marker = "/v1/files/"
        val i = url.lastIndexOf(marker)
        if (i < 0) return null
        val tail = url.substring(i + marker.length).substringBefore('?')
        return tail.takeIf { it.isNotBlank() }
    }
}
