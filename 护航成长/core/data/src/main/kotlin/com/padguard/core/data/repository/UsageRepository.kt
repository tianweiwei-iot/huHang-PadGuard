package com.padguard.core.data.repository

import com.padguard.core.common.TimeProvider
import com.padguard.core.data.db.AppUsageDao
import com.padguard.core.data.db.DailyUsageDao
import com.padguard.core.data.model.AppUsageStat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用时长统计仓库（对应说明书「应用时长与次数限额管控」）。
 *
 * **防作弊设计**：
 * 所有累计时长由调用方基于 [android.os.SystemClock.elapsedRealtime] 差值计算后传入，
 * 本仓库不做任何墙钟换算。因此修改系统时间、重启设备都无法清零或缩短已用时长。
 * 日切（dayKey）以设备本地日期为准，服务端可下发时区覆盖。
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
open class UsageRepository @Inject constructor(
    private val appUsageDao: AppUsageDao,
    private val dailyUsageDao: DailyUsageDao,
    private val timeProvider: TimeProvider
) {

    /**
     * 日切口径必须与服务端 [com.padguard.server.service.StatisticsService] 完全一致：
     * 服务端固定 ZoneId.of("Asia/Shanghai")，本端也必须显式锁定同一时区。
     * Locale.getDefault() 只决定数字/语言，真正决定"算到哪一天"的是 TimeZone；
     * 若设备时区非中国时区（或用户改过时区），用默认时区算日切会让"写 A 日、读 B 日"，
     * 孩子端本地累计的"今日已用"与家长端服务端聚合（Asia/Shanghai）错位 ——
     * 这正是"学生端与家长端不同步"的又一类成因，必须钉死时区。
     */
    private val dayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    @Synchronized
    fun dayKey(timestamp: Long): String = dayKeyFormat.format(timestamp)

    /**
     * 无参版必须走服务端校准时间 [TimeProvider.now]，不能用设备墙钟。
     *
     * 写入方（AppLimitEnforcer）与读取方（首页）若用两套时刻算日切，
     * 零点附近一小时内的用量会"写进昨天、读自今天"（或反之），
     * 首页"已用 / 剩余"直接归零，而服务端按 Asia/Shanghai 聚合又是另一个值 ——
     * 这正是"孩子端与家长端时间对不上"的一类成因，必须全程单一口径。
     *
     * 配合 [dayKeyFormat] 已钉死的 Asia/Shanghai 时区：本函数只决定"用哪个时刻"
     * （校准后的 now），[dayKeyFormat] 决定"按哪个时区切日"，两者齐备才能保证
     * 本地日界 == 服务端日界，孩子端未激活分支的本地累计与家长端服务端统计不再错位。
     */
    @Synchronized
    fun dayKey(): String = dayKey(timeProvider.now())

    /** 累加某应用当日使用时长 */
    open suspend fun addAppUsage(packageName: String, deltaMs: Long, timestamp: Long = timeProvider.now()) {
        if (deltaMs <= 0) return
        appUsageDao.addUsage(dayKey(timestamp), packageName, deltaMs, System.currentTimeMillis())
    }

    /** 累加某应用当日启动次数 */
    open suspend fun addAppLaunch(packageName: String, timestamp: Long = timeProvider.now()) {
        appUsageDao.addLaunch(dayKey(timestamp), packageName, System.currentTimeMillis())
    }

    /** 累加全局当日使用时长 */
    open suspend fun addTotalUsage(deltaMs: Long, timestamp: Long = timeProvider.now()) {
        if (deltaMs <= 0) return
        dailyUsageDao.addUsage(dayKey(timestamp), deltaMs, System.currentTimeMillis())
    }

    suspend fun getAppUsage(packageName: String, key: String): AppUsageStat? =
        appUsageDao.get(key, packageName)?.let {
            AppUsageStat(it.packageName, it.dayKey, it.usedMs, it.launchCount, it.lastUpdateAt, it.firstUsedAt, it.lastUsedAt)
        }

    suspend fun getTotalUsage(key: String): Long = dailyUsageDao.get(key)?.totalMs ?: 0L

    fun observeAppUsage(key: String): Flow<List<AppUsageStat>> =
        appUsageDao.observeByDay(key).map { list ->
            list.map { AppUsageStat(it.packageName, it.dayKey, it.usedMs, it.launchCount, it.lastUpdateAt, it.firstUsedAt, it.lastUsedAt) }
        }

    /**
     * 当日应用用量快照（按使用时长降序）。
     *
     * 与 [observeAppUsage] 的区别：这是一次性查询，适合"周期性上报"这种取一次就走的场景。
     * 用 Flow 版本需要长期持有订阅，而上报协程是短命的，订阅会在协程结束时被取消，
     * 反而可能一次数据都拿不到（Room 的 Flow 在首次收集前不发射）。
     */
    suspend fun snapshotAppUsage(key: String = dayKey(timeProvider.now())): List<AppUsageStat> =
        appUsageDao.snapshotByDay(key).map {
            AppUsageStat(it.packageName, it.dayKey, it.usedMs, it.launchCount, it.lastUpdateAt, it.firstUsedAt, it.lastUsedAt)
        }

    /**
     * 首页"今日使用"专用：返回当前本地日的全局累计使用分钟数。
     *
     * 注意：返回的是**真实采集**的时长（不是策略下发的限额），由 GuardService 周期累加。
     * 当数据为空时返回 0，与策略下发的限额做减法得"剩余时间"。
     *
     * **必须与 [addTotalUsage] 写入用同一个日切口径**：写入方用的是校准后的
     * [TimeProvider.now]（服务端时区），这里读取也必须用同一时刻，否则设备本地墙钟
     * 与服务端校准时间存在偏移时，"写"进 A 日、"读"出 B 日，首页"已用/剩余"与
     * 孩子端锁机判定、家长端服务端聚合（Asia/Shanghai）全部错位，表现为"学生端与家长端不同步"。
     *
     * ## 为什么不能只在构造 Flow 时算一次 dayKey（历史缺陷）
     * 早期实现是 `dailyUsageDao.observe(dayKey(timeProvider.now()))` —— dayKey 在**订阅建立那一刻**
     * 求值一次并固定下来。于是跨过零点后，悬浮卡与首页仍然订阅着**昨天**那一行：
     * 孩子熬到 0 点看到"今日已用 3 小时"，而真实值已归零；次日早上家长端显示 0、孩子端显示 3 小时，
     * 双方各执一词。锁机判定（按当时 dayKey 现算）却是对的，于是出现
     * "卡上还剩 2 小时、设备却被锁"的矛盾。
     *
     * 现在改成：每分钟吐一次当前 dayKey，靠 [distinctUntilChanged] 只在**跨日那一刻**
     * 切换内层订阅。轮询粒度取分钟而不是"睡到下一个日界"，是因为校准时间可能被服务端
     * 一次性拉前/拉后若干小时，固定睡到日界会睡过头或提前醒；分钟级 tick 能自愈。
     */
    fun observeTodayUsageMinutes(): Flow<Int> =
        dayKeyTicks()
            .distinctUntilChanged()
            .flatMapLatest { key -> dailyUsageDao.observe(key) }
            .map { entity -> ((entity?.totalMs ?: 0L) / 60_000L).toInt() }
            .distinctUntilChanged()

    /** 日切信号源：每次醒来重新求值 dayKey（用校准时间，口径与写入方一致）。 */
    private fun dayKeyTicks(): Flow<String> = flow {
        while (true) {
            emit(dayKey())
            delay(DAY_KEY_POLL_MS)
        }
    }

    /**
     * 清理 N 天前的统计记录。
     * 统计类数据丢失不影响审计（原始行为日志另行留存），可安全清理。
     */
    suspend fun purgeOlderThan(days: Int) {
        val cutoff = dayKey(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000)
        appUsageDao.deleteBefore(cutoff)
        dailyUsageDao.deleteBefore(cutoff)
    }

    private companion object {
        /**
         * 日切轮询间隔：1 分钟。
         *
         * 取 1 分钟而不是"精确睡到下一个日界"，是因为 [TimeProvider.now] 是**校准时间**，
         * 服务端校时可能把它一次性拉前或拉后几小时。固定睡到旧日界会睡过头（跨日很久才切）
         * 或提前醒（醒在旧日里空转）。分钟级 tick 的最坏误差是 1 分钟，且能随校时自愈。
         * 订阅方常驻（首页 / 悬浮卡），每分钟一次空转的代价可以忽略。
         */
        const val DAY_KEY_POLL_MS = 60_000L
    }
}
