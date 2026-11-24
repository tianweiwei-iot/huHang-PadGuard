package com.padguard.server.service

import com.padguard.server.repository.AppDistributionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 应用分发台账的状态收敛（R-P2-12）。
 *
 * ## 背景
 * `AppDistribution` 记录下发时初始化为 `PENDING`，但过去**没有任何代码再写这个字段** ——
 * 家长端看到的"分发中"永远不闭环，分不清"设备在下载"、"装好了"还是"根本没收到"。
 *
 * ## 状态机（单向，终态不可回退）
 * ```
 * PENDING ──ack SUCCESS(已受理)──> DISPATCHED ──台账出现该包──> SUCCESS
 *    │                                  │
 *    └──────────ack FAILED/UNSUPPORTED/REJECTED──────> FAILED
 * ```
 *
 * ## 为什么"回执 SUCCESS"不能直接算 SUCCESS
 * 孩子端 `INSTALL_APP` 的回执语义是"**会话已提交**，下载与安装异步进行"
 * （见孩子端 `CommandExecutor.installApp` → `AckStatus.SUCCESS, "ACCEPTED"`）。
 * 把它当成"已安装"会让家长端显示一个假的成功：静默安装失败、APK 损坏、
 * 下载中断都仍然显示已完成。
 *
 * 真正的完成证据只有一条：孩子端**应用台账上报**里出现了该包（`syncInventory`）。
 * 那条通道同时覆盖了"回执丢了但应用确实装上了"的情况。
 *
 * ## 为什么不无限期等待台账
 * 分发是即时动作。一条一周前没装上的记录，等到家长后来自己装了同包名应用再"补成功"，
 * 是错误归因。因此只收敛 [CONVERGE_WINDOW_MS] 内的记录，超窗口的留在 FAILED/PENDING
 * 等家长重发（重发会新建一条记录，语义更清楚）。
 */
@Service
class AppDistributionConverger(
    private val appDistributionRepository: AppDistributionRepository
) {

    private val log = LoggerFactory.getLogger(AppDistributionConverger::class.java)

    /**
     * 指令回执到达时收敛（进度 + 失败可见）。
     *
     * @param ackStatus 孩子端回执白名单内的状态（SUCCESS/FAILED/UNSUPPORTED/DUPLICATED/REJECTED）
     */
    fun onCommandAck(
        msgId: String,
        deviceId: String,
        ackStatus: String,
        errorCode: String?,
        errorMessage: String?
    ) {
        if (msgId.isBlank()) return
        val records = appDistributionRepository.findByMsgIdAndDeviceId(msgId, deviceId)
        if (records.isEmpty()) return
        val now = System.currentTimeMillis()
        for (record in records) {
            if (record.status == STATUS_SUCCESS) continue // 快路径：已终态直接跳过
            val next = when (ackStatus) {
                // 已受理：下载/安装进行中，不是完成
                "SUCCESS" -> STATUS_DISPATCHED
                // 重复下发：设备账本里已有该指令的执行结论，按已完成处理
                "DUPLICATED" -> STATUS_SUCCESS
                // FAILED / UNSUPPORTED / REJECTED：设备侧明确无法完成
                else -> STATUS_FAILED
            }
            if (next == STATUS_DISPATCHED && record.status == STATUS_DISPATCHED) continue
            // 绝不改内存实体再 save 整行：「status == SUCCESS 才跳过」只是读判，
            // 挡不住另一路在本次查询之后提交的终态。状态由带 WHERE status <> 'SUCCESS'
            // 的条件 UPDATE 决定；影响 0 行说明别人已收敛，本轮放弃。
            val hasError = if (!errorCode.isNullOrBlank() || !errorMessage.isNullOrBlank()) 1 else 0
            val changed = appDistributionRepository.applyAckIfNotSuccess(
                id = record.id,
                status = next,
                errorCode = errorCode,
                errorMessage = errorMessage,
                hasError = hasError,
                finishedAt = if (next == STATUS_SUCCESS) now else null,
                success = STATUS_SUCCESS
            )
            if (changed > 0) {
                log.info(
                    "distribution {} -> {} (msgId={}, device={}, ack={})",
                    record.id, next, msgId, deviceId, ackStatus
                )
            }
        }
    }

    /**
     * 孩子端应用台账上报到达时收敛（权威完成证据）。
     *
     * @param installedPackages 本次上报里**确实已安装**的包名集合
     */
    fun onInventoryInstalled(deviceId: String, installedPackages: Collection<String>) {
        if (installedPackages.isEmpty()) return
        val openStatuses = listOf(STATUS_PENDING, STATUS_DISPATCHED, STATUS_FAILED)
        val open = appDistributionRepository.findByDeviceIdAndStatusIn(deviceId, openStatuses)
        if (open.isEmpty()) return
        val now = System.currentTimeMillis()
        val seen = installedPackages.toSet()
        var converged = 0
        for (record in open) {
            if (record.packageName !in seen) continue
            // 超窗口不补成功：陈旧记录不该被后来的手动安装"洗白"（见类注释）
            if (now - record.createdAt > CONVERGE_WINDOW_MS) continue
            // 同样不碰实体字段、不整行 save：查询期间另一路可能已把记录推到终态，
            // 条件 UPDATE 只接受仍处于开放态的行，避免旧快照覆盖新状态。
            if (appDistributionRepository.markSuccessIfOpen(
                    id = record.id,
                    finishedAt = now,
                    success = STATUS_SUCCESS,
                    openStatuses = openStatuses
                ) > 0
            ) converged++
        }
        if (converged > 0) {
            log.info("distribution converged to SUCCESS: device={} count={}", deviceId, converged)
        }
    }

    companion object {
        /** 已下发，等待设备回执 */
        const val STATUS_PENDING = "PENDING"
        /** 设备已受理（下载/安装进行中） */
        const val STATUS_DISPATCHED = "DISPATCHED"
        /** 台账已出现该包 = 安装完成 */
        const val STATUS_SUCCESS = "SUCCESS"
        /** 设备明确失败/不支持/拒绝 */
        const val STATUS_FAILED = "FAILED"

        /** 台账收敛窗口：超过该时长的旧分发不再由后续上报补成 SUCCESS */
        const val CONVERGE_WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}
