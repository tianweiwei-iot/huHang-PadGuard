package com.padguard.server.service

import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.AppDistributionRepository
import com.padguard.server.repository.AppPolicyRepository
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.repository.DeviceFeaturePolicyRepository
import com.padguard.server.repository.DevicePermissionPolicyRepository
import com.padguard.server.repository.DeviceSettingRepository
import com.padguard.server.repository.GeofenceZoneRepository
import com.padguard.server.repository.InstalledAppRepository
import com.padguard.server.repository.LocationTrackRepository
import com.padguard.server.repository.MediaTaskRepository
import com.padguard.server.repository.PublishedMessageRepository
import com.padguard.server.repository.ScreenshotRepository
import com.padguard.server.repository.TimeRestrictionRepository
import com.padguard.server.repository.UnlockTicketRepository
import com.padguard.server.repository.UploadedFileRepository
import com.padguard.server.repository.UsageLogRepository
import com.padguard.server.repository.UsageSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 设备私有数据清理（唯一入口）。
 *
 * ## 为什么必须有这个集中入口
 * 设备台账（Device）按硬件指纹复用，一台平板会在"孤儿回收"和"跨账号复用"两种情况下
 * 换到新家长名下。此前只有一条 `usage_logs` 的清理散落在 [com.padguard.server.service.DeviceService]
 * 里，其余私有数据（截图、轨迹、告警、指令、已装应用、围栏、设备设置、设备事件、使用时长、
 * 上传文件）全部原样留给下一任家长 —— 这是实打实的儿童隐私泄漏，而且解绑路径下**连 usage_logs
 * 都没清**，数据只增不减。
 *
 * 把清理收在一处，新增私有表时只需在这里补一行，不可能漏；两处调用点（跨账号复用、解绑）
 * 共用同一份口径。
 *
 * ## 口径
 * - 只删设备私有业务数据，**不动** `devices` 台账本身（硬件指纹要靠它复用）；
 * - **不动** `audit_logs`（审计留痕属于合规要求，管理员追责需要看到历史）；
 * - 整轮清理在同一 `@Transactional` 内**原子提交**：任意一张表删除失败即抛异常、整体回滚，
 *   由调用方（[com.padguard.server.service.DeviceService]）捕获后重试；
 *   早期版本用"逐表 runCatching 跳过"的设计在 PostgreSQL 下不成立——事务内任一语句失败会使
 *   整事务进入仅回滚状态，即使捕获异常也会在提交时整体失败，于是"漏清"被静默掩盖成"成功"，
 *   造成解绑隐私删除承诺实际不可信（假成功）。现在改为原子回滚 + 上层重试，杜绝该问题；
 * - 覆盖 `device_events` / `usage_sessions` / `uploaded_files` 等**全部**设备私有表，
 *   解绑 / 易主时隐私删除承诺才可信；
 * - 删除走各 Repository 上的 JPQL 批量 delete，不全表加载。
 */
@Service
class DeviceDataPurgeService(
    private val usageLogRepository: UsageLogRepository,
    private val screenshotRepository: ScreenshotRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val commandRepository: CommandRepository,
    private val deviceEventRepository: DeviceEventRepository,
    private val alertRepository: AlertRepository,
    private val locationTrackRepository: LocationTrackRepository,
    private val geofenceRepository: GeofenceZoneRepository,
    private val publishedMessageRepository: PublishedMessageRepository,
    private val mediaTaskRepository: MediaTaskRepository,
    private val unlockTicketRepository: UnlockTicketRepository,
    private val usageSessionRepository: UsageSessionRepository,
    private val uploadedFileRepository: UploadedFileRepository,
    private val deviceSettingRepository: DeviceSettingRepository,
    private val appDistributionRepository: AppDistributionRepository,
    private val appPolicyRepository: AppPolicyRepository,
    private val timeRestrictionRepository: TimeRestrictionRepository,
    private val devicePermissionPolicyRepository: DevicePermissionPolicyRepository,
    private val deviceFeaturePolicyRepository: DeviceFeaturePolicyRepository
) {

    private val log = LoggerFactory.getLogger(DeviceDataPurgeService::class.java)

    /**
     * 清除该设备的全部私有数据。
     *
     * @param deviceId 设备台账 id
     * @param reason   触发原因（写日志用），取值如 `TRANSFER`（易主）/ `UNBIND`（解绑）
     * @return 各表实际删除行数，key 为表名
     */
    @Transactional
    fun purge(deviceId: String, reason: String): Map<String, Int> {
        val removed = LinkedHashMap<String, Int>()
        // 顺序按"敏感度 + 体积"排：最敏感的先删，即使中途中断也不会留下截图/轨迹。
        removed += purgeOne("screenshots", deviceId) { screenshotRepository.deleteByDeviceId(it) }
        removed += purgeOne("location_tracks", deviceId) { locationTrackRepository.deleteByDeviceId(it) }
        removed += purgeOne("usage_logs", deviceId) { usageLogRepository.deleteByDeviceId(it) }
        removed += purgeOne("commands", deviceId) { commandRepository.deleteByDeviceId(it) }
        removed += purgeOne("device_events", deviceId) { deviceEventRepository.deleteByDeviceId(it) }
        removed += purgeOne("alerts", deviceId) { alertRepository.deleteByDeviceId(it) }
        removed += purgeOne("installed_apps", deviceId) { installedAppRepository.deleteByDeviceId(it) }
        removed += purgeOne("usage_sessions", deviceId) { usageSessionRepository.deleteByDeviceId(it) }
        removed += purgeOne("media_tasks", deviceId) { mediaTaskRepository.deleteByDeviceId(it) }
        removed += purgeOne("uploaded_files", deviceId) { uploadedFileRepository.deleteByDeviceId(it) }
        removed += purgeOne("published_messages", deviceId) { publishedMessageRepository.deleteByDeviceId(it) }
        removed += purgeOne("unlock_tickets", deviceId) { unlockTicketRepository.deleteByDeviceId(it) }
        removed += purgeOne("app_policies", deviceId) { appPolicyRepository.deleteByDeviceId(it) }
        removed += purgeOne("time_restrictions", deviceId) { timeRestrictionRepository.deleteByDeviceId(it) }
        removed += purgeOne("geofence_zones", deviceId) { geofenceRepository.deleteByDeviceId(it) }
        removed += purgeOne("device_settings", deviceId) { deviceSettingRepository.deleteByDeviceId(it) }
        removed += purgeOne("app_distributions", deviceId) { appDistributionRepository.deleteByDeviceId(it) }
        removed += purgeOne("device_permission_policy", deviceId) { devicePermissionPolicyRepository.deleteByDeviceId(it) }
        removed += purgeOne("device_feature_policy", deviceId) { deviceFeaturePolicyRepository.deleteByDeviceId(it) }

        val total = removed.values.sum()
        log.info("设备 {} 私有数据清理完成（原因={}）：共 {} 行，明细 {}", deviceId, reason, total, removed)
        return removed
    }

    /**
     * 删除单张设备私有表的数据。
     *
     * 不再用 `runCatching` 吞错：在 PostgreSQL 下，事务内任一语句失败会使整事务进入仅回滚状态，
     * 即使捕获异常也会在提交时整体失败。这里让异常直接抛出，使 [purge] 的 `@Transactional`
     * 真正原子回滚，由上层调用方捕获并安排重试，杜绝"漏清 = 假成功"的隐私残留。
     */
    private inline fun purgeOne(table: String, deviceId: String, block: (String) -> Int): Pair<String, Int> =
        table to block(deviceId)
}
