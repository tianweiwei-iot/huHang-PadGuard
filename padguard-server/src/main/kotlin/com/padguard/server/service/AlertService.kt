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
            if (status != null) {
                alertRepository.findByUserIdAndStatus(userId, status, pageable)
                    .filter { it.deviceId == deviceId }
            } else {
                alertRepository.findByUserId(userId, pageable).filter { it.deviceId == deviceId }
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
            "POLICY_APPLY_FAILED" to ("策略应用失败" to "部分管控策略未能生效")
        )
    }
}
