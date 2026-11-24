package com.padguard.core.data.repository

import com.padguard.core.common.TimeProvider
import com.padguard.core.data.db.AppUsageDao
import com.padguard.core.data.db.AppUsageEntity
import com.padguard.core.data.db.DailyUsageDao
import com.padguard.core.data.db.DailyUsageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * [UsageRepository] 的日切口径与时间戳口径测试。
 *
 * 钉住两条**曾经真实错位**的性质：
 *
 * 1. **日切键必须是公历 + 固定时区**。原实现是
 *    `SimpleDateFormat("yyyyMMdd", Locale.getDefault())` —— 时区钉死了，历法却没钉。
 *    在 `th-TH`（佛历）下同一时刻会被格式化成 `25691107`，与服务端 ISO 日界、
 *    `PolicyEngine.currentDayKey()` 永久对不上：`UsageSessionManager` 每次启动都判成
 *    "跨日"并丢掉当天的会话快照（离线等于当天无额度），`AppLimitEnforcer.parseDayKey`
 *    会把星期算错，使「工作日/周末」单应用规则在错误的日子生效。
 * 2. **分桶时刻与记录时刻必须同一个**。原实现分桶用受信钟、`lastUpdateAt/firstUsedAt/lastUsedAt`
 *    用裸墙钟：改系统时间后用量仍进对的那一天，但"最近使用时段"与上报的行为日志时间戳
 *    跳到别处，家长端看到的证据可被孩子单方面改写。
 *
 * fake DAO 说明：Room `@Dao` 是纯接口，这里记录实参即可验证仓库层口径，
 * SQL 语义由 Room 编译期校验覆盖。
 */
class UsageRepositoryTest {

    private fun epochOf(date: String, hour: Int, minute: Int): Long =
        LocalDate.parse(date).atTime(hour, minute)
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

    private class FakeAppUsageDao : AppUsageDao {
        var lastDayKey: String? = null
        var lastPackage: String? = null
        var lastDelta: Long = 0
        var lastNow: Long = 0
        val rows = LinkedHashMap<String, AppUsageEntity>()
        var deletedBefore: String? = null

        override fun observeByDay(dayKey: String): Flow<List<AppUsageEntity>> = emptyFlow()
        override suspend fun snapshotByDay(dayKey: String): List<AppUsageEntity> = emptyList()
        override suspend fun get(dayKey: String, pkg: String): AppUsageEntity? = rows["$dayKey|$pkg"]
        override suspend fun upsert(entity: AppUsageEntity) {
            rows["${entity.dayKey}|${entity.packageName}"] = entity
        }

        override suspend fun addUsage(dayKey: String, pkg: String, deltaMs: Long, now: Long) {
            lastDayKey = dayKey
            lastPackage = pkg
            lastDelta = deltaMs
            lastNow = now
            super.addUsage(dayKey, pkg, deltaMs, now)
        }

        override suspend fun addLaunch(dayKey: String, pkg: String, now: Long) {
            lastDayKey = dayKey
            lastPackage = pkg
            lastNow = now
            super.addLaunch(dayKey, pkg, now)
        }

        override suspend fun deleteDay(dayKey: String) {
            rows.keys.removeAll { it.startsWith("$dayKey|") }
        }

        override suspend fun deleteBefore(beforeDayKey: String) {
            deletedBefore = beforeDayKey
        }
    }

    private class FakeDailyUsageDao : DailyUsageDao {
        var lastDayKey: String? = null
        var lastNow: Long = 0
        val rows = LinkedHashMap<String, DailyUsageEntity>()
        var deletedBefore: String? = null

        override suspend fun get(dayKey: String): DailyUsageEntity? = rows[dayKey]
        override fun observe(dayKey: String): Flow<DailyUsageEntity?> = emptyFlow()
        override suspend fun upsert(entity: DailyUsageEntity) {
            rows[entity.dayKey] = entity
        }

        override suspend fun addUsage(dayKey: String, deltaMs: Long, now: Long) {
            lastDayKey = dayKey
            lastNow = now
            super.addUsage(dayKey, deltaMs, now)
        }

        override suspend fun deleteBefore(beforeDayKey: String) {
            deletedBefore = beforeDayKey
        }
    }

    // ==================== 日切键：历法与时区 ====================

