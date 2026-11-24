package com.padguard.server.service

import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.UsageStartRequest
import com.padguard.server.repository.UsageStartRequestRepository
import com.padguard.server.ws.WebSocketPushService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.time.Instant
import java.time.LocalDate

/**
 * 「开始计时」工单服务单元测试（每日计时须孩子确认后才开始）。
 *
 * 仓储层用内存假实现（[FakeJpaRepository] 体系，与 [UsageDelayServiceTest] 同一套理由：
 * 工单状态是"保存后修改"的副作用，Mockito 默认 mock 会丢弃）；协作服务（指令下发 / WS 推送 /
 * 设备归属）用 Mockito 桩。afterCommit 在测试线程没有活动事务时会**立即执行**，
 * 因此指令下发与推送可以直接 verify。
 */
@DisplayName("UsageStartRequestService —— 家长发起、孩子确认的计时开始工单")
class UsageStartRequestServiceTest {

    private lateinit var repo: FakeUsageStartRequestRepo
    private lateinit var commandService: CommandService
    private lateinit var webSocketPush: WebSocketPushService
    private lateinit var deviceService: DeviceService
    private lateinit var service: UsageStartRequestService

    private val today: String = LocalDate.now(SERVER_ZONE_ID).toString()
    private val yesterday: String = LocalDate.now(SERVER_ZONE_ID).minusDays(1).toString()

    @BeforeEach
    fun setUp() {
        repo = FakeUsageStartRequestRepo()
        commandService = mock(CommandService::class.java)
        webSocketPush = mock(WebSocketPushService::class.java)
        deviceService = mock(DeviceService::class.java)
        org.mockito.Mockito.`when`(deviceService.getUserId("device-1")).thenReturn("user-1")

        service = UsageStartRequestService(repo, commandService, webSocketPush, deviceService)
    }

    private fun ticket(
        requestId: String = "st_test",
        deviceId: String = "device-1",
        dayKey: String = today,
        status: String = "PENDING"
    ) = repo.save(
        UsageStartRequest(
            requestId = requestId, deviceId = deviceId, dayKey = dayKey,
            status = status, source = "PARENT", createdAt = 1_000L
        )
    )

    @Test
    @DisplayName("家长发起：落 PENDING 工单并下发 START_TIMER_CONFIRM 指令")
    fun `request creates pending ticket and issues command`() {
        service.request("user-1", "device-1")

        val t = repo.table.values.single()
        assertThat(t.status).isEqualTo("PENDING")
        assertThat(t.requestId).startsWith("st_")
        // Kotlin 非空参数 vs Mockito matcher 返回 null：必须 `?:` 兜底占位值，否则
        // 编译器插入的 null 检查直接抛 NPE（matcher 的占位返回值与断言无关）。
        verify(commandService).issueCommand(
            eq("device-1") ?: "", eq("START_TIMER_CONFIRM") ?: "",
            org.mockito.ArgumentMatchers.argThat<Map<String, Any?>> { it["requestId"] == t.requestId } ?: emptyMap(),
            anyString() ?: ""
        )
        verify(webSocketPush).pushToUser(eq("user-1"), eventType("usage.start.requested"))
    }

    @Test
    @DisplayName("重复点击 = 催办：复用当日 PENDING 工单、重发指令，不建新单")
    fun `repeat request reuses pending ticket and re-issues command`() {
        service.request("user-1", "device-1")
        service.request("user-1", "device-1")

        assertThat(repo.table.values).hasSize(1)
        verify(commandService, times(2)).issueCommand(
            eq("device-1") ?: "", eq("START_TIMER_CONFIRM") ?: "",
            org.mockito.ArgumentMatchers.any<Map<String, Any?>>() ?: emptyMap(),
            anyString() ?: ""
        )
    }

    @Test
    @DisplayName("今日已 CONFIRMED：幂等返回，不再打扰孩子端")
    fun `confirmed ticket is returned as-is without re-issuing`() {
        ticket(status = "CONFIRMED")

        val dto = service.request("user-1", "device-1")

        assertThat(dto.status).isEqualTo("CONFIRMED")
        verify(commandService, never()).issueCommand(
            anyString() ?: "", anyString() ?: "",
            org.mockito.ArgumentMatchers.any<Map<String, Any?>>() ?: emptyMap(),
            anyString() ?: ""
        )
        verify(webSocketPush, never()).pushToUser(anyString() ?: "", org.mockito.ArgumentMatchers.any<Map<String, Any?>>() ?: emptyMap())
    }

