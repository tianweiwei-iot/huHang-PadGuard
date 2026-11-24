package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.AccountDeletionRequest
import com.padguard.server.domain.Appeal
import com.padguard.server.domain.Device
import com.padguard.server.domain.GuardianConsent
import com.padguard.server.dto.*
import com.padguard.server.repository.AccountDeletionRepository
import com.padguard.server.repository.AppealRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.GuardianConsentRepository
import com.padguard.server.repository.LocationTrackRepository
import com.padguard.server.repository.ParentErasureDao
import com.padguard.server.repository.ScreenshotRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.repository.UsageLogRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.LocalDate
import java.time.Period
import java.util.UUID

/**
 * 数据主体权利服务（第四步合规 P0）。
 *
 * 覆盖《个人信息保护法》撤回同意 / 查阅复制 / 删除注销 / 申诉：
 * - 撤回授权：置位 `devices.consent_revoked`，服务端 [IngestService] 停止落库个人数据；
 *   同时**重建策略包并递增版本**，把撤回态下发到孩子端。策略包加密落盘，设备离线时
 *   下次拉包才生效，但不会因进程重启/网络断开而丢失——这是 PIPL「撤回同意」的本地闸门。
 * - 数据导出：账号档案、设备、用量/位置/截图明细（分页限流）、申诉与注销请求。
 * - 账号注销：冷静期 + 凭证复核，到期/确认后级联清除设备个人数据并匿名化账号（等同删除、规避外键）。
 * - 申诉：可提交、可查询状态。
 *
 * 全部动作写审计（含哈希防篡改），供合规追溯。
 */
