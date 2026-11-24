package com.padguard.core.data.repository

import com.padguard.core.data.model.RiskEvent

/**
 * 高危告警的即时单发通道。
 *
 * ## 为什么存在
 * [LogRepository.raise] 产生的所有风险事件原本都只落库，等 `GuardService.uploadPending`
 * 的周期 tick 才走 HTTPS 批量上报。HIGH 级告警（管控完整性丢失、正在被卸载、设备管理员被禁用…）
 * 是分秒必争的信号，被批量窗口延迟几十秒~几分钟不可接受。
 *
 * 本接口在 `raise` 落库的同时，对 HIGH 事件 fire-and-forget 地经 MQTT QoS1 即时推送，
 * 让关键告警秒级到达家长端。
 *
 * ## 约束力（fail-closed）
 * - 本方法在 [LogRepository.raise] 内已被包在 `scope.launch { runCatching { ... } }` 中调用，
 *   故实现可以是挂起函数；调用方永远不会被阻塞，也不承担异常。
 * - 实现必须**吞掉一切异常**：调用方（卸载防御、权限自检）处于拦截热路径，
 *   任何 MQTT 异常/序列化异常/空指针都绝不能冒泡到 [LogRepository.raise]。
 * - 即时通道只是「加速冗余」：即使发送失败或未连接，[LogRepository] 的批量 HTTPS 上报
 *   仍是权威去重路径，告警**不会因此丢失**。
 * - 实现**不得**修改事件的已上传状态，避免与批量路径竞争。
 *
 * 接口定义在 `core:data` 是为了不引入对 `core:transport` 的反向依赖
 * （`core:transport` 已依赖 `core:data`，反向会形成循环依赖）。
 * 真实实现 [com.padguard.core.transport.mqtt.MqttRealtimeAlertPublisher] 在 `core:transport`，
 * 由 app 模块用 `@Binds` 注入。
 */
interface RealtimeAlertPublisher {
    /** 即时推送一条 HIGH 风险事件。实现内部须保证不向调用方抛异常。 */
    suspend fun sendNow(event: RiskEvent)
}

/** 默认实现：什么都不做。供未注入真实实现（如纯 JVM 测试）时兜底。 */
object NoopRealtimeAlertPublisher : RealtimeAlertPublisher {
    override suspend fun sendNow(event: RiskEvent) = Unit
}
