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
    suspend fun deleteBefore(before: Long)
}

@Dao
interface AppUsageDao {

    @Query("SELECT * FROM app_usage WHERE dayKey = :dayKey")
    fun observeByDay(dayKey: String): Flow<List<AppUsageEntity>>

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
                lastUpdateAt = now
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
                lastUpdateAt = now
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

    @Query("SELECT * FROM risk_event WHERE uploaded = 0 ORDER BY timestamp ASC LIMIT :limit")
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

    @Query("UPDATE unlock_request SET status = :status, decidedAt = :decidedAt WHERE requestId = :requestId")
    suspend fun updateStatus(requestId: String, status: String, decidedAt: Long)

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
