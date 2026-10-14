package com.padguard.parent.domain.model

/**
 * 一次「设备接入」（Enrollment）。
 *
 * 它取代了原先"一个 6 位绑定码兼任凭证与状态容器"的做法：
 * 二维码只是 [pairingCode] 的一种呈现（[qrPayload]），状态是服务端权威的 [state]，
 * 绑定结果落在 [deviceId] 上。家长端照此渲染，就不可能出现
 * "二维码里的码和显示的配对码不是同一个"——两个展示位置读的是同一个字段。
 */
data class Enrollment(
    val sessionId: String,
    /** 孩子端输入 / 扫码用的 6 位接入码 */
    val pairingCode: String,
    /** 二维码内容：与 [pairingCode] 同源，由服务端封装后下发 */
    val qrPayload: String,
    val state: EnrollmentState,
    /** `state=BOUND` 时必有值 */
    val deviceId: String?,
    val expiresAt: Long,
    /** 服务端时钟：据此计算剩余有效期，不依赖本机时间 */
    val serverNow: Long,
    /**
     * 收到这份快照时的本地单调时刻（[android.os.SystemClock.elapsedRealtime]）。
     * 剩余时长 = expiresAt - (serverNow + 已流逝的单调时间)，
     * 这样用户在手机上改系统时间也不会把倒计时改坏。
     */
    val receivedAtRealtime: Long
) {
    /** 剩余有效毫秒（<=0 表示已过期） */
    fun remainingMillis(nowRealtime: Long): Long =
        expiresAt - (serverNow + (nowRealtime - receivedAtRealtime))
}

/** 接入会话状态，取值与服务端 `EnrollmentService.STATE_*` 一一对应 */
enum class EnrollmentState {
    /** 已生成，等待孩子端扫码 / 输入 */
    PENDING,

    /** 孩子端已绑定，[Enrollment.deviceId] 即新接入的设备 */
    BOUND,

    /** 超时失效 */
    EXPIRED,

    /** 家长主动作废或已换码 */
    CANCELLED;

    companion object {
        /** 未知取值一律按 EXPIRED 处理：宁可让家长刷新，也不要对着失效的码干等 */
        fun of(raw: String?): EnrollmentState =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: EXPIRED
    }
}

/** 服务端推送的「接入完成」事件（WS 快路径；状态查询是它的必然正确兜底） */
data class EnrollmentCompleted(val sessionId: String, val deviceId: String)
