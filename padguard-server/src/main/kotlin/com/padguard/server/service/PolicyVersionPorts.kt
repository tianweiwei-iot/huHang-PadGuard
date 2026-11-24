package com.padguard.server.service

import com.padguard.server.dto.CommandDto
import com.padguard.server.mqtt.MqttGateway
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 策略版本能力对外的**窄依赖**（端口与适配器）。
 *
 * 为什么不让 `PolicyVersionService` 直接依赖 `PolicyExtensionService` / `CommandService` /
 * `AuditService` / `MqttGateway` 这四个大类：
 * 1. 它实际只用到每个类的一个方法，直接依赖会把两个上帝类的整张依赖图拖进版本管理链路；
 * 2. 单测里无法用 Mockito inline mock maker 修改 `PolicyExtensionService`（当前 JDK17 +
 *    Windows 环境报 "Could not modify all classes"），导致「为了测 20 行逻辑必须先起 Spring 容器」；
 * 3. 端口是接口，测试传 lambda 即可，验证的是契约而不是 mock 框架行为。
 *
 * 生产侧由 [PolicyVersionAdapters] 把端口适配到真实 Spring Bean，运行期行为完全不变。
 */

fun interface PolicyRebuilder {
    /** 以子表为唯一事实源重建策略包并通知设备；同时写出 NORMAL 行以退出回滚态 */
    fun rebuild(deviceId: String)
}

fun interface CommandIssuer {
    fun issue(deviceId: String, type: String, payload: Map<String, Any?>?, priority: String): CommandDto
}

fun interface PolicyAuditor {
    fun record(
        userId: String?, deviceId: String?, action: String,
        targetType: String?, targetId: String?, detail: String?
    )
}

fun interface PolicyNotifier {
    fun notifyPolicyChanged(deviceId: String, version: Int)
}

@Configuration
class PolicyVersionAdapters {

    @Bean
    fun policyRebuilder(extension: PolicyExtensionService) = PolicyRebuilder { deviceId ->
        extension.rebuildPolicy(deviceId)
    }

    @Bean
    fun commandIssuer(commands: CommandService) = CommandIssuer { deviceId, type, payload, priority ->
        commands.issueCommand(deviceId, type, payload, priority)
    }

    @Bean
    fun policyAuditor(audit: AuditService) = PolicyAuditor { userId, deviceId, action, targetType, targetId, detail ->
        audit.record(
            userId, deviceId, action,
            targetType = targetType, targetId = targetId, detail = detail
        )
    }

    @Bean
    fun policyNotifier(mqtt: MqttGateway) = PolicyNotifier { deviceId, version ->
        // 与 PolicyService / PolicyExtensionService 同口径：调用点在 afterCommit 之后，
        // 这里只负责发，失败由调用方 runCatching 记录，不让 Broker 抖动影响策略落库。
        mqtt.publishPolicyNotify(deviceId, version)
    }
}