    @Test
    fun `dayKey is gregorian regardless of device locale calendar`() {
        val original = Locale.getDefault()
        try {
            val ts = epochOf("2026-11-07", 12, 0)
            // 佛历（th-TH）下 SimpleDateFormat 会输出 25691107，伊斯兰历（ar-SA）又是另一套。
            // 无论设备 Locale 是什么，日切键都必须与服务端 ISO 口径一致。
            for (tag in listOf("th-TH", "ar-SA", "en-US", "zh-CN")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val repo = UsageRepository(FakeAppUsageDao(), FakeDailyUsageDao(), TimeProvider())
                assertEquals("locale=$tag 的日切键必须与公历一致", "20261107", repo.dayKey(ts))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `dayKey switches at Asia Shanghai midnight not device timezone`() {
        val repo = UsageRepository(FakeAppUsageDao(), FakeDailyUsageDao(), TimeProvider())
        val beforeMidnight = epochOf("2026-11-07", 23, 59)
        val afterMidnight = epochOf("2026-11-08", 0, 0)

        assertEquals("20261107", repo.dayKey(beforeMidnight))
        assertEquals("20261108", repo.dayKey(afterMidnight))
        // 同一天内任意时刻必须落在同一个桶里（否则一次使用会被拆成多天）
        assertEquals("20261107", repo.dayKey(epochOf("2026-11-07", 0, 0)))
        assertEquals("20261107", repo.dayKey(epochOf("2026-11-07", 23, 59)))
    }

    @Test
    fun `dayKey is compact ISO basic format without separators`() {
        val repo = UsageRepository(FakeAppUsageDao(), FakeDailyUsageDao(), TimeProvider())
        val key = repo.dayKey(epochOf("2026-01-05", 9, 30))
        assertEquals("20260105", key)
        // 与服务端 ISO（2026-01-05）之间靠"去符号"归一化对齐，位数必须是 8
        assertEquals(8, key.length)
        assertTrue(key.all { it.isDigit() })
    }

    // ==================== 分桶时刻与记录时刻同源 ====================

    @Test
    fun `addAppUsage uses one instant for both bucket and timestamps`() = runBlocking {
        val appDao = FakeAppUsageDao()
        val repo = UsageRepository(appDao, FakeDailyUsageDao(), TimeProvider())
        val ts = epochOf("2026-11-07", 21, 30)

        repo.addAppUsage("com.game", 60_000L, ts)

        assertEquals("20261107", appDao.lastDayKey)
        assertEquals("分桶时刻与记录时刻必须是同一个", ts, appDao.lastNow)
        val row = appDao.rows["20261107|com.game"]!!
        assertEquals(ts, row.lastUpdateAt)
        assertEquals(ts, row.firstUsedAt)
        assertEquals(ts, row.lastUsedAt)
    }

    @Test
    fun `addAppLaunch uses one instant for both bucket and timestamps`() = runBlocking {
        val appDao = FakeAppUsageDao()
        val repo = UsageRepository(appDao, FakeDailyUsageDao(), TimeProvider())
        val ts = epochOf("2026-11-08", 8, 15)

        repo.addAppLaunch("com.video", ts)

        assertEquals("20261108", appDao.lastDayKey)
        assertEquals(ts, appDao.lastNow)
        assertEquals(ts, appDao.rows["20261108|com.video"]!!.lastUsedAt)
    }

    @Test
    fun `addTotalUsage uses one instant for both bucket and timestamps`() = runBlocking {
        val dailyDao = FakeDailyUsageDao()
        val repo = UsageRepository(FakeAppUsageDao(), dailyDao, TimeProvider())
        val ts = epochOf("2026-12-31", 23, 59)

        repo.addTotalUsage(120_000L, ts)

        assertEquals("20261231", dailyDao.lastDayKey)
        assertEquals("跨年边界也必须落对桶且时刻同源", ts, dailyDao.lastNow)
        assertEquals(ts, dailyDao.rows["20261231"]!!.lastUpdateAt)
    }

    @Test
    fun `zero or negative delta never writes`() = runBlocking {
        val appDao = FakeAppUsageDao()
        val dailyDao = FakeDailyUsageDao()
        val repo = UsageRepository(appDao, dailyDao, TimeProvider())

        repo.addAppUsage("com.game", 0L, epochOf("2026-11-07", 10, 0))
        repo.addAppUsage("com.game", -5L, epochOf("2026-11-07", 10, 0))
        repo.addTotalUsage(0L, epochOf("2026-11-07", 10, 0))

        assertEquals(null, appDao.lastDayKey)
        assertEquals(null, dailyDao.lastDayKey)
        assertTrue(appDao.rows.isEmpty())
        assertTrue(dailyDao.rows.isEmpty())
    }

    // ==================== 清理阈值 ====================

    @Test
    fun `purgeOlderThan cutoff never lands on today`() = runBlocking {
        val appDao = FakeAppUsageDao()
        val dailyDao = FakeDailyUsageDao()
        val repo = UsageRepository(appDao, dailyDao, TimeProvider())

        repo.purgeOlderThan(7)

        val cutoff = appDao.deletedBefore
        assertEquals("两张表必须用同一个阈值", cutoff, dailyDao.deletedBefore)
        assertNotEquals("阈值不能等于今天，否则今天的用量会被当成历史删掉", repo.dayKey(), cutoff)
        assertTrue("阈值必须早于今天（字典序同日界顺序一致）", cutoff!! < repo.dayKey())
    }
}
