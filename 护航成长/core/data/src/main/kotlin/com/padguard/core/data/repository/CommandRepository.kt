package com.padguard.core.data.repository

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.db.CommandRecordDao
import com.padguard.core.data.db.CommandRecordEntity
import com.padguard.core.data.model.AckStatus
import com.padguard.core.data.model.CommandType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 指令仓库：负责指令**幂等去重**与执行留痕。
 *
 * 防重放三道关（对应接口契约 §4.1），关卡实现分布如下：
 * 1. 时效（expiresAt）—— [com.padguard.core.transport.CommandGate.evaluate] 关卡 1
 * 2. 签名（HMAC）—— 同上关卡 2
 * 3. msgId 幂等 —— 同上关卡 3，落到本类的 [tryClaim]
 *
 * 幂等窗口持久化在本地数据库（`command_record`），因此设备重启后依然有效，
 * 避免了"重启后重放旧指令"的绕过路径。
 *
 * ## 生命周期（缺一不可）
 * `tryClaim`（写 PENDING）→ 执行 → `recordResult`（写终态）→ 定期 `purgeExpired`。
 * 中间任何一环断开都会让幂等窗口失真：**只认领不写终态**正是本项目曾经的真实缺陷，
 * 表现为"每次重启后整个窗口被清空、旧指令可被重放"。
 *
 * ## 时间源纪律（新增缺陷 G）
 * 本账本**所有**时间戳必须落在 `TimeProvider.now()` 的受信时间轴上，禁止裸墙钟。
 * `purgeExpired` 曾用 `System.currentTimeMillis() - 7天` 当 `deleteBefore` 阈值：
 * 孩子把系统时间前拨一年（软管控守不住的已知攻击面），阈值瞬间跳到未来，
 * 一次清理就删光整张 `command_record`，7 天内执行过的 LOCK/UNLOCK/PAUSE 指令
 * 全部可被服务端重发或报文重放重新执行 —— 时间护栏把管控时刻钳住了，幂等账本却裸奔。
 * 现在阈值由受信钟推导，前拨/回拨都不再改变可见范围；脏值则本轮不按年龄删。
 */
