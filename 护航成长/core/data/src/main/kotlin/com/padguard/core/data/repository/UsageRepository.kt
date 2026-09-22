package com.padguard.core.data.repository

import com.padguard.core.data.db.AppUsageDao
import com.padguard.core.data.db.DailyUsageDao
import com.padguard.core.data.model.AppUsageStat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Locale
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
class UsageRepository @Inject constructor(
    private val appUsageDao: AppUsageDao,
    private val dailyUsageDao: DailyUsageDao
) {

    private val dayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    @Synchronized
    fun dayKey(timestamp: Long = System.currentTimeMillis()): String =
        dayKeyFormat.format(timestamp)

    /** 累加某应用当日使用时长 */
    suspend fun addAppUsage(packageName: String, deltaMs: Long, timestamp: Long = System.currentTimeMillis()) {
        if (deltaMs <= 0) return
        appUsageDao.addUsage(dayKey(timestamp), packageName, deltaMs, System.currentTimeMillis())
    }

    /** 累加某应用当日启动次数 */
    suspend fun addAppLaunch(packageName: String, timestamp: Long = System.currentTimeMillis()) {
        appUsageDao.addLaunch(dayKey(timestamp), packageName, System.currentTimeMillis())
    }

    /** 累加全局当日使用时长 */
    suspend fun addTotalUsage(deltaMs: Long, timestamp: Long = System.currentTimeMillis()) {
        if (deltaMs <= 0) return
        dailyUsageDao.addUsage(dayKey(timestamp), deltaMs, System.currentTimeMillis())
    }

    suspend fun getAppUsage(packageName: String, key: String): AppUsageStat? =
        appUsageDao.get(key, packageName)?.let {
            AppUsageStat(it.packageName, it.dayKey, it.usedMs, it.launchCount, it.lastUpdateAt)
        }

    suspend fun getTotalUsage(key: String): Long = dailyUsageDao.get(key)?.totalMs ?: 0L

    fun observeAppUsage(key: String): Flow<List<AppUsageStat>> =
        appUsageDao.observeByDay(key).map { list ->
            list.map { AppUsageStat(it.packageName, it.dayKey, it.usedMs, it.launchCount, it.lastUpdateAt) }
        }

    /**
     * 当日应用用量快照（按使用时长降序）。
     *
     * 与 [observeAppUsage] 的区别：这是一次性查询，适合"周期性上报"这种取一次就走的场景。
     * 用 Flow 版本需要长期持有订阅，而上报协程是短命的，订阅会在协程结束时被取消，
     * 反而可能一次数据都拿不到（Room 的 Flow 在首次收集前不发射）。
     */
    suspend fun snapshotAppUsage(key: String = dayKey()): List<AppUsageStat> =
        appUsageDao.snapshotByDay(key).map {
            AppUsageStat(it.packageName, it.dayKey, it.usedMs, it.launchCount, it.lastUpdateAt)
        }

    /**
     * 首页"今日使用"专用：返回当前本地日的全局累计使用分钟数。
     *
     * 注意：返回的是**真实采集**的时长（不是策略下发的限额），由 GuardService 周期累加。
     * 当数据为空时返回 0，与策略下发的限额做减法得"剩余时间"。
     */
    fun observeTodayUsageMinutes(): Flow<Int> =
        dailyUsageDao.observe(dayKey()).map { entity ->
            (entity?.totalMs ?: 0L).let { ms -> (ms / 60_000L).toInt() }
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
}
