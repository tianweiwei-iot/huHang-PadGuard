package com.padguard.server.security

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

private val encoder = BCryptPasswordEncoder()

object Passwords {
    fun encode(raw: String): String = encoder.encode(raw)
    fun matches(raw: String, hash: String): Boolean = encoder.matches(raw, hash)
}
