package com.padguard.server.service

import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.UsageLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import java.time.LocalDate

/**
 * 用量统计「两层聚合」的回归测试（R-P3-9）。
 *
 * 这些断言锁的是**线上真出现过的事故口径**，而不是实现细节：
 * - 2026-10-03 现场：孩子端 3 分钟、家长端 1 小时 40 分 —— 同一天的累计值被求和了上百次；
 * - 旧版孩子端只报 APP_USAGE，没有 USAGE_TOTAL —— 若整批按"有 TOTAL 就只用 TOTAL"判定，
 *   没有 TOTAL 的那天会显示 0。
 */
class UsageAggregatorTest {

    private val zone = SERVER_ZONE_ID
    private val today: LocalDate = LocalDate.now(zone)
    private val yesterday: LocalDate = today.minusDays(1)

    // ---------- 工具 ----------

    private fun tsOf(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun log(
        type: String,
        date: LocalDate,
        hour: Int,
        minute: Int,
        payload: String
    ) = UsageLog(
        id = "$type-$date-$hour-$minute-${payload.hashCode()}",
        deviceId = "dev-1",
        logId = "$type-$date-$hour-$minute-${payload.hashCode()}",
        type = type,
        timestamp = tsOf(date, hour, minute),
        payloadJson = payload,
        receivedAt = 0
    )

    private fun total(date: LocalDate, hour: Int, minute: Int, sec: Int, cumulative: Boolean) =
        log("USAGE_TOTAL", date, hour, minute, """{"durationSec":$sec,"cumulative":$cumulative}""")

    private fun app(
        pkg: String,
        date: LocalDate,
        hour: Int,
        minute: Int,
        sec: Int,
        cumulative: Boolean,
        name: String? = pkg,
        startAt: Long? = null,
        endAt: Long? = null
    ): UsageLog {
        val nameJson = name?.let { ""","appName":"$it"""" } ?: ""
        val range = (startAt?.let { ""","startAt":$it""" } ?: "") + (endAt?.let { ""","endAt":$it""" } ?: "")
        return log(
            "APP_USAGE", date, hour, minute,
            """{"packageName":"$pkg","durationSec":$sec,"cumulative":$cumulative$nameJson$range}"""
        )
    }

    private fun fold(vararg logs: UsageLog): UsageAggregator {
        val agg = UsageAggregator(zone) { it.payloadJson?.let(::parse) ?: emptyMap() }
        logs.forEach(agg::accept)
        return agg
    }

    private fun parse(json: String): Map<String, Any?> {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        @Suppress("UNCHECKED_CAST")
        return mapper.readValue(json, Map::class.java) as Map<String, Any?>
    }

    // ---------- 总时长口径 ----------

    @Test
    fun `同日多条累计值取最大值而非求和_RP39`() {
        // 同一天 18 条累计值（10 秒一条，到 180 秒时正好 3 分钟），真实答案 = 180 秒 = 3 分钟
        val logs = (1..18).map { i -> total(today, 9, i - 1, i * 10, cumulative = true) }
        val agg = fold(*logs.toTypedArray())
        assertEquals(3, agg.totalMinutes(), "同一天的累计值必须取 MAX；求和会得到 28 分钟的错误结果")
    }

    @Test
    fun `旧版增量日志按段求和_RP39`() {
        // 早期孩子端上报增量且不带 cumulative 标记：3 段各 60 秒 = 3 分钟
        val logs = listOf(
            total(yesterday, 9, 0, 60, cumulative = false),
            total(yesterday, 9, 1, 60, cumulative = false),
            total(yesterday, 9, 2, 60, cumulative = false)
        )
        assertEquals(3, fold(*logs.toTypedArray()).totalMinutes())
    }

    @Test
    fun `同日新旧混合时取最大值_RP39`() {
        // 升级当天：先有增量段，后出现累计值。累计值恒 >= 增量之和，MAX 自然取到真实累计
        val logs = listOf(
            total(today, 9, 0, 60, cumulative = false),
            total(today, 9, 1, 60, cumulative = false),
            total(today, 9, 2, 150, cumulative = true)
        )
        assertEquals(2, fold(*logs.toTypedArray()).totalMinutes())
    }

    @Test
    fun `跨天总量等于各天真实累计之和_RP39`() {
        val logs = listOf(
            total(yesterday, 9, 0, 300, cumulative = true),   // 昨天 5 分钟
            total(yesterday, 9, 1, 600, cumulative = true),   // 昨天 10 分钟（MAX）
            total(today, 9, 0, 120, cumulative = true)        // 今天 2 分钟
        )
        val agg = fold(*logs.toTypedArray())
        assertEquals(12, agg.totalMinutes())
        assertEquals(10, agg.minutesOn(yesterday))
        assertEquals(2, agg.minutesOn(today))
    }

    @Test
    fun `某天没有USAGE_TOTAL时按天回退到APP_USAGE_RP39`() {
        // 昨天：新版报 USAGE_TOTAL；今天：旧版只有 APP_USAGE。
        // 若按"整批有 TOTAL 就只用 TOTAL"判定，今天会显示 0 —— 这是必须锁死的按天选源语义。
        val logs = listOf(
            total(yesterday, 9, 0, 600, cumulative = true),
            app("com.a", today, 10, 0, 180, cumulative = true),
            app("com.a", today, 10, 1, 240, cumulative = true)
        )
        val agg = fold(*logs.toTypedArray())
        assertEquals(10, agg.minutesOn(yesterday))
        assertEquals(4, agg.minutesOn(today), "今天只有 APP_USAGE，必须回退到它而不是显示 0")
        assertEquals(14, agg.totalMinutes())
    }

    @Test
    fun `没有任何用量日志时总时长为零_RP39`() {
        val agg = fold(log("URL_VISIT", today, 9, 0, """{"url":"https://a.com/x"}"""))
        assertTrue(agg.isEmpty())
        assertEquals(0, agg.totalMinutes())
        assertTrue(agg.topApps(10) { null }.isEmpty())
    }

    // ---------- 按应用聚合 ----------

    @Test
    fun `按应用先取当天最大值再跨天求和_RP39`() {
        val startAt = tsOf(today, 9, 0)
        val endAt = tsOf(today, 9, 5)
        val logs = listOf(
            // 今天：同一天 3 条累计值，真实 20 分钟
            app("com.game", today, 9, 0, 600, cumulative = true, startAt = startAt, endAt = endAt),
            app("com.game", today, 9, 1, 900, cumulative = true, startAt = startAt, endAt = endAt),
            app("com.game", today, 9, 2, 1200, cumulative = true, startAt = startAt, endAt = endAt),
            // 昨天：一次 600 秒
            app("com.game", yesterday, 9, 0, 600, cumulative = true)
        )
        val apps = fold(*logs.toTypedArray()).topApps(10) { null }
        assertEquals(1, apps.size)
        assertEquals(30, apps[0].usageMinutes, "20(今天MAX) + 10(昨天) = 30，不能把今天三条求和成 55")
        assertEquals(4, apps[0].launchCount)
        assertEquals(startAt, apps[0].startAt!!)
        assertEquals(endAt, apps[0].endAt!!)
        assertEquals("com.game", apps[0].appName)
        assertNull(apps[0].iconUrl)
    }

    @Test
    fun `按应用聚合按分钟降序取前N_RP39`() {
        val logs = listOf(
            app("com.small", today, 9, 0, 60, cumulative = true),
            app("com.big", today, 9, 0, 600, cumulative = true),
            app("com.mid", today, 9, 0, 300, cumulative = true)
        )
        val apps = fold(*logs.toTypedArray()).topApps(2) { null }
        assertEquals(listOf("com.big", "com.mid"), apps.map { it.packageName })
    }

    @Test
    fun `包名缺失时归入unknown而不是丢弃_RP39`() {
        val logs = listOf(app("", today, 9, 0, 600, cumulative = true, name = null))
        val apps = fold(*logs.toTypedArray()).topApps(10) { null }
        assertEquals(1, apps.size)
        assertEquals("unknown", apps[0].packageName)
    }

    @Test
    fun `appLogCountOn统计当天APP_USAGE条数_RP39`() {
        val logs = listOf(
            app("com.a", today, 9, 0, 60, cumulative = true),
            app("com.b", today, 9, 1, 60, cumulative = true),
            total(today, 9, 2, 60, cumulative = true)
        )
        val agg = fold(*logs.toTypedArray())
        assertEquals(2, agg.appLogCountOn(today), "USAGE_TOTAL 不计入应用使用次数")
        assertEquals(0, agg.appLogCountOn(yesterday))
    }

    @Test
    fun `durationSec为字符串时也能解析_RP39`() {
        val logs = listOf(log("USAGE_TOTAL", today, 9, 0, """{"durationSec":"600","cumulative":"true"}"""))
        assertEquals(10, fold(*logs.toTypedArray()).totalMinutes())
    }

    // ---------- 分页读取 ----------

    @Test
    fun `分页读取不重不漏_PAGER`() {
        val all = (1..(UsageLogPager.PAGE_SIZE * 2 + 7)).map { i ->
            UsageLog(
                id = "id-$i", deviceId = "dev-1", logId = "log-$i", type = "USAGE_TOTAL",
                timestamp = i.toLong(), payloadJson = """{"durationSec":$i}""", receivedAt = 0
            )
        }
        val seen = ArrayList<String>()
        val read = UsageLogPager.read(
            fetch = { pageable: PageRequest -> all.drop(pageable.offset.toInt()).take(pageable.pageSize) },
            consume = { seen += it.id }
        )
        assertEquals(all.size, read)
        assertEquals(all.size, seen.size)
        assertEquals(all.map { it.id }, seen, "顺序必须是 (timestamp,id) 升序，否则 OFFSET 分页会重复/漏读")
    }

    @Test
    fun `恰好整数页时不会多读_PAGER`() {
        val all = (1..(UsageLogPager.PAGE_SIZE * 2)).map { i ->
            UsageLog(id = "id-$i", deviceId = "dev-1", logId = "log-$i", type = "APP_USAGE", timestamp = i.toLong())
        }
        var calls = 0
        val seen = ArrayList<String>()
        UsageLogPager.read(
            fetch = { pageable: PageRequest ->
                calls++
                all.drop(pageable.offset.toInt()).take(pageable.pageSize)
            },
            consume = { seen += it.id }
        )
        assertEquals(all.size, seen.size)
        // 两满页 + 一次空页收尾，绝不因"满页即认为还有下一页"而漏掉结尾
        assertEquals(3, calls)
        assertFalse(seen.isEmpty())
    }

    @Test
    fun `空数据集直接结束_PAGER`() {
        var calls = 0
        val read = UsageLogPager.read(fetch = { calls++; emptyList() }, consume = {})
        assertEquals(0, read)
        assertEquals(1, calls)
    }
}
