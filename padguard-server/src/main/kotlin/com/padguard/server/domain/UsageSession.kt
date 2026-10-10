package com.padguard.server.domain

import jakarta.persistence.*

/**
 * 平板使用计时会话（三端时间一致的权威事实源）。
 *
 * ## 为什么必须放在服务端
 * 旧方案里"今日剩余时长"完全由孩子端本地用 `elapsedRealtime` 累计、家长端另算，
 * 三端口径天然不一致：孩子改系统时间、采样截断、日切判定差异都会让三端数字对不上，
 * 家长看到的剩余和孩子看到的永远差一截，投诉"时间管控不准"。
 *
 * 新方案把**计时起点与额度**收归服务端，三端都拿同一份 `startEpoch` + `serverNow` 派生剩余：
 * 剩余 = (quotaMinutes + extendedMinutes) - (serverNow - startEpoch)
 * 由于三端使用同一个服务端墙钟与同一个起点，派生结果必然严格一致。
 *
 * ## 生命周期
 * - 孩子端确认"计时开始" → [com.padguard.server.service.UsageSessionService.start] 锁定当日起点；
 * - 孩子端/家长端轮询 [get] 读取当前剩余（携带服务端 `serverNow`，避免各端本地时钟漂移）；
 * - 孩子端申请延时 → [extend] 增加 extendedMinutes，并通知家长端。
 *
 * `dayKey` 用于每日重置：新的一天首次读取若与当天不符，视为"今日尚未开始"。
 */
@Entity
@Table(name = "usage_sessions")
class UsageSession(
    /** 设备 ID，同时为主键（一台设备一天一条） */
    @Id
    @Column(name = "device_id")
    var deviceId: String = "",

    /** 本地日期键（Asia/Shanghai），如 2026-10-09；用于跨日重置计时 */
    @Column(name = "day_key")
    var dayKey: String = "",

    /**
     * 计时起点：孩子端确认"计时开始"那一刻的服务端墙钟毫秒。
     * null 表示今日尚未开始计时（孩子还没确认 / 设备当天没被使用）。
     */
    @Column(name = "start_epoch")
    var startEpoch: Long? = null,

    /** 开始计时时锁定的当日额度（分钟），由服务端按工作日/周末解析家长设置 */
    @Column(name = "quota_minutes")
    var quotaMinutes: Int = 0,

    /** 累计延时时长（分钟）：孩子每次申请延时累加，受 [MAX_DAILY_EXTEND_MINUTES] 约束 */
    @Column(name = "extended_minutes")
    var extendedMinutes: Int = 0,

    /** 最近一次延时申请的服务端墙钟；用于家长端展示"最近一次延时"与防刷 */
    @Column(name = "last_delay_epoch")
    var lastDelayEpoch: Long? = null,

    @Column(name = "created_at")
    var createdAt: Long = 0L,

    @Column(name = "updated_at")
    var updatedAt: Long = 0L
) {
    companion object {
        /** 延时档位（分钟）：孩子端申请延时的可选等级 */
        val DELAY_OPTIONS = listOf(15, 30, 45, 60)

        /** 单日累计延时上限（分钟）：防止孩子反复点延时把管控名存实亡 */
        const val MAX_DAILY_EXTEND_MINUTES = 120

        /** 进入"倒计时提示"的剩余阈值（毫秒）：剩余 <= 此值才弹出 10 分钟倒计时框 */
        const val COUNTDOWN_THRESHOLD_MS = 10 * 60_000L
    }
}
