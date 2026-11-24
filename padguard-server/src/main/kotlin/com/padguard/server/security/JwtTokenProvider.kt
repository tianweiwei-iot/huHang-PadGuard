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

    /**
     * 暴露给其他签名场景（如媒体 URL 签名）的底层密钥。
     * 与 JWT 共用同一密钥文件：密钥的生成/持久化/丢失告警逻辑已在 [loadOrCreateSecret] 落实，不重复造轮子。
     */
    fun signingSecret(): String = secret

    private fun resolveSecret(): String {
        val configured = configuredSecret.trim()
        if (configured.isNotBlank()) return configured
        return loadOrCreateSecret()
    }

    private fun loadOrCreateSecret(): String {
        val file = File(SECRET_FILE)
        // 多副本竞态（R-P2-16）：未显式配置 secret 时，多个实例可能同时读到空文件、
        // 各自生成不同密钥，导致用户在一个副本登录后被另一个副本随机登出。
        // 用独立 `.lock` 文件的阻塞锁把"读-生成-落盘"串行化：先拿到锁的实例生成并落盘，
        // 后到的实例读到同一份文件内容，密钥保持一致。
        // 注：POSIX 建议锁只在同内核有效；跨主机 NFS 场景仍依赖部署方显式注入
        // `padguard.jwt.secret`（k8s secret / 环境变量）以彻底闭环，本处兜底同主机并发。
        val lockFile = File(file.parentFile ?: File("."), "${file.name}.lock")
        val lockChannel = runCatching {
            java.nio.channels.FileChannel.open(
                lockFile.toPath(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE
            )
        }.getOrNull()
        val lock = lockChannel?.let { runCatching { it.lock() }.getOrNull() }
        try {
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
                    "该文件丢失会导致已登录用户需要重新登录，请勿删除。" +
                    "多副本部署请显式注入 padguard.jwt.secret 以彻底避免跨实例密钥漂移。",
                file.absolutePath
            )
            return generated
        } finally {
            runCatching { lock?.release() }
            runCatching { lockChannel?.close() }
        }
    }

    /**
     * 签发家长 access token。
     *
     * [tokenVersion] 取自 `users.token_version`，写入 `ver` 声明。**必传**：
     * 默认值 0 会让未来漏传版本号的调用点静默签出 `ver=0` 令牌（版本闸门对它形同虚设），
     * 改为无默认值后漏传直接编译失败。内部工具/测试需签发时显式传 0 表达意图。
     */
    fun createAccessToken(userId: String, tokenVersion: Long): String = Jwts.builder()
        .subject(userId)
        .claim("typ", "access")
        .claim("ver", tokenVersion)
        .issuedAt(java.util.Date())
        .expiration(java.util.Date(System.currentTimeMillis() + accessTtlMinutes * 60_000))
        .signWith(key)
        .compact()

    fun createRefreshToken(userId: String, tokenVersion: Long, jti: String = java.util.UUID.randomUUID().toString()): String = Jwts.builder()
        .subject(userId)
        .claim("typ", "refresh")
        .claim("ver", tokenVersion)
        .claim("jti", jti)
        .issuedAt(java.util.Date())
        .expiration(java.util.Date(System.currentTimeMillis() + refreshTtlDays * 86_400_000))
        .signWith(key)
        .compact()

    /**
     * 按令牌类型解析。三类令牌（家长 access / refresh、管理员）用同一密钥签发，
     * 仅靠 typ 声明区分 —— 早期 parseUserId 只验签不验 typ，导致：
     * 30 天的 refresh token 可当 access token 调全部业务接口；
     * access token 可被拿去无限续期；管理员令牌可通过家长 WS 握手。
     * 现在调用方必须声明自己期望的令牌类型，类型不符一律拒绝。
     */
    fun parseAccessToken(token: String): String = parseWithTyp(token, "access")

    fun parseRefreshToken(token: String): String = parseWithTyp(token, "refresh")

    /**
     * 解析家长令牌并带回版本号，用于"登出即吊销"。
     *
     * 历史令牌没有 `ver` 声明 → 0，与 `users.token_version` 默认值一致；
     * 家长一旦登出，列值变 1，此前签发的全部令牌（含 refresh）立刻失效。
     */
    fun parseUserToken(token: String, expectedTyp: String): UserTokenPayload {
        val claims = parseClaimsWithTyp(token, expectedTyp)
        val version = (claims["ver"] as? Number)?.toLong() ?: 0L
        val jti = claims["jti"] as? String
        return UserTokenPayload(userId = claims.subject, version = version, jti = jti)
    }

    /** 家长令牌解析结果：[version] 与库内 `users.token_version` 比对以判定是否已吊销；[jti] 用于单会话吊销/轮换 */
    data class UserTokenPayload(val userId: String, val version: Long, val jti: String? = null)

    /**
     * 解析管理员令牌，返回身份与令牌版本号。
     *
     * [AdminTokenPayload.version] 取自签发时写入的 `ver` 声明；历史令牌没有该声明时回落 0，
     * 与 `admins.token_version` 的默认值一致 —— 这样新列上线不会把已登录的运营一次性踢下线。
     * 调用方（拦截器）负责拿它与库里的当前值比对，实现登出吊销。
     */
    fun parseAdminToken(token: String): AdminTokenPayload {
        val claims = parseClaimsWithTyp(token, "admin_access")
        val version = (claims["ver"] as? Number)?.toLong() ?: 0L
        return AdminTokenPayload(adminId = claims.subject, version = version)
    }

    private fun parseWithTyp(token: String, expectedTyp: String): String =
        parseClaimsWithTyp(token, expectedTyp).subject

    private fun parseClaimsWithTyp(token: String, expectedTyp: String): io.jsonwebtoken.Claims {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        val typ = claims["typ"] as? String
        if (typ != expectedTyp) {
            throw io.jsonwebtoken.JwtException("token typ mismatch: expected=$expectedTyp actual=$typ")
        }
        return claims
    }

    /**
     * 签发管理员令牌。与家长令牌用同一密钥，但带 `scope=admin` 声明，
     * 由 [com.padguard.server.security.AdminAuthInterceptor] 区分两类令牌，
     * 家长令牌即便被拿到也无法通过 admin 端点的鉴权（scope 不符）。
     *
     * [tokenVersion] 为管理员当前的令牌版本号（取自 `admins.token_version`），写入 `ver` 声明。
     * 登出 / 改密码时服务端把库内值 +1，持有旧令牌的请求会因版本号落后而被拒绝 ——
     * 这是 JWT 无状态下实现"主动吊销"的唯一可靠途径。
     */
    fun createAdminToken(adminId: String, tokenVersion: Long): String = Jwts.builder()
        .subject(adminId)
        .claim("typ", "admin_access")
        .claim("scope", "admin")
        .claim("ver", tokenVersion)
        .issuedAt(java.util.Date())
        .expiration(java.util.Date(System.currentTimeMillis() + accessTtlMinutes * 60_000))
        .signWith(key)
        .compact()

    /** 管理员令牌解析结果：[version] 用于与库内 `admins.token_version` 比对以实现吊销 */
    data class AdminTokenPayload(val adminId: String, val version: Long)

    /** 判定令牌是否为管理员令牌（scope=admin）；解析失败一律返回 false */
    fun isAdminToken(token: String): Boolean = runCatching {
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload["scope"] == "admin"
    }.getOrDefault(false)

    private companion object {
        /** 相对应用工作目录；不进安装包，每次部署各不相同 */
        const val SECRET_FILE = "config/jwt-secret.txt"
    }
}
