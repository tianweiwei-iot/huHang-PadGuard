package com.padguard.server.service

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.locks.ReentrantLock

/**
 * 绑定流程的按设备互斥（同硬件指纹串行）。
 *
 * ## 要解决的问题
 * [DeviceService.matchExistingDevice] 是"先查有没有同一台平板的历史台账，没有就新建"：
 *
 * ```
 * if (matchExistingDevice(...) != null) 复用 else 新建
 * ```
 *
 * 这是典型的 check-then-act。孩子端真实存在这种并发：
 * 绑定请求超时（弱网/服务端慢）→ 端上重试 → 两个请求同时在飞；
 * 或者家长一边扫码、一边让孩子用账号密码绑定。
 * 两个事务都查不到台账，于是**各建一条**，家长端出现两台同名设备，
 * 用量、策略、指令从此分裂到两条 deviceId 上 —— 而且没有任何报错能提示这一点。
 *
 * ## 为什么用键锁而不是给 `device_sn` 加唯一索引
 * 唯一索引是更强的保证，但线上存量数据可能已经因这个缺陷产生重复 `device_sn`
 * （同一台平板被建了两次），加索引的迁移会直接失败；而且 `device_sn` 允许为空，
 * 空值不参与唯一约束，覆盖不到"只有 fingerprint"的场景。
 * 键锁不改表结构、不改数据语义，把并发收窄到"同一台平板串行绑定"，风险最小。
 *
 * ## 锁为什么必须活到事务提交
 * 锁在事务内获取、在方法返回时释放是无效的：T1 释放锁时事务还没提交，
 * T2 拿到锁后仍然看不到 T1 刚插入的行，照样会再建一条。
 * 因此这里把解锁挂到 [TransactionSynchronization.afterCompletion]（提交/回滚之后），
 * 保证下一个进入者一定能读到上一次的结果。
 *
 * ## 局限（写在明处）
 * 这是**进程内**锁：单实例部署（当前 docker-compose 形态）成立，多实例部署则只在实例内互斥。
 * 多实例时需升级为数据库唯一约束或分布式锁 —— 这一点已记入迭代报告的遗留项，不假装已解决。
 */
internal object DeviceBindLocks {

    /** 固定数量的锁槽：设备指纹基数不可控，不能按 key 建 map（会随设备数无限增长） */
    private val stripes = Array(64) { ReentrantLock() }

    private fun slot(key: String): ReentrantLock = stripes[Math.floorMod(key.hashCode(), stripes.size)]

    /**
     * 绑定去重键：优先 ANDROID_ID（重装不变），退而用 ROM 指纹。
     *
     * 两者都缺时返回空串 —— 此时没有任何跨请求的"同一台设备"依据，
     * 加锁只会把不相干的绑定串到一起，反而不如不加。
     */
    fun bindKey(deviceSn: String?, fingerprint: String?): String = when {
        !deviceSn.isNullOrBlank() -> "sn:" + deviceSn.trim()
        !fingerprint.isNullOrBlank() -> "fp:" + fingerprint.trim()
        else -> ""
    }

    fun <T> withKey(key: String, block: () -> T): T {
        if (key.isEmpty()) return block()
        val lock = slot(key)
        lock.lock()
        var releaseInFinally = true
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    lock.unlock()
                }
            })
            // 解锁权已交给事务回调：无论如何都不能在 finally 里再解一次（ReentrantLock 会抛异常）
            releaseInFinally = false
        }
        try {
            return block()
        } finally {
            if (releaseInFinally) lock.unlock()
        }
    }
}
