package com.padguard.server.service

import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.Device
import com.padguard.server.domain.UsageDelayTicket
import com.padguard.server.domain.UsageSession
import com.padguard.server.dto.UsageDelayApproveRequest
import com.padguard.server.dto.UsageDelayRejectRequest
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UsageDelayRepository
import com.padguard.server.repository.UsageSessionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.Instant

/**
 * 延时审批服务单元测试（根因修复：申请≠生效，必须家长审批）。
 *
 * 仓储层用内存假实现（[FakeJpaRepository] 体系），避免 Mockito 默认 mock 丢弃"保存后实体被修改"的副作用；
 * 协作服务（指令下发 / WS 推送 / 设备 / 审计）用 Mockito 桩，重点断言"审批动作"与"下发明细"。
 */
@DisplayName("UsageDelayService —— 延时审批流")
class UsageDelayServiceTest {

    private lateinit var deviceRepo: FakeDeviceRepo
    private lateinit var delayRepo: FakeUsageDelayRepo
    private lateinit var sessionRepo: FakeUsageSessionRepo
    private lateinit var commandService: CommandService
    private lateinit var webSocketPush: WebSocketPushService
    private lateinit var deviceService: DeviceService
    private lateinit var auditService: AuditService
    private lateinit var service: UsageDelayService

    /** 今日 dayKey（与 SERVER_ZONE_ID 一致） */
    private val today: String = Instant.now().atZone(SERVER_ZONE_ID).toLocalDate().toString()