@Singleton
class CommandRepository @Inject constructor(
    private val dao: CommandRecordDao,
    private val timeProvider: TimeProvider
) {

    companion object {
        /** 幂等窗口大小：保留最近 500 条 msgId */
        const val IDEMPOTENT_WINDOW = 500
        /** 记录保留时长：7 天 */
        private const val RETENTION_MS = 7 * 24 * 60 * 60 * 1000L

        /** 未认领即到终态时的类型占位。正常路径永远写真实 [CommandType]。 */
        private const val TYPE_UNKNOWN = "UNKNOWN"

        /**
         * 按年龄清理的阈值；**纯函数**，便于 JVM 单测钉住"墙钟前拨不会清空账本"。
         *
         * 返回 null = 本轮不按年龄删（只跑条数窗），用于挡住 0 / 负数 / 溢出等脏值：
         * 一个负数阈值不会删任何行，但一个因溢出变成极大正数的阈值会删光全表，
         * 所以宁可跳过一轮也不能拿脏值去 DELETE。
         */
        internal fun purgeThreshold(trustedNowMs: Long): Long? {
            if (trustedNowMs <= 0L) return null
            val threshold = trustedNowMs - RETENTION_MS
            if (threshold <= 0L || threshold >= trustedNowMs) return null
            return threshold
        }
    }

    private val mutex = Mutex()

    private val TAG = "CommandRepository"

    // 注：曾有一个 `isDuplicate(msgId)` 公开方法，已删除。
    // 它的语义是"先查重、再另行调用 tryClaim 占位"，属于典型的 check-then-act ——
    // MQTT 与轮询双通道同时收到同一指令时，两边都能查到不存在、随后双双执行。
    // 去重必须由 tryClaim 在同一把锁里"查重 + 占位"一次完成，不提供单独的查重入口。

    /**
     * 登记指令（在处理前调用，抢占幂等标记）。
     * @return false 表示已处理过，调用方应回执 DUPLICATED 并终止
     *
     * 认领阶段只写 [AckStatus.PENDING] 中间态，[recordResult] 才置终态（SUCCESS/FAILED/...）。
     * 早期实现直接写 SUCCESS，进程在"认领→执行"之间崩溃会导致：本地记录已是成功、但服务端重发被幂等窗口判重丢弃，
     * 指令静默丢失却显示"已成功"。配合 [resetInFlightOnStartup] 清理残留 PENDING，保证崩溃的指令可被重发重放。
     */
    suspend fun tryClaim(
        msgId: String,
        type: CommandType,
        /** 显式值只应由 `CommandGate` 传入的同一帧 `timeProvider.now()`；留空则内部取受信钟 */
        receivedAt: Long? = null
    ): Boolean = mutex.withLock {
        if (dao.get(msgId) != null) return false
        dao.upsert(
            CommandRecordEntity(
                msgId = msgId,
                type = type.name,
                receivedAt = receivedAt ?: timeProvider.now(),
                executedAt = null,
                status = AckStatus.PENDING.name
            )
        )
        true
    }

    /**
     * 启动清理：删除所有残留 [AckStatus.PENDING] 中间态记录。
     *
     * 这些记录意味着"认领了但没执行完就退出了"（进程崩溃 / 设备重启）。
     * 不清的话服务端在 7 天幂等窗口内重发会被 tryClaim 判重丢弃，指令永久丢失。
     * 清除后服务端重发会重新认领并执行，保证最终一致。
     *
     * **取舍**：这里刻意"宁可重放，不可静默丢失"——重放最坏是幂等指令再做一次，
     * 而丢失意味着家长点了锁屏/解锁却什么也没发生且无人知晓。
     * 唯一的代价是 TEMP_UNLOCK 重放会从当下起重新计时（见 [CommandExecutor.tempUnlock]），
     * 概率极低（须恰好在执行中途崩溃）且方向是"多放行几分钟"，可接受。
     *
     * @return 被清理的条数。常态应为 0；持续非 0 说明有指令在执行中途稳定崩溃，需要看日志。
     */
    suspend fun resetInFlightOnStartup(): Int =
        runCatching { dao.deleteByStatus(AckStatus.PENDING.name) }
            .onFailure { Logger.w(TAG) { "resetInFlightOnStartup failed: ${it.message}" } }
            .getOrDefault(0)
            .also { if (it > 0) Logger.w(TAG) { "cleared $it in-flight command(s) left by a crash, will replay on resend" } }

    /**
     * 写入指令终态。
     *
     * ## 为什么必须调用（历史缺陷）
     * 早期版本只有 [tryClaim] 被调用、本方法零调用点，于是：
     * 1. 每条指令永远停在 [AckStatus.PENDING]，`resetInFlightOnStartup` 每次启动把**全部**记录删光，
     *    7 天幂等窗口实际不存在（重启即清零）；
     * 2. 服务端因回执丢失而重发的旧指令会被重新认领并执行 ——
     *    例如孩子已被家长解锁、设备重启后又收到一条早该失效的 LOCK_SCREEN，平板被莫名锁上；
     * 3. 本地审计里没有任何一条"执行成功/失败"的痕迹。
     *
     * @param type 指令类型。正常执行路径传 `command.type`；
     *   [CommandGate] 因时效/签名拒绝时指令**未认领**（tryClaim 未执行），此处传 null，
     *   记录仍会补插一条终态，保证"被拒绝"这一事实可追溯，而不是查无此令。
     */
    suspend fun recordResult(
        msgId: String,
        type: CommandType?,
        status: AckStatus,
        /** 显式值来自 `CommandAck.executedAt`（执行器同样取 `timeProvider.now()`）；留空则内部取受信钟 */
        executedAt: Long? = null,
        errorMessage: String? = null,
        retryCount: Int = 0
    ) {
        val at = executedAt ?: timeProvider.now()
        // 与 tryClaim 共用同一把锁：避免"recordResult 读到 null → 补插终态"与
        // 另一通道的 "tryClaim 写入 PENDING" 交错，把已完成的终态覆盖回 PENDING。
        mutex.withLock {
            runCatching {
                val existing = dao.get(msgId)
                if (existing == null) {
                    // 未认领就到达终态（拒绝路径）：补插而不是丢弃。
                    // 丢了会让"签名校验失败"这类高危事件在本地毫无痕迹，事后无法复盘。
                    dao.upsert(
                        CommandRecordEntity(
                            msgId = msgId,
                            type = type?.name ?: TYPE_UNKNOWN,
                            receivedAt = at,
                            executedAt = at,
                            status = status.name,
                            retryCount = retryCount,
                            errorMessage = errorMessage
                        )
                    )
                } else {
                    dao.upsert(
                        existing.copy(
                            executedAt = at,
                            status = status.name,
                            errorMessage = errorMessage,
                            retryCount = retryCount
                        )
                    )
                }
            }.onFailure { Logger.w(TAG) { "recordResult($msgId) failed: ${it.message}" } }
        }
    }

    /**
     * 周期清理：时间窗口（7 天）+ 条数窗口（[IDEMPOTENT_WINDOW]）双裁剪。
     *
     * @return 本次删除的总行数，供调用方判断是否需要记日志。
     */
    suspend fun purgeExpired(trustedNowMs: Long = timeProvider.now()): Int {
        // 阈值只认受信钟（新增缺陷 G）。显式参数供 JVM 单测注入受控时刻，
        // 生产路径由默认参数走 TimeProvider —— 前拨/回拨墙钟都不会移动这个阈值。
        val byAge = purgeThreshold(trustedNowMs)
            ?.let { threshold -> runCatching { dao.deleteBefore(threshold) }.getOrDefault(0) }
            ?: 0
        // 条数窗与时间源无关，脏值时仍必须跑，否则重投风暴能把表撑到无限大。
        val byCount = runCatching { dao.deleteBeyondWindow(IDEMPOTENT_WINDOW) }
            .getOrDefault(0)
        return byAge + byCount
    }
}