@Service
class ComplianceService(
    private val deviceRepository: DeviceRepository,
    private val ownershipGuard: DeviceOwnershipGuard,
    private val auditService: AuditService,
    private val purgeService: DeviceDataPurgeService,
    private val appealRepository: AppealRepository,
    private val deletionRepository: AccountDeletionRepository,
    private val userRepository: UserRepository,
    @Value("\${padguard.compliance.deletion-cooldown-ms:172800000}")
    private val deletionCooldownMs: Long = 172_800_000L,
    /** 可选依赖：纯 JVM 单测直构时为 null；生产由容器注入。 */
    private val policyService: PolicyExtensionService? = null,
    private val usageLogRepository: UsageLogRepository? = null,
    private val locationTrackRepository: LocationTrackRepository? = null,
    private val screenshotRepository: ScreenshotRepository? = null,
    private val guardianConsentRepository: GuardianConsentRepository? = null,
    /**
     * 账号注销的集中式关联清理。生产由容器注入；纯 JVM 单测若不构造本依赖，
     * 注销路径会显式拒绝（fail-closed），绝不退化成"只匿名账号、残留隐私"的假注销。
     */
    private val erasureDao: ParentErasureDao? = null,
    /** 注销后主动踢 WS 长连接（HTTP 侧因 tokenVersion+1 自然失效）。 */
    private val webSocketPush: com.padguard.server.ws.WebSocketPushService? = null
) {
    private val log = LoggerFactory.getLogger(ComplianceService::class.java)

    /** 撤回授权：deviceIds 为空 = 全部设备。返回实际置位设备数。 */
    @Transactional
    fun withdrawConsent(userId: String, deviceIds: List<String>?): Int {
        var n = 0
        resolveDevices(userId, deviceIds).forEach { dev ->
            if (dev.consentRevoked != true) {
                dev.consentRevoked = true
                deviceRepository.save(dev)
                n++
                // 只改 devices 行时，孩子端拉到的仍是旧策略包，离线/在线都不会停采——
                // 注释承诺的「本地停采信号源」必须是真实下发的状态，而不是服务端一厢情愿。
                policyService?.rebuildPolicy(dev.id)
                auditService.record(userId, dev.id, AuditAction.CONSENT_WITHDRAW, "DEVICE", dev.id, "撤回授权，停止采集")
            }
        }
        return n
    }

    /** 恢复授权。 */
    @Transactional
    fun reinstateConsent(userId: String, deviceIds: List<String>?): Int {
        var n = 0
        resolveDevices(userId, deviceIds).forEach { dev ->
            if (dev.consentRevoked != false) {
                dev.consentRevoked = false
                deviceRepository.save(dev)
                n++
                // 恢复同意走同一条策略通道；否则设备上会永久停在"已撤回"的采集闸门。
                policyService?.rebuildPolicy(dev.id)
                auditService.record(userId, dev.id, AuditAction.CONSENT_REINSTATE, "DEVICE", dev.id, "恢复授权，恢复采集")
            }
        }
        return n
    }

    /**
     * 不满十四周岁监护人单独同意留痕。
     *
     * 年龄按监护人申报的出生日期与服务器时区当天计算，只接受 <14 周岁；
     * 这不是实名核验，前端必须明确告知家长"请如实填写"。同设备 + 同生日 + 同文案版本
     * 重复提交幂等，避免家长连点产生多份互相矛盾的凭证。
     */
    @Transactional
    fun submitUnder14GuardianConsent(
        userId: String,
        req: Under14GuardianConsentRequest
    ): GuardianConsentStatusDto {
        ownershipGuard.requireOwned(userId, req.deviceId)
        val birth = runCatching { LocalDate.parse(req.childBirthDate.trim()) }
            .getOrElse { throw BizException(ParentErr.PARAM_ERROR, "出生日期格式应为 yyyy-MM-dd", Audience.PARENT) }
        val today = LocalDate.now(SERVER_ZONE_ID)
        if (birth.isAfter(today)) {
            throw BizException(ParentErr.PARAM_ERROR, "出生日期不能晚于今天", Audience.PARENT)
        }
        val age = Period.between(birth, today).years
        if (age >= 14) {
            throw BizException(ParentErr.PARAM_ERROR, "孩子已满 14 周岁，无需监护人单独同意", Audience.PARENT)
        }
        if (!req.consent) {
            throw BizException(ParentErr.PARAM_ERROR, "必须勾选监护人单独同意才能提交", Audience.PARENT)
        }
        val version = req.consentTextVersion.trim().take(40)
        if (version.isBlank()) {
            throw BizException(ParentErr.PARAM_ERROR, "缺少同意文案版本，无法事后还原同意内容", Audience.PARENT)
        }
        val repo = guardianConsentRepository
            ?: throw IllegalStateException("guardianConsentRepository 未注入：单独同意无法留痕，禁止静默通过")
        repo.findByUserIdAndDeviceIdOrderByCreatedAtDesc(userId, req.deviceId)
            .firstOrNull { it.consent && it.childBirthDate == birth.toString() && it.consentTextVersion == version }
            ?.let { return it.toDto() }

        val record = GuardianConsent(
            id = "gc_" + UUID.randomUUID().toString().replace("-", ""),
            userId = userId,
            deviceId = req.deviceId,
            childBirthDate = birth.toString(),
            consent = true,
            consentTextVersion = version,
            createdAt = System.currentTimeMillis()
        )
        repo.save(record)
        auditService.record(
            userId, req.deviceId, AuditAction.GUARDIAN_CONSENT_UNDER14, "DEVICE", req.deviceId,
            "不满14周岁监护人单独同意（申报出生日期=${birth}，文案版本=$version）"
        )
        return record.toDto()
    }

    /** 家长端回显：是否已提交过有效单独同意。 */
    fun guardianConsentStatus(userId: String, deviceId: String): GuardianConsentStatusDto? {
        ownershipGuard.requireOwned(userId, deviceId)
        return guardianConsentRepository
            ?.findByUserIdAndDeviceIdOrderByCreatedAtDesc(userId, deviceId)
            ?.firstOrNull()
            ?.toDto()
    }

    private fun GuardianConsent.toDto() = GuardianConsentStatusDto(
        deviceId = deviceId, childBirthDate = childBirthDate, consented = consent,
        consentTextVersion = consentTextVersion, decidedAt = createdAt
    )

    private fun resolveDevices(userId: String, deviceIds: List<String>?): List<Device> {
        val owned = deviceRepository.findByUserId(userId)
        if (deviceIds.isNullOrEmpty()) return owned
        val ownedIds = owned.map { it.id }.toSet()
        val missing = deviceIds.filter { it !in ownedIds }
        if (missing.isNotEmpty()) {
            throw BizException(ParentErr.PARAM_ERROR, "设备不存在或不属于当前账号: $missing", Audience.PARENT)
        }
        return owned.filter { it.id in deviceIds }
    }

    /** 个人数据导出（结构化、可读）。 */
    fun exportData(userId: String): Map<String, Any?> {
        val user = userRepository.findById(userId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "账号不存在", Audience.PARENT)
        val devices = deviceRepository.findByUserId(userId)
        return mapOf(
            "exportedAt" to System.currentTimeMillis(),
            "account" to mapOf(
                "id" to user.id,
                "phone" to maskPhone(user.phone),
                "nickname" to user.nickname,
                "email" to user.email,
                "status" to user.status,
                "createdAt" to user.createdAt
            ),
            "devices" to devices.map { d ->
                mapOf(
                    "deviceId" to d.id,
                    "name" to d.name,
                    "childName" to d.childName,
                    "controlMode" to d.controlMode,
                    "consentRevoked" to (d.consentRevoked ?: false),
                    "policyVersion" to d.policyVersion,
                    "createdAt" to d.createdAt
                )
            },
            "appeals" to appealRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 100)).map { a ->
                mapOf("id" to a.id, "deviceId" to a.deviceId, "subject" to a.subject,
                    "status" to a.status, "createdAt" to a.createdAt)
            },
            "deletionRequests" to deletionRepository.findByUserId(userId).map { r ->
                mapOf("ticket" to r.ticket, "status" to (if (r.cancelled) "CANCELLED" else r.status),
                    "requestedAt" to r.requestedAt, "executeAt" to r.executeAt)
            },
            // 明细按设备分页限流：单台设备一个月约 26 万条用量日志，全量导出会把
            // 服务端内存和 HTTP 响应一起打爆。这里给每台设备最近 N 条，并明确告知截断上限。
            "detailLimitPerDevice" to EXPORT_DETAIL_LIMIT,
            "usageLogs" to devices.flatMap { d ->
                (usageLogRepository?.findByDeviceIdOrderByReceivedAtDesc(
                    d.id, PageRequest.of(0, EXPORT_DETAIL_LIMIT)
                ) ?: emptyList()).map { l ->
                    mapOf(
                        "id" to l.id, "deviceId" to l.deviceId, "logId" to l.logId,
                        "type" to l.type, "timestamp" to l.timestamp,
                        "receivedAt" to l.receivedAt, "payloadJson" to l.payloadJson
                    )
                }
            },
            "locationTracks" to devices.flatMap { d ->
                (locationTrackRepository?.findByDeviceIdOrderByTsDesc(
                    d.id, PageRequest.of(0, EXPORT_DETAIL_LIMIT)
                ) ?: emptyList()).map { t ->
                    mapOf(
                        "id" to t.id, "deviceId" to t.deviceId, "lat" to t.lat, "lng" to t.lng,
                        "accuracy" to t.accuracy, "provider" to t.provider, "ts" to t.ts,
                        "address" to t.address, "fresh" to t.fresh, "receivedAt" to t.receivedAt
                    )
                }
            },
            "screenshots" to devices.flatMap { d ->
                (screenshotRepository?.findByDeviceIdOrderByCapturedAtDesc(
                    d.id, PageRequest.of(0, EXPORT_DETAIL_LIMIT)
                ) ?: emptyList()).map { s ->
                    mapOf(
                        "id" to s.id, "deviceId" to s.deviceId, "shotId" to s.shotId,
                        "triggerType" to s.triggerType, "fileUrl" to s.fileUrl,
                        "thumbnailUrl" to s.thumbnailUrl, "width" to s.width, "height" to s.height,
                        "capturedAt" to s.capturedAt, "receivedAt" to s.receivedAt, "status" to s.status
                    )
                }
            }
        )
    }

    private fun maskPhone(phone: String?): String? {
        if (phone.isNullOrBlank() || phone.length < 7) return phone
        return phone.take(3) + "****" + phone.takeLast(4)
    }

    /** 提交账号注销请求：生成凭证 + 冷静期。幂等：已有 PENDING 未取消则返回原单。 */
    @Transactional
    fun requestAccountDeletion(userId: String, reason: String?): DeletionRequestCreatedDto {
        deletionRepository.findByUserId(userId).firstOrNull { it.status == "PENDING" && !it.cancelled }
            ?.let { return toDto(it) }
        val now = System.currentTimeMillis()
        val req = AccountDeletionRequest(
            id = "adr_" + UUID.randomUUID().toString().replace("-", ""),
            userId = userId,
            ticket = UUID.randomUUID().toString().replace("-", ""),
            reason = reason?.take(200),
            status = "PENDING",
            requestedAt = now,
            executeAt = now + deletionCooldownMs
        )
        deletionRepository.save(req)
        auditService.record(userId, null, AuditAction.CONSENT_ACCOUNT_DELETE_REQUEST, "ACCOUNT", userId, "提交账号注销请求")
        return toDto(req)
    }

    private fun toDto(r: AccountDeletionRequest) = DeletionRequestCreatedDto(
        ticket = r.ticket, requestedAt = r.requestedAt, executeAt = r.executeAt,
        cooldownMs = deletionCooldownMs, status = if (r.cancelled) "CANCELLED" else r.status
    )

    /**
     * 确认注销：冷静期闸门 → 凭证复核 → 全量关联清理 → 匿名化账号。
     *
     * 与管理员注销（[AdminParentService.eraseParent]）走**同一条** [ParentErasurePlan] 清理链，
     * 不再只匿名账号却留下 subscriptions / alerts / 工单 / 订单归属等个人数据（"假注销"）。
     * 设备历史清理在独立事务里，必须先于外层批量 DELETE 跑完，否则 PG 上会死锁。
     */
    @Transactional
    fun confirmAccountDeletion(userId: String, ticket: String) {
        val req = deletionRepository.findByUserIdAndTicket(userId, ticket)
            ?: throw BizException(ParentErr.PARAM_ERROR, "注销请求不存在", Audience.PARENT)
        if (req.cancelled) throw BizException(ParentErr.PARAM_ERROR, "注销请求已取消", Audience.PARENT)
        if (req.status != "PENDING") throw BizException(ParentErr.PARAM_ERROR, "注销请求状态异常", Audience.PARENT)

        // 冷静期是注销的核心防冲动闸门：ticket 提交即返回，但绝不意味着可以立刻执行。
        val now = System.currentTimeMillis()
        if (now < req.executeAt) {
            val hours = ((req.executeAt - now) / 3_600_000L).coerceAtLeast(1)
            throw BizException(
                ParentErr.PARAM_ERROR, "冷静期未到，请于约 ${hours} 小时后再次确认", Audience.PARENT
            )
        }
        val dao = erasureDao
            ?: throw BizException(ParentErr.PARAM_ERROR, "注销能力未就绪，请联系管理员", Audience.PARENT)

        // 顺序硬约束：purge 走 REQUIRES_NEW 独立事务，必须在 dao.erase 的批量 UPDATE/DELETE
        // 之前提交；反过来会出现"外层持锁等独立事务、独立事务等外层释放"的死锁。
        val deviceIds = dao.findDeviceIds(userId)
        var purgedRows = 0
        for (deviceId in deviceIds) {
            var done = false
            repeat(3) { attempt ->
                if (done) return@repeat
                try {
                    purgedRows += purgeService.purge(deviceId, "ACCOUNT_ERASE").values.sum()
                    done = true
                } catch (e: Exception) {
                    log.warn("注销清理设备 {} 历史数据失败（第 {} 次）：{}", deviceId, attempt + 1, e.message)
                }
            }
            // 三次全败必须让整个注销回滚：账号还在、凭据未作废，绝不返回"已注销"却残留隐私。
            if (!done) throw BizException(
                ParentErr.PARAM_ERROR,
                "注销失败：设备历史数据清理未完成，账号未删除，请重试", Audience.PARENT
            )
        }

        // 每个注销账号一个稳定匿名键：订单流水保留但反推不回原 userId。
        val anonKey = "erased_" + MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(16)
        val outcome = dao.erase(userId, anonKey, deviceIds)

        // dao.erase 已把 devices.user_id 置空并轮换旧凭据；这行在真实库上是空集合，
        // 保留仅为兼容既有测试桩（它们不理解 bulk update 对 Repository 查询的影响）。
        deviceRepository.deleteAll(deviceRepository.findByUserId(userId))

        userRepository.findById(userId).ifPresent { u ->
            u.phone = "deleted_" + UUID.randomUUID().toString().replace("-", "").take(16)
            u.nickname = null
            u.avatar = null
            u.email = null
            u.passwordHash = "erased_" + UUID.randomUUID().toString().replace("-", "")
            u.status = "deleted"
            u.tokenVersion = u.tokenVersion + 1 // 吊销全部已签发令牌
            userRepository.save(u)
        }
        runCatching { webSocketPush?.revokeUser(userId) }
            .onFailure { log.warn("注销后踢 WS 失败: {}", it.message) }
        req.status = "EXECUTED"
        deletionRepository.save(req)
        auditService.record(
            userId, null, AuditAction.CONSENT_ACCOUNT_DELETED, "ACCOUNT", userId,
            "账号注销完成：清除设备历史数据 ${purgedRows} 行、解绑 ${outcome.deviceIds.size} 台设备、账号已匿名化"
        )
    }

    /** 取消注销请求（冷静期内可反悔）。 */
    @Transactional
    fun cancelAccountDeletion(userId: String, ticket: String) {
        val req = deletionRepository.findByUserIdAndTicket(userId, ticket)
            ?: throw BizException(ParentErr.PARAM_ERROR, "注销请求不存在", Audience.PARENT)
        req.cancelled = true
        req.cancelledAt = System.currentTimeMillis()
        req.status = "CANCELLED"
        deletionRepository.save(req)
        auditService.record(userId, null, AuditAction.CONSENT_ACCOUNT_DELETE_REQUEST, "ACCOUNT", userId, "取消账号注销请求")
    }

    /** 提交申诉。deviceId 非空时先校验归属。 */
    fun submitAppeal(userId: String, deviceId: String?, subject: String, detail: String?, contact: String?): String {
        if (!deviceId.isNullOrBlank()) ownershipGuard.requireOwned(userId, deviceId)
        val now = System.currentTimeMillis()
        val appeal = Appeal(
            id = "ap_" + UUID.randomUUID().toString().replace("-", ""),
            userId = userId, deviceId = deviceId,
            subject = subject.take(60), detail = detail?.take(4000), contact = contact?.take(120),
            status = "OPEN", createdAt = now, updatedAt = now
        )
        appealRepository.save(appeal)
        auditService.record(userId, deviceId, AuditAction.APPEAL_SUBMIT, "APPEAL", appeal.id, subject)
        return appeal.id
    }

    fun listAppeals(userId: String): List<AppealDto> =
        appealRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 100)).map { a ->
            AppealDto(a.id, a.deviceId, a.subject, a.detail, a.contact, a.status, a.createdAt, a.updatedAt)
        }

    fun getAppeal(userId: String, id: String): AppealDto {
        val a = appealRepository.findByUserIdAndId(userId, id)
            ?: throw BizException(ParentErr.PARAM_ERROR, "申诉不存在", Audience.PARENT)
        return AppealDto(a.id, a.deviceId, a.subject, a.detail, a.contact, a.status, a.createdAt, a.updatedAt)
    }

    companion object {
        /** 导出明细每台设备上限；超出部分需走后台按设备/时间段另行导出。 */
        const val EXPORT_DETAIL_LIMIT: Int = 500
    }
}
