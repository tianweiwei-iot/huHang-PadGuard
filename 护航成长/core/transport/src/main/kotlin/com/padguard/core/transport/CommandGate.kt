package com.padguard.core.transport

import com.padguard.core.common.Crypto
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.AckStatus
import com.padguard.core.data.model.Command
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.repository.CommandRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.transport.http.CredentialStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程指令安全门卫（契约 §4.1 的四条校验规则）。
 *
 * 三道关按"代价从低到高"排序，任一不过即拒绝执行：
 *
 * | 顺序 | 关卡 | 防的攻击 | 回执 |
 * |---|---|---|---|
 * | 1 | expiresAt 时效 | 抓包后延迟重放（比如录下"解锁"指令晚上再放） | REJECTED |
 * | 2 | HMAC-SHA256 签名 | 伪造指令（自建 Broker 或中间人下发"解除管控"） | REJECTED |
 * | 3 | msgId 幂等窗口 | 同一指令重复投递（QoS1 天然可能重复） | DUPLICATED |
 *
 * 关键取舍：**签名校验失败不静默丢弃，而是上报 INVALID_SIGNATURE 高危告警**。
 * 因为签名失败要么是有人在攻击，要么是服务端签名实现与终端不一致 —— 两者都必须被看到。
 * 静默丢弃会让"指令全部失效"这种严重故障在管控端看起来只是"设备不响应"。
 */
@Singleton
class CommandGate @Inject constructor(
    private val credentials: CredentialStore,
    private val timeProvider: TimeProvider,
    private val commandRepository: CommandRepository,
    private val logRepository: LogRepository,
    private val settings: TransportSettings
) {

    sealed interface Verdict {
        /** 通过全部校验，可以执行 */
        data class Accepted(val command: Command) : Verdict

        /** 拒绝执行，[ackStatus] 仍需回执给服务端 */
        data class Rejected(val command: Command, val ackStatus: AckStatus, val reason: String) : Verdict
    }

    suspend fun evaluate(command: Command): Verdict {
        // ---------- 关卡 1：时效 ----------
        // 用校准后的服务端时间，否则学生把系统时间改到未来即可让所有指令"过期"
        val now = timeProvider.now()
        if (command.expiresAt > 0 && command.expiresAt < now) {
            val reason = "expired at ${command.expiresAt}, now=$now"
            Logger.w(TAG) { "reject ${command.msgId}: $reason" }
            return Verdict.Rejected(command, AckStatus.REJECTED, reason)
        }

        // 未来时间戳超出合理范围也拒绝：正常服务端不会下发 24 小时后才生效的指令
        if (command.timestamp > 0 && command.timestamp - now > MAX_FUTURE_SKEW_MS) {
            val reason = "timestamp too far in future: ${command.timestamp} vs $now"
            Logger.w(TAG) { "reject ${command.msgId}: $reason" }
            return Verdict.Rejected(command, AckStatus.REJECTED, reason)
        }

        // ---------- 关卡 2：签名 ----------
        val secret = credentials.hmacSecret
        if (secret.isBlank()) {
            // Mock 联调期尚未从服务端拿到 hmacSecret，仅在 debug + mock 下放行
            if (settings.useMock.value && BuildConfig.DEBUG) {
                Logger.w(TAG) { "hmacSecret absent, signature check SKIPPED (mock mode only)" }
            } else {
                val reason = "hmacSecret missing, cannot verify signature"
                Logger.e(TAG) { "reject ${command.msgId}: $reason" }
                raiseInvalidSignature(command, reason)
                return Verdict.Rejected(command, AckStatus.REJECTED, reason)
            }
        } else if (!Crypto.verifyHmac(command.signingPayload(), command.signature, secret)) {
            val reason = "HMAC signature mismatch"
            Logger.e(TAG) { "reject ${command.msgId}: $reason" }
            raiseInvalidSignature(command, reason)
            return Verdict.Rejected(command, AckStatus.REJECTED, reason)
        }

        // ---------- 关卡 3：幂等 ----------
        // tryClaim 在同一把锁里"查重 + 占位"，避免 MQTT 与轮询双通道同时收到同一指令时并发执行
        if (!commandRepository.tryClaim(command.msgId, command.type, now)) {
            Logger.d(TAG) { "duplicate command ${command.msgId}, ack DUPLICATED" }
            return Verdict.Rejected(command, AckStatus.DUPLICATED, "already handled")
        }

        return Verdict.Accepted(command)
    }

    private suspend fun raiseInvalidSignature(command: Command, reason: String) {
        runCatching {
            logRepository.raise(
                type = RiskType.INVALID_SIGNATURE,
                level = RiskLevel.HIGH,
                detail = mapOf(
                    "msgId" to command.msgId,
                    "commandType" to command.type.name,
                    "reason" to reason
                ),
                deviceId = credentials.deviceId
            )
        }.onFailure { Logger.w(TAG, it) { "failed to raise INVALID_SIGNATURE alert" } }
    }

    companion object {
        private const val TAG = "CommandGate"

        /** 允许的未来时间偏差：5 分钟（与服务端 X-Timestamp 容忍度一致） */
        private const val MAX_FUTURE_SKEW_MS = 5 * 60 * 1000L
    }
}
