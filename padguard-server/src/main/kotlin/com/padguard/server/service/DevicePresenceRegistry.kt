package com.padguard.server.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 设备在线态的**内存**快照 —— 秒级上下线检测的数据基础。
 *
 * ## 为什么需要它：在线判定原先慢在"读时惰性 + 2 分钟阈值"
 * 改造前孩子端的存活信号其实很密（在线时每 3 秒拉一次指令，见孩子端 `CommandGate` 轮询），
 * 但服务端把它丢了：
 * 1. [DeviceService.markAlive] 被 30 秒节流，`devices.last_online_at` 最多滞后 30s；
 * 2. 在线判定阈值 [DeviceService] 的 `ONLINE_STALE_MS` 是 120 秒；
 * 3. 离线判定还叠加 [OfflineMonitorService] 的 30 秒扫描。
 * 家长端看到"孩子端已关机"最坏要等 **2.5 分钟**。
 *
 * ## 设计取舍：内存里算，库只记结论
 * - 每个存活信号只做一次 `ConcurrentHashMap.compute`（纳秒级，无锁竞争、无 SQL）；
 *   设备不再每 3 秒把 `devices` 行重写一遍（那会和孩子端日志上报抢行锁）。
 * - 落库只在"状态翻转"或每 [DB_REFRESH_MS] 的兜底刷新时发生，写放大被压到
 *   「在线设备数 / 15s」这个量级。
 * - 扫描器只遍历**内存里已知在线**的设备，设备一离线就从表里摘掉 —— 离线设备零开销，
 *   不会像全表扫描那样随历史设备累积越扫越慢。
 *
 * ## 阈值为什么是"自适应"的，而不是写死 5 秒
 * 检测延迟 = 超时阈值本身，所以阈值必须贴着孩子端的真实心跳节奏，否则会误判：
 * 孩子端在线时 3s 一次、MQTT 断开降级时 5~10s 一次、弱网下单次请求本身就要几秒。
 * 写死 5s 的后果是"网络稍抖就闪一下离线"，家长端横幅反复跳，比慢更糟。
 * 这里用每个设备自己的信号间隔 EWMA 推算超时：
 *
 *     超时 = clamp(1.8 × 平均间隔, [MIN_TIMEOUT_MS], [MAX_TIMEOUT_MS])
 *
 * 正常在线（间隔 ≈3s）→ 约 5.4 秒判定离线，命中「5 秒左右」的目标；
 * 设备节奏本身就慢（如夜间低功耗下的 10s）→ 阈值自动放宽，不误报。
 */
