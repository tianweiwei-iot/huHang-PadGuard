package com.padguard.core.transport

/**
 * 远程指令**时效**判定（纯函数，无 Android 依赖，便于 JVM 单测逐条钉住）。
 *
 * ## 为什么需要"签名封顶"（新增缺陷 H）
 * 两端 HMAC 的 canonical 串是 `$msgId|$type|$timestamp|${canonicalJson(payload)}`
 * （服务端 `CommandService.issueCommand`、终端 `Command.signingPayload` 逐字符对齐），
 * **`expiresAt` 不在签名覆盖范围内**。这是一个可篡改字段：把抓包到的旧指令的
 * `expiresAt` 改成 `0`（永不过期）或一个远期值，`signature` 仍然完全有效，
 * 关卡 1 的 `expiresAt < now` 就形同虚设 —— 服务端 5 分钟 TTL 被绕过。
 * 配合"幂等账本被墙钟前拨清空"（新增缺陷 G），一条旧的 UNLOCK / PAUSE 指令
 * 就能在任意时间被重放执行。
 *
 * 根治要把 `expiresAt` 纳入 v2 签名串（跨端协议变更，须两端同步发布并留兼容期）。
 * 本轮**不改协议**，只补一道纯终端侧的兜底：`timestamp` 是被 HMAC 覆盖的，
 * 攻击者改不动它，因此可以给指令有效期设一个**由已签名时间戳推出的硬上限**：
 * `expiresAt` 只被允许**缩短**有效期，绝不能延长超过 `timestamp + 15min`。
 * 服务端当前 TTL 是 `timestamp + 5min`，落在封顶之内，正常指令行为完全不变；
 * 把 TTL 调大也不会导致全量拒绝，只是被封顶（收紧而非中断），无跨端硬耦合。
 *
 * ## 如实记录的边界
 * - `timestamp <= 0` 时没有签名锚点可依，只能维持旧行为（仅看 `expiresAt`）。
 *   真实服务端总是下发 `timestamp`，攻击者也无法把它改成 0（会破坏 HMAC），
 *   所以这不是可利用的绕过口，只是协议层的历史遗留。
 * - 封顶是 15 分钟而非服务端的 5 分钟：留出 MQTT 排队 / 长轮询挂起 / 两端时钟偏差的余量。
 *   该值与既有 `MAX_FUTURE_SKEW_MS` 同源假设（两端时钟偏差 < 5min）；
 *   设备时钟偏差超过 15min 且从未对时，会误拒指令，联网对时后自愈。
 * - 15 分钟内的重放仍由 `CommandRepository` 的 msgId 幂等窗口兜底（见新增缺陷 G 的修复）。
 */
object CommandFreshness {

    /** 允许的未来时间偏差：5 分钟（与服务端 X-Timestamp 容忍度一致） */
    const val MAX_FUTURE_SKEW_MS = 5 * 60 * 1000L

    /**
     * 基于已签名 `timestamp` 的最长寿命。服务端 TTL 5min，取 3 倍余量；
     * 不需要与服务端常量逐字符一致（超出只会被封顶，不会被拒）。
     */
    const val MAX_COMMAND_LIFETIME_MS = 15 * 60 * 1000L

    sealed interface Verdict {
        /** 时效通过，可以继续做验签与幂等判定 */
        object Fresh : Verdict

        /** 拒绝执行；[reason] 会写进 REJECTED 回执与本地日志 */
        data class Rejected(val reason: String) : Verdict
    }

    /**
     * 有效截止时间：显式 `expiresAt` 可以缩短，但不能越过签名封顶延长。
     *
     * 返回 `Long.MAX_VALUE` 表示"无可用上限"（`timestamp <= 0` 且 `expiresAt <= 0`），
     * 即维持协议历史行为；见类注释的边界说明。
     */
    fun effectiveExpiryAt(timestamp: Long, expiresAt: Long): Long {
        val cap = signedLifetimeCap(timestamp)
        return if (expiresAt > 0L) minOf(expiresAt, cap) else cap
    }

    /** @param trustedNowMs 必须来自 `TimeProvider.now()`，不能是裸墙钟 */
    fun evaluate(timestamp: Long, expiresAt: Long, trustedNowMs: Long): Verdict {
        // 1. 显式过期（原有语义，优先保留原有回执文案，避免家长端/服务端解析回归）
        if (expiresAt > 0L && expiresAt < trustedNowMs) {
            return Verdict.Rejected("expired at $expiresAt, now=$trustedNowMs")
        }
        // 2. 未来时间偏差（原有语义）
        if (timestamp > 0L && timestamp - trustedNowMs > MAX_FUTURE_SKEW_MS) {
            return Verdict.Rejected("timestamp too far in future: $timestamp vs $trustedNowMs")
        }
        // 3. 签名封顶：挡住"expiresAt 被改成 0 / 远期"的重放
        val cap = signedLifetimeCap(timestamp)
        if (effectiveExpiryAt(timestamp, expiresAt) < trustedNowMs) {
            return Verdict.Rejected(
                "signed timestamp cap exceeded " +
                    "(expiresAt=$expiresAt is NOT covered by HMAC): cap=$cap, now=$trustedNowMs"
            )
        }
        return Verdict.Fresh
    }

    /**
     * 由已签名时间戳推出的硬上限；含加法溢出保护。
     * 脏 `timestamp`（接近 Long.MAX_VALUE）退回"无上限"，不能因为溢出把 cap 算成负数
     * 而把一条正常指令误判成过期 —— 脏值应交给验签关卡，而不是在这里制造新的拒绝理由。
     */
    private fun signedLifetimeCap(timestamp: Long): Long {
        if (timestamp <= 0L) return Long.MAX_VALUE
        if (timestamp > Long.MAX_VALUE - MAX_COMMAND_LIFETIME_MS) return Long.MAX_VALUE
        return timestamp + MAX_COMMAND_LIFETIME_MS
    }
}
