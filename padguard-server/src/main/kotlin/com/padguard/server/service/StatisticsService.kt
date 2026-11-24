package com.padguard.server.service

import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.UsageLog
import com.padguard.server.dto.*
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.InstalledAppRepository
import com.padguard.server.repository.UsageLogRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

@Service
class StatisticsService(
    private val deviceRepository: DeviceRepository,
    private val ownershipGuard: com.padguard.server.security.DeviceOwnershipGuard,
    private val usageLogRepository: UsageLogRepository,
    private val deviceEventRepository: DeviceEventRepository,
    private val alertRepository: AlertRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val fileStorageService: FileStorageService,
    private val objectMapper: ObjectMapper,
    private val usageSessionService: UsageSessionService
) {
    private val zone = SERVER_ZONE_ID

    /**
     * 今日用量缓存：孩子端每 10 秒上行一次，家长端既有 10 秒轮询兜底、又新增 WS 实时推送，
     * 二者都会频繁调用 [today]。每次都重扫全天日志并反序列化 payload 并不便宜，
     * 故在此做一层短 TTL 缓存（[TODAY_CACHE_TTL_MS]）：
     * - 新日志入库时由 [invalidateToday] 失效，保证推送/轮询拿到的是最新值；
     * - 两次入库之间的轮询直接命中缓存，省去重扫与反序列化。
     * 缓存按 deviceId 维度，单设备单条，内存占用可忽略。
     */
    private val todayCache = ConcurrentHashMap<String, CachedToday>()
    private data class CachedToday(val dto: UsageStatsDto, val ts: Long)
    private companion object {
        const val TODAY_CACHE_TTL_MS = 15_000L

        /** 网址统计只需要这两类日志，把过滤下推到 SQL，避免把全量日志捞回来 */
        val WEB_TYPES = listOf("URL_VISIT", "URL_BLOCKED")

        /** 单应用历史的来源类型 */
        val APP_ONLY = listOf(UsageAggregator.TYPE_APP)

        /**
         * 单应用历史的返回上限。重度使用的应用，月视图按"一次打开"粒度可达数千条，
         * 一次性全量返回会撑大 HTTP 响应、拖慢家长端渲染；服务端截断到最近的 N 条
         * （已按时间倒序）既够家长回溯，也避免无界增长。N 取 500 是"一个月每天约 16 次"的余量。
         */
        const val APP_USAGE_HISTORY_MAX = 500
    }

    /** 新 usage 日志入库后调用，使该设备接下来的 today() 重算而非命中旧缓存 */
    fun invalidateToday(deviceId: String) {
        todayCache.remove(deviceId)
    }

    fun today(userId: String, deviceId: String): UsageStatsDto {
        requireOwned(userId, deviceId)
        val cached = todayCache[deviceId]
        if (cached != null && System.currentTimeMillis() - cached.ts < TODAY_CACHE_TTL_MS) {
            return cached.dto
        }
        // 过期条目顺手摘除：设备一旦停止上报，其 todayCache 条目在进程生命周期内永不失效会无限堆积
        // （invalidateToday 只在收到新日志时触发），形成缓慢内存泄漏。访问到过期项即移除，
        // 让缓存随"曾见过的设备数"自然回落，而非无界增长。
        if (cached != null) {
            todayCache.remove(deviceId, cached)
        }
        val (start, end) = dayRange(0)
        val agg = aggregate(deviceId, start, end)
        // 头条「今日已用」唯一权威口径：
        // 设备当日存在进行中的使用会话时，必须取 UsageSession 服务端派生的已用时长
        // （= 墙钟流逝、封顶到额度），与孩子端首页、锁机判定三端同源；
        // 否则（未开始计时 / 离线）才回退到上报累计用量的当天 MAX，避免两端数字定义不一致
        // 而长期对不上（详见 [com.padguard.server.service.UsageSessionService.usedMinutesIfActive]）。
        val totalMin = usageSessionService.usedMinutesIfActive(deviceId) ?: agg.totalMinutes()
        val appUsages = agg.topApps(50) { pkg -> null }
        val dto = UsageStatsDto(deviceId, todayStr(), totalMin, resolveIcons(deviceId, appUsages))
        todayCache[deviceId] = CachedToday(dto, System.currentTimeMillis())
        return dto
    }

    fun usage(userId: String, deviceId: String, period: String): StatisticsReportDto {
        requireOwned(userId, deviceId)
        val days = daysOf(period)
        val (start, end) = range(days)

        // 一次分页折叠拿到「总量 + 逐日 + 按应用」三份结果：
        // 旧实现是先全量捞日志，再按天 filter 一遍（30 天 = 30 趟全扫），再来一次按应用 groupBy。
        val agg = aggregate(deviceId, start, end)
        val daily = (0 until days).map { d ->
            val date = LocalDate.now(zone).minusDays(d.toLong())
            DailyUsageDto(dayStr(d), agg.minutesOn(date), agg.appLogCountOn(date))
        }
        val events = deviceEventRepository.findByDeviceIdAndTimestampBetween(deviceId, start, end)
        // 计数下沉到 SQL：原先是捞回该用户全部 ACTIVE 告警再在内存里 count 出属于本设备的，
        // 告警累积后这里会拉回大量用不到的行。
        val alertCount = alertRepository.countByUserIdAndStatusAndDeviceId(userId, "ACTIVE", deviceId).toInt()
        return StatisticsReportDto(
            deviceId = deviceId, period = period, startDate = dayStr(days - 1), endDate = todayStr(),
            totalUsageMinutes = agg.totalMinutes(), dailyUsages = daily,
            topApps = resolveIcons(deviceId, agg.topApps(10) { pkg -> null }),
            violationCount = events.size, alertCount = alertCount
        )
    }

    /**
     * 单应用在指定周期内的逐次使用记录（按时间倒序）。
     *
     * 数据源就是已有的 `APP_USAGE` 用量日志：孩子端每次上报一个使用段（带 startAt / endAt），
     * 因此不需要孩子端再做任何新上报 —— 历史一直都在库里，缺的只是把它按应用捞出来的出口。
     *
     * 之所以早期的 "应用使用历史" 页只能报错：该出口从未实现，而家长端的入口已经通到二级页，
     * 点进去必然落到错误态。这里补上，同时保持与 [topApps] 一致的时区口径（服务端统一时区）。
     *
     * 读取走分页流式（[UsageLogPager]）：月视图下 APP_USAGE 也可达数万行，
     * 全量捞回再 filter 包名同样会吃内存。
     *
     * @param packageName 目标应用包名
     * @param period      daily / weekly / monthly
     */
    fun appUsageHistory(
        userId: String,
        deviceId: String,
        packageName: String,
        period: String
    ): List<AppUsageHistoryEntryDto> {
        requireOwned(userId, deviceId)
        if (packageName.isBlank()) return emptyList()
        val days = daysOf(period)
        val (start, end) = range(days)

        val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
        val result = ArrayList<AppUsageHistoryEntryDto>()

        readLogs(deviceId, start, end, APP_ONLY) { log ->
            val p = parsePayload(log)
            if (p["packageName"] as? String != packageName) return@readLogs
            // 缺 startAt/endAt 的旧日志无法还原"一次打开到关闭"的区间，直接跳过而不是塞 0——
            // 占位数据会让家长误以为孩子"在某个时间打开了但没关"，比不显示更糟。
            val s = UsageAggregator.asLong(p["startAt"]) ?: return@readLogs
            val e = UsageAggregator.asLong(p["endAt"]) ?: return@readLogs
            if (e < s) return@readLogs
            val startDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(s), zone)
            val endDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(e), zone)
            val sec = durationSecOf(p).takeIf { it > 0 } ?: ((e - s) / 1000).toInt()
            result += AppUsageHistoryEntryDto(
                date = startDt.format(dateFmt),
                startTime = startDt.format(timeFmt),
                endTime = endDt.format(timeFmt),
                usageSeconds = sec
            )
        }
        return result.sortedByDescending { "${it.date} ${it.startTime}" }.take(APP_USAGE_HISTORY_MAX)
    }

    fun web(userId: String, deviceId: String, period: String): WebActivityStatsDto {
        requireOwned(userId, deviceId)
        val days = daysOf(period)
        val (start, end) = range(days)

        var visits = 0
        var blocked = 0
        val domainCounts = HashMap<String, Int>()
        readLogs(deviceId, start, end, WEB_TYPES) { log ->
            when (log.type) {
                "URL_VISIT" -> {
                    visits++
                    domainOf(parsePayload(log))?.let { domainCounts.merge(it, 1, Int::plus) }
                }
                "URL_BLOCKED" -> blocked++
                else -> Unit
            }
        }
        val domains = domainCounts.toList().sortedByDescending { it.second }.take(10)
            .map { DomainVisitDto(it.first, it.second, System.currentTimeMillis()) }
        return WebActivityStatsDto(visits, domains, blocked)
    }

    fun violations(userId: String, deviceId: String, period: String): ViolationStatsDto {
        requireOwned(userId, deviceId)
        val days = daysOf(period)
        val (start, end) = range(days)
        // 违规事件量级远小于用量日志（一次违规一条），保持一次性读取；
        // 若后续事件也开始按秒上报，同样应改为 [UsageLogPager] 式分页。
        val events = deviceEventRepository.findByDeviceIdAndTimestampBetween(deviceId, start, end)
        val byCategory = events.groupingBy { it.type ?: "OTHER" }.eachCount()
        val trend = (0 until days).map { d ->
            val (ds, de) = dayRange(d)
            DailyViolationDto(dayStr(d), events.count { (it.timestamp ?: 0) in ds..de })
        }
        return ViolationStatsDto(events.size, byCategory, trend)
    }

    fun export(userId: String, deviceId: String, period: String): ExportResultDto {
        val report = usage(userId, deviceId, period)
        val sb = StringBuilder()
        sb.appendLine("设备,$deviceId,周期,$period")
        sb.appendLine("总使用时长(分钟),${report.totalUsageMinutes}")
        sb.appendLine("违规次数,${report.violationCount}")
        sb.appendLine("告警数,${report.alertCount}")
        sb.appendLine("日期,时长(分钟),违规数")
        report.dailyUsages?.forEach { sb.appendLine("${it.date},${it.usageMinutes},${it.violationCount}") }
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        // 导出报表含孩子的使用明细，归属到请求者，避免下载链接泄露后被别人拉走
        val url = fileStorageService.store(
            "text/csv", bytes, "report_${deviceId}_$period.csv", ownerUserId = userId
        )
        return ExportResultDto(url, "report_${deviceId}_$period.csv", bytes.size.toLong())
    }

    // ---------- 聚合与读取 ----------

    /**
     * 分页折叠出该设备在 [start, end] 内的用量聚合结果。
     *
     * **这是本类唯一读取用量日志的入口**：所有"总时长 / 逐日 / 按应用"都从这一趟折叠里出，
     * 避免同一批日志为不同指标被反复扫描与反序列化（旧实现月视图要读 30+ 趟）。
     */
    private fun aggregate(deviceId: String, start: Long, end: Long): UsageAggregator {
        val aggregator = UsageAggregator(zone, ::parsePayload)
        readLogs(deviceId, start, end, UsageAggregator.USAGE_TYPES) { aggregator.accept(it) }
        return aggregator
    }

    /** 分页读取指定类型的日志并逐条消费（类型过滤在 SQL 侧完成） */
    private fun readLogs(deviceId: String, start: Long, end: Long, types: List<String>, consume: (UsageLog) -> Unit) {
        UsageLogPager.read(
            fetch = { pageable ->
                usageLogRepository.findByDeviceIdAndTypeInAndTimestampBetween(deviceId, types, start, end, pageable)
            },
            consume = consume
        )
    }

    /** 按包名回查图标：聚合阶段不碰 DB，避免每折叠一条日志就打一次库 */
    private fun resolveIcons(deviceId: String, apps: List<AppUsageDto>): List<AppUsageDto> {
        if (apps.isEmpty()) return apps
        val iconOf = installedAppRepository.findByDeviceId(deviceId).associate { it.packageName to it.iconUrl }
        if (iconOf.isEmpty()) return apps
        return apps.map { if (it.iconUrl == null) it.copy(iconUrl = iconOf[it.packageName]) else it }
    }

    private fun durationSecOf(payload: Map<String, Any?>): Int = when (val raw = payload["durationSec"]) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull() ?: 0
        else -> 0
    }

    private fun domainOf(payload: Map<String, Any?>): String? {
        val url = payload["url"] as? String ?: return null
        return runCatching { java.net.URI(url).host }.getOrNull() ?: url.split("/").getOrNull(0)
    }

    private fun parsePayload(log: UsageLog): Map<String, Any?> =
        log.payloadJson?.let { runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull() } ?: emptyMap()

    /**
     * 设备归属校验已收敛至 [com.padguard.server.security.DeviceOwnershipGuard]（唯一实现），
     * 消除逐 Service 复制私有副本的漏网风险（详见该组件类注释）。
     */
    private fun requireOwned(userId: String, deviceId: String) =
        ownershipGuard.requireOwned(userId, deviceId)

    private fun daysOf(period: String): Int = when (period) { "weekly" -> 7; "monthly" -> 30; else -> 1 }

    /**
     * 统计区间统一取半开 `[start, end)`：Spring Data 的 `Between` 与 Kotlin `in ds..de`
     * 都是**闭区间**，end 若取次日零点整毫秒，恰好落在该时刻的一条日志/事件会
     * 同时计入相邻两天，trend 求和大于总数。减 1ms 即等价半开，且不漏当天最后 1ms。
     */
    private fun range(days: Int): Pair<Long, Long> {
        val end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val start = LocalDate.now(zone).minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    private fun dayRange(offsetDays: Int): Pair<Long, Long> {
        val day = LocalDate.now(zone).minusDays(offsetDays.toLong())
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return start to end
    }

    private fun dayStr(offsetDays: Int): String =
        LocalDate.now(zone).minusDays(offsetDays.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun todayStr(): String = LocalDate.now(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)
}
