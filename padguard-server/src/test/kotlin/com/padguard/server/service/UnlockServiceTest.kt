package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.domain.Device
import com.padguard.server.domain.UnlockTicket
import com.padguard.server.domain.UsageSession
import com.padguard.server.dto.UnlockApproveRequest
import com.padguard.server.dto.UnlockRejectRequest
import com.padguard.server.dto.UsageSessionDto
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UnlockTicketRepository
import com.padguard.server.repository.UsageSessionRepository
import com.padguard.server.security.DeviceOwnershipGuard
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.*

/**
 * `UnlockService` 是「孩子端临时解锁申请 → 家长审批」闭环的信任关键服务端（与 [com.padguard.server.service.IngestService]
 * 的 UNLOCK_REQUEST 上报直接衔接）。此前**零单测**，且历史上出过「重复上报刷一堆工单」「忽略态无法再批准」
 * 「跨日仍可被批准误放行」「双击同意下发两条 TEMP_UNLOCK」等真实缺陷——回归改坏会静默破坏审批闭环。
 *
 * 覆盖：createFromRequest 幂等/设备存在性/时长解析、approve/reject/dismiss 的原子抢占语义（claimX=0 即已处理→抛错）、
 * 跨日作废、归属校验、以及 approve/reject 正确下发对应指令（TEMP_UNLOCK / SHOW_MESSAGE）。
 * 不加载 Spring 上下文，沿用既定 Mockito 占位范式（非空参数 matcher 补 `?: 默认值`；`any()` 仅用于可空参数）。
 */
class UnlockServiceTest {
    private val deviceRepository = mock(DeviceRepository::class.java)
    private val unlockTicketRepository = mock(UnlockTicketRepository::class.java)
    private val usageSessionRepository = mock(UsageSessionRepository::class.java)
    private val usageSessionService = mock(UsageSessionService::class.java)
    private val commandService = mock(CommandService::class.java)
    private val webSocketPush = mock(WebSocketPushService::class.java)
    private val auditService = mock(AuditService::class.java)
    private val ownershipGuard = mock(DeviceOwnershipGuard::class.java)

    private val userId = "u1"
    private val deviceId = "d1"

    /** 默认会话快照：额度未耗尽。用例需要「已耗尽 / 已到上限」时自行覆盖 stub。 */
    private fun session(
        remainingMs: Long = 30 * 60_000L,
        extendedMinutes: Int = 0,
        active: Boolean = true,
        quotaMinutes: Int = 60
    ) = UsageSessionDto(
        deviceId = deviceId, dayKey = "2026-11-12", active = active,
        quotaMinutes = quotaMinutes, extendedMinutes = extendedMinutes,
        remainingMs = remainingMs, serverNow = System.currentTimeMillis()
    )

    private fun svc(snapshot: UsageSessionDto = session()): UnlockService {
        // Mockito 对 Kotlin 非空返回类型默认给 null，不 stub 会在 before.active 处 NPE。
        `when`(usageSessionService.get(anyString() ?: "")).thenReturn(snapshot)
        return UnlockService(
            deviceRepository, unlockTicketRepository, usageSessionRepository, usageSessionService,
            commandService, webSocketPush, auditService, ownershipGuard
        )
    }

    private fun device(): Device = Device().apply {
        id = this@UnlockServiceTest.deviceId
        userId = this@UnlockServiceTest.userId
    }

    // ---- createFromRequest ----

    @Test
    fun createFromRequest_newRequest_savesAndPushes() {
        `when`(unlockTicketRepository.existsById(anyString() ?: "")).thenReturn(false)
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        var saved: UnlockTicket? = null
        `when`(unlockTicketRepository.save(any(UnlockTicket::class.java) ?: UnlockTicket()))
            .thenAnswer { inv -> saved = inv.arguments[0] as UnlockTicket; inv.arguments[0] }

        svc().createFromRequest(deviceId, mapOf("requestId" to "req1", "packageName" to "com.game", "durationMinutes" to "30"))

        assertNotNull(saved)
        assertEquals("req1", saved!!.id)
        assertEquals("com.game", saved!!.packageName)
        assertEquals(30, saved!!.durationMinutes)
        assertEquals("PENDING", saved!!.status)
        // 首次插入成功才推送（幂等重试不再推）
        verify(webSocketPush).pushToUser(eq(userId), any() ?: emptyMap())
    }

