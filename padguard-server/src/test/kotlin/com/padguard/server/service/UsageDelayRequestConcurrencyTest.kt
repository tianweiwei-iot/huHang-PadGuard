package com.padguard.server.service

import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.UsageDelayTicket
import com.padguard.server.domain.UsageSession
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `UsageDelayService.request` 的「查 PENDING → 判空 → 插工单」是典型 check-then-act。
 *
 * 两个并发事务（孩子端网络重试 / 双通道同时上报）各自在 READ_COMMITTED 下都读不到
 * 对方尚未提交的 PENDING 行，于是都判定「没有待审申请」并各插一张工单。后果不只是
 * 家长端多一个弹窗：家长连点同意两次，`approve` 会累加两笔时长，单日 120 分钟上限
 * 虽有 `addExtendedMinutes` 的 CAS 兜底，但工单台账里会留下两条各自合法的申请，
 * 事后完全看不出是重复提交。
 *
 * 这里用两个线程 + 假仓储复现「各自读到旧快照」的窗口：
 * - `findByDeviceId`（普通读）：启用快照闸门，两个线程都在**任何写入之前**取走空列表；
 * - `findByDeviceIdForUpdate`（带行锁的读）：第二个读阻塞到第一个事务落库之后，
 *   与数据库 `SELECT ... FOR UPDATE` 的串行化语义一致。
 *
 * 因此旧实现必然落下两张 PENDING（护栏红），改成行锁后第二张被「已有待审核」挡下（绿）。
 */
@DisplayName("P2-01 延时申请并发串行化")
class UsageDelayRequestConcurrencyTest {

    private val today: String = Instant.now().atZone(SERVER_ZONE_ID).toLocalDate().toString()

    @Test
    @DisplayName("同设备并发两次申请只允许落一张 PENDING 工单")
    fun `concurrent requests produce a single pending ticket`() {
        val firstTicketSaved = CountDownLatch(1)
        val delayRepo = CoordinatedDelayRepo(firstTicketSaved)
        val sessionRepo = CoordinatedSessionRepo(firstTicketSaved, delayRepo)

        val deviceService = mock(DeviceService::class.java)
        `when`(deviceService.getUserId("device-1")).thenReturn("user-1")
        val service = UsageDelayService(
            FakeDeviceRepo(), delayRepo, sessionRepo,
            mock(CommandService::class.java),
            mock(WebSocketPushService::class.java),
            deviceService,
            mock(AuditService::class.java),
            UsageDelayExpirer(delayRepo)
        )

        val now = System.currentTimeMillis()
        sessionRepo.table["device-1"] = UsageSession(
            deviceId = "device-1",
            dayKey = today,
            startEpoch = now - 3_600_000L,
            quotaMinutes = 120,
            createdAt = now,
            updatedAt = now
        )

        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CyclicBarrier(2)
            val tasks = (0 until 2).map {
                pool.submit(Callable {
                    start.await(2, TimeUnit.SECONDS)
                    runCatching { service.request("device-1", 30) }
                })
            }
            val outcomes = tasks.map { it.get(10, TimeUnit.SECONDS) }
            val tickets = delayRepo.table.values.filter {
                it.deviceId == "device-1" && it.status == "PENDING"
            }
            assertEquals(
                1, tickets.size,
                "同一设备并发申请必须只留一张待审工单，实际 ${tickets.size} 张（重复提交会变成两笔可批准时长）"
            )
            assertEquals(1, outcomes.count { it.isSuccess }, "并发中应恰好一次成功、一次被拒")
        } finally {
            pool.shutdownNow()
        }
    }
}

/**
 * 假工单仓储：在**查询发生的那一刻**冻结快照，并让两个无锁读者汇合后才返回。
 * 这样两个线程拿到的都是「写入前」的空列表，稳定复现 check-then-act，而不是靠调度运气。
 */
private class CoordinatedDelayRepo(
    private val firstTicketSaved: CountDownLatch
) : FakeUsageDelayRepo() {

    @Volatile
    var snapshotGateEnabled = false
    private val bothRead = CountDownLatch(2)
    private val reads = AtomicInteger(0)

    override fun findByDeviceIdAndStatus(deviceId: String, status: String): List<UsageDelayTicket> {
        val snapshot = super.findByDeviceIdAndStatus(deviceId, status)
        if (snapshotGateEnabled && reads.getAndIncrement() < 2) {
            bothRead.countDown()
            bothRead.await(2, TimeUnit.SECONDS)
        }
        return snapshot
    }

    @Synchronized
    override fun <S : UsageDelayTicket> save(entity: S): S {
        val saved = super.save(entity)
        firstTicketSaved.countDown()
        return saved
    }
}

/**
 * 假会话仓储：区分「普通读」与「带行锁的读」。
 *
 * 修复前这里不能写 `override`（接口里还没有这个方法），接口补上方法后 Kotlin 会以
 * accidental override 报错提醒同步 —— 正是「契约变更必须连带测试」的效果。
 */
private class CoordinatedSessionRepo(
    private val firstTicketSaved: CountDownLatch,
    private val delayRepo: CoordinatedDelayRepo
) : FakeSessionRepo() {

    private val lockedReads = AtomicInteger(0)

    override fun findByDeviceId(deviceId: String): UsageSession? {
        // 普通读：两个事务各读到旧快照，闸门打开，check-then-act 窗口敞开。
        delayRepo.snapshotGateEnabled = true
        return super.findByDeviceId(deviceId)
    }

    override fun findByDeviceIdForUpdate(deviceId: String): UsageSession? {
        // 行锁读：后到的事务必须等先到者提交（落库）后才拿到行。
        if (lockedReads.getAndIncrement() > 0) {
            firstTicketSaved.await(2, TimeUnit.SECONDS)
        }
        return super.findByDeviceId(deviceId)
    }
}
