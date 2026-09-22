package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.ScreenshotRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 过期指令回收：把已超时却仍未被终端取走执行的指令标记为 EXPIRED，
 * 并联动把关联的截图记录收敛到终态。
 *
 * 背景：指令带 5 分钟有效期，但若终端一直未取走（例如终端停留在 MQTT 模式、App 未运行、
 * 或 Bind 后从未轮询），指令会连同其关联记录（如 REMOTE 截图的 PENDING 记录）
 * 永久停留在中间态，家长端看到的是“永远转圈”的僵尸请求。这里让状态有确定终点。
 */
@Component
class ExpiredCommandSweeper(
    private val commandRepository: CommandRepository,
    private val screenshotRepository: ScreenshotRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(ExpiredCommandSweeper::class.java)

    /**
     * 回收指定设备下所有已过期的待执行指令。
     * @param deviceId 设备 ID
     * @param now 基准时间（默认服务端当前时间；注入便于测试与批处理）
     */
    fun sweep(deviceId: String, now: Long = System.currentTimeMillis()) {
        val expired = commandRepository.findByDeviceIdOrderByCreatedAtAsc(deviceId)
            .filter { it.status == STATUS_PENDING && it.expiresAt <= now }
        if (expired.isEmpty()) return

        expired.forEach { cmd ->
            cmd.status = STATUS_EXPIRED
            convergeRelatedRecords(deviceId, cmd.type, cmd.payloadJson)
        }
        commandRepository.saveAll(expired)
        log.info("回收过期指令 {} 条: deviceId={}, msgIds={}", expired.size, deviceId, expired.map { it.msgId })
    }

    /** SCREENSHOT 指令会连带生成一条 PENDING 截图记录，需一并收敛，否则永久悬挂 */
    private fun convergeRelatedRecords(deviceId: String, type: String, payloadJson: String?) {
        if (type != TYPE_SCREENSHOT) return
        val shotId = readPayload(payloadJson)[KEY_SHOT_ID]
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return
        val shot = screenshotRepository.findFirstByDeviceIdAndShotId(deviceId, shotId) ?: return
        if (shot.status == STATUS_PENDING) {
            shot.status = STATUS_EXPIRED
            screenshotRepository.save(shot)
        }
    }

    private fun readPayload(payloadJson: String?): Map<*, *> =
        payloadJson?.let { runCatching { objectMapper.readValue(it, Map::class.java) }.getOrNull() }
            ?: emptyMap<String, Any?>()

    private companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_EXPIRED = "EXPIRED"
        const val TYPE_SCREENSHOT = "SCREENSHOT"
        const val KEY_SHOT_ID = "shotId"
    }
}
