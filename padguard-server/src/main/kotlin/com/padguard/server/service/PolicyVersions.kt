package com.padguard.server.service

import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PolicyRepository
import org.slf4j.Logger
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 策略版本号分配（全工程唯一入口）。
 *
 * ## 要解决的问题
 * 改造前"下一个版本号"是各处自己写的 `policy.version + 1` —— 读最新行、加一、插新行。
 * 这是典型的 check-then-act：两个并发写入（家长连点开关、模板应用与设置保存撞车、
 * 多端同时改）都会读到同一个旧版本号，插出**两条同号策略行**。
 * 而孩子端对账与家长端回显都靠 `findFirstByDeviceIdOrderByVersionDesc`，
 * 同号两行取到哪条完全取决于数据库返回顺序 —— 结果就是"设置保存成功，孩子端没变"，
 * 没有任何报错、没有任何日志，只在用户投诉里出现。
 *
 * ## 怎么解决
 * 先对 `devices` 行加悲观写锁（[DeviceRepository.findByIdForUpdate]），再查 `max(version)+1`。
 * 同一台设备的策略写入因此串行化，锁到**事务提交**才释放 ——
 * 用数据库行锁而不是 JVM 锁，是因为多实例部署下进程内锁根本挡不住另一个实例。
 *
 * ## 为什么单独抽出来
 * 版本号有五处递增点（默认策略、未成年人模式、切换模式、模板应用、策略重建）。
 * 只改其中一两处等于没改：漏掉的那条路径照样能插出同号行。
 */
internal object PolicyVersions {

    /**
     * 分配该设备的下一个策略版本号。必须在事务内调用（行锁要活到事务提交）。
     *
     * 无事务时降级为不加锁的 `max+1` 并记 warn：宁可保住功能、也不让"存个设置"直接报错，
     * 但要在日志里留下可追踪的痕迹（这类调用点应被视为缺陷修复）。
     */
    fun allocate(
        deviceId: String,
        devices: DeviceRepository,
        policies: PolicyRepository,
        log: Logger
    ): Int {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 设备不存在时拿不到锁，但也不应阻断写入（历史数据/测试夹具里可能存在无台账的策略）
            devices.findByIdForUpdate(deviceId)
        } else {
            log.warn(
                "策略版本分配缺少事务，无法加行锁，存在并发同号风险: deviceId={}",
                deviceId
            )
        }
        return policies.nextVersion(deviceId)
    }
}
