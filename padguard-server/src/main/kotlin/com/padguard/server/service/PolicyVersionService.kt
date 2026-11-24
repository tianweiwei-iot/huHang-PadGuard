package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Policy
import com.padguard.server.dto.*
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PolicyRepository
import com.padguard.server.repository.PolicyVersionRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 策略行的来源。`NORMAL` = 由子表重建的常规策略；`ROLLBACK` = 版本回滚产物。
 *
 * 存在意义：孩子端每次拉策略前，`PolicyExtensionService.ensureRebuilt` 会用
 * 「子表 → 策略包」重建一遍并覆盖任何漂移。回滚写入的是**历史快照**，天然与当前子表不一致，
 * 若不标记并跳过自愈，设备下一次 `GET /api/v1/device/policy` 就会把刚回滚的包冲回当前配置，
 * 家长端却显示「回滚成功」—— 一条不留错误、只在投诉里出现的假功能。
 *
 * 退出回滚态不需要额外开关：家长任何一次主动改设置都会走 `rebuildPolicy` 写一条 NORMAL 行，
 * 版本号更高，回滚态自然结束。
 */
internal object PolicySource {
    const val NORMAL = "NORMAL"
    const val ROLLBACK = "ROLLBACK"
}

/** 版本历史查询上限；策略表会持续增长，列表接口不允许无界拉取 */
internal const val MAX_POLICY_VERSIONS = 100

/** 单次批量操作的设备数上限：防止一次请求扇出上千台打爆行锁与 MQTT */
internal const val MAX_BATCH_DEVICES = 200

/**
 * 批量指令白名单。
 *
 * 只放行「无额外前置条件、可安全重试」的指令。刻意排除 `SET_MANAGEMENT_ACTIVE`
 * （一键交还/收回整机管控）、`REVEAL_MANAGEMENT_UI`（揭示管理控制台）、
 * `SET_RUNTIME_PERMISSION` / `SET_FEATURE_POLICY`（需要逐设备不同的 payload）。
 * 未来 `CommandType` 新增类型时**不会自动进入批量通道**，必须显式评审后加入。
 */
internal val BATCHABLE_COMMAND_TYPES: Set<String> = setOf(
    CommandType.REFRESH_POLICY, CommandType.FLUSH_LOGS, CommandType.SHOW_MESSAGE,
    CommandType.LOCK_SCREEN, CommandType.UNLOCK, CommandType.LOCATE,
    CommandType.SCREENSHOT, CommandType.SCREEN_RECORD, CommandType.STOP_SCREEN_RECORD
)

/**
 * 第二步 · 策略版本历史 / 回滚 / 定时生效 / 批量下发。
 *
 * 实现约束（逐条对应代码，勿凭直觉改）：
 * 1. 孩子端 `PolicyPackage.mergeWith` 只接受 `incoming.version > version`，因此回滚
 *    **绝不能重发旧版本号**，必须取历史包内容 + 分配更高的新版本号。
 * 2. 版本号一律走 [PolicyVersions.allocate]（持 devices 行锁），不得本地 `+1`。
 * 3. 回滚行标记 [PolicySource.ROLLBACK]，由 `ensureRebuilt` 跳过子表自愈。
 * 4. MQTT 通知放事务提交之后（[afterCommit]），Broker 抖动不能让回滚整体失败。
 */
