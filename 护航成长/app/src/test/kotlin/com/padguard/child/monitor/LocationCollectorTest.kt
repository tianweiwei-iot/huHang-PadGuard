package com.padguard.child.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocationCollector.fixAgeMs] / [LocationCollector.isCacheFresh] 的纯 JVM 单测（MON-09）。
 *
 * 锁定「位置缓存新鲜度必须用同源单调钟」这条安全语义：
 * 旧实现用 `TimeProvider.now() - Location.time`，前者是受信单调护栏时间、后者是系统墙钟，
 * 墙钟回拨会让 age 变负，于是几小时前的陈旧点被当成 `fresh=true` 的实时位置上报。
 * 这里不触碰 [android.location.Location] / [android.os.SystemClock]（android.jar 在 JVM 单测里是 stub），
 * 只验证抽出来的纯函数，覆盖回拨、对时重锚、单调标记缺失、边界值四类分支。
 */
class LocationCollectorTest {

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val NOW_WALL_MS = 1_700_000_000_000L
        const val FRESHNESS_MS = 60_000L
    }

    @Test
    fun `单调钟基准给出正确年龄`() {
        assertEquals(
            10_000L,
            LocationCollector.fixAgeMs(
                fixElapsedNanos = 1_000L * NANOS_PER_MS,
                nowElapsedNanos = 11_000L * NANOS_PER_MS,
                fixWallMs = NOW_WALL_MS - 10_000L,
                nowWallMs = NOW_WALL_MS
            )
        )
    }

    @Test
    fun `墙钟回拨不得把陈旧点判为新鲜`() {
        // 系统时间被回拨 2 小时：Location.time 反而落在"未来"，墙钟差值为负。
        // 旧实现 age 为负 → 命中 age <= freshnessMs → fresh=true 上报假实时位置。
        val age = LocationCollector.fixAgeMs(
            fixElapsedNanos = 1_000L * NANOS_PER_MS,
            nowElapsedNanos = 3_600_000L * NANOS_PER_MS, // 该点实际采于约 1 小时前
            fixWallMs = NOW_WALL_MS + 2 * 60 * 60 * 1000L,
            nowWallMs = NOW_WALL_MS
        )
        assertEquals(3_599_000L, age)
        assertFalse("一小时前的点不得被判为新鲜", LocationCollector.isCacheFresh(age, FRESHNESS_MS))
    }

    @Test
    fun `服务端对时重新锚定不得把新点判为陈旧`() {
        // TimeProvider 被服务端时间重新锚定后 now() 可能领先墙钟数十分钟；
        // 单调钟基准下 5 秒前刚拿到的点仍应被判新鲜，不至于反复唤醒 GPS。
        val age = LocationCollector.fixAgeMs(
            fixElapsedNanos = 100_000L * NANOS_PER_MS,
            nowElapsedNanos = 105_000L * NANOS_PER_MS,
            fixWallMs = NOW_WALL_MS - 5_000L - 30 * 60 * 1000L,
            nowWallMs = NOW_WALL_MS
        )
        assertEquals(5_000L, age)
        assertTrue(LocationCollector.isCacheFresh(age, FRESHNESS_MS))
    }

    @Test
    fun `单调标记缺失时退回同源墙钟差值`() {
        assertEquals(
            30_000L,
            LocationCollector.fixAgeMs(
                fixElapsedNanos = 0L,
                nowElapsedNanos = 1_000_000L * NANOS_PER_MS,
                fixWallMs = NOW_WALL_MS - 30_000L,
                nowWallMs = NOW_WALL_MS
            )
        )
    }

    @Test
    fun `单调标记缺失且墙钟回拨时判为不可信`() {
        val age = LocationCollector.fixAgeMs(
            fixElapsedNanos = 0L,
            nowElapsedNanos = 1_000_000L * NANOS_PER_MS,
            fixWallMs = NOW_WALL_MS + 60_000L,
            nowWallMs = NOW_WALL_MS
        )
        assertEquals(Long.MAX_VALUE, age)
        assertFalse("不可信基准必须判陈旧，强制主动取新点", LocationCollector.isCacheFresh(age, FRESHNESS_MS))
    }

    @Test
    fun `单调标记来自上次开机时退回墙钟`() {
        // 极少数 ROM 会返回跨重启的持久缓存点，其 elapsedRealtimeNanos 大于本次开机时长；
        // 此时不能拿它算单调年龄，应安全退回墙钟基准。
        assertEquals(
            20_000L,
            LocationCollector.fixAgeMs(
                fixElapsedNanos = 9_000_000L * NANOS_PER_MS,
                nowElapsedNanos = 1_000_000L * NANOS_PER_MS,
                fixWallMs = NOW_WALL_MS - 20_000L,
                nowWallMs = NOW_WALL_MS
            )
        )
    }

    @Test
    fun `新鲜度边界恰好等于阈值仍算新鲜`() {
        assertTrue(LocationCollector.isCacheFresh(FRESHNESS_MS, FRESHNESS_MS))
        assertFalse(LocationCollector.isCacheFresh(FRESHNESS_MS + 1L, FRESHNESS_MS))
        assertFalse(
            "负年龄（旧实现在回拨时的产物）不得判新鲜",
            LocationCollector.isCacheFresh(-5_000L, FRESHNESS_MS)
        )
        assertFalse("阈值非法时不得放行任何点", LocationCollector.isCacheFresh(0L, -1L))
    }

    // ==================== R-P3-12：离线轨迹去重键 ====================

    @Test
    fun `同一毫秒的不同坐标必须是两个点，不得互相覆盖`() {
        // 旧实现只按 ts 去重：后到的点被判"重复"直接丢弃，同时按 ts 删除还会把
        // 没传出去的那条一起删掉 —— 轨迹静默丢点且无法事后追回。
        assertNotEquals(
            outboxKey(ts = NOW_WALL_MS, lat = 31.2304, lng = 121.4737),
            outboxKey(ts = NOW_WALL_MS, lat = 31.2305, lng = 121.4738)
        )
    }

    @Test
    fun `同一毫秒同一坐标仍是同一个点，必须去重`() {
        // 同一枚 fix 被重复上报（立即定位与周期上报撞车）时不能重复入队，
        // 否则补传会反复重发同一个点。
        assertEquals(
            outboxKey(ts = NOW_WALL_MS, lat = 31.2304, lng = 121.4737),
            outboxKey(ts = NOW_WALL_MS, lat = 31.2304, lng = 121.4737)
        )
    }

    @Test
    fun `坐标相同但时间不同是两个点`() {
        assertNotEquals(
            outboxKey(ts = NOW_WALL_MS, lat = 31.2304, lng = 121.4737),
            outboxKey(ts = NOW_WALL_MS + 1L, lat = 31.2304, lng = 121.4737)
        )
    }
}
