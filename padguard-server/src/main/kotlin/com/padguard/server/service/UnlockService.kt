package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.UnlockTicket
import com.padguard.server.domain.UsageSession
import com.padguard.server.dto.UnlockApproveRequest
import com.padguard.server.dto.UnlockRejectRequest
import com.padguard.server.dto.UnlockTicketDto
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UnlockTicketRepository
import com.padguard.server.repository.UsageSessionRepository
import com.padguard.server.service.AuditAction
import com.padguard.server.service.AuditService
import com.padguard.server.ws.WebSocketPushService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 分钟 → 毫秒，与 UsageSessionService.toDto 的额度换算同口径 */
private const val MINUTE_MS = 60_000L

@Service
class UnlockService(
    private val deviceRepository: DeviceRepository,
    private val unlockTicketRepository: UnlockTicketRepository,
    private val usageSessionRepository: UsageSessionRepository,
    private val usageSessionService: UsageSessionService,
    private val commandService: CommandService,
    private val webSocketPush: WebSocketPushService,
    private val auditService: AuditService,
    private val ownershipGuard: com.padguard.server.security.DeviceOwnershipGuard
) {
    private val log = LoggerFactory.getLogger(UnlockService::class.java)

    /**
     * 孩子端 UNLOCK_REQUEST 日志 -> 工单（工单 ID = requestId，便于回执对齐）。
     *
     * ## 为什么 save 要包 try/catch（而不是只靠前面的 existsById）
     * `existsById` + `save` 是 check-then-act：同一 requestId 的两条日志（孩子端本地重试
     * 会生成新的 logId，但 requestId 不变）若被并发处理，两边都会查到不存在，
     * 随后第二个 INSERT 撞主键抛 [DataIntegrityViolationException]。
     * 该异常一路冒泡到 `/v1/logs` 的控制器，孩子端收到 500 后会把**整批**日志重发，
     * 其中已经写进库的部分再度撞 logId 唯一约束，形成"越重试越失败"的雪崩。
     *
     * 主键冲突在这里的语义就是"另一条并发请求已经建好了同一张工单"，
     * 属于**幂等成功**，不是错误。因此捕获后回查一次确认，确认存在就安静返回。
     */
    fun createFromRequest(deviceId: String, payload: Map<String, Any?>?) {
        val requestId = (payload?.get("requestId") as? String)?.takeIf { it.isNotBlank() }
            ?: "ur_" + UUID.randomUUID().toString().replace("-", "").take(20)
        // 快路径：绝大多数重复上报在这里就返回，不去触发一次注定失败的主键冲突
        if (unlockTicketRepository.existsById(requestId)) return
        val dev = deviceRepository.findById(deviceId).orElse(null) ?: return
        val ticket = UnlockTicket(
            id = requestId, deviceId = deviceId, userId = dev.userId,
            packageName = payload?.get("packageName") as? String,
            appLabel = payload?.get("appLabel") as? String,
            durationMinutes = (payload?.get("durationMinutes") as? String)?.toIntOrNull()
                ?: (payload?.get("durationMinutes") as? Number)?.toInt(),
            reason = payload?.get("reason") as? String,
            status = "PENDING", createdAt = System.currentTimeMillis()
        )
        try {
            unlockTicketRepository.save(ticket)
        } catch (e: DataIntegrityViolationException) {
            // 只有"确实是同一 requestId 已被插入"才视为幂等成功；
            // 其他完整性错误（如归属家长已不存在导致外键失败）仍然如实抛出。
            if (!unlockTicketRepository.existsById(requestId)) throw e
            log.info("concurrent unlock ticket ignored (already created): {}", requestId)
            return
        }
        // 实时推送（与延时申请 usage.delay.requested 同口径）：孩子发起解锁申请后，
        // 家长端若只靠 10s 轮询消息中心，弱网/后台场景下可能永远不知道——
        // 孩子在锁屏页等着，家长却毫无感知，是"申请-审批"闭环最伤的一环。
        // 只在**首次插入成功**时推（幂等重试不再推），避免重复打扰。
        dev.userId?.let {
            webSocketPush.pushToUser(
                it,
                mapOf(
                    "type" to "unlock.requested",
                    "deviceId" to deviceId,
                    "ticketId" to ticket.id,
                    "packageName" to ticket.packageName,
                    "appLabel" to ticket.appLabel,
                    "createdAt" to ticket.createdAt
                )
            )
        }
    }

    /**
     * 列出该设备的全部工单（含已处理与已忽略）。
     *
     * 为什么要把 APPROVED / REJECTED / IGNORED 一起返回：
     * 家长端"小铃铛"是一个**消息中心**，忽略之后必须还能在这里翻出来继续处理。
     * 只返回 PENDING 的话，家长一旦点了忽略，这条申请就从地球上消失了，
     * 想再放行只能等孩子重新提交一次 —— 而孩子往往不会重提。
     */
    fun listTickets(userId: String, deviceId: String): List<UnlockTicketDto> {
        // 设备归属校验（与其他 {deviceId} 接口同口径）：跨用户访问他人设备应 403，
        // 而不是靠下方 userId filter 兜底返回 200 空列表 —— 后者虽不泄漏数据，
        // 但让"任意登录家长可探测 deviceId 是否存在"成立，且与全系统行为不一致。
        // 第 4 轮边界测试实测：修复前跨用户访问返回 200 + 空列表。
        ownershipGuard.requireOwned(userId, deviceId)
        val today = dayKey(System.currentTimeMillis())
        return listOf("PENDING", "APPROVED", "REJECTED", "IGNORED")
            .flatMap { unlockTicketRepository.findByDeviceIdAndStatus(deviceId, it) }
            .filter { it.userId == userId }
            .sortedByDescending { it.createdAt }
            .map { toDto(it, today) }
    }

    /**
     * 忽略：关闭弹窗并归档，**不下发任何消息给孩子**，且工单仍可被后续批准。
     *
     * 为什么不能复用 reject：
     * reject 会给孩子端下发一条 SHOW_MESSAGE（"申请未通过"），并把工单置为终态不可再批。
     * 但"忽略"的语义是"我现在不想管"，不是"我不同意"——
     * 孩子此刻正在锁屏页盯着，一条拒绝对他是无谓的打击；而家长稍后改变主意时工单已死。
     * 因此单独引入 IGNORED 中间态：弹窗不再打扰，记录留在铃铛里随时可翻出来批准。
     */
    @Transactional
    fun dismiss(userId: String, deviceId: String, ticketId: String) {
        ownedOpen(userId, deviceId, ticketId)
        val claimed = unlockTicketRepository.claimDismiss(ticketId, System.currentTimeMillis())
        if (claimed == 0) {
            throw BizException(ParentErr.PARAM_ERROR, "该申请已被处理，请刷新后查看", Audience.PARENT)
        }
        auditService.record(userId, deviceId, AuditAction.UNLOCK_DISMISS, targetType = "ticket", targetId = ticketId)
    }

    /** 家长同意 -> 下发 TEMP_UNLOCK（带 packageName / durationMinutes） */
    @Transactional
    fun approve(userId: String, deviceId: String, ticketId: String, req: UnlockApproveRequest) {
        val t = ownedOpen(userId, deviceId, ticketId)
        val requestedDuration = req.durationMinutes ?: t.durationMinutes ?: 30
        // P1（2026-11-18）：整机申请此前下发**未钳制**的原始时长（家长可填 480），
        // 而额度记账在 grantWholeDeviceMinutes 里钳制到日上限 120 —— 设备放行 8 小时、账面只加 2 小时，
        // 等于突破"单日累计延时 120 分钟"这条硬防线。下发值必须与记账上限同源。
        // 带包名的应用级解锁不改每日额度，保留原时长（仅夹非正下界）。
        // 下界必须夹到 >= 1：孩子端 CommandExecutor 对 durationMinutes<=0 直接 ACK BAD_PAYLOAD
        // 拒绝整条 TEMP_UNLOCK；若放行 -5，会出现「账面已加时、设备却拒绝解锁→家长点同意孩子端毫无反应」，
        // 正是本方法注释承诺要避免的事故。记账侧 grantWholeDeviceMinutes 已 coerceIn(1,max)，
        // 下发值必须与之一致，三端口径才统一。
        val effectiveDuration = if (t.packageName.isNullOrBlank()) {
            requestedDuration.coerceIn(1, UsageSession.MAX_DAILY_EXTEND_MINUTES)
        } else {
            requestedDuration.coerceAtLeast(1)
        }
        // 原子抢占，避免"双击同意 / 超时重试"下发两条 TEMP_UNLOCK。
        // 两条 HIGH 优先级指令孩子端都会执行，若两次时长不同还会互相覆盖
        // （家长批 30 分钟，设备被放开 60 分钟）。
        val claimed = unlockTicketRepository.claimApprove(ticketId, effectiveDuration, System.currentTimeMillis())
        if (claimed == 0) {
            throw BizException(ParentErr.PARAM_ERROR, "该申请已被处理，请刷新后查看", Audience.PARENT)
        }
        auditService.record(userId, deviceId, AuditAction.UNLOCK_APPROVE, targetType = "ticket", targetId = ticketId,
            detail = "package=${req.packageName ?: t.packageName ?: ""} duration=${effectiveDuration}")
        // 整机使用时间申请（packageName 为空）：同意即把申请时长累加到当日总时长（extendedMinutes）。
        // 否则设备虽被 TEMP_UNLOCK 放开，但三端（孩子端倒计时 / 家长端看板 / 服务端锁机判定）
        // 的"总时长"不变 —— 表现为"解锁了却没加时间"，与用户预期"总时长增加"不符。
        // 仅整机申请做此累加：带包名的应用级解锁只放开那一个应用，不应改动每日总额度。
        if (t.packageName.isNullOrBlank()) {
            grantWholeDeviceMinutes(deviceId, ticketId, effectiveDuration)
        }
        commandService.issueCommand(
            deviceId, CommandType.TEMP_UNLOCK,
            mapOf(
                CommandKey.PACKAGE_NAME to (req.packageName ?: t.packageName ?: ""),
                CommandKey.DURATION_MINUTES to effectiveDuration
            ),
            priority = "HIGH"
        )
    }

    /**
     * 整机使用时间申请批准后的额度落地。
     *
     * 本场景（用户原话「当天时间已达上限，申请解锁时增加的时长」）有两处坑，缺一都会
     * 表现为"家长同意了、设备也解锁了，可时长没加上去"：
     *
     * 1. **额度耗尽后的历史超支**：服务端剩余按 `(quota + extended) - (now - startEpoch)` 派生，
     *    额度用完后孩子等审批的那段锁屏时间仍在流逝，会把新增时长整笔吃掉。故在累加后调用
     *    [UsageSessionRepository.rebaseExpiredSession] 把这段不可用时间从计时里剔除，
     *    使批准的 N 分钟真实变成孩子端 N 分钟倒计时（三端同源，仍由服务端 DTO 派生）。
     * 2. **单日 120 分钟上限的"全有或全无"**：[addExtendedMinutes] 的写条件是
     *    `extended + minutes <= max`，只剩 10 分钟额度时家长批 30 分钟会**整笔返回 0**。
     *    这里钳制到剩余额度再试一次，能加多少加多少；确实到顶才只解锁、不加额度并记日志，
     *    不再静默失败。与 [UsageDelayService.approve] 共用同一上限，两条通道不会叠加突破。
     *
     * 命中上限 / 无会话时**不抛异常**：TEMP_UNLOCK 已给到申请时长，不能因为额度记账失败
     * 把整个审批事务回滚掉，否则家长点同意后孩子端毫无反应。
     */
    private fun grantWholeDeviceMinutes(deviceId: String, ticketId: String, requestedDuration: Int) {
        val now = System.currentTimeMillis()
        val max = UsageSession.MAX_DAILY_EXTEND_MINUTES
        // 异常 payload 兜底：负数 / 0 会把 extended_minutes 写坏，超上限也不该直接顶穿防线。
        val requested = requestedDuration.coerceIn(1, max)
        // 会话快照取服务端的实时解析额度（家长当天改过额度时实体列里的旧值不可用）。
        val before = usageSessionService.get(deviceId)

        var granted = requested
        var added = usageSessionRepository.addExtendedMinutes(deviceId, granted, now, max)
        if (added == 0 && before.extendedMinutes < max) {
            granted = minOf(requested, max - before.extendedMinutes)
            if (granted > 0) {
                added = usageSessionRepository.addExtendedMinutes(deviceId, granted, now, max)
            }
        }
        if (added == 0) {
            log.warn(
                "whole-device unlock approve: extendedMinutes not added (cap reached or no session): " +
                    "device={} ticket={} duration={} extended={}", deviceId, ticketId, requested, before.extendedMinutes
            )
            return
        }
        // 额度已耗尽才补偿：未耗尽时基础剩余会自然增加 granted 分钟，动起点反而会多送时长。
        if (before.active && before.remainingMs <= 0L) {
            val rebased = usageSessionRepository.rebaseExpiredSession(
                deviceId, before.dayKey, now, before.quotaMinutes, granted, MINUTE_MS
            )
            log.info(
                "whole-device unlock approve: rebase expired session device={} ticket={} rows={}",
                deviceId, ticketId, rebased
            )
        }
        log.info("whole-device unlock approve: +{}min device={} ticket={}", granted, deviceId, ticketId)
    }

    /** 家长拒绝 -> 下发 SHOW_MESSAGE（带 requestId + 拒因 body） */
    @Transactional
    fun reject(userId: String, deviceId: String, ticketId: String, req: UnlockRejectRequest) {
        val t = ownedOpen(userId, deviceId, ticketId)
        // 原子抢占（同 approve）：防止并发 reject 把刚 claimApprove 的 APPROVED 覆盖回 REJECTED，
        // 继而让家长二次批准、下发第二条 TEMP_UNLOCK。
        val claimed = unlockTicketRepository.claimReject(ticketId, System.currentTimeMillis())
        if (claimed == 0) {
            throw BizException(ParentErr.PARAM_ERROR, "该申请已被处理，请刷新后查看", Audience.PARENT)
        }
        auditService.record(userId, deviceId, AuditAction.UNLOCK_REJECT, targetType = "ticket", targetId = ticketId,
            detail = "reason=${req.reason ?: "申请未通过"}")
        commandService.issueCommand(
            deviceId, CommandType.SHOW_MESSAGE,
            mapOf(
                CommandKey.REQUEST_ID to t.id,
                CommandKey.CONTENT to (req.reason ?: "申请未通过")
            ),
            priority = "NORMAL"
        )
    }

    /**
     * 取出"仍可处理"的工单：PENDING（未看过）或 IGNORED（看过但没决定）。
     *
     * 早期这里只认 PENDING，一旦引入忽略态就会把"忽略后想再批准"挡在门外，
     * 只会报一个含糊的"已处理"。忽略不是终态，必须和 PENDING 同等对待。
     */
    private fun ownedOpen(userId: String, deviceId: String, ticketId: String): UnlockTicket {
        val dev = deviceRepository.findById(deviceId).orElse(null)
            ?: throw BizException(ParentErr.DEVICE_NOT_FOUND, "设备不存在", Audience.PARENT)
        if (dev.userId != userId) {
            throw BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        }
        val t = unlockTicketRepository.findByIdAndDeviceId(ticketId, deviceId)
            ?: throw BizException(ParentErr.PARAM_ERROR, "工单不存在", Audience.PARENT)
        if (t.status != "PENDING" && t.status != "IGNORED") {
            throw BizException(ParentErr.PARAM_ERROR, "工单已处理（已同意或已拒绝）", Audience.PARENT)
        }
        // 跨日作废（与延时申请 UsageDelayService 同口径）：解锁申请只在提交当日有效。
        // 原实现没有时效——三十年前的 PENDING 工单今天照样能批准并下发 TEMP_UNLOCK；
        // 孩子的诉求有很强的时效性（"我现在想用一下"），隔天批准没有意义还可能误放行。
        if (dayKey(t.createdAt) != dayKey(System.currentTimeMillis())) {
            throw BizException(ParentErr.PARAM_ERROR, "该申请已过期（仅当日有效），请让孩子重新提交", Audience.PARENT)
        }
        return t
    }

    private fun dayKey(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(com.padguard.server.common.SERVER_ZONE_ID).toLocalDate().toString()

    /** [today] 由调用方传入：列表一次计算，避免每行取当日键 */
    private fun toDto(t: UnlockTicket, today: String): UnlockTicketDto {
        // 展示级作废：非当日仍敞开的工单在消息中心显示"已过期"（不回写库，保留原始轨迹）。
        // 家长端按 status 展示徽标、按 isPending 过滤弹窗 —— "EXPIRED" 两条路都天然收敛。
        val status = if ((t.status == "PENDING" || t.status == "IGNORED") && dayKey(t.createdAt) != today) {
            "EXPIRED"
        } else t.status
        return UnlockTicketDto(
            id = t.id, deviceId = t.deviceId, packageName = t.packageName, appLabel = t.appLabel,
            durationMinutes = t.durationMinutes, reason = t.reason, status = status,
            createdAt = t.createdAt, resolvedAt = t.resolvedAt
        )
    }
}
