package com.padguard.server.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 操作密码闸门的服务端哈希工具。
 *
 * 必须与**孩子端** [com.padguard.child.guard.GuardPasswordManager.protocolHash] 完全一致：
 * `pbkdf2:<iter>:<base64salt>:<base64hash>`，使用 PBKDF2WithHmacSHA1（**加盐、慢哈希**）。
 *
 * ## 为什么升级（相对早期的无盐 SHA-256）
 * 无盐 SHA-256 密钥空间仅 10^4–10^6（4~6 位 PIN），离线可被 GPU 毫秒级爆破；
 * 改用 PBKDF2 加盐慢哈希后，每试一个 PIN 都要付出高迭代成本，离线爆破在经济上不可行。
 * 盐随哈希一并下发/存储，跨设备口径仍绝对一致（孩子端本地按同算法校验），
 * 不再有早期"无盐换取一致性"的取舍。
 */
object GuardPasswords {

    /** 最小长度，避免家长设出 123 这种弱口令（弱口令会让闸门形同虚设）。 */
    const val MIN_LENGTH = 4

    /** 计算协议哈希：PBKDF2WithHmacSHA1（加盐、慢哈希），格式见 [pbkdf2Hash]。 */
    fun hash(plain: String): String = pbkdf2Hash(plain)

    fun isValid(plain: String?): Boolean = !plain.isNullOrBlank() && plain.length >= MIN_LENGTH

    // ---- 与孩子的 GuardPasswordManager 完全一致的 PBKDF2 参数（跨设备口径一致）----
    private const val PBKDF2_ITERATIONS = 120_000
    private const val PBKDF2_SALT_BYTES = 16
    private const val PBKDF2_KEY_BYTES = 32

    private fun pbkdf2Hash(plain: String): String {
        val salt = ByteArray(PBKDF2_SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(plain.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BYTES * 8)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val hash = skf.generateSecret(spec).encoded
        return "pbkdf2:$PBKDF2_ITERATIONS:${b64(salt)}:${b64(hash)}"
    }

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
}
