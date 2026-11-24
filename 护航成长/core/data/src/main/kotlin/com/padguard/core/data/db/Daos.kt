package com.padguard.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PolicyDao {

    @Query("SELECT * FROM policy WHERE id = :id")
    suspend fun get(id: Int = PolicyEntity.SINGLETON_ID): PolicyEntity?

    @Query("SELECT * FROM policy WHERE id = :id")
    fun observe(id: Int = PolicyEntity.SINGLETON_ID): Flow<PolicyEntity?>

    @Upsert
    suspend fun upsert(entity: PolicyEntity)

    @Query("DELETE FROM policy")
    suspend fun clear()
}

@Dao
interface BehaviorLogDao {

    /** 取一批未上传日志，按时间正序（保证服务端收到的是因果顺序） */
    @Query("SELECT * FROM behavior_log WHERE uploaded = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<BehaviorLogEntity>

    @Query("SELECT COUNT(*) FROM behavior_log WHERE uploaded = 0")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM behavior_log WHERE uploaded = 0")
    fun observePendingCount(): Flow<Int>

    /**
     * 首页"最近拦截"专用：按时间倒序取指定类型的最新 N 条。
     * 包含已上传的（拦截历史不应因上报完就被清空）。
     */
    @Query("SELECT * FROM behavior_log WHERE type = :type ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentOfType(type: String, limit: Int): Flow<List<BehaviorLogEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: BehaviorLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<BehaviorLogEntity>)

    @Query("UPDATE behavior_log SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    /**
     * 清理过期日志（对应说明书「本地日志默认保留 30 天，超期自动安全销毁」）。
     * 注意：只清理**已上传**的日志，未上传的审计数据永不因清理而丢失。
     */
    @Query("DELETE FROM behavior_log WHERE uploaded = 1 AND timestamp < :before")
    suspend fun deleteUploadedBefore(before: Long)

    /**
     * pending 超限兜底（C2'）：丢弃最旧的 N 条**未上传**普通日志。
     * 只在 behavior_log 堆积超过软上限时由 [com.padguard.core.data.repository.LogRepository] 触发，
     * 属于极端离线场景的容量保护 —— 告警表（risk_event）没有对应方法，永不因容量丢弃。
     */
    @Query(
        """
        DELETE FROM behavior_log WHERE id IN (
            SELECT id FROM behavior_log WHERE uploaded = 0
            ORDER BY timestamp ASC LIMIT :count
        )
        """
    )
    suspend fun deleteOldestPending(count: Int): Int

    @Query("DELETE FROM behavior_log")
    suspend fun clear()
}

@Dao
interface CommandRecordDao {

    @Query("SELECT * FROM command_record WHERE msgId = :msgId")
    suspend fun get(msgId: String): CommandRecordEntity?

    @Upsert
    suspend fun upsert(entity: CommandRecordEntity)

    /** 最近 500 条 msgId，用于指令幂等去重窗口 */
    @Query("SELECT msgId FROM command_record ORDER BY receivedAt DESC LIMIT 500")
    suspend fun recentIds(): List<String>

    @Query("DELETE FROM command_record WHERE receivedAt < :before")
    suspend fun deleteBefore(before: Long): Int

    /** 按状态删除（启动清理残留 PENDING 中间态，使其可被服务端重发重新认领） */
    @Query("DELETE FROM command_record WHERE status = :status")
    suspend fun deleteByStatus(status: String): Int

    /**
     * 幂等窗口裁剪：只保留最近 [keep] 条，其余删除。
     *
     * 与 [deleteBefore] 并存而不是二选一：时间窗口保证「7 天后旧 msgId 自然作废」，
     * 条数窗口保证「指令风暴（服务端重试放大 / 批量下发）不会把本地库撑爆」。
     * 只按时间裁剪时，一次异常重发就能在 7 天内写进几十万行；
     * 只按条数裁剪时，长期离线又复连的设备会把窗口压到只剩几小时。两者都在才稳。
     */
    @Query(
        """
        DELETE FROM command_record WHERE msgId NOT IN (
            SELECT msgId FROM command_record ORDER BY receivedAt DESC LIMIT :keep
        )
        """
    )
    suspend fun deleteBeyondWindow(keep: Int): Int
}

@Dao
interface AppUsageDao {

    @Query("SELECT * FROM app_usage WHERE dayKey = :dayKey")
    fun observeByDay(dayKey: String): Flow<List<AppUsageEntity>>

    /** 一次性快照：供周期上报使用，避免为取一次数据而长期持有 Flow 订阅 */
    @Query("SELECT * FROM app_usage WHERE dayKey = :dayKey ORDER BY usedMs DESC")
    suspend fun snapshotByDay(dayKey: String): List<AppUsageEntity>

    @Query("SELECT * FROM app_usage WHERE dayKey = :dayKey AND packageName = :pkg")
    suspend fun get(dayKey: String, pkg: String): AppUsageEntity?

    @Upsert
    suspend fun upsert(entity: AppUsageEntity)

    /** 原子累加使用时长，避免并发写覆盖 */
    @Transaction
    suspend fun addUsage(dayKey: String, pkg: String, deltaMs: Long, now: Long) {
        val current = get(dayKey, pkg)
        upsert(
            AppUsageEntity(
                packageName = pkg,
                dayKey = dayKey,
                usedMs = (current?.usedMs ?: 0L) + deltaMs,
                launchCount = current?.launchCount ?: 0,
                lastUpdateAt = now,
                // 首次使用时间只在"这一天的第一条记录"时落定，之后不再被覆盖；
                // 否则每次累加都会把它推到当下，家长看到的就是"开始时间≈结束时间"，这个字段等于没有。
                firstUsedAt = current?.firstUsedAt?.takeIf { it > 0 } ?: now,
                lastUsedAt = now
            )
        )
    }

    /** 原子累加启动次数 */
    @Transaction
    suspend fun addLaunch(dayKey: String, pkg: String, now: Long) {
        val current = get(dayKey, pkg)
        upsert(
            AppUsageEntity(
                packageName = pkg,
                dayKey = dayKey,
                usedMs = current?.usedMs ?: 0L,
                launchCount = (current?.launchCount ?: 0) + 1,
                lastUpdateAt = now,
                // 启动本身也是一次"使用"：首次启动即为首次使用，供家长端展示开始时间
                firstUsedAt = current?.firstUsedAt?.takeIf { it > 0 } ?: now,
                lastUsedAt = now
            )
        )
    }

    @Query("DELETE FROM app_usage WHERE dayKey = :dayKey")
    suspend fun deleteDay(dayKey: String)

    /** 清理 N 天前的统计（统计类数据无需长期留存） */
    @Query("DELETE FROM app_usage WHERE dayKey < :beforeDayKey")
    suspend fun deleteBefore(beforeDayKey: String)
}

@Dao
interface DailyUsageDao {

    @Query("SELECT * FROM daily_usage WHERE dayKey = :dayKey")
    suspend fun get(dayKey: String): DailyUsageEntity?

    @Query("SELECT * FROM daily_usage WHERE dayKey = :dayKey")
    fun observe(dayKey: String): Flow<DailyUsageEntity?>

    @Transaction
    suspend fun addUsage(dayKey: String, deltaMs: Long, now: Long) {
        val current = get(dayKey)
        upsert(
            DailyUsageEntity(
                dayKey = dayKey,
                totalMs = (current?.totalMs ?: 0L) + deltaMs,
                lastUpdateAt = now
            )
        )
    }

    @Upsert
    suspend fun upsert(entity: DailyUsageEntity)

    @Query("DELETE FROM daily_usage WHERE dayKey < :beforeDayKey")
    suspend fun deleteBefore(beforeDayKey: String)
}

@Dao
interface RiskEventDao {

    /**
     * 待上报告警：按 RiskLevel 优先级出队（HIGH 永远排在最前），同级内再按时间升序（FIFO）。
     *
     * 为什么不是纯 `ORDER BY timestamp ASC`：良性事件（环境自检 / 应用装卸 / 代理检测等
     * NORMAL/INFO）洪泛时，纯时间序会把 `CONTROL_LOST` / `UNINSTALL_ATTEMPT` 这类高危告警
     * 压在队尾，每批（ALERT_BATCH_SIZE=50）只能慢慢排空前面的良性事件，导致家长延迟收到
     * 「正在被卸载 / 管控完整性丢失」等关键信号。改为按级别优先后，HIGH 告警在任意批次都
     * 会被优先取走，链路不被良性噪声淹没。
     * 优先级映射与 [com.padguard.core.data.model.RiskLevel] 保持一致（HIGH<NORMAL<INFO）。
     */
    @Query(
        "SELECT * FROM risk_event WHERE uploaded = 0 " +
            "ORDER BY CASE level " +
            "WHEN 'HIGH' THEN 0 " +
            "WHEN 'NORMAL' THEN 1 " +
            "WHEN 'INFO' THEN 2 " +
            "ELSE 3 END ASC, " +
            "timestamp ASC " +
            "LIMIT :limit"
    )
    suspend fun pending(limit: Int): List<RiskEventEntity>

    @Query("SELECT COUNT(*) FROM risk_event WHERE uploaded = 0")
    suspend fun pendingCount(): Int

    /**
     * 首页"最近拦截"专用：按时间倒序取最新 N 条告警事件，用于在首页展示"最近拦截"。
     * 不区分已上传/未上传，告警永久本地留存。
     */
    @Query("SELECT * FROM risk_event ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RiskEventEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: RiskEventEntity): Long

    @Query("UPDATE risk_event SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    @Query("DELETE FROM risk_event WHERE uploaded = 1 AND timestamp < :before")
    suspend fun deleteUploadedBefore(before: Long)
}

@Dao
interface UnlockRequestDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: UnlockRequestEntity): Long

    /** 申请列表（含历史），按时间倒序 */
    @Query("SELECT * FROM unlock_request ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<UnlockRequestEntity>>

    /**
     * 是否还有在途申请。
     * UI 用它来禁用"再次申请"按钮 —— 孩子连点 10 次会给家长刷屏。
     */
    @Query("SELECT COUNT(*) FROM unlock_request WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    /**
     * 找最近一条待审批申请，用于收到 TEMP_UNLOCK 指令时回填审批结果。
     *
     * @param pkg 传空串表示"不限包名"（整机放行指令），此时匹配任意在途申请
     */
    @Query(
        """
        SELECT * FROM unlock_request
        WHERE status = 'PENDING' AND (:pkg = '' OR packageName = :pkg)
        ORDER BY createdAt DESC LIMIT 1
        """
    )
    suspend fun latestPending(pkg: String): UnlockRequestEntity?

    @Query("SELECT * FROM unlock_request WHERE uploaded = 0 ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pendingUpload(limit: Int): List<UnlockRequestEntity>

    @Query("UPDATE unlock_request SET uploaded = 1 WHERE requestId IN (:requestIds)")
    suspend fun markUploaded(requestIds: List<String>)

    /**
     * 审批状态转移：**只能从 PENDING 出发**，终态不可回退（与服务端状态机同一纪律）。
     *
     * 原 SQL 只按 requestId 更新：`approveLatest` 的 latestPending 查询与这里的 UPDATE
     * 之间存在竞态窗口，家长已拒绝（REJECTED）后迟到的 TEMP_UNLOCK 指令仍可把
     * 终态覆盖回 APPROVED；反之 `reject` 也能把已放行的申请改回 REJECTED。
     * 把守卫放进 DB 的 WHERE（而非"先读后判"），跨协程原子。
     *
     * @return 实际更新的行数：0 表示已是终态或被并发抢先，调用方按正常情况处理。
     */
    @Query(
        "UPDATE unlock_request SET status = :status, decidedAt = :decidedAt " +
            "WHERE requestId = :requestId AND status = 'PENDING'"
    )
    suspend fun updateStatus(requestId: String, status: String, decidedAt: Long): Int

    /** 超时未审批的申请统一置为失效，让孩子可以重新发起 */
    @Query("UPDATE unlock_request SET status = 'EXPIRED' WHERE status = 'PENDING' AND createdAt < :before")
    suspend fun expireStale(before: Long)

    @Query("DELETE FROM unlock_request WHERE createdAt < :before")
    suspend fun deleteBefore(before: Long)
}

@Dao
interface AgreementDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AgreementEntity): Long

    @Query("SELECT * FROM agreement ORDER BY signedAt DESC LIMIT 1")
    suspend fun latest(): AgreementEntity?

    @Query("SELECT * FROM agreement ORDER BY signedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AgreementEntity>>

    @Query("DELETE FROM agreement WHERE signedAt < :before")
    suspend fun deleteBefore(before: Long)
}

@Dao
interface ParentMessageDao {

    /** 全部家长消息，按时间倒序（消息列表页直接消费） */
    @Query("SELECT * FROM parent_message ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ParentMessageEntity>>

    /** 单条消息（消息详情页按 serverMsgId 取） */
    @Query("SELECT * FROM parent_message WHERE serverMsgId = :serverMsgId")
    fun observeByServerId(serverMsgId: String): Flow<ParentMessageEntity?>

    @Query("SELECT * FROM parent_message WHERE serverMsgId = :serverMsgId")
    suspend fun getByServerId(serverMsgId: String): ParentMessageEntity?

    /** upsert：同一条消息重复下发只更新不新增（serverMsgId 唯一约束） */
    @Upsert
    suspend fun upsert(entity: ParentMessageEntity)

    /** 标记已读（孩子打开详情后调用） */
    @Query("UPDATE parent_message SET isRead = 1 WHERE serverMsgId = :serverMsgId")
    suspend fun markRead(serverMsgId: String)

    /** 未读数量，用于消息 Tab 红点 */
    @Query("SELECT COUNT(*) FROM parent_message WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("DELETE FROM parent_message")
    suspend fun clear()
}
