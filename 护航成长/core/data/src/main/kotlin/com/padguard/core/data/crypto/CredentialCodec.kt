package com.padguard.core.data.crypto

/**
 * 绑定凭据「明文 / 密文」判别。
 *
 * ## 为什么需要它
 * 11-01 起 [com.padguard.core.data.repository.AuthRepository] 把 deviceToken / hmacSecret /
 * MQTT 口令经 Android Keystore(AES-GCM) 加密后以标准 Base64 落库；此前的数据是明文。
 * 读取端为了兼容升级统一走「先解密、失败回退原文」。
 *
 * 但「解密失败」还有第三种、且更危险的情形：**Keystore 密钥已销毁**——卸载重装后恢复
 * 数据、换机备份恢复、恢复出厂都会发生（Android Keystore 密钥与 APK 签名/安装态绑定）。
 * 此时旧实现把 Base64 密文当成 deviceToken 发给服务端，得到一串 401 TOKEN_INVALID；
 * hmacSecret 同时变成密文，指令签名全部校验失败；家长端看到的就是「设备离线 /
 * 连不上服务器」。2026-11-07 孩子端救砖（完整卸载→全新安装→恢复数据）后 18:53 的
 * 连续 401 正是这条链。
 *
 * ## 判别依据（不靠猜测明文语义）
 * - 服务端下发的四类凭据明文只由 `[A-Za-z0-9_.-]` 组成：`DeviceService` 里
 *   deviceToken 是两段去横线 UUID（纯 hex）、hmacSecret/mqttPassword 带 `hs_`/`mk_` 前缀、
 *   mqttUsername 是 UUID 或固定账号 —— **绝不会出现标准 Base64 专有的 `+` `/` `=`**。
 * - 本模块密文是 [com.padguard.core.common.Crypto.base64] 输出的标准 Base64（带填充），
 *   必然含 `=` 或 `+` `/`；结构是 `[12B IV][密文][16B TAG]`。
 * - 再加长度与解码结构校验（≥40 字符、严格 Base64 可解、解码后 ≥29 字节），避免把
 *   自定义共享口令里偶发的一个 `=` 误判成密文。
 *
 * 详见 [looksLikeCipherText]。此对象不依赖任何 Android 类型，可在纯 JVM 单测里直接验证。
 */
object CredentialCodec {

    /** AES-GCM 打包结构：[12B IV][密文][16B TAG]，密文至少 1 字节。 */
    private const val MIN_PACKED_BYTES = 12 + 16 + 1

    /** 短于此长度的一律视作明文（最短密文 Base64 也有 40 字符）。 */
    private const val MIN_BASE64_CHARS = 40

    /**
     * 该值是否像本模块写出的加密凭据。返回 true 且解密失败 ⇒ 密钥已失效，凭据不可用。
     * 返回 false ⇒ 老版本明文（或空值），原样使用以完成平滑迁移。
     */
    fun looksLikeCipherText(value: String): Boolean {
        if (value.length < MIN_BASE64_CHARS) return false
        // 明文凭据绝不含这三个字符；这是最关键的判据，优先级高于长度。
        if (value.none { it == '+' || it == '/' || it == '=' }) return false
        val packed = runCatching { java.util.Base64.getDecoder().decode(value) }.getOrNull()
            ?: return false
        return packed.size >= MIN_PACKED_BYTES
    }

    /**
     * 读取凭据的完整决策，抽成纯函数让这条安全关键分支能在 JVM 单测里直接覆盖：
     * 解密成功永远优先；只有「解密失败 + 形态是密文」才判定密钥失效。
     */
    fun resolve(raw: String, decrypt: (String) -> String): Resolution {
        if (raw.isEmpty()) return Resolution.Plain("")
        val plain = runCatching { decrypt(raw) }.getOrNull()
        if (plain != null) return Resolution.Plain(plain)
        return if (looksLikeCipherText(raw)) Resolution.Corrupted else Resolution.Plain(raw)
    }

    sealed interface Resolution {
        /** 可直接使用的明文：解密结果，或老版本明文原样返回（平滑迁移）。 */
        data class Plain(val value: String) : Resolution

        /** 密文但密钥已失效：调用方必须返回空串，绝不能把密文当凭据发出去。 */
        object Corrupted : Resolution
    }
}
