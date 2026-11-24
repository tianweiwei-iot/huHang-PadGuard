package com.padguard.server.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.UsageLog
import com.padguard.server.dto.AgreementStatusDto
import com.padguard.server.repository.UsageLogRepository
import org.springframework.stereotype.Service

/**
 * 使用授权协议签署凭证查询（说明书 §5.4）。
 *
 * ## 背景
 * 孩子端点「同意并授权」后，`AgreementRepository` 会做两件事：本地 Room 合规表留痕，
 * 以及往行为日志队列追加一条 `LogType.AGREEMENT`（脱敏 SN 后 4 位 + 协议版本 + 签署时刻），
 * 随批量上行落到服务端 `usage_logs`。说明书明确要求「供管控端可查『已阅已同意』」——
 * 但此前服务端**没有任何读取方**，家长端也就无从查询；更糟的是这条凭证混在用量日志里，
 * 会被 [UsageLogCleanup] 按 30 天 TTL 一并删掉。
 *
 * 本服务把凭证从行为日志里还原出来（不新增表、不动 schema），配合清理器的
 * [UsageLogCleanup.PROTECTED_TYPES] 形成完整闭环：留得住、查得到。
 *
 * ## 为什么解析要 fail-closed
 * 合规凭证的结论只有「查到了」和「没查到」，**没有"大概签了"**。
 * 缺版本、缺时间、payload 损坏时一律返回 signed=false；宁可让家长看到
 * "暂未查询到签署记录"（措辞本身已说明不等于未同意），也不能把脏数据当凭证。
 */
@Service
class AgreementService(
    private val deviceService: DeviceService,
    private val usageLogRepository: UsageLogRepository,
    private val objectMapper: ObjectMapper
) {
    /** 家长端查询：先校验设备归属，再取最近一条签署凭证。 */
    fun status(userId: String, deviceId: String): AgreementStatusDto {
        // getDevice 内含归属校验：越权查别人设备的协议记录会抛 DEVICE_NOT_OWNED。
        // 协议凭证带脱敏 SN，属于设备隐私，不能直接按 deviceId 裸查。
        deviceService.getDevice(userId, deviceId)
        val log = usageLogRepository.findFirstByDeviceIdAndTypeOrderByTimestampDesc(deviceId, TYPE)
            ?: return AgreementStatusDto(signed = false)
        return parse(log, objectMapper)
    }

    companion object {
        /** 与孩子端 `LogType.AGREEMENT` 枚举名逐字符一致。 */
        const val TYPE = "AGREEMENT"
        private val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}

        /**
         * 从一条行为日志还原签署凭证（纯函数，便于 JVM 单测）。
         * 任何异常/缺字段都退化为「未查到」，绝不抛异常打断家长端设备详情页。
         */
        @JvmStatic
        fun parse(log: UsageLog, mapper: ObjectMapper): AgreementStatusDto {
            val payload = runCatching {
                mapper.readValue(log.payloadJson ?: "{}", MAP_TYPE)
            }.getOrDefault(emptyMap())

            val version = payload["version"]?.toString()?.takeIf { it.isNotBlank() }
            // 优先用孩子端写的签署时刻；缺失/类型错时回退日志自身的事件时间戳。
            val signedAt = payload["signedAt"]?.toString()?.toLongOrNull() ?: log.timestamp
            val snMask = payload["snMask"]?.toString()?.takeIf { it.isNotBlank() }

            return if (version == null || signedAt == null || signedAt <= 0) {
                AgreementStatusDto(signed = false)
            } else {
                AgreementStatusDto(signed = true, version = version, signedAt = signedAt, snMask = snMask)
            }
        }
    }
}
