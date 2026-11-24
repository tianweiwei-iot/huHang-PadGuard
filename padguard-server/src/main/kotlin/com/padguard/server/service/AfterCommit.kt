package com.padguard.server.service

import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

private val AFTER_COMMIT_LOG = LoggerFactory.getLogger("AfterCommit")

/**
 * 提交后副作用必须**自吞异常**：事务已经提交，回调再抛异常只会让接口返回 500，
 * 家长看到"保存失败"但策略/审批其实已经生效，制造无法对账的状态错乱。
 * 副作用失败只记日志，业务结果不再改变。
 */
private fun runSideEffect(block: () -> Unit) {
    runCatching(block).onFailure { AFTER_COMMIT_LOG.warn("afterCommit side effect failed: {}", it.message, it) }
}

/**
 * 「当前线程正在执行提交回调」标记。
 *
 * Spring 的 `TransactionSynchronizationUtils.triggerAfterCommit()` 遍历的是
 * `TransactionSynchronizationManager.getSynchronizations()` 返回的**快照**。
 * 在 `afterCommit()` 回调里再次 `registerSynchronization()`，新回调既不会进入本次遍历，
 * 也不会有第二次提交来触发它 —— 这个副作用会**静默丢失**：没有异常、没有日志，
 * 只表现为「偶发少一条 WS 推送 / 少一次 MQTT 通知」，极难定位。
 *
 * 因此回调执行期间把嵌套调用降级为「立即执行」，并在 finally 复位（容器线程会复用，
 * 残留标记会让下一条无关事务的副作用提前执行）。
 */
private val IN_AFTER_COMMIT = ThreadLocal.withInitial { false }

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
 *
 * public 而非 internal：WS 推送、MQTT 上行等其它包同样需要「提交后再对外」的语义。
 */
fun afterCommit(block: () -> Unit) {
    // 已在提交回调内：不能再注册，否则新回调永远不会被执行（见 IN_AFTER_COMMIT 说明）。
    if (IN_AFTER_COMMIT.get() == true) {
        runSideEffect(block)
        return
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                val previous = IN_AFTER_COMMIT.get()
                IN_AFTER_COMMIT.set(true)
                try {
                    runSideEffect(block)
                } finally {
                    if (previous) IN_AFTER_COMMIT.set(true) else IN_AFTER_COMMIT.remove()
                }
            }
        })
    } else {
        // 无事务（例如只读路径 / 单元测试直调）：立即执行，保持行为可预期
        runSideEffect(block)
    }
}