@Service
class PolicyVersionService(
    private val policyRepository: PolicyRepository,
    private val policyVersionRepository: PolicyVersionRepository,
    private val deviceRepository: DeviceRepository,
    private val ownershipGuard: DeviceOwnershipGuard,
    private val rebuilder: PolicyRebuilder,
    private val commandIssuer: CommandIssuer,
    private val auditor: PolicyAuditor,
    private val notifier: PolicyNotifier,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(PolicyVersionService::class.java)

    /** 版本历史（倒序、分页、不含包体）。 */
    fun listVersions(userId: String, deviceId: String, limit: Int): PolicyVersionList {
        ownershipGuard.requireOwned(userId, deviceId)
        val size = limit.coerceIn(1, MAX_POLICY_VERSIONS)
        val rows = policyVersionRepository.listVersions(deviceId, PageRequest.of(0, size))
        val currentVersion = rows.firstOrNull()?.version
            ?: policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)?.version ?: 0
        val current = rows.firstOrNull()
        return PolicyVersionList(
            deviceId = deviceId,
            currentVersion = currentVersion,
            rollbackActive = current?.source == PolicySource.ROLLBACK,
            versions = rows.map {
                PolicyVersionItem(
                    version = it.version,
                    updatedAt = it.updatedAt,
                    source = it.source,
                    effectiveFrom = it.effectiveFrom,
                    effectiveTo = it.effectiveTo,
                    current = it.version == currentVersion,
                    rollbackOf = it.rollbackOf
                )
            }
        )
    }

    /**
     * 回滚到指定历史版本。
     *
     * @return 新版本号（一定大于当前版本）。重复回滚到同一目标且窗口未变时幂等返回当前版本。
     */
    @Transactional
    fun rollback(userId: String, deviceId: String, req: RollbackPolicyRequest): RollbackResult {
        ownershipGuard.requireOwned(userId, deviceId)
        if (req.targetVersion <= 0) {
            throw BizException(ParentErr.PARAM_ERROR, "targetVersion 必须为正整数", Audience.PARENT)
        }
        val (from, to) = normalizeWindow(req.effectiveFrom, req.effectiveTo)

        val current = policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)
            ?: throw BizException(ParentErr.POLICY_NOT_FOUND, "该设备尚无策略版本", Audience.PARENT)

        // 幂等：同一目标 + 同一窗口重复点击不再插入新版本，避免刷版本号把设备端对账打乱
        if (current.source == PolicySource.ROLLBACK &&
            current.rollbackOf == req.targetVersion &&
            current.effectiveFrom == from && current.effectiveTo == to
        ) {
            return RollbackResult(
                deviceId = deviceId, version = current.version, rolledBackTo = req.targetVersion,
                effectiveFrom = from, effectiveTo = to, idempotent = true
            )
        }

        val target = policyVersionRepository.findVersion(deviceId, req.targetVersion)
            ?: throw BizException(ParentErr.POLICY_NOT_FOUND, "目标策略版本不存在", Audience.PARENT)

        @Suppress("UNCHECKED_CAST")
        val pkg: MutableMap<String, Any?> = target.packageJson?.let {
            runCatching { objectMapper.readValue(it, Map::class.java) as MutableMap<String, Any?> }
                .getOrElse {
                    log.warn("rollback target package unreadable device={} v={}", deviceId, req.targetVersion)
                    throw BizException(ParentErr.POLICY_CONFLICT, "目标版本策略内容损坏，无法回滚", Audience.PARENT)
                }
        } ?: mutableMapOf()

        val newVersion = PolicyVersions.allocate(deviceId, deviceRepository, policyRepository, log)
        // 版本号与 deviceId 必须用新值覆盖：孩子端按包内 version 做本地镜像对账
        pkg["version"] = newVersion
        pkg["deviceId"] = deviceId
        if (from != null) pkg["effectiveFrom"] = from else pkg.remove("effectiveFrom")
        if (to != null) pkg["effectiveTo"] = to else pkg.remove("effectiveTo")

        policyRepository.save(
            Policy(
                id = UUID.randomUUID().toString(), deviceId = deviceId, version = newVersion,
                packageJson = objectMapper.writeValueAsString(pkg), scene = target.scene,
                effectiveFrom = from, effectiveTo = to,
                source = PolicySource.ROLLBACK, rollbackOf = req.targetVersion,
                updatedAt = System.currentTimeMillis()
            )
        )
        auditor.record(
            userId, deviceId, AuditAction.POLICY_ROLLBACK,
            targetType = "policy", targetId = req.targetVersion.toString(),
            detail = "from=v${current.version} to=v$newVersion window=${from ?: 0}..${to ?: 0}"
        )
        afterCommit {
            runCatching { notifier.notifyPolicyChanged(deviceId, newVersion) }
                .onFailure { log.warn("rollback policy notify failed device={} v={}: {}", deviceId, newVersion, it.message) }
        }
        return RollbackResult(
            deviceId = deviceId, version = newVersion, rolledBackTo = req.targetVersion,
            effectiveFrom = from, effectiveTo = to
        )
    }

    /**
     * 单独设置 / 清除定时生效窗口（不改策略内容）。
     *
     * 窗口写在最新策略行上，再由 `rebuildPolicy` 生成新版本继承；
     * `buildPolicyPackage` 会把它注入下发包的 `effectiveFrom/effectiveTo`，
     * 因此后续任何一次子表重建都不会把窗口冲掉。孩子端 `PolicyEngine.resolveEffectivePolicy`
     * 是唯一的执行判定点，服务端只负责把窗口可靠地送到设备上。
     */
    @Transactional
    fun setSchedule(userId: String, deviceId: String, req: PolicyScheduleRequest): Int {
        ownershipGuard.requireOwned(userId, deviceId)
        val (from, to) = normalizeWindow(req.effectiveFrom, req.effectiveTo)
        val latest = policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)
            ?: throw BizException(ParentErr.POLICY_NOT_FOUND, "该设备尚无策略版本", Audience.PARENT)
        latest.effectiveFrom = from
        latest.effectiveTo = to
        policyRepository.save(latest)
        // rebuildPolicy 会分配新版本、继承上面的窗口、写 NORMAL 行（同时退出回滚态）
        rebuilder.rebuild(deviceId)
        val newVersion = policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)?.version ?: latest.version
        auditor.record(
            userId, deviceId, AuditAction.POLICY_SCHEDULE_UPDATE,
            targetType = "policy", targetId = newVersion.toString(),
            detail = "effectiveFrom=${from ?: 0} effectiveTo=${to ?: 0}"
        )
        return newVersion
    }

    /**
     * 批量重新发布当前策略：逐设备独立处理，单台失败不影响其余设备。
     *
     * 走 `rebuildPolicy` 而不是「把当前包原样再存一遍」：前者以子表为唯一事实源，
     * 产出的包与 `ensureRebuilt` 的期望值一致，设备拉取时不会被自愈覆盖，
     * 同时天然修复了设备端策略镜像漂移。
     */
    fun batchPublish(userId: String, req: BatchPublishRequest): BatchResult {
        val ids = normalizeDeviceIds(req.deviceIds)
        val results = ids.map { deviceId ->
            runCatching {
                ownershipGuard.requireOwned(userId, deviceId)
                rebuilder.rebuild(deviceId)
                val version = policyRepository.findFirstByDeviceIdOrderByVersionDesc(deviceId)?.version
                BatchDeviceResult(deviceId = deviceId, ok = true, version = version)
            }.getOrElse { failureResult(deviceId, it) }
        }
        val ok = results.count { it.ok }
        auditor.record(
            userId, null, AuditAction.POLICY_BATCH_PUBLISH,
            targetType = "device", targetId = ids.size.toString(),
            detail = "succeeded=$ok failed=${ids.size - ok}"
        )
        return BatchResult(ids.size, ok, ids.size - ok, results)
    }

    /** 批量下发管控指令：白名单类型 + 逐设备归属校验 + 逐设备独立结果 */
    fun batchCommand(userId: String, req: BatchCommandRequest): BatchResult {
        val type = req.type.trim().uppercase()
        if (type !in BATCHABLE_COMMAND_TYPES) {
            throw BizException(ParentErr.PARAM_ERROR, "该指令类型不支持批量下发：$type", Audience.PARENT)
        }
        val ids = normalizeDeviceIds(req.deviceIds)
        val results = ids.map { deviceId ->
            runCatching {
                ownershipGuard.requireOwned(userId, deviceId)
                val cmd = commandIssuer.issue(deviceId, type, req.payload, "HIGH")
                BatchDeviceResult(deviceId = deviceId, ok = true, msgId = cmd.msgId)
            }.getOrElse { failureResult(deviceId, it) }
        }
        val ok = results.count { it.ok }
        auditor.record(
            userId, null, AuditAction.COMMAND_BATCH_ISSUE,
            targetType = "command", targetId = type,
            detail = "devices=${ids.size} succeeded=$ok failed=${ids.size - ok}"
        )
        return BatchResult(ids.size, ok, ids.size - ok, results)
    }

    /** @return (from, to)；null/<=0 视为不设置，且 to 必须晚于 from */
    private fun normalizeWindow(fromRaw: Long?, toRaw: Long?): Pair<Long?, Long?> {
        val from = fromRaw?.takeIf { it > 0 }
        val to = toRaw?.takeIf { it > 0 }
        if (from != null && to != null && to <= from) {
            throw BizException(ParentErr.PARAM_ERROR, "生效结束时间必须晚于开始时间", Audience.PARENT)
        }
        return from to to
    }

    private fun normalizeDeviceIds(raw: List<String>): List<String> {
        val ids = raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) throw BizException(ParentErr.PARAM_ERROR, "deviceIds 不能为空", Audience.PARENT)
        if (ids.size > MAX_BATCH_DEVICES) {
            throw BizException(ParentErr.PARAM_ERROR, "单次批量最多 $MAX_BATCH_DEVICES 台设备", Audience.PARENT)
        }
        return ids
    }

    /** 失败项统一文案，不把堆栈、SQL 或他人设备的存在性暴露给调用方 */
    private fun failureResult(deviceId: String, e: Throwable): BatchDeviceResult {
        val msg = when (e) {
            is BizException -> e.message
            else -> {
                log.warn("batch op failed device={}: {}", deviceId, e.message)
                "操作失败，请稍后重试"
            }
        }
        return BatchDeviceResult(deviceId = deviceId, ok = false, message = msg)
    }
}
