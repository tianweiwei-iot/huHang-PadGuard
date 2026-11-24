package com.padguard.server.service

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
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
 * 或者家长一边扫码、一边让孩子用账号密码绑定；局域网接入/批量导入也在抢同一台孤儿设备。
 * 两个事务都查不到台账，于是**各建一条**，家长端出现两台同名设备，
 * 用量、策略、指令从此分裂到两条 deviceId 上 —— 而且没有任何报错能提示这一点。
 *
 * ## 为什么用键锁而不是给 `device_sn` 加唯一索引
 * 唯一索引是更强的保证，但线上存量数据可能已经因这个缺陷产生重复 `device_sn`
 * （同一台平板被建了两次），加索引的迁移会直接失败；而且 `device_sn` 允许为空，
 * 空值不参与唯一约束，覆盖不到"只有 fingerprint"的场景。
 * 键锁不改表结构、不改数据语义，把并发收窄到"同一台平板串行绑定"，风险最小。
 *
 * ## 双层锁：进程内条带锁 + PostgreSQL 事务级咨询锁
 * - **JVM 条带锁（所有档位均有）**：进程内互斥，同时把并发压到 DB 之前，减少咨询锁争用。
 * - **PG 咨询锁（仅默认/docker 生产档）**：`pg_advisory_xact_lock` 由**数据库**持有，
 *   多个副本连的是同一个 PG，于是在副本之间也互斥。它是事务级锁，
 *   **随事务提交/回滚自动释放**，无需手动解锁，也不留残留锁。
 *
 * 为什么只对 PG 加咨询锁：`home` / `local` 用的是 `jdbc:h2:file:./data/padguard`，
 * 且刻意不开 `AUTO_SERVER`（发布包内置 H2 缺 `NetworkConnectionInfo`，开了直接起不来）。
 * 文件库同一时刻只能被一个 JVM 打开 —— 这两个档位**物理上不可能多副本**，
 * JVM 条带锁已经足够。与其为了 H2 引入一张锁表 + 启动建表 + 迁移 + 锁超时调优，
 * 不如按真实部署拓扑给唯一会多副本的 PG 加一层，其余档位零成本零风险。
 *
 * ## 锁为什么必须活到事务提交
 * 锁在事务内获取、在方法返回时释放是无效的：T1 释放锁时事务还没提交，
 * T2 拿到锁后仍然看不到 T1 刚插入的行，照样会再建一条。
 * - JVM 条带锁：解锁挂到 [TransactionSynchronization.afterCompletion]（提交/回滚之后）。
 * - PG 咨询锁：`pg_advisory_xact_lock` 本身就是事务级，提交/回滚由 PG 自动释放。
 * 两条路径都保证下一个进入者一定能读到上一次的结果。
 */
internal object DeviceBindLocks {

    private val log = LoggerFactory.getLogger(DeviceBindLocks::class.java)

    /** 固定数量的条带：设备指纹基数不可控，不能按 key 建 map（会随设备数无限增长） */
    private val stripes = Array(64) { ReentrantLock() }

    private fun slot(key: String): ReentrantLock = stripes[Math.floorMod(key.hashCode(), stripes.size)]

    /** 上层在启动期注入；未注入时（纯 JVM 单测）自动退化为"只有进程内锁"。 */
    @Volatile
    private var jdbc: JdbcTemplate? = null

    /** null=尚未探测；探测结果缓存，避免每次加锁都跑一次 metadata 查询。 */
    @Volatile
    private var advisoryEnabled: Boolean? = null

    internal fun install(jdbcTemplate: JdbcTemplate) {
        jdbc = jdbcTemplate
        advisoryEnabled = null
    }

    /** 仅供单测复位注入状态，避免测试间互相污染。 */
    internal fun resetForTest() {
        jdbc = null
        advisoryEnabled = null
    }

    /**
     * 仅供单测：直接注入已探明的数据库类型，跳过 metadata 探测，
     * 以便用 mock JdbcTemplate 验证 PG 咨询锁路径确实发起了 SQL。
     */
    internal fun installForTest(jdbcTemplate: JdbcTemplate, postgres: Boolean) {
        jdbc = jdbcTemplate
        advisoryEnabled = postgres
    }

    /** 是否为 PostgreSQL —— 只有它才会多副本，才需要跨副本咨询锁。 */
    private fun isPostgres(): Boolean {
        advisoryEnabled?.let { return it }
        val template = jdbc ?: return false // 未注入（纯 JVM 单测）→ 无 DB，退化为只有进程内锁
        val detected = try {
            val product = template.execute(ConnectionCallback<String> { c: java.sql.Connection ->
                c.metaData.databaseProductName
            }) ?: ""
            product.contains("postgres", ignoreCase = true)
        } catch (t: Throwable) {
            log.warn("数据库类型探测失败，跨副本咨询锁不启用（单副本仍由 JVM 条带锁保证）: {}", t.message)
            false
        }
        advisoryEnabled = detected
        return detected
    }

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

    /**
     * 咨询锁键：FNV-1a 64 位，跨 JVM/跨语言稳定。
     * 不用 [String.hashCode] 是因为它只有 32 位、碰撞概率高 —— 碰撞只会带来多余串行，
     * 不影响正确性，但稳定的 64 位哈希能把无谓争用压到最低。
     */
    internal fun advisoryId(key: String): Long {
        // FNV-1a 64：offset basis 与 prime 用十进制写，避免 Kotlin 把超出 Long.MAX_VALUE 的
        // 十六进制字面量判为 out of range（0xcbf29ce484222325 = -3750763034362895579 有符号）。
        var h = -3750763034362895579L
        for (b in key.toByteArray(Charsets.UTF_8)) {
            h = (h xor (b.toLong() and 0xffL)) * 1099511628211L
        }
        return h
    }

    fun <T> withKey(key: String, block: () -> T): T {
        if (key.isEmpty()) return block()
        val lock = slot(key)
        lock.lock()
        // 解锁权是否已交给事务回调：交给回调后 finally 绝不能再解一次（ReentrantLock 会抛异常）
        var handedOff = false
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                // JVM 锁必须活到事务提交：下一个进入者要能读到本次提交的结果
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        lock.unlock()
                    }
                })
                handedOff = true

                // 唯一会多副本的 PG：叠加事务级咨询锁，随事务提交/回滚自动释放。
                // 先注册 JVM 解锁回调再抢咨询锁：若抢锁抛异常，事务回滚时 afterCompletion
                // 仍会释放 JVM 锁，不会把它永久占住。
                if (isPostgres()) {
                    // advisoryId 是从 key 算出的纯数字，无注入面；整条 SQL 由我们自己拼装。
                    jdbc!!.execute("SELECT pg_advisory_xact_lock(${advisoryId(key)})")
                }
            }
            return block()
        } finally {
            if (!handedOff) lock.unlock()
        }
    }
}

/**
 * 把事务可用的 [JdbcTemplate] 交给 [DeviceBindLocks]，供其探测数据库类型并申请咨询锁。
 *
 * 用 ApplicationRunner 而非构造注入到 [com.padguard.server.service.DeviceService]：
 * 后者被多处 JVM 单测按现有签名直构，加构造参数会连锁改动无关 fixture；
 * 而咨询锁是横切基础设施，注册一次全局生效，与业务服务解耦。
 */
@Component
internal class DeviceBindLockRegistrar(
    private val jdbc: JdbcTemplate
) : ApplicationRunner {
    override fun run(args: ApplicationArguments?) {
        DeviceBindLocks.install(jdbc)
    }
}
