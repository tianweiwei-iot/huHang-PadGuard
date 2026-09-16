package com.padguard.server.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @Value("\${padguard.jwt.secret}") private val secret: String,
    @Value("\${padguard.jwt.access-ttl-minutes:120}") private val accessTtlMinutes: Long,
    @Value("\${padguard.jwt.refresh-ttl-days:30}") private val refreshTtlDays: Long
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))

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

    fun parseUserId(token: String): String =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload.subject

    fun isValid(token: String): Boolean = runCatching { parseUserId(token) }.isSuccess
}
