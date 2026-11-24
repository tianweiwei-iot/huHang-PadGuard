package com.padguard.server.service

import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.UsageStartRequest
import com.padguard.server.repository.DeviceSettingRepository
import com.padguard.server.repository.UsageSessionRepository
import com.padguard.server.repository.UsageStartRequestRepository
import com.padguard.server.service.CommandService
import com.padguard.server.service.DeviceService
import com.padguard.server.service.UsageDelayService
import com.padguard.server.service.UsageStartRequestService
import com.padguard.server.ws.WebSocketPushService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import java.time.Instant
import java.util.Optional

/**
 * [UsageSessionService.start] 与「开始计时」工单的闭环契约测试。
 *
 * A13 不变量（第二十九轮）：孩子确认「开始计时」只凭设备+日期收敛，把当日**全部** PENDING 工单
 * 置 CONFIRMED，**不依赖孩子端回传 requestId** —— 孩子可能从等待页按钮 / 日切浮层等多个入口确认，
 * 任一回传链路缺失都不该让工单悬空。
 *
 * 这条契约之前只有 [UsageStartRequestServiceTest]（单测服务自身）与 [UsageSessionServiceTest]
 * （用 mock 直接跳过闭环）覆盖，缺少"start() 真的把工单翻成 CONFIRMED"的端到端证据。
 * 这里用真实 [UsageStartRequestService] + 内存仓库把链路跑通。
 */
@DisplayName("UsageSessionService.start —— 孩子确认按 deviceId+dayKey 全量闭环 PENDING 工单")
class UsageSessionStartClosesTicketsTest {

    private lateinit var sessionRepo: FakeSessionRepo
    private lateinit var startRequestRepo: FakeStartRequestRepo
    private lateinit var startRequestService: UsageStartRequestService
    private lateinit var service: UsageSessionService

    private val today: String = Instant.now().atZone(SERVER_ZONE_ID).toLocalDate().toString()

    @BeforeEach
    fun setUp() {
        sessionRepo = FakeSessionRepo()
        startRequestRepo = FakeStartRequestRepo()

        // 协作服务用 mock：本测试只走 confirmPendingToday → pushStartEvent（runCatching 包裹，副作用被吞），
        // 不触碰 issueCommand。
        val commandService = mock(CommandService::class.java)
        val webSocketPush = mock(WebSocketPushService::class.java)
        val deviceService = mock(DeviceService::class.java)
        `when`(deviceService.getUserId(anyString())).thenReturn("user-1")
        startRequestService = UsageStartRequestService(startRequestRepo, commandService, webSocketPush, deviceService)

        val deviceSettingRepo = mock(DeviceSettingRepository::class.java)
        `when`(deviceSettingRepo.findById(anyString())).thenReturn(Optional.empty())
        val delayService = mock(UsageDelayService::class.java)
        `when`(delayService.pendingDelayMinutes(anyString())).thenReturn(0)

        service = UsageSessionService(sessionRepo, deviceSettingRepo, deviceService, delayService, startRequestService)
    }

    @Test
    @DisplayName("首次开始：家长工单（带特定 requestId）被翻成 CONFIRMED，且不依赖回传 requestId")
    fun `fresh start closes parent ticket by device and day independent of requestId`() {
        startRequestRepo.save(
            UsageStartRequest(
                requestId = "st_parent_xyz", deviceId = "device-1", dayKey = today,
                status = "PENDING", source = "PARENT", createdAt = System.currentTimeMillis()
            )
        )
        assertThat(sessionRepo.table).doesNotContainKey("device-1")

        // 孩子确认：start() 的签名只接收 deviceId，从不接收 requestId
        val dto = service.start("device-1")

        val ticket = startRequestService.todayRequest("device-1")
        assertThat(ticket).isNotNull()
        assertThat(ticket!!.status).isEqualTo("CONFIRMED")
        assertThat(ticket.requestId).isEqualTo("st_parent_xyz") // 被闭环的正是那张家长工单

        assertThat(dto.active).isTrue()
        assertThat(dto.startRequest?.status).isEqualTo("CONFIRMED")
    }

    @Test
    @DisplayName("今日已起表（幂等快路径）：重复 start 仍把当日 PENDING 工单闭环")
    fun `idempotent re-start still closes pending tickets`() {
        // 先让 today 已有一份进行中会话
        sessionRepo.table["device-1"] = com.padguard.server.domain.UsageSession(
            deviceId = "device-1", dayKey = today,
            startEpoch = System.currentTimeMillis() - 30 * 60_000L,
            quotaMinutes = 120, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
        )
        startRequestRepo.save(
            UsageStartRequest(
                requestId = "st_reprompt", deviceId = "device-1", dayKey = today,
                status = "PENDING", source = "PARENT", createdAt = System.currentTimeMillis()
            )
        )

        service.start("device-1")

        assertThat(startRequestService.todayRequest("device-1")!!.status).isEqualTo("CONFIRMED")
    }

    @Test
    @DisplayName("无工单时 start 仍成功且 startRequest 为 null（不崩溃、不误建单）")
    fun `start without any ticket returns null startRequest`() {
        val dto = service.start("device-1")
        assertThat(dto.active).isTrue()
        assertThat(dto.startRequest).isNull()
        assertThat(startRequestRepo.table).isEmpty()
    }

    @Test
    @DisplayName("昨日的 PENDING 工单不被今日 start 闭环（跨日作废语义归 confirmPendingToday 的 dayKey 过滤）")
    fun `yesterday ticket is not closed by today start`() {
        val yesterday = Instant.now().atZone(SERVER_ZONE_ID).minusDays(1).toLocalDate().toString()
        startRequestRepo.save(
            UsageStartRequest(
                requestId = "st_yesterday", deviceId = "device-1", dayKey = yesterday,
                status = "PENDING", source = "PARENT", createdAt = System.currentTimeMillis()
            )
        )

        service.start("device-1")

        val stale = startRequestRepo.table["st_yesterday"]!!
        assertThat(stale.status).isEqualTo("PENDING") // 今日 start 不动昨日工单
    }
}

/** [UsageStartRequestRepository] 的内存实现：业务自定义查询用等值过滤模拟 SQL 语义。 */
class FakeStartRequestRepo : FakeJpaRepository<UsageStartRequest, String>(), UsageStartRequestRepository {
    override val table = LinkedHashMap<String, UsageStartRequest>()
    override fun idOf(entity: UsageStartRequest): String = entity.requestId

    override fun findByDeviceIdAndDayKey(deviceId: String, dayKey: String): List<UsageStartRequest> =
        table.values.filter { it.deviceId == deviceId && it.dayKey == dayKey }

    @Synchronized
    override fun confirmPendingToday(deviceId: String, dayKey: String, now: Long): Int {
        var n = 0
        for (r in table.values) {
            if (r.deviceId == deviceId && r.dayKey == dayKey && r.status == "PENDING") {
                r.status = "CONFIRMED"
                r.confirmedAt = now
                n++
            }
        }
        return n
    }

    @Synchronized
    override fun expireStale(deviceId: String, today: String): Int {
        var n = 0
        for (r in table.values) {
            if (r.deviceId == deviceId && r.status == "PENDING" && r.dayKey != today) {
                r.status = "EXPIRED"
                n++
            }
        }
        return n
    }
}
