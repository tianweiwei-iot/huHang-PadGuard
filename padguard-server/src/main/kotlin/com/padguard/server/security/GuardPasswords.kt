package com.padguard.server.security

import java.security.MessageDigest
import java.util.Base64

/**
 * 操作密码闸门的服务端哈希工具。
 *
 * 必须与**孩子端** [com.padguard.child.guard.GuardPasswordManager.protocolHash] 完全一致：
 * `Base64(SHA-256(明文 UTF-8))`，**不引入盐**。
 *
 * 原因：孩子端只存哈希，校验时把家长输入的明文在本地再算一次 SHA-256 与存储哈希比对。
 * 若服务端/客户端盐不一致或算法不同，跨设备校验永远失败，表现为"家长在家长端设了密码、
 * 孩子端输对也提示错误"。无盐是刻意的——牺牲一点抗彩虹表能力，换取跨设备哈希口径绝对一致。
 */
object GuardPasswords {

    /** 最小长度，避免家长设出 123 这种弱口令（弱口令会让闸门形同虚设）。 */
    const val MIN_LENGTH = 4

    /** 计算协议哈希：SHA-256(明文) 的 Base64（无盐）。 */
    fun hash(plain: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return Base64.getEncoder().encodeToString(md.digest(plain.toByteArray(Charsets.UTF_8)))
    }

    fun isValid(plain: String?): Boolean = !plain.isNullOrBlank() && plain.length >= MIN_LENGTH
}