@Component
class DevicePresenceRegistry(
    // 默认值让单测可以 `DevicePresenceRegistry()` 直接构造，Spring 侧仍由 @Value 注入
    @Value("\${padguard.presence.min-timeout-ms:5000}") private val minTimeoutMs: Long = 5_000,
    @Value("\${padguard.presence.max-timeout-ms:20000}") private val maxTimeoutMs: Long = 20_000
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 单设备的存活信号快照。字段各自 volatile：扫描线程读、请求线程写，不需要整体加锁。 */
    private class Entry(
        @Volatile var lastSeenAt: Long,
        @Volatile var knownOnline: Boolean,
        @Volatile var lastDbRefreshAt: Long,
        /** 信号间隔的指数滑动均值（EWMA），用于推算该设备的判定阈值 */
        @Volatile var avgGapMs: Long,
        /**
         * 下一次信号是否跳过节奏采样。
         *
         * 启动预热时 `lastSeenAt` 用的是**库里**的上次在线时间（可能已过去几分钟），
         * 这个间隔不是真实节奏，喂进 EWMA 会把阈值临时撑到十几秒 ——
         * 恰好让"服务端刚重启就掉线"的设备又慢一拍。第一次真实信号只用于校准 LastSeen。
         */
        @Volatile var skipNextGapSample: Boolean = false
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * 记录一次存活信号，返回**是否需要落库刷新在线态**。
     *
     * 返回 true 的两种情况：
     * - 设备此前不在表里 / 已知离线 → 需要立刻走一次 `OFFLINE -> ONLINE`（含家长端 WS 推送）；
     * - 距离上次落库已超过 [DB_REFRESH_MS] → 兜底刷新 `last_online_at`，
     *   供「最后在线时间」展示、脱管告警与局域网发现使用。
     *
     * @param now 当前时间戳（毫秒），便于测试注入
     */
    fun touch(deviceId: String, now: Long = System.currentTimeMillis()): Boolean {
        var refresh = false
        entries.compute(deviceId) { _, old ->
            if (old == null) {
                // 首次出现（服务端刚重启 / 设备刚上线）：交给落库路径判断真实翻转
                refresh = true
                Entry(now, true, now, DEFAULT_GAP_MS)
            } else {
                val gap = now - old.lastSeenAt
                // 只用"合理节奏"的间隔喂 EWMA：设备离线几小时后再回来，那个巨大间隔
                // 不能把阈值撑到 20 秒，否则下次掉线要等 20 秒才发现。
                if (old.skipNextGapSample) {
                    old.skipNextGapSample = false
                } else if (gap in 1..GAP_SAMPLE_MAX_MS) {
                    old.avgGapMs = (old.avgGapMs * 7 + gap * 3) / 10
                }
                if (!old.knownOnline) refresh = true
                old.lastSeenAt = now
                old.knownOnline = true
                if (now - old.lastDbRefreshAt >= DB_REFRESH_MS) {
                    old.lastDbRefreshAt = now
                    refresh = true
                }
                old
            }
        }
        return refresh
    }

    /**
     * 判断并摘除"已超时未上报"的在线设备，返回需要置为离线的设备 ID。
     *
     * 摘除而不是只打标记：离线设备不参与后续扫描，扫描成本 = 在线设备数，
     * 家庭部署几百台设备时每秒一次也只是一次数组遍历，没有任何 SQL。
     */
    fun takeTimedOut(now: Long = System.currentTimeMillis()): List<String> {
        if (entries.isEmpty()) return emptyList()
        val timedOut = ArrayList<String>(4)
        for (id in entries.keys) {
            // computeIfPresent 保证"判定 + 摘除"是原子的：
            // 与并发到达的 touch 不会出现"刚判定超时、信号已进来又被删"的错杀。
            entries.computeIfPresent(id) { key, e ->
                if (now - e.lastSeenAt > timeoutFor(e)) {
                    timedOut += key
                    null
                } else {
                    e
                }
            }
        }
        return timedOut
    }

    /** 该设备最近一次存活信号时刻（毫秒）；从未上报过返回 null */
    fun lastSeenAt(deviceId: String): Long? = entries[deviceId]?.lastSeenAt

    /** 当前内存中判定为在线的设备（供脱管告警清理"已告警"标记用） */
    fun onlineIds(): Set<String> = entries.filterValues { it.knownOnline }.keys

    /** 设备被置为离线（本服务判定超时、或解绑等其它路径）：从表里摘掉，等下一次信号重新登记 */
    fun markOffline(deviceId: String) {
        entries.remove(deviceId)
    }

    /**
     * 启动预热：把库里仍标记 ONLINE 的设备灌进内存。
     *
     * 不预热的话，服务端重启后这台"实际已关机"的设备会一直显示在线，
     * 直到它自己发来一个信号（不可能）或惰性超时兜底 —— 又回到分钟级。
     */
    fun seed(deviceId: String, lastSeenAt: Long) {
        entries.putIfAbsent(
            deviceId,
            Entry(lastSeenAt, true, 0, DEFAULT_GAP_MS, skipNextGapSample = true)
        )
    }

    /** 该设备当前的判定阈值（测试与运维排查用） */
    fun timeoutOf(deviceId: String): Long =
        entries[deviceId]?.let { timeoutFor(it) } ?: minTimeoutMs

    private fun timeoutFor(e: Entry): Long =
        (e.avgGapMs * TIMEOUT_FACTOR_NUM / TIMEOUT_FACTOR_DEN).coerceIn(minTimeoutMs, maxTimeoutMs)

    private companion object {
        /** 孩子端默认信号节奏：在线时每 3 秒一次指令轮询（孩子端 COMMAND_PULL_INTERVAL_MS） */
        const val DEFAULT_GAP_MS = 3_000L

        /** 超过该间隔的信号不作为节奏样本（离线很久后回归的那一次） */
        const val GAP_SAMPLE_MAX_MS = 60_000L

        /** 阈值系数：1.8 × 平均间隔（整数运算，避免浮点抖动） */
        const val TIMEOUT_FACTOR_NUM = 18L
        const val TIMEOUT_FACTOR_DEN = 10L

        /** 在线态落库的最小间隔。远小于 [DeviceService] 的惰性超时兜底，足够支撑展示与告警 */
        const val DB_REFRESH_MS = 15_000L
    }

    init {
        log.info("device presence registry: timeout window = [{}, {}] ms (adaptive ×1.8 of observed gap)", minTimeoutMs, maxTimeoutMs)
    }
}
