package com.padguard.server.service

import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.UsageSession
import com.padguard.server.dto.UsageSessionDto
import com.padguard.server.repository.DeviceSettingRepository
import com.padguard.server.repository.UsageSessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Collections
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 日切「计时开始」的并发护栏。
 *
 * `start` 原实现是「读实体 → 判断跨日 → 改字段 → save(整行)」。孩子端一次开机可能
 * 同时走 MQTT 与 HTTP 双通道，或网络重试让两个请求几乎同时到达；两个事务都读到
 * 昨天的旧快照时**都会判定需要重置**，各自拿自己的 `System.currentTimeMillis()`
 * 写回整行：
 * - 后写者把计时起点推迟（孩子凭空多出一段可用时长）；
 * - 上一次 `reportRest` 用 CAS 累加的休息时长 / 去重 id 被整行覆盖清零，
 *   CAS 白做，孩子提前被锁屏。
 *
 * 判据用「两次调用必须返回同一个 startEpoch」而不是「写没写库」：前者是产品契约
 * （重启 / 双通道不能刷新额度），后者是实现细节。仓储刻意返回独立快照并在读之后
 * 设屏障，把偶发竞态变成确定性复现（同 [UsageSessionRestConcurrencyTest] 的口径）。
 */
@DisplayName("UsageSessionService.start —— 并发日切重置必须幂等")
class UsageSessionStartConcurrencyTest {

    private val today: String = Instant.now().atZone(SERVER_ZONE_ID).toLocalDate().toString()

    private fun buildService(repo: UsageSessionRepository): UsageSessionService {
        val settings = mock(DeviceSettingRepository::class.java)
        `when`(settings.findById(anyString())).thenReturn(Optional.empty())
        val delay = mock(UsageDelayService::class.java)
        `when`(delay.pendingDelayMinutes(anyString())).thenReturn(0)
        return UsageSessionService(repo, settings, mock(DeviceService::class.java), delay, mock(UsageStartRequestService::class.java))
    }

    private fun staleSession(now: Long) = UsageSession(
        deviceId = "device-1",
        dayKey = "2000-01-01",
        startEpoch = now - 86_400_000L,
        quotaMinutes = 120,
        extendedMinutes = 30,
        restAccumulatedMs = 60_000L,
        restIds = "old-rest",
        createdAt = now - 86_400_000L,
        updatedAt = now - 86_400_000L
    )

    @Test
    @DisplayName("两个并发 start 只能落一个计时起点，且权威行与返回值一致")
    fun `concurrent rollover starts agree on one startEpoch`() {
        val repo = StaleSnapshotRepo()
        repo.coordinateReads = true
        val seedAt = System.currentTimeMillis()
        repo.table["device-1"] = staleSession(seedAt)
        val service = buildService(repo)

        val go = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<UsageSessionDto>())
        val t1 = Thread { go.await(); results.add(service.start("device-1")) }
        // 错开 40ms：Windows 的 currentTimeMillis 精度约 15ms，保证两次 now 确实不同，
        // 否则旧实现下两个 DTO 可能碰巧相等，护栏假绿。
        val t2 = Thread { go.await(); Thread.sleep(40); results.add(service.start("device-1")) }
        t1.start(); t2.start()
        go.countDown()
        t1.join(10_000); t2.join(10_000)

        assertEquals(2, results.size, "两个线程都必须正常返回")
        val epochs = results.map { it.startEpoch }.toSet()
        assertEquals(
            1, epochs.size,
            "两次并发 start 返回了不同起点 $epochs —— 后写者把计时起点推迟/整行覆盖，" +
                "孩子可凭重试刷新额度，且会把并发写入的休息时长清掉"
        )
        assertEquals(
            epochs.single(), repo.table["device-1"]!!.startEpoch,
            "数据库权威行的起点必须与两次返回值一致"
        )
        assertEquals(1, repo.resetWrites.get(), "整行 save 发生了 ${repo.saveWrites.get()} 次，跨日重置应当只写一次")
    }

    @Test
    @DisplayName("今日已开始时 start 绝不写库")
    fun `start is read-only when today already started`() {
        val repo = StaleSnapshotRepo()
        val now = System.currentTimeMillis()
        repo.table["device-1"] = UsageSession(
            deviceId = "device-1", dayKey = today, startEpoch = now - 10 * 60_000L,
            quotaMinutes = 120, createdAt = now, updatedAt = now
        )
        val service = buildService(repo)

        val before = repo.table["device-1"]!!.startEpoch
        val dto = service.start("device-1")

        assertEquals(before, dto.startEpoch)
        assertEquals(0, repo.saveWrites.get(), "今日已开始仍整行写回，会覆盖并发的延时/休息累加")
    }

    @Test
    @DisplayName("跨日重置清零延时与护眼休息累计")
    fun `rollover clears yesterday accumulators`() {
        val repo = StaleSnapshotRepo()
        repo.table["device-1"] = staleSession(System.currentTimeMillis())
        val service = buildService(repo)

        val dto = service.start("device-1")
        val row = repo.table["device-1"]!!

        assertEquals(today, row.dayKey)
        assertEquals(0, row.extendedMinutes)
        assertEquals(0L, row.restAccumulatedMs)
        assertEquals("", row.restIds)
        assertTrue(dto.active)
    }

    /**
     * 模拟真实事务隔离：每次读都拿到独立快照。前两次读用屏障卡住，强制两个线程
     * 都基于「昨天」的旧值继续走重置分支；之后的读（CAS 输家重读）不再进屏障。
     */
    private class StaleSnapshotRepo : FakeSessionRepo() {
        private val barrier = CyclicBarrier(2)
        private val reads = AtomicInteger(0)
        val saveWrites = AtomicInteger(0)
        val resetWrites = AtomicInteger(0)
        /** 单线程用例不能进屏障（只有一方会永远等超时）。 */
        var coordinateReads = false

        @Synchronized
        override fun findByDeviceId(deviceId: String): UsageSession? {
            val snapshot = table[deviceId]?.let { s ->
                UsageSession(
                    deviceId = s.deviceId, dayKey = s.dayKey, startEpoch = s.startEpoch,
                    quotaMinutes = s.quotaMinutes, extendedMinutes = s.extendedMinutes,
                    lastDelayEpoch = s.lastDelayEpoch, restAccumulatedMs = s.restAccumulatedMs,
                    restIds = s.restIds, createdAt = s.createdAt, updatedAt = s.updatedAt
                )
            }
            if (coordinateReads && reads.getAndIncrement() < 2) {
                runCatching { barrier.await(2, TimeUnit.SECONDS) }
            }
            return snapshot
        }

        /** 与 JpaRepository.save 一致：整行覆盖。用于暴露读-改-写丢更新。 */
        @Synchronized
        override fun <S : UsageSession> save(entity: S): S {
            saveWrites.incrementAndGet()
            resetWrites.incrementAndGet()
            table[entity.deviceId] = entity
            return entity
        }

        @Synchronized
        override fun startFreshIfStale(
            deviceId: String, dayKey: String, startEpoch: Long, quotaMinutes: Int, now: Long
        ): Int {
            val updated = super.startFreshIfStale(deviceId, dayKey, startEpoch, quotaMinutes, now)
            if (updated > 0) resetWrites.incrementAndGet()
            return updated
        }
    }
}