    @Test
    fun createFromRequest_duplicateRequestId_fastPathNoSave() {
        // 已存在 requestId → 快路径直接返回，不落库、不推送
        `when`(unlockTicketRepository.existsById(anyString() ?: "")).thenReturn(true)

        svc().createFromRequest(deviceId, mapOf("requestId" to "req1"))

        verify(unlockTicketRepository, never()).save(any(UnlockTicket::class.java) ?: UnlockTicket())
        verify(webSocketPush, never()).pushToUser(any(), any() ?: emptyMap())
    }

    @Test
    fun createFromRequest_deviceNotFound_noSave() {
        `when`(unlockTicketRepository.existsById(anyString() ?: "")).thenReturn(false)
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.empty())

        svc().createFromRequest(deviceId, mapOf("requestId" to "req1"))

        verify(unlockTicketRepository, never()).save(any(UnlockTicket::class.java) ?: UnlockTicket())
        verify(webSocketPush, never()).pushToUser(any(), any() ?: emptyMap())
    }

    @Test
    fun createFromRequest_durationAsNumber_parsed() {
        `when`(unlockTicketRepository.existsById(anyString() ?: "")).thenReturn(false)
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        var saved: UnlockTicket? = null
        `when`(unlockTicketRepository.save(any(UnlockTicket::class.java) ?: UnlockTicket()))
            .thenAnswer { inv -> saved = inv.arguments[0] as UnlockTicket; inv.arguments[0] }

        svc().createFromRequest(deviceId, mapOf("requestId" to "req2", "durationMinutes" to 45)) // 数值型

        assertEquals(45, saved!!.durationMinutes)
    }

    // ---- approve ----

    private fun pendingTicket(id: String, createdAt: Long = System.currentTimeMillis(), packageName: String? = null): UnlockTicket =
        UnlockTicket(id = id, deviceId = deviceId, userId = userId, status = "PENDING", createdAt = createdAt, packageName = packageName)

    @Test
    fun approve_issuesTempUnlockCommandAndAudits() {
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        `when`(unlockTicketRepository.findByIdAndDeviceId(anyString() ?: "", anyString() ?: "")).thenReturn(pendingTicket("t1"))
        `when`(unlockTicketRepository.claimApprove(anyString() ?: "", any() ?: 0, anyLong())).thenReturn(1)
        `when`(usageSessionRepository.addExtendedMinutes(anyString() ?: "", anyInt(), anyLong(), anyInt())).thenReturn(1)

        svc().approve(userId, deviceId, "t1", UnlockApproveRequest(durationMinutes = 30))

        // 同意必须下发 TEMP_UNLOCK 高优指令（带时长）
        verify(commandService).issueCommand(eq(deviceId) ?: deviceId, eq("TEMP_UNLOCK") ?: "TEMP_UNLOCK", any() ?: emptyMap(), eq("HIGH") ?: "HIGH")
        verify(auditService).record(any(), any(), anyString() ?: "", any(), any(), any(), anyString() ?: "", any())
    }

    @Test
    fun approve_wholeDeviceRequest_extendsDailyTotal() {
        // 整机使用时间申请（packageName 为空）：同意必须同时把时长累加到当日总时长
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        `when`(unlockTicketRepository.findByIdAndDeviceId(anyString() ?: "", anyString() ?: "")).thenReturn(pendingTicket("t1", packageName = null))
        `when`(unlockTicketRepository.claimApprove(anyString() ?: "", any() ?: 0, anyLong())).thenReturn(1)
        `when`(usageSessionRepository.addExtendedMinutes(anyString() ?: "", anyInt(), anyLong(), anyInt())).thenReturn(1)

        svc().approve(userId, deviceId, "t1", UnlockApproveRequest(durationMinutes = 30))

        // 整机申请：累加 30 分钟到 extendedMinutes（与申请时长一致）
        verify(usageSessionRepository).addExtendedMinutes(eq(deviceId) ?: deviceId, eq(30), anyLong(), anyInt())
    }

    @Test
    fun approve_appSpecificRequest_doesNotExtendDailyTotal() {
        // 应用级解锁（带包名）：只下发 TEMP_UNLOCK，不应改动每日总额度
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        `when`(unlockTicketRepository.findByIdAndDeviceId(anyString() ?: "", anyString() ?: "")).thenReturn(pendingTicket("t1", packageName = "com.game"))
        `when`(unlockTicketRepository.claimApprove(anyString() ?: "", any() ?: 0, anyLong())).thenReturn(1)

        svc().approve(userId, deviceId, "t1", UnlockApproveRequest(durationMinutes = 30))

        verify(usageSessionRepository, never()).addExtendedMinutes(anyString() ?: "", anyInt(), anyLong(), anyInt())
    }

    @Test
    fun approve_wholeDeviceRequest_expiredSession_rebasesToGrantFullMinutes() {
        // 「当天时间已达上限」：基础额度耗尽（remainingMs=0）时，新增时长会被
        // (now - startEpoch) 的历史超支整笔吃掉，必须补偿，否则孩子端剩余仍为 0。
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        `when`(unlockTicketRepository.findByIdAndDeviceId(anyString() ?: "", anyString() ?: "")).thenReturn(pendingTicket("t1"))
        `when`(unlockTicketRepository.claimApprove(anyString() ?: "", any() ?: 0, anyLong())).thenReturn(1)
        `when`(usageSessionRepository.addExtendedMinutes(anyString() ?: "", anyInt(), anyLong(), anyInt())).thenReturn(1)

        svc(session(remainingMs = 0L)).approve(userId, deviceId, "t1", UnlockApproveRequest(durationMinutes = 30))

        verify(usageSessionRepository).rebaseExpiredSession(
            eq(deviceId) ?: deviceId, anyString() ?: "", anyLong(), eq(60), eq(30), eq(60_000L)
        )
    }

    @Test
    fun approve_wholeDeviceRequest_nearCap_clampsInsteadOfDroppingWholeGrant() {
        // 已累计 110 分钟、家长再批 30 分钟：整笔撞 120 上限会返回 0，
        // 应钳制到剩余 10 分钟继续累加，而不是「一分不加却显示已同意」。
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        `when`(unlockTicketRepository.findByIdAndDeviceId(anyString() ?: "", anyString() ?: "")).thenReturn(pendingTicket("t1"))
        `when`(unlockTicketRepository.claimApprove(anyString() ?: "", any() ?: 0, anyLong())).thenReturn(1)
        `when`(usageSessionRepository.addExtendedMinutes(anyString() ?: "", eq(30), anyLong(), anyInt())).thenReturn(0)
        `when`(usageSessionRepository.addExtendedMinutes(anyString() ?: "", eq(10), anyLong(), anyInt())).thenReturn(1)

        svc(session(extendedMinutes = 110)).approve(userId, deviceId, "t1", UnlockApproveRequest(durationMinutes = 30))

        verify(usageSessionRepository).addExtendedMinutes(eq(deviceId) ?: deviceId, eq(30), anyLong(), anyInt())
        verify(usageSessionRepository).addExtendedMinutes(eq(deviceId) ?: deviceId, eq(10), anyLong(), anyInt())
    }

    @Test
    fun approve_wholeDeviceRequest_capReached_stillIssuesTempUnlock() {
        // 确实到顶：不再加额度，但审批必须照常解锁，不能因记账失败把整个事务回滚。
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        `when`(unlockTicketRepository.findByIdAndDeviceId(anyString() ?: "", anyString() ?: "")).thenReturn(pendingTicket("t1"))
        `when`(unlockTicketRepository.claimApprove(anyString() ?: "", any() ?: 0, anyLong())).thenReturn(1)
        `when`(usageSessionRepository.addExtendedMinutes(anyString() ?: "", anyInt(), anyLong(), anyInt())).thenReturn(0)

        svc(session(remainingMs = 0L, extendedMinutes = 120))
            .approve(userId, deviceId, "t1", UnlockApproveRequest(durationMinutes = 30))

        verify(commandService).issueCommand(
            eq(deviceId) ?: deviceId, eq("TEMP_UNLOCK") ?: "TEMP_UNLOCK", any() ?: emptyMap(), eq("HIGH") ?: "HIGH"
        )
        verify(usageSessionRepository, never()).rebaseExpiredSession(
            anyString() ?: "", anyString() ?: "", anyLong(), anyInt(), anyInt(), anyLong()
        )
    }

    @Test
    fun approve_alreadyProcessed_throwsAndDoesNotIssue() {
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        `when`(unlockTicketRepository.findByIdAndDeviceId(anyString() ?: "", anyString() ?: "")).thenReturn(pendingTicket("t1"))
        // 原子抢占失败（已被并发处理）→ 视为已处理
        `when`(unlockTicketRepository.claimApprove(anyString() ?: "", any() ?: 0, anyLong())).thenReturn(0)

        assertThrows(BizException::class.java) { svc().approve(userId, deviceId, "t1", UnlockApproveRequest()) }
        // 抢占失败不应下发第二条指令（防双 TEMP_UNLOCK）
        verify(commandService, never()).issueCommand(anyString() ?: "", anyString() ?: "", any(), anyString() ?: "")
    }

    @Test
    fun approve_crossDayTicket_expiredThrows() {
        // 昨天的工单：解锁申请仅当日有效，跨日批准应被拒（防误放行）
        val yesterday = System.currentTimeMillis() - 24 * 3600_000L
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        `when`(unlockTicketRepository.findByIdAndDeviceId(anyString() ?: "", anyString() ?: "")).thenReturn(pendingTicket("t1", yesterday))

        assertThrows(BizException::class.java) { svc().approve(userId, deviceId, "t1", UnlockApproveRequest()) }
        verify(unlockTicketRepository, never()).claimApprove(anyString() ?: "", any() ?: 0, anyLong())
    }

    // ---- reject ----

    @Test
    fun reject_issuesShowMessageCommand() {
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        `when`(unlockTicketRepository.findByIdAndDeviceId(anyString() ?: "", anyString() ?: "")).thenReturn(pendingTicket("t1"))
        `when`(unlockTicketRepository.claimReject(anyString() ?: "", anyLong())).thenReturn(1)

        svc().reject(userId, deviceId, "t1", UnlockRejectRequest(reason = "现在不行"))

        // 拒绝下发 SHOW_MESSAGE（带拒因），不签发解锁
        verify(commandService).issueCommand(eq(deviceId) ?: deviceId, eq("SHOW_MESSAGE") ?: "SHOW_MESSAGE", any() ?: emptyMap(), eq("NORMAL") ?: "NORMAL")
    }

    // ---- dismiss ----

    @Test
    fun dismiss_alreadyProcessed_throws() {
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        `when`(unlockTicketRepository.findByIdAndDeviceId(anyString() ?: "", anyString() ?: "")).thenReturn(pendingTicket("t1"))
        // 抢占失败 → 已处理
        `when`(unlockTicketRepository.claimDismiss(anyString() ?: "", anyLong())).thenReturn(0)

        assertThrows(BizException::class.java) { svc().dismiss(userId, deviceId, "t1") }
        verify(commandService, never()).issueCommand(anyString() ?: "", anyString() ?: "", any(), anyString() ?: "")
    }

    // ---- listTickets ----

    @Test
    fun listTickets_returnsMappedAndGuardsOwnership() {
        `when`(ownershipGuard.requireOwned(anyString() ?: "", anyString() ?: "")).thenReturn(device())
        `when`(unlockTicketRepository.findByDeviceIdAndStatus(anyString() ?: "", anyString() ?: "")).thenAnswer { inv ->
            val status = inv.arguments[1] as String
            if (status == "PENDING") listOf(pendingTicket("t1")) else emptyList()
        }
        // 另一个用户的工单不应出现（按 userId 过滤）
        `when`(unlockTicketRepository.findByDeviceIdAndStatus(anyString() ?: "", eq("APPROVED") ?: "APPROVED")).thenReturn(
            listOf(UnlockTicket(id = "other", deviceId = deviceId, userId = "otherUser", status = "APPROVED"))
        )

        val list = svc().listTickets(userId, deviceId)

        assertEquals(1, list.size)
        assertEquals("t1", list[0].id)
        verify(ownershipGuard).requireOwned(userId, deviceId)
    }

    @Test
    fun approve_wholeDeviceRequest_overCap_clampsIssuedDurationToDailyLimit() {
        // P1（2026-11-18）：此前下发原始 480 分钟、账面只加 120 分钟，
        // 相当于用"整机临时解锁"突破单日延时上限。这里锁死"下发值 ≤ 入账上限"。
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        `when`(unlockTicketRepository.findByIdAndDeviceId(anyString() ?: "", anyString() ?: "")).thenReturn(pendingTicket("t1"))
        `when`(unlockTicketRepository.claimApprove(anyString() ?: "", any() ?: 0, anyLong())).thenReturn(1)
        `when`(usageSessionRepository.addExtendedMinutes(anyString() ?: "", anyInt(), anyLong(), anyInt())).thenReturn(1)

        svc().approve(userId, deviceId, "t1", UnlockApproveRequest(durationMinutes = 480))

        // Mockito 的 captor.capture() 对 Kotlin 非空 Map 参数会返回 null 并在 verify 中 NPE，
        // 用 argThat 捕获（同样补 ?: 非空占位），避免把 Mockito 留在"未完成校验"态污染后续用例。
        var captured: Map<String, Any?>? = null
        verify(commandService).issueCommand(
            eq(deviceId) ?: deviceId, eq("TEMP_UNLOCK") ?: "TEMP_UNLOCK",
            argThat<Map<String, Any?>> { m -> captured = m; true } ?: emptyMap(),
            eq("HIGH") ?: "HIGH"
        )
        assertEquals(
            UsageSession.MAX_DAILY_EXTEND_MINUTES,
            captured?.get(CommandKey.DURATION_MINUTES),
            "下发的临时解锁时长不得超过单日入账上限"
        )
        verify(unlockTicketRepository).claimApprove(
            eq("t1") ?: "t1",
            eq(UsageSession.MAX_DAILY_EXTEND_MINUTES) ?: UsageSession.MAX_DAILY_EXTEND_MINUTES,
            anyLong()
        )
    }

    @Test
    fun approve_wholeDeviceRequest_negativeDuration_clampsToMinOne() {
        // 防御性下限：家长填写/孩子上报负时长时，下发值必须 >= 1。
        // 孩子端 CommandExecutor 对 durationMinutes<=0 直接 ACK BAD_PAYLOAD 拒绝解锁，
        // 若服务端下发 -5，会出现「账面 +1 分钟、孩子端拒绝解锁→毫无反应」的三端不一致，
        // 正好违反 approve 自身注释声明的「不能让家长点了同意孩子端却没反应」底线。
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        `when`(unlockTicketRepository.findByIdAndDeviceId(anyString() ?: "", anyString() ?: "")).thenReturn(pendingTicket("t1", packageName = null))
        `when`(unlockTicketRepository.claimApprove(anyString() ?: "", any() ?: 0, anyLong())).thenReturn(1)
        `when`(usageSessionRepository.addExtendedMinutes(anyString() ?: "", anyInt(), anyLong(), anyInt())).thenReturn(1)

        svc().approve(userId, deviceId, "t1", UnlockApproveRequest(durationMinutes = -5))

        var captured: Map<String, Any?>? = null
        verify(commandService).issueCommand(
            eq(deviceId) ?: deviceId, eq("TEMP_UNLOCK") ?: "TEMP_UNLOCK",
            argThat<Map<String, Any?>> { m -> captured = m; true } ?: emptyMap(),
            eq("HIGH") ?: "HIGH"
        )
        assertEquals(1, captured?.get(CommandKey.DURATION_MINUTES), "下发的临时解锁时长必须 >= 1")
        verify(unlockTicketRepository).claimApprove(eq("t1") ?: "t1", eq(1) ?: 1, anyLong())
    }

    @Test
    fun approve_appSpecificRequest_negativeDuration_clampsToMinOne() {
        // 应用级解锁分支（packageName 非空）此前上下界都不夹，同样不能下发非正时长。
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(Optional.of(device()))
        `when`(unlockTicketRepository.findByIdAndDeviceId(anyString() ?: "", anyString() ?: "")).thenReturn(pendingTicket("t1", packageName = "com.game"))
        `when`(unlockTicketRepository.claimApprove(anyString() ?: "", any() ?: 0, anyLong())).thenReturn(1)

        svc().approve(userId, deviceId, "t1", UnlockApproveRequest(durationMinutes = -5))

        var captured: Map<String, Any?>? = null
        verify(commandService).issueCommand(
            eq(deviceId) ?: deviceId, eq("TEMP_UNLOCK") ?: "TEMP_UNLOCK",
            argThat<Map<String, Any?>> { m -> captured = m; true } ?: emptyMap(),
            eq("HIGH") ?: "HIGH"
        )
        assertEquals(1, captured?.get(CommandKey.DURATION_MINUTES), "应用级解锁下发时长也必须 >= 1")
        verify(usageSessionRepository, never()).addExtendedMinutes(anyString() ?: "", anyInt(), anyLong(), anyInt())
    }
}
