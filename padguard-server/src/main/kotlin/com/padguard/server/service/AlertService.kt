package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Alert
import com.padguard.server.dto.AlertDto
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.ws.WebSocketPushService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AlertService(
    private val alertRepository: AlertRepository,
    private val deviceRepository: DeviceRepository,
    private val webSocketPush: WebSocketPushService,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(AlertService::class.java)

    /** 孩子端上报事件后建告警（事件级 HIGH/NORMAL/INFO -> 告警 CRITICAL/WARNING/INFO） */
    fun createFromEvent(
        deviceId: String, eventType: String, eventLevel: String, detail: Map<String, Any?>?
    ): Alert? {
        val level = when (eventLevel) {
            "HIGH" -> "CRITICAL"
            "NORMAL" -> "WARNING"
            else -> "INFO"
        }
        val now = System.currentTimeMillis()
        val mapping = EVENT_TITLES[eventType] ?: (eventType to "设备事件")
        val dev = deviceRepository.findById(deviceId).orElse(null) ?: return null
        val alert = alertRepository.save(
            Alert(
                id = UUID.randomUUID().toString(), deviceId = deviceId, userId = dev.userId,
                level = level, title = mapping.first, message = mapping.second,
                category = eventType, status = "ACTIVE",
                detailJson = detail?.let { objectMapper.writeValueAsString(it) },
                triggeredAt = now
            )
        )
        dev.userId?.let { uid ->
            webSocketPush.pushToUser(uid, mapOf(
                "type" to "alert.new", "alert" to toDto(alert)
            ))
        }
        return alert
    }

    /** 服务端主动建告警（如围栏越界） */
    fun create(
        deviceId: String, level: String, title: String, message: String, category: String
    ): Alert? {
        val dev = deviceRepository.findById(deviceId).orElse(null) ?: return null
        val now = System.currentTimeMillis()
        val alert = alertRepository.save(
            Alert(
                id = UUID.randomUUID().toString(), deviceId = deviceId, userId = dev.userId,
                level = level, title = title, message = message, category = category,
                status = "ACTIVE", triggeredAt = now
            )
        )
        dev.userId?.let { uid ->
            webSocketPush.pushToUser(uid, mapOf("type" to "alert.new", "alert" to toDto(alert)))
        }
        return alert
    }

    fun list(userId: String, deviceId: String?, status: String?, page: Int, size: Int): List<AlertDto> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceAtLeast(1).coerceAtMost(100))
        val alerts = if (deviceId != null) {
            // 过滤下沉到 SQL：原先"先按 user 分页、再在内存里 filter deviceId"，
            // 结果是**分页后过滤**——本页里属于该设备的条目可能只剩两三条，
            // 家长端看到"明明有告警却只显示几条"，且翻页还会漏数据。
            if (status != null) {
                alertRepository.findByUserIdAndDeviceIdAndStatus(userId, deviceId, status, pageable)
            } else {
                alertRepository.findByUserIdAndDeviceId(userId, deviceId, pageable)
            }
        } else {
            if (status != null) alertRepository.findByUserIdAndStatus(userId, status, pageable)
            else alertRepository.findByUserId(userId, pageable)
        }
        return alerts.map { toDto(it) }
    }

    fun unreadCount(userId: String): Long = alertRepository.countByUserIdAndStatus(userId, "ACTIVE")

    fun acknowledge(userId: String, alertId: String) {
        val alert = requireOwned(userId, alertId)
        alert.status = "ACKNOWLEDGED"
        alert.acknowledgedAt = System.currentTimeMillis()
        alertRepository.save(alert)
    }

    fun close(userId: String, alertId: String, reason: String?) {
        val alert = requireOwned(userId, alertId)
        alert.status = "CLOSED"
        alert.resolvedAt = System.currentTimeMillis()
        alert.message = if (reason != null) "${alert.message ?: ""} [处理: $reason]" else alert.message
        alertRepository.save(alert)
    }

    /**
     * 孩子端自愈成功后关闭某类别下全部 ACTIVE 告警（恢复即关闭闭环）。
     *
     * ## 为何需要
     * 此前告警只能由家长手动关闭。DO 形态设备静默重授予权限（无障碍 / 运行时危险权限）后，
     * 服务端 ACTIVE 告警仍长期挂起，家长端持续提示"权限被撤销 / 无障碍已关闭"，
     * 形成"已修复却仍报警"的体验。孩子端在自愈成功时调用本方法，使告警随恢复自动消失。
     *
     * ## 设计取舍
     * - 复用既有 `findByDeviceIdOrderByTriggeredAtDesc` + `saveAll` 完成批量关闭，**不向
     *   `AlertRepository` 接口新增抽象方法**，避免波及所有测试假仓储与历史实现；
     *   单设备同类别 ACTIVE 告警数量极小，内存过滤 + 批量保存的代价可忽略；
     * - 仅关闭 ACTIVE：已 ACK/CLOSED（家长已处置）的告警保留，不越权改动；
     * - deviceId 由鉴权拦截器注入、不可被报文伪造，按它隔离即可精准定位归属设备；
     * - 关闭即向归属家长推 WS `alert.resolved`：家长端红点 / 未读随自愈即时回落，形成完整闭环。
     */
    fun resolveActiveByCategory(deviceId: String, category: String, reason: String? = "已自动恢复") {
        require(category.isNotBlank()) { "category 不可为空" }
        val targets = alertRepository.findByDeviceIdOrderByTriggeredAtDesc(deviceId)
            .filter { it.category == category && it.status == "ACTIVE" }
        if (targets.isEmpty()) return
        val now = System.currentTimeMillis()
        targets.forEach {
            it.status = "CLOSED"
            it.resolvedAt = now
        }
        alertRepository.saveAll(targets)
        deviceRepository.findById(deviceId).orElse(null)?.userId?.let { uid ->
            webSocketPush.pushToUser(uid, mapOf(
                "type" to "alert.resolved",
                "deviceId" to deviceId,
                "category" to category,
                "count" to targets.size
            ))
        }
        log.info("resolved {} ACTIVE alert(s) category={} device={} reason={}", targets.size, category, deviceId, reason)
    }

    private fun requireOwned(userId: String, alertId: String): Alert {
        val alert = alertRepository.findById(alertId).orElse(null)
            ?: throw BizException(ParentErr.PARAM_ERROR, "告警不存在", Audience.PARENT)
        if (alert.userId != userId) {
            throw BizException(ParentErr.FORBIDDEN, "告警不属于当前用户", Audience.PARENT)
        }
        return alert
    }

    private fun toDto(a: Alert) = AlertDto(
        id = a.id, deviceId = a.deviceId,
        deviceName = deviceRepository.findById(a.deviceId).orElse(null)?.name ?: a.deviceId,
        level = a.level, title = a.title, message = a.message, status = a.status,
        category = a.category, triggeredAt = a.triggeredAt,
        acknowledgedAt = a.acknowledgedAt, resolvedAt = a.resolvedAt
    )

    companion object {
        private val EVENT_TITLES = mapOf(
            "UNINSTALL_ATTEMPT" to ("尝试卸载管控应用" to "孩子尝试卸载护航成长应用"),
            "FORCE_STOP_ATTEMPT" to ("尝试强制停止" to "孩子尝试强制停止管控应用"),
            "CLEAR_DATA_ATTEMPT" to ("尝试清除数据" to "孩子尝试清除管控应用数据"),
            "CLOCK_TAMPERING" to ("时间被篡改" to "检测到系统时间被手动修改"),
            "ROOT_DETECTED" to ("检测到 Root" to "设备已 Root，存在安全风险"),
            "DEVELOPER_OPTIONS_ENABLED" to ("开发者选项开启" to "开发者选项已开启"),
            "USB_DEBUG_ENABLED" to ("USB 调试开启" to "USB 调试已开启"),
            "BLACKLIST_APP_LAUNCH" to ("打开黑名单应用" to "孩子打开了被禁止的应用"),
            "BLOCKED_URL_ACCESS" to ("访问被拦截网址" to "尝试访问被屏蔽的网址"),
            "TIME_LIMIT_EXCEEDED" to ("使用时长超限" to "今日使用时长已超过限制"),
            "PERMISSION_REVOKED" to ("权限被撤销" to "管控所需权限被撤销"),
            "POLICY_APPLY_FAILED" to ("策略应用失败" to "部分管控策略未能生效"),
            "ACCESSIBILITY_DISABLED" to ("无障碍服务被关闭" to "操作闸门的系统级拦截已关闭，孩子可在设置中绕过密码闸门，请引导孩子重新开启"),
            "USAGE_ACCESS_LOST" to ("使用情况权限被关闭" to "使用情况访问权限被关闭，应用管控与时长统计将失效"),
            "GUARD_PASSWORD_NOT_SET" to ("操作密码尚未设置" to "管控设备未设置操作密码，设置页无法验证家长身份，请尽快补设")
        )
    }
}
