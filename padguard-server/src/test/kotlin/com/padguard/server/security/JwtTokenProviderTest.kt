package com.padguard.server.security

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Date

/**
 * [JwtTokenProvider] 的令牌类型隔离与吊销声明。
 *
 * 三类令牌（家长 access / refresh、管理员）共用同一把密钥，仅靠 `typ` 声明区分。
 * 这里锁死的是「refresh 可当 access 用」「管理员令牌能过家长 WS 握手」这类
 * 一旦验签函数被改回「只验签不验 typ」就会立刻复发的安全边界。
 *
 * 所有用例都显式传入 32 字节密钥，绕过 `config/jwt-secret.txt` 的读写分支，
 * 不产生任何文件副作用，也不加载 Spring 上下文。
 */
class JwtTokenProviderTest {

    private companion object {
        const val SECRET = "0123456789abcdef0123456789abcdef"
        const val OTHER_SECRET = "fedcba9876543210fedcba9876543210"
    }

    private fun provider(accessMinutes: Long = 120L) = JwtTokenProvider(SECRET, accessMinutes, 30L)

    /** 模拟历史令牌：没有 ver / jti 声明，验证回落口径与库内默认 0 一致。 */
    private fun legacyToken(typ: String, scope: String? = null): String {
        val key = Keys.hmacShaKeyFor(SECRET.toByteArray(Charsets.UTF_8))
        var builder = Jwts.builder()
            .subject("U-legacy")
            .claim("typ", typ)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 60_000))
        if (scope != null) builder = builder.claim("scope", scope)
        return builder.signWith(key).compact()
    }

    @Test
    fun `signingSecret 返回显式配置的密钥且不读文件`() {
        val p = provider()
        assertEquals(SECRET, p.signingSecret())
        assertEquals(SECRET, p.signingSecret())
    }

    @Test
    fun `家长 access token 往返解析并带回 ver 声明`() {
        val p = provider()
        val token = p.createAccessToken("U1", 7L)
        assertEquals("U1", p.parseAccessToken(token))
        val payload = p.parseUserToken(token, "access")
        assertEquals("U1", payload.userId)
        assertEquals(7L, payload.version)
        assertEquals(null, payload.jti)
    }

    @Test
    fun `refresh token 携带自定义 jti 与版本号`() {
        val p = provider()
        val token = p.createRefreshToken("U1", 5L, "JTI-1")
        val payload = p.parseUserToken(token, "refresh")
        assertEquals(5L, payload.version)
        assertEquals("JTI-1", payload.jti)
        assertEquals("U1", p.parseRefreshToken(token))
    }

    @Test
    fun `refresh token 不能当 access token 使用`() {
        val p = provider()
        val refresh = p.createRefreshToken("U1", 0L)
        assertThrows(JwtException::class.java) { p.parseAccessToken(refresh) }
    }

    @Test
    fun `access token 不能被拿去续期`() {
        val p = provider()
        val access = p.createAccessToken("U1", 0L)
        assertThrows(JwtException::class.java) { p.parseRefreshToken(access) }
    }

    @Test
    fun `管理员令牌不能通过家长 access 解析`() {
        val p = provider()
        val admin = p.createAdminToken("A1", 3L)
        assertThrows(JwtException::class.java) { p.parseAccessToken(admin) }
        assertThrows(JwtException::class.java) { p.parseUserToken(admin, "access") }
    }

    @Test
    fun `家长令牌不能被解析为管理员令牌`() {
        val p = provider()
        val access = p.createAccessToken("U1", 0L)
        assertThrows(JwtException::class.java) { p.parseAdminToken(access) }
    }

    @Test
    fun `parseAdminToken 返回 adminId 与 ver`() {
        val p = provider()
        val payload = p.parseAdminToken(p.createAdminToken("A1", 9L))
        assertEquals("A1", payload.adminId)
        assertEquals(9L, payload.version)
    }

    @Test
    fun `isAdminToken 只对带 admin scope 的令牌为真`() {
        val p = provider()
        assertTrue(p.isAdminToken(p.createAdminToken("A1", 0L)))
        assertFalse(p.isAdminToken(p.createAccessToken("U1", 0L)))
        assertFalse(p.isAdminToken(p.createRefreshToken("U1", 0L)))
        assertFalse(p.isAdminToken("not-a-jwt"))
    }

    @Test
    fun `历史令牌缺少 ver 声明时回落为 0`() {
        val p = provider()
        val payload = p.parseUserToken(legacyToken("access"), "access")
        assertEquals(0L, payload.version)
        assertEquals(null, payload.jti)
        assertEquals(0L, p.parseAdminToken(legacyToken("admin_access", "admin")).version)
    }

    @Test
    fun `篡改签名后验签失败`() {
        val p = provider()
        val token = p.createAccessToken("U1", 0L)
        // JWT 形如 header.payload.signature；signature 是 base64url 编码的 32 字节 HMAC，
        // 43 个字符里只有最后一个字符含填充位（仅 4 位有效）。若改最后一位，可能只改到
        // 填充位 → 解码出的签名字节不变 → 验签仍通过，测试随机红/绿（已实测 3 跑 1 红）。
        // 改 signature 段的首字符：它承载完整 6 位有效载荷，改后签名字节必然不同，验签必失败。
        val parts = token.split(".")
        assertEquals(3, parts.size)
        val sig = parts[2]
        assertTrue(sig.isNotEmpty())
        val mutatedSig = (if (sig.first() == 'A') 'B' else 'A') + sig.drop(1)
        val tampered = "${parts[0]}.${parts[1]}.$mutatedSig"
        assertNotEquals(token, tampered)
        assertThrows(JwtException::class.java) { p.parseAccessToken(tampered) }
        assertFalse(p.isAdminToken(tampered))
    }

    @Test
    fun `另一把密钥签发的令牌不被接受`() {
        val other = JwtTokenProvider(OTHER_SECRET, 120L, 30L)
        val token = other.createAccessToken("U1", 0L)
        assertThrows(JwtException::class.java) { provider().parseAccessToken(token) }
    }

    @Test
    fun `过期 access token 解析抛 JwtException`() {
        val p = JwtTokenProvider(SECRET, -1L, 30L)
        val token = p.createAccessToken("U1", 0L)
        assertThrows(JwtException::class.java) { p.parseAccessToken(token) }
    }

    @Test
    fun `同一用户不同版本号的令牌 ver 相互独立`() {
        val p = provider()
        assertEquals(1L, p.parseUserToken(p.createAccessToken("U1", 1L), "access").version)
        assertEquals(2L, p.parseUserToken(p.createAccessToken("U1", 2L), "access").version)
        assertEquals(1L, p.parseUserToken(p.createRefreshToken("U1", 1L), "refresh").version)
    }
}