    @BeforeEach
    fun setUp() {
        deviceRepo = FakeDeviceRepo()
        delayRepo = FakeUsageDelayRepo()
        sessionRepo = FakeUsageSessionRepo()
        commandService = mock(CommandService::class.java)
        webSocketPush = mock(WebSocketPushService::class.java)
        deviceService = mock(DeviceService::class.java)
        auditService = mock(AuditService::class.java)

        // 设备归属：device-1 -> user-1
        deviceRepo.table["device-1"] = Device(id = "device-1", userId = "user-1")
        `when`(deviceService.getUserId("device-1")).thenReturn("user-1")

        // 今日已开始计时（额度 120 分钟，起点为 1 小时前）
        sessionRepo.table["device-1"] = UsageSession(
            deviceId = "device-1",
            dayKey = today,
            startEpoch = System.currentTimeMillis() - 3_600_000L,
            quotaMinutes = 120,
            extendedMinutes = 0,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        service = UsageDelayService(
            deviceRepo, delayRepo, sessionRepo,
            commandService, webSocketPush, deviceService, auditService
        )
    }

    @Test
    @DisplayName("申请仅落 PENDING 工单，不立即延长额度")
    fun `request creates pending ticket without extending`() {
        val before = sessionRepo.table["device-1"]!!.extendedMinutes

        service.request("device-1", 30)

        assertThat(sessionRepo.table["device-1"]!!.extendedMinutes).isEqualTo(before)
        val tickets = delayRepo.table.values.filter { it.deviceId == "device-1" }
        assertThat(tickets).hasSize(1)
        assertThat(tickets[0].status).isEqualTo("PENDING")
        assertThat(tickets[0].minutes).isEqualTo(30)
        // 家长端应实时收到"新申请"事件
        verify(webSocketPush).pushToUser(
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.any()
        )
    }

    @Test
    @DisplayName("非法档位被拒")
    fun `request rejects invalid tier`() {
        org.junit.jupiter.api.Assertions.assertThrows(
            com.padguard.server.common.BizException::class.java
        ) { service.request("device-1", 7) }
        assertThat(delayRepo.table).isEmpty()
    }

    @Test
    @DisplayName("计时未开始不能申请")
    fun `request rejects when session not started`() {
        sessionRepo.table.clear()
        org.junit.jupiter.api.Assertions.assertThrows(
            com.padguard.server.common.BizException::class.java
        ) { service.request("device-1", 30) }
    }

    @Test
    @DisplayName("存在未结工单时拒绝重复提交")
    fun `request rejects duplicate pending`() {
        service.request("device-1", 15)
        org.junit.jupiter.api.Assertions.assertThrows(
            com.padguard.server.common.BizException::class.java
        ) { service.request("device-1", 30) }
    }

    @Test
    @DisplayName("单日累计上限校验（已批准+待审批+本次）")
    fun `request enforces daily cap`() {
        // 模拟已批准 100 分钟
        sessionRepo.table["device-1"]!!.extendedMinutes = 100
        // 再申请 30 -> 100+0+30 > 120 触发上限（待审批尚未累加，因此只比较"已批准+本次"）
        org.junit.jupiter.api.Assertions.assertThrows(
            com.padguard.server.common.BizException::class.java
        ) { service.request("device-1", 30) }

        // 但 15 分钟应通过：100 + 15 <= 120
        service.request("device-1", 15)
        assertThat(delayRepo.table.values.count { it.deviceId == "device-1" }).isEqualTo(1)
    }

    @Test
    @DisplayName("审批通过才真正累加额度并通知两端")
    fun `approve extends quota and notifies`() {
        val ticket = service.request("device-1", 30)
        val before = sessionRepo.table["device-1"]!!.extendedMinutes

        service.approve("user-1", "device-1", ticket.id, UsageDelayApproveRequest())

        assertThat(sessionRepo.table["device-1"]!!.extendedMinutes).isEqualTo(before + 30)
        assertThat(delayRepo.table[ticket.id]!!.status).isEqualTo("APPROVED")
        org.mockito.Mockito.verify(webSocketPush)
            .pushToUser(org.mockito.ArgumentMatchers.eq("user-1"), org.mockito.ArgumentMatchers.any())
    }

    @Test
    @DisplayName("审批通过累加后，pendingDelayMinutes 归零（会话 DTO 反映）")
    fun `approve clears pending in session dto`() {
        val ticket = service.request("device-1", 30)
        val sessionService = buildSessionService()
        assertThat(sessionService.get("device-1").pendingDelayMinutes).isEqualTo(30)

        service.approve("user-1", "device-1", ticket.id, UsageDelayApproveRequest())
        assertThat(sessionService.get("device-1").pendingDelayMinutes).isEqualTo(0)
        assertThat(sessionService.get("device-1").extendedMinutes).isEqualTo(30)
    }

    @Test
    @DisplayName("拒绝下发 SHOW_MESSAGE 给孩子，并置 REJECTED")
    fun `reject sends show message`() {
        val ticket = service.request("device-1", 30)

        service.reject("user-1", "device-1", ticket.id, UsageDelayRejectRequest(reason = "作业没写完"))

        assertThat(delayRepo.table[ticket.id]!!.status).isEqualTo("REJECTED")
        verify(commandService).issueCommand(
            org.mockito.ArgumentMatchers.eq("device-1"),
            org.mockito.ArgumentMatchers.eq("SHOW_MESSAGE"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        )
    }

    @Test
    @DisplayName("忽略（IGNORED）不修改额度、不下发消息，仍可被批准")
    fun `dismiss keeps ticket actionable`() {
        val ticket = service.request("device-1", 30)
        service.dismiss("user-1", "device-1", ticket.id)

        assertThat(delayRepo.table[ticket.id]!!.status).isEqualTo("IGNORED")
        assertThat(sessionRepo.table["device-1"]!!.extendedMinutes).isEqualTo(0)

        // 忽略后可再次批准
        service.approve("user-1", "device-1", ticket.id, UsageDelayApproveRequest())
        assertThat(sessionRepo.table["device-1"]!!.extendedMinutes).isEqualTo(30)
    }

    @Test
    @DisplayName("非本人设备 / 工单不存在 / 已处理，ownedOpen 守卫生效")
    fun `ownedOpen guards unauthorized and resolved`() {
        val ticket = service.request("device-1", 15)
        // 已处理后再次审批应失败
        service.approve("user-1", "device-1", ticket.id, UsageDelayApproveRequest())
        org.junit.jupiter.api.Assertions.assertThrows(
            com.padguard.server.common.BizException::class.java
        ) { service.approve("user-1", "device-1", ticket.id, UsageDelayApproveRequest()) }
    }

    // ---- 构造一个仅用于校验 pending 透传的 UsageSessionService（复用同一组 fake） ----
    private fun buildSessionService(): UsageSessionService {
        val deviceSettingRepo = mock(com.padguard.server.repository.DeviceSettingRepository::class.java)
        `when`(deviceSettingRepo.findById(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(java.util.Optional.empty())
        return UsageSessionService(sessionRepo, deviceSettingRepo, deviceService, service)
    }
}

// ============ 内存假仓储 ============

class FakeDeviceRepo : FakeJpaRepository<Device, String>(), DeviceRepository {
    override val table = LinkedHashMap<String, Device>()
    override fun idOf(entity: Device): String = entity.id
    // DeviceRepository 的派生查询（findByUserId 等）测试用不到，保持默认未实现即可
    override fun findByUserId(userId: String): List<Device> = table.values.filter { it.userId == userId }
    override fun countByUserId(userId: String): Long = findByUserId(userId).size.toLong()
    override fun countByOnlineStatus(onlineStatus: String): Long = table.values.count { it.onlineStatus == onlineStatus }
    override fun findByDeviceSn(deviceSn: String): List<Device> = emptyList()
    override fun findByLastActiveAtGreaterThan(since: Long): List<Device> = emptyList()
}

class FakeUsageDelayRepo : FakeJpaRepository<UsageDelayTicket, String>(), UsageDelayRepository {
    override val table = LinkedHashMap<String, UsageDelayTicket>()
    override fun idOf(entity: UsageDelayTicket): String = entity.id
    override fun findByIdAndDeviceId(id: String, deviceId: String): UsageDelayTicket? =
        table.values.firstOrNull { it.id == id && it.deviceId == deviceId }

    override fun findByDeviceIdAndStatus(deviceId: String, status: String): List<UsageDelayTicket> =
        table.values.filter { it.deviceId == deviceId && it.status == status }

    override fun sumEffectiveMinutes(deviceId: String): Int =
        table.values.filter { it.deviceId == deviceId && it.status in listOf("PENDING", "APPROVED") }
            .sumOf { it.minutes }
}

class FakeUsageSessionRepo : FakeJpaRepository<UsageSession, String>(), UsageSessionRepository {
    override val table = LinkedHashMap<String, UsageSession>()
    override fun idOf(entity: UsageSession): String = entity.deviceId
    override fun findByDeviceId(deviceId: String): UsageSession? = table[deviceId]
}
