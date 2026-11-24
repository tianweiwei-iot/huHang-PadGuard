package com.padguard.server.support

import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 分布式定时任务互斥锁（租约制，H2(MODE=PostgreSQL) 与 PG 双兼容）。
 *
 * ## 为什么不用 pg_advisory_lock / ShedLock
 * - `pg_advisory_lock` 是 PG 专属，本地/CI 跑 H2(MODE=PostgreSQL)，SQL 必须可移植；
 * - ShedLock 引第三方依赖只为包住几条 SQL，得不偿失（最小改动纪律）。
 *
 * ## 语义
 * - **抢锁**：`UPDATE task_locks SET ... WHERE task_name=? AND lease_until < now`（抢占过期租约）
 *   影响 0 行则尝试 `INSERT`（首次）；INSERT 撞主键说明他副本持有效租约 → 本轮放弃返回 null。
 * - **崩溃自愈**：持锁副本崩溃不释放，租约到期后任何副本都能重新抢占，无需人工清锁。
 * - **释放**：`DELETE WHERE task_name=? AND holder=?` 只删自己的行——租约被抢走后
 *   迟到的 DELETE 不会误删新持有者的锁。
 * - **租约续期**：长任务超过租约时间也不能让第二个副本抢锁。[runExclusively] 在持锁
 *   期间按 `leaseMs/3` 心跳续租；续租失败（锁被抢走 / DB 故障）立即中断任务线程，
 *   宁可本轮半途而废，也不允许两个副本同时清理同一批数据。
 * - 家庭单机档没有 `task_locks` 表时，抢锁直接放行且不启动心跳（见 [tryAcquire]），
 *   不能因为"续租失败"把单副本的清理任务永久停摆。
 *
 * ## 与 @Scheduled 的关系
 * 单副本部署时锁恒获取成功、零开销；多副本时到点竞争，输家直接跳过本轮。
 */
@Component
class TaskExecutionLock(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(TaskExecutionLock::class.java)

    /**
     * 尝试以租约持有 [task] 并执行 [body]：
     * - 拿到锁：执行并释放，返回 [body] 结果；
     * - 没拿到（他副本持有效租约）：返回 null，跳过本轮。
     * [body] 抛出的异常原样上抛（调度层各自的 try/catch 语义不变），finally 保证释放。
     */
    fun <T> runExclusively(task: String, holder: String, leaseMs: Long, body: () -> T): T? {
        if (!tryAcquire(task, holder, leaseMs)) {
            log.info("task lock busy, skip this round: task={} holder={}", task, holder)
            return null
        }
        // 无表单机档抢锁是"假放行"，没有可续期的行；启心跳只会每 3 分钟刷一条 DB 异常。
        val renewNeeded = lockTableExists()
        val leaseLost = AtomicBoolean(false)
        val bodyThread = Thread.currentThread()
        val scheduler = if (renewNeeded) {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "task-lock-renew-$task").apply { isDaemon = true }
            }
        } else null
        val renewFuture = scheduler?.scheduleAtFixedRate(
            {
                val now = System.currentTimeMillis()
                runCatching {
                    jdbc.update(
                        "UPDATE task_locks SET lease_until = ? " +
                            "WHERE task_name = ? AND holder = ? AND lease_until > ?",
                        now + leaseMs, task, holder, now
                    )
                }.onFailure { e ->
                    log.error("task lock renew failed, aborting task: task={} err={}", task, e.message)
                    leaseLost.set(true)
                    bodyThread.interrupt()
                }.onSuccess { affected ->
                    if (affected == 0) {
                        log.error("task lock lease lost, aborting task: task={} holder={}", task, holder)
                        leaseLost.set(true)
                        bodyThread.interrupt()
                    }
                }
            },
            (leaseMs / 3).coerceAtLeast(1_000L),
            (leaseMs / 3).coerceAtLeast(1_000L),
            TimeUnit.MILLISECONDS
        )
        try {
            return body()
        } finally {
            renewFuture?.cancel(false)
            scheduler?.shutdownNow()
            // 只在真的因为丢锁中断过时才清标志，避免吞掉调用方自己的中断意图。
            if (leaseLost.get()) Thread.interrupted()
            release(task, holder)
        }
    }

    /** 抢占/续期失败一律 false；锁表异常不能打断定时调度，降级为"本轮跳过"。 */
    private fun tryAcquire(task: String, holder: String, leaseMs: Long): Boolean {
        val now = System.currentTimeMillis()
        return try {
            val renewed = jdbc.update(
                "UPDATE task_locks SET holder = ?, acquired_at = ?, lease_until = ? " +
                    "WHERE task_name = ? AND lease_until < ?",
                holder, now, now + leaseMs, task, now
            )
            if (renewed > 0) {
                true
            } else {
                jdbc.update(
                    "INSERT INTO task_locks (task_name, holder, acquired_at, lease_until) VALUES (?, ?, ?, ?)",
                    task, holder, now, now + leaseMs
                ) > 0
            }
        } catch (e: DuplicateKeyException) {
            false // 他副本持有效租约
        } catch (e: Exception) {
            // 区分两种"抢锁失败"，不能一律跳过：
            // 1) 锁表不存在——家庭部署的 local/home 档 flyway 关闭、ddl-auto=update，
            //    这张裸表（无对应实体）根本不会被建；此时是单副本环境，必须放行执行，
            //    否则所有 @Scheduled 清理任务永久停摆（表一天比一天大）。
            // 2) 锁表存在但 DB 故障——多副本场景，跳过本轮由健康副本执行，不炸调度线程。
            if (!lockTableExists()) {
                log.debug("task_locks table absent (single-replica home/local), run without lock: task={}", task)
                true
            } else {
                log.warn("task lock acquire failed, degrade to skip: task={} err={}", task, e.message)
                false
            }
        }
    }

    /**
     * 锁表是否存在。必须查 information_schema（表不存在时返回 0，不抛异常），
     * 不能用 `SELECT count(*) FROM task_locks`——那样表不存在会抛异常，
     * 与"DB 故障"无法区分，会把单副本无表场景误判成"有表→跳过"，清理永久停摆。
     * H2(MODE=PostgreSQL, DATABASE_TO_LOWER=TRUE) 与 PG 的 table_name 均可 lower() 匹配。
     * 探测本身异常（连库都连不上）按"有表"处理并跳过，避免 DB 故障时多副本并发清理。
     */
    private fun lockTableExists(): Boolean = try {
        val n = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE lower(table_name) = 'task_locks'",
            Int::class.java
        )
        (n ?: 0) > 0
    } catch (e: Exception) {
        true
    }

    private fun release(task: String, holder: String) {
        try {
            jdbc.update("DELETE FROM task_locks WHERE task_name = ? AND holder = ?", task, holder)
        } catch (e: Exception) {
            // 释放失败无碍正确性：租约到期后他副本可自然抢占
            log.warn("task lock release failed (lease will expire): task={} err={}", task, e.message)
        }
    }
}
