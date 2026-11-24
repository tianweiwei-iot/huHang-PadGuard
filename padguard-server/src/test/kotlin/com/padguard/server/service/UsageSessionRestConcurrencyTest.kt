package com.padguard.server.service

import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.UsageSession
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
import java.util.Optional
import java.util.concurrent.CountDownLatch

/**
 * 护眼休息上报的并发回归。
 *
 * 关键在 [SnapshotRepo.findByDeviceId] 返回**独立快照**而不是共享引用：
 * 真实数据库里两个事务各自读到的是自己那一份行快照，随后各自 save 整行时后者覆盖前者。
 * 内存假仓储若返回同一个对象引用，两次累加会"碰巧"都落在同一对象上，缺陷被掩盖 ——
 * 那种测试永远是绿的，等于没测。
 */
@DisplayName("UsageSessionService.reportRest —— 并发上报不丢更新")
class UsageSessionRestConcurrencyTest {

    private val today: String = Instant.now().atZone(SERVER_ZONE_ID).toLocalDate().toString()

    private fun buildService(repo: UsageSessionRepository): UsageSessionService {
        val settings = mock(DeviceSettingRepository::class.java)
        `when`(settings.findById(anyString())).thenReturn(Optional.empty())
        val delay = mock(UsageDelayService::class.java)
        `when`(delay.pendingDelayMinutes(anyString())).thenReturn(0)
        return UsageSessionService(repo, settings, mock(DeviceService::class.java), delay, mock(UsageStartRequestService::class.java))
    }

    private fun freshSession() = UsageSession(
        deviceId = "device-1",
        dayKey = today,
        startEpoch = System.currentTimeMillis() - 30 * 60_000L,
        quotaMinutes = 120,
        extendedMinutes = 0,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    @Test
    @DisplayName("两段休息并发上报：累计时长与去重集合都必须保留两条")
    fun `concurrent rest reports never lose an update`() {
        val repo = SnapshotRepo()
        repo.table["device-1"] = freshSession()
        val service = buildService(repo)

        val start = CountDownLatch(1)
        val t1 = Thread { start.await(); service.reportRest("device-1", "rest-a", 60_000L) }
        val t2 = Thread { start.await(); service.reportRest("device-1", "rest-b", 90_000L) }
        t1.start(); t2.start()
        start.countDown()
        t1.join(10_000); t2.join(10_000)

        val final = repo.table["device-1"]!!
        assertEquals(
            150_000L, final.restAccumulatedMs,
            "两段休息都应计入额度；少算的一段会让孩子被提前锁屏。实际=${final.restAccumulatedMs}, restIds=${final.restIds}"
        )
        assertTrue("rest-a" in final.restIds, "去重集合丢失 rest-a（重试会被重复扣减）：${final.restIds}")
        assertTrue("rest-b" in final.restIds, "去重集合丢失 rest-b（重试会被重复扣减）：${final.restIds}")
    }

    @Test
    @DisplayName("同一 restId 并发重试只计一次")
    fun `concurrent duplicate restId is counted once`() {
        val repo = SnapshotRepo()
        repo.table["device-1"] = freshSession()
        val service = buildService(repo)

        val start = CountDownLatch(1)
        val threads = (1..4).map {
            Thread { start.await(); service.reportRest("device-1", "same-rest", 30_000L) }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(10_000) }

        val final = repo.table["device-1"]!!
        assertEquals(
            30_000L, final.restAccumulatedMs,
            "同一 restId 只应计一次；重复扣减会凭空多出可用额度。实际=${final.restAccumulatedMs}"
        )
    }

    /** 模拟真实事务隔离：每次读都拿到一份独立快照，整行 save 会覆盖并发写。 */
    private class SnapshotRepo : FakeSessionRepo() {
        @Synchronized
        override fun findByDeviceId(deviceId: String): UsageSession? =
            table[deviceId]?.let { s ->
                UsageSession(
                    deviceId = s.deviceId,
                    dayKey = s.dayKey,
                    startEpoch = s.startEpoch,
                    quotaMinutes = s.quotaMinutes,
                    extendedMinutes = s.extendedMinutes,
                    lastDelayEpoch = s.lastDelayEpoch,
                    restAccumulatedMs = s.restAccumulatedMs,
                    restIds = s.restIds,
                    createdAt = s.createdAt,
                    updatedAt = s.updatedAt
                )
            }

        /** 整行覆盖：与 JpaRepository.save 的语义一致，用于暴露读-改-写丢更新。 */
        @Synchronized
        override fun <S : UsageSession> save(entity: S): S {
            table[entity.deviceId] = entity
            return entity
        }
    }
}
