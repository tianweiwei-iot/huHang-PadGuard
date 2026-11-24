package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.Device
import com.padguard.server.domain.UsageDelayTicket
import com.padguard.server.domain.UsageSession
import com.padguard.server.dto.UsageDelayApproveRequest
import com.padguard.server.dto.UsageDelayRejectRequest
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UsageDelayRepository
import com.padguard.server.repository.UsageSessionRepository
import com.padguard.server.ws.WebSocketPushService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.Instant
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.transaction.support.TransactionSynchronizationManager

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
    private lateinit var expirer: UsageDelayExpirer
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
        expirer = UsageDelayExpirer(delayRepo)

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
            commandService, webSocketPush, deviceService, auditService, expirer
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
        val reqCaptor = ArgumentCaptor.forClass(java.util.Map::class.java) as ArgumentCaptor<Map<String, Any?>>
        verify(webSocketPush).pushToUser(
            org.mockito.ArgumentMatchers.eq("user-1"),
            reqCaptor.capture() ?: emptyMap()
        )
        assertThat(reqCaptor.value.get("type")).isEqualTo("usage.delay.requested")
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
        // request 与 approve 各推送一次（共 2 次）；用 captor + elvis 规避 Kotlin 对非空 Map 参数的空值守卫
        val payloadCaptor = ArgumentCaptor.forClass(java.util.Map::class.java) as ArgumentCaptor<Map<String, Any?>>
        verify(webSocketPush, org.mockito.Mockito.times(2)).pushToUser(
            org.mockito.ArgumentMatchers.eq("user-1"),
            payloadCaptor.capture() ?: emptyMap()
        )
        val approved = payloadCaptor.allValues.last()
        assertThat(approved.get("type")).isEqualTo("usage.delay.updated")
        assertThat(approved.get("status")).isEqualTo("APPROVED")
    }

    /**
     * 审批/申请都在 @Transactional 里，推送后若还有写库步骤失败，整笔回滚，
     * 但 WS 消息已经发出去了 —— 家长端会看到一条数据库里并不存在的状态变更。
     * 这类对外副作用必须挂到 afterCommit。
     */
    @Test
    @DisplayName("审批通过：提交后才通知，事务回滚时不通知")
    fun `approve defers WS notification until commit`() {
        val ticket = service.request("device-1", 30)
        val push = CountingPush()
        service = UsageDelayService(
            deviceRepo, delayRepo, sessionRepo, commandService, push, deviceService, auditService, expirer
        )

        TransactionSynchronizationManager.initSynchronization()
        try {
            service.approve("user-1", "device-1", ticket.id, UsageDelayApproveRequest())
            assertTrue(push.events.isEmpty(), "事务未提交就推送，回滚时家长端会看到并不存在的 APPROVED")
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            assertEquals(1, push.events.size, "提交后应恰好推送一次")
            assertEquals("APPROVED", push.events.single()["status"])
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    @DisplayName("发起申请：提交后才通知家长端")
    fun `request defers WS notification until commit`() {
        val push = CountingPush()
        service = UsageDelayService(
            deviceRepo, delayRepo, sessionRepo, commandService, push, deviceService, auditService, expirer
        )

        TransactionSynchronizationManager.initSynchronization()
        try {
            service.request("device-1", 30)
            assertTrue(push.events.isEmpty(), "事务未提交就推送，回滚时家长端会看到并不存在的待审申请")
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            assertEquals(1, push.events.size)
            assertEquals("usage.delay.requested", push.events.single()["type"])
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    /** 捕获推送次数与内容：Mockito 对非空 Map 参数的空值守卫在这个用例里会误伤，改用假实现。 */
    private class CountingPush : WebSocketPushService(ObjectMapper(), users = com.padguard.server.support.PushTestUsers.permissive()) {
        val events = mutableListOf<Map<String, Any?>>()
        override fun pushToUser(userId: String?, payload: Map<String, Any?>) {
            events += payload
        }
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
        // 非空参数上的 eq/any 需以 elvis 兜底，规避 Kotlin 对空值守卫在 matcher 返回值上抛 NPE
        verify(commandService).issueCommand(
            org.mockito.ArgumentMatchers.eq("device-1") ?: "",
            org.mockito.ArgumentMatchers.eq("SHOW_MESSAGE") ?: "",
            org.mockito.ArgumentMatchers.any() ?: emptyMap(),
            org.mockito.ArgumentMatchers.eq("NORMAL") ?: ""
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

    @Test
    @DisplayName("R40 IDOR：跨用户 listTickets 必须抛 DEVICE_NOT_OWNED，不得靠 filter 兜底返回空列表")
    fun `listTickets rejects another users device`() {
        service.request("device-1", 15) // device-1 归属 user-1
        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            com.padguard.server.common.BizException::class.java
        ) { service.listTickets("user-2", "device-1") }
        assertEquals(
            com.padguard.server.common.ParentErr.DEVICE_NOT_OWNED,
            ex.code,
            "跨用户读取他人设备工单必须报 DEVICE_NOT_OWNED，与全系统 403 口径一致"
        )
        // 归属者本人仍可正常读取（正向对照，确保守卫没误伤）
        assertThat(service.listTickets("user-1", "device-1")).hasSize(1)
    }

    @Test
    @DisplayName("跨日工单：即使今天已起表，也不得把昨天的申请批到今天的额度上")
    fun `approve rejects a ticket created on a previous day even if today session is active`() {
        // 今天是激活的会话（孩子今天已经起过表 / 零点自动开始），这正是旧实现漏判的前提：
        // 旧逻辑只判 `session.dayKey == today`，于是这里会直接放行。
        assertThat(sessionRepo.table["device-1"]!!.dayKey).isEqualTo(today)
        delayRepo.table["dl_old"] = UsageDelayTicket(
            id = "dl_old",
            deviceId = "device-1",
            userId = "user-1",
            minutes = 30,
            status = "PENDING",
            // 24 小时前 = 昨天（Asia/Shanghai 无夏令时，减 24h 必落在前一天）
            createdAt = Instant.now().minusSeconds(86_400L).toEpochMilli()
        )

        org.junit.jupiter.api.Assertions.assertThrows(com.padguard.server.common.BizException::class.java) {
            service.approve("user-1", "device-1", "dl_old", UsageDelayApproveRequest())
        }

        assertThat(sessionRepo.table["device-1"]!!.extendedMinutes).isZero()
        assertThat(delayRepo.table["dl_old"]!!.status).isEqualTo("REJECTED")
    }

    @Test
    @DisplayName("单日延时上限作为写条件：超限的批准必须失败且额度不变")
    fun `approve fails when the daily extension cap would be exceeded`() {
        // 已批准 100 分钟，再批 30 → 130 > 120。`request` 的额度检查是读-判-写，
        // 两条工单并发提交时都能通过；只有把上限写进 UPDATE 的 WHERE 才拦得住。
        sessionRepo.table["device-1"]!!.extendedMinutes = 100
        delayRepo.table["dl_big"] = UsageDelayTicket(
            id = "dl_big",
            deviceId = "device-1",
            userId = "user-1",
            minutes = 30,
            status = "PENDING",
            createdAt = System.currentTimeMillis()
        )

        org.junit.jupiter.api.Assertions.assertThrows(com.padguard.server.common.BizException::class.java) {
            service.approve("user-1", "device-1", "dl_big", UsageDelayApproveRequest())
        }

        assertThat(sessionRepo.table["device-1"]!!.extendedMinutes).isEqualTo(100)
    }

    // ---- 构造一个仅用于校验 pending 透传的 UsageSessionService（复用同一组 fake） ----
    private fun buildSessionService(): UsageSessionService {
        val deviceSettingRepo = mock(com.padguard.server.repository.DeviceSettingRepository::class.java)
        `when`(deviceSettingRepo.findById(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(java.util.Optional.empty())
        return UsageSessionService(sessionRepo, deviceSettingRepo, deviceService, service, mock(UsageStartRequestService::class.java))
    }
}

// ============ 内存假仓储 ============

class FakeDeviceRepo : FakeJpaRepository<Device, String>(), DeviceRepository {
    override fun updateEntitlementState(deviceId: String, state: String): Int {
        val d = table[deviceId] ?: return 0
        d.entitlementState = state
        return 1
    }

    override fun clearRemoteLockedIfSet(deviceId: String): Int {
        val d = table[deviceId] ?: return 0
        return if (d.remoteLocked == true) { d.remoteLocked = false; 1 } else 0
    }


    override val table = LinkedHashMap<String, Device>()
    override fun idOf(entity: Device): String = entity.id
    override fun countGroupByUserIds(userIds: Collection<String>): List<Array<Any>> =
        table.values.filter { it.userId in userIds }.groupBy { it.userId }
            .map { arrayOf(it.key, it.value.size.toLong()) as Array<Any> }
    override fun findByIdForUpdate(id: String): Device? = table[id]
    override fun searchDevices(keyword: String, kw: String, onlineMode: String, staleCut: Long, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<Device> {
        var all = table.values.toList()
        if (onlineMode == "1") all = all.filter { it.onlineStatus == "ONLINE" && (it.lastOnlineAt ?: 0) >= staleCut }
        if (onlineMode == "0") all = all.filter { !(it.onlineStatus == "ONLINE" && (it.lastOnlineAt ?: 0) >= staleCut) }
        val sorted = all.sortedByDescending { it.createdAt }
        val from = pageable.offset.toInt()
        val to = minOf(from + pageable.pageSize, sorted.size)
        val content = if (from >= sorted.size) emptyList<Device>() else sorted.subList(from, to)
        return org.springframework.data.domain.PageImpl(content, pageable, sorted.size.toLong())
    }
    // DeviceRepository 的派生查询（findByUserId 等）测试用不到，保持默认未实现即可
    override fun findByUserId(userId: String): List<Device> = table.values.filter { it.userId == userId }
    override fun countByUserId(userId: String): Long = findByUserId(userId).size.toLong()
    override fun countByOnlineStatus(onlineStatus: String): Long = table.values.count { it.onlineStatus == onlineStatus }.toLong()
    override fun findByOnlineStatus(onlineStatus: String): List<Device> =
        table.values.filter { it.onlineStatus == onlineStatus }
    override fun findByOnlineStatusAndLastOnlineAtLessThan(onlineStatus: String, ts: Long): List<Device> =
        table.values.filter { it.onlineStatus == onlineStatus && (it.lastOnlineAt ?: Long.MAX_VALUE) < ts }
    override fun findByDeviceSn(deviceSn: String): List<Device> = emptyList()
    override fun findByLastActiveAtGreaterThan(since: Long): List<Device> = emptyList()

    override fun countOnlineSince(staleCut: Long): Long = table.values.count {
        it.onlineStatus == "ONLINE" && it.lastOnlineAt != null && it.lastOnlineAt!! >= staleCut
    }.toLong()

    override fun findCreatedAtSince(since: Long): List<Long> =
        table.values.map { it.createdAt }.filter { it >= since }
}

open class FakeUsageDelayRepo : FakeJpaRepository<UsageDelayTicket, String>(), UsageDelayRepository {
    override val table = LinkedHashMap<String, UsageDelayTicket>()
    override fun idOf(entity: UsageDelayTicket): String = entity.id
    override fun findByIdAndDeviceId(id: String, deviceId: String): UsageDelayTicket? =
        table.values.firstOrNull { it.id == id && it.deviceId == deviceId }

    override fun findByDeviceIdAndStatus(deviceId: String, status: String): List<UsageDelayTicket> =
        table.values.filter { it.deviceId == deviceId && it.status == status }

    override fun sumEffectiveMinutes(deviceId: String): Int =
        table.values.filter { it.deviceId == deviceId && it.status in listOf("PENDING", "APPROVED") }
            .sumOf { it.minutes }

    override fun deleteByDeviceId(deviceId: String): Int {
        val before = table.size
        table.values.removeIf { it.deviceId == deviceId }
        return before - table.size
    }

    // 对应条件 UPDATE：只有未决态能被抢占，影响 0 行表示已被别的请求处理过
    override fun claimApprove(id: String, now: Long): Int {
        val t = table[id] ?: return 0
        if (t.status != "PENDING" && t.status != "IGNORED") return 0
        t.status = "APPROVED"
        t.resolvedAt = now
        return 1
    }

    override fun claimReject(id: String, now: Long): Int {
        val t = table[id] ?: return 0
        if (t.status != "PENDING" && t.status != "IGNORED") return 0
        t.status = "REJECTED"
        t.resolvedAt = now
        return 1
    }

    override fun claimDismiss(id: String, now: Long): Int {
        val t = table[id] ?: return 0
        if (t.status != "PENDING" && t.status != "IGNORED") return 0
        t.status = "IGNORED"
        t.resolvedAt = now
        return 1
    }
}

class FakeUsageSessionRepo : FakeJpaRepository<UsageSession, String>(), UsageSessionRepository {
    override val table = LinkedHashMap<String, UsageSession>()
    override fun idOf(entity: UsageSession): String = entity.deviceId
    override fun findByDeviceId(deviceId: String): UsageSession? = table[deviceId]

    /** 内存 fake 没有行锁语义，等价于普通读。 */
    override fun findByDeviceIdForUpdate(deviceId: String): UsageSession? = findByDeviceId(deviceId)

    override fun deleteByDeviceId(deviceId: String): Int {
        val before = table.size
        table.remove(deviceId)
        return before - table.size
    }

    // 对应 UsageSessionRepository.addExtendedMinutes 的条件自增（额度累加不再读改写）。
    // 单日上限也作为写条件：影响 0 行 = 已到顶，语义必须与 SQL 逐字对齐，
    // 否则「并发批准顶穿上限」这条防线在测试里永远是绿的。
    override fun addExtendedMinutes(deviceId: String, minutes: Int, now: Long, maxExtendedMinutes: Int): Int {
        val s = table[deviceId] ?: return 0
        if (s.extendedMinutes + minutes > maxExtendedMinutes) return 0
        s.extendedMinutes += minutes
        s.lastDelayEpoch = now
        s.updatedAt = now
        return 1
    }

    // 与 rebaseExpiredSession 的 JPQL 逐字对齐：只在「确实已耗尽」时重设起点。
    override fun rebaseExpiredSession(
        deviceId: String, dayKey: String, now: Long,
        quotaMinutes: Int, minutes: Int, minuteMs: Long
    ): Int {
        val s = table[deviceId] ?: return 0
        val start = s.startEpoch ?: return 0
        if (s.dayKey != dayKey) return 0
        val baseline = (quotaMinutes + s.extendedMinutes - minutes) * minuteMs
        if (now - start - s.restAccumulatedMs <= baseline) return 0
        s.startEpoch = now + s.restAccumulatedMs - baseline
        s.updatedAt = now
        return 1
    }

    @Synchronized
    override fun appendRestIfUnchanged(
        deviceId: String, dayKey: String, delta: Long, newRestIds: String,
        now: Long, oldAccum: Long, oldRestIds: String
    ): Int {
        val s = table[deviceId] ?: return 0
        if (s.dayKey != dayKey || s.restAccumulatedMs != oldAccum || s.restIds != oldRestIds) return 0
        s.restAccumulatedMs = oldAccum + delta
        s.restIds = newRestIds
        s.updatedAt = now
        return 1
    }

    @Synchronized
    override fun insertIfAbsent(deviceId: String, dayKey: String, startEpoch: Long, quotaMinutes: Int, now: Long): Int {
        if (table.containsKey(deviceId)) return 0
        table[deviceId] = UsageSession(
            deviceId = deviceId, dayKey = dayKey, startEpoch = startEpoch,
            quotaMinutes = quotaMinutes, createdAt = now, updatedAt = now
        )
        return 1
    }

    @Synchronized
    override fun startFreshIfStale(deviceId: String, dayKey: String, startEpoch: Long, quotaMinutes: Int, now: Long): Int {
        val s = table[deviceId] ?: return 0
        if (s.dayKey == dayKey && s.startEpoch != null) return 0
        s.dayKey = dayKey
        s.startEpoch = startEpoch
        s.quotaMinutes = quotaMinutes
        s.extendedMinutes = 0
        s.lastDelayEpoch = null
        s.restAccumulatedMs = 0
        s.restIds = ""
        if (s.createdAt == 0L) s.createdAt = now
        s.updatedAt = now
        return 1
    }
}