    @Test
    @DisplayName("孩子确认：当日 PENDING 工单闭环为 CONFIRMED 并推送家长端")
    fun `confirm closes pending tickets and pushes event`() {
        ticket()

        val confirmed = service.confirmPendingToday("device-1", today, 2_000L)

        assertThat(confirmed).isTrue()
        val t = repo.table.values.single()
        assertThat(t.status).isEqualTo("CONFIRMED")
        assertThat(t.confirmedAt).isEqualTo(2_000L)
        verify(webSocketPush).pushToUser(eq("user-1"), eventType("usage.start.confirmed"))
    }

    @Test
    @DisplayName("确认幂等：已确认过的再次确认影响 0 行、无重复推送")
    fun `confirm is idempotent when already confirmed`() {
        ticket(status = "CONFIRMED")

        val confirmed = service.confirmPendingToday("device-1", today, 3_000L)

        assertThat(confirmed).isFalse()
        verify(webSocketPush, never()).pushToUser(anyString() ?: "", org.mockito.ArgumentMatchers.any<Map<String, Any?>>() ?: emptyMap())
    }

    @Test
    @DisplayName("孩子自己确认（家长从未发起）：无工单可闭环，静默返回")
    fun `confirm without ticket is a silent no-op`() {
        val confirmed = service.confirmPendingToday("device-1", today, 2_000L)
        assertThat(confirmed).isFalse()
        verify(webSocketPush, never()).pushToUser(anyString() ?: "", org.mockito.ArgumentMatchers.any<Map<String, Any?>>() ?: emptyMap())
    }

    @Test
    @DisplayName("昨天的 PENDING 在今天发起新请求时被作废（跨日不续命）")
    fun `stale pending from yesterday is expired on next request`() {
        ticket(requestId = "st_yesterday", dayKey = yesterday)

        service.request("user-1", "device-1")

        assertThat(repo.table["st_yesterday"]!!.status).isEqualTo("EXPIRED")
        val fresh = repo.table.values.single { it.requestId != "st_yesterday" }
        assertThat(fresh.status).isEqualTo("PENDING")
        assertThat(fresh.dayKey).isEqualTo(today)
    }

    @Test
    @DisplayName("todayRequest 只返回当日未作废的工单")
    fun `todayRequest filters expired and returns current`() {
        ticket(requestId = "st_yesterday", dayKey = yesterday, status = "EXPIRED")
        ticket(requestId = "st_today")

        val dto = service.todayRequest("device-1")

        assertThat(dto?.requestId).isEqualTo("st_today")
        assertEquals(null, service.todayRequest("device-unknown"))
    }
}

/** 事件体 type 断言用的 matcher（宽松匹配：只要 type 相符） */
private fun eventType(expectedType: String): Map<String, Any?> =
    org.mockito.ArgumentMatchers.argThat { m: Map<String, Any?> -> m["type"] == expectedType }
        ?: emptyMap()

open class FakeUsageStartRequestRepo : FakeJpaRepository<UsageStartRequest, String>(), UsageStartRequestRepository {
    override val table = LinkedHashMap<String, UsageStartRequest>()
    override fun idOf(entity: UsageStartRequest): String = entity.requestId

    override fun findByDeviceIdAndDayKey(deviceId: String, dayKey: String): List<UsageStartRequest> =
        table.values.filter { it.deviceId == deviceId && it.dayKey == dayKey }

    /** 条件 UPDATE 的内存等价：只有 PENDING 能被确认（终态不可回退） */
    override fun confirmPendingToday(deviceId: String, dayKey: String, now: Long): Int {
        val pending = table.values.filter {
            it.deviceId == deviceId && it.dayKey == dayKey && it.status == "PENDING"
        }
        pending.forEach {
            it.status = "CONFIRMED"
            it.confirmedAt = now
        }
        return pending.size
    }

    override fun expireStale(deviceId: String, today: String): Int {
        val stale = table.values.filter {
            it.deviceId == deviceId && it.status == "PENDING" && it.dayKey != today
        }
        stale.forEach { it.status = "EXPIRED" }
        return stale.size
    }
}
