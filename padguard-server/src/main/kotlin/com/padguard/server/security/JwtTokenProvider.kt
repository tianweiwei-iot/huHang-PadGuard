package com.padguard.server.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @Value("\${padguard.jwt.secret:}") private val configuredSecret: String,
    @Value("\${padguard.jwt.access-ttl-minutes:120}") private val accessTtlMinutes: Long,
    @Value("\${padguard.jwt.refresh-ttl-days:30}") private val refreshTtlDays: Long
) {

    private val log = LoggerFactory.getLogger(JwtTokenProvider::class.java)

    /**
     * 密钥来源优先级：
     * 1. 配置显式给出（含环境变量注入）→ 直接用，满足运维统一管理的需求；
     * 2. 否则读本地 `config/jwt-secret.txt`；没有就**随机生成并写入**。
     *
     * 第 2 条是必须的：密钥原先写死在 application.yml 里并随安装包分发，
     * 任何人拿到安装包都能自己签发 token，冒充任意家长登录。
     * 现在每次部署的密钥都不同，且不进安装包。
     */
    private val secret: String = resolveSecret()

    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))

    private fun resolveSecret(): String {
        val configured = configuredSecret.trim()
        if (configured.isNotBlank()) return configured
        return loadOrCreateSecret()
    }

    private fun loadOrCreateSecret(): String {
        val file = File(SECRET_FILE)
        val existing = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()
        if (!existing.isNullOrBlank() && existing.length >= 32) return existing

        val generated = ByteArray(32).let { bytes ->
            SecureRandom().nextBytes(bytes)
            Base64.getEncoder().encodeToString(bytes)
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(generated, Charsets.UTF_8)
        }.onFailure { log.error("写入 JWT 密钥文件失败: {}", it.message) }
        log.warn(
            "padguard.jwt.secret 未配置，已生成随机密钥并写入 {}；" +
                "该文件丢失会导致已登录用户需要重新登录，请勿删除。",
            file.absolutePath
        )
        return generated
    }

    fun createAccessToken(userId: String): String = Jwts.builder()
        .subject(userId)
        .claim("typ", "access")
        .issuedAt(java.util.Date())
        .expiration(java.util.Date(System.currentTimeMillis() + accessTtlMinutes * 60_000))
        .signWith(key)
        .compact()

    fun createRefreshToken(userId: String): String = Jwts.builder()
        .subject(userId)
        .claim("typ", "refresh")
        .issuedAt(java.util.Date())
        .expiration(java.util.Date(System.currentTimeMillis() + refreshTtlDays * 86_400_000))
        .signWith(key)
        .compact()

    /**
     * 按令牌类型解析。三类令牌（家长 access / refresh、管理员）用同一密钥签发，
     * 仅靠 typ 声明区分 —— 此前 parseUserId 只验签不验 typ，导致：
     * 30 天的 refresh token 可当 access token 调全部业务接口；
     * access token 可被拿去无限续期；管理员令牌可通过家长 WS 握手。
     * 现在调用方必须声明自己期望的令牌类型，类型不符一律拒绝。
     */
    fun parseAccessToken(token: String): String = parseWithTyp(token, "access")

    fun parseRefreshToken(token: String): String = parseWithTyp(token, "refresh")

    fun parseAdminToken(token: String): String = parseWithTyp(token, "admin_access")

    private fun parseWithTyp(token: String, expectedTyp: String): String {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        val typ = claims["typ"] as? String
        if (typ != expectedTyp) {
            throw io.jsonwebtoken.JwtException("token typ mismatch: expected=$expectedTyp actual=$typ")
        }
        return claims.subject
    }

    /**
     * 签发管理员令牌。与家长令牌用同一密钥，但带 `scope=admin` 声明，
     * 由 [com.padguard.server.security.AdminAuthInterceptor] 区分两类令牌，
     * 家长令牌即便被拿到也无法通过 admin 端点的鉴权（scope 不符）。
     */
    fun createAdminToken(adminId: String): String = Jwts.builder()
        .subject(adminId)
        .claim("typ", "admin_access")
        .claim("scope", "admin")
        .issuedAt(java.util.Date())
        .expiration(java.util.Date(System.currentTimeMillis() + accessTtlMinutes * 60_000))
        .signWith(key)
        .compact()

    /** 判定令牌是否为管理员令牌（scope=admin）；解析失败一律返回 false */
    fun isAdminToken(token: String): Boolean = runCatching {
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload["scope"] == "admin"
    }.getOrDefault(false)

    private companion object {
        /** 相对应用工作目录；不进安装包，每次部署各不相同 */
        const val SECRET_FILE = "config/jwt-secret.txt"
    }
}
