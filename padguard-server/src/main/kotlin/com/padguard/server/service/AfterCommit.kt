package com.padguard.server.service

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 把「对外的副作用」推迟到事务真正提交之后执行；当前没有事务时立即执行。
 *
 * ## 为什么需要它
 * MQTT 下发（[com.padguard.server.mqtt.MqttGateway.publish] 是 QoS1 同步发布，阻塞到 PUBACK）
 * 曾被直接写在 `@Transactional` 方法体内，带来两个后果：
 *
 * 1. **Broker 抖动会让业务失败**：发布抛异常 → 整个事务回滚 → 家长改设置报「保存失败」，
 *    孩子端仍在执行旧策略。设置本身与 Broker 是否在线毫无关系，不该被它连坐。
 * 2. **长事务 + 长行锁**：`PolicyVersions.allocate` 对 `devices` 行取了悲观写锁，
 *    这把锁要一直持有到事务提交 —— Broker 慢的时候，同一设备的心跳、其它策略写入全部排队，
 *    严重时连接池被拖干。
 *
 * 放到提交之后还有一层正确性收益：此时对方（孩子端）来拉策略，一定能读到刚提交的新版本，
 * 不会出现「收到通知 → 立刻拉取 → 还是旧版本」的竞态。
 */
internal fun afterCommit(block: () -> Unit) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = block()
        })
    } else {
        // 无事务（例如只读路径 / 单元测试直调）：立即执行，保持行为可预期
        block()
    }
}
