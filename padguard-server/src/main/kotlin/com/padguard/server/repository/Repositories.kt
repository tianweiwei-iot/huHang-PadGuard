package com.padguard.server.repository

import com.padguard.server.domain.*
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface UserRepository : JpaRepository<User, String> {
    fun existsByPhone(phone: String): Boolean
    fun findByPhone(phone: String): User?
    /** 按账号状态计数（冻结/激活看板指标） */
    fun countByStatus(status: String): Long
}

@Repository
interface DeviceRepository : JpaRepository<Device, String> {
    fun findByUserId(userId: String): List<Device>
    /** 已绑定设备数：订阅按设备数分档，绑定前要校验是否超限 */
    fun countByUserId(userId: String): Long
    /** 在线设备数（看板指标）；注意在线态由 DeviceService 惰性超时兜底，此处为近似计数 */
    fun countByOnlineStatus(onlineStatus: String): Long

    /** 按硬件序列号查设备（跨用户）：用于"孤儿设备回收"与重绑复用同一条台账 */
    fun findByDeviceSn(deviceSn: String): List<Device>

    /**
     * 局域网发现：取出指定时刻之后仍有 HTTP 活动的全部设备。
     *
     * 此处只按 `last_active_at` 做范围过滤（同网段等其余条件在 Service 层做，行数已经很少），
     * 避免把"网段比对"这种无法走索引的运算下沉成全表扫 —— 这张表随设备与时间持续增长。
     */
    fun findByLastActiveAtGreaterThan(since: Long): List<Device>
}

@Repository
interface EnrollmentSessionRepository : JpaRepository<EnrollmentSession, String> {
    /*
     * 这里刻意**没有**"按短码抢占会话"的条件 UPDATE。
     *
     * 曾经有过，并且它制造了一个线上缺陷：`@Modifying` 的批量 UPDATE 绕过持久化上下文，
     * 数据库里 state 已变成 BOUND，但同一个事务里缓存的实体仍是 PENDING；
     * 随后为回写 device_id 而执行的 save() 在提交时做脏检查，把整行写回，
     * 于是 state 被覆盖回 PENDING —— 设备绑上了，家长端却永远停在"等待孩子扫码"。
     *
     * 现在状态迁移只在实体上做，排他性由 [EnrollmentSession.revision]（@Version）保证：
     * 两个孩子端同时提交同一短码时，后提交者的 `update ... where id=? and revision=?`
     * 影响行数为 0，触发乐观锁冲突并回滚整笔事务。写入路径只剩一条，不可能再互相覆盖。
     */

    fun findByPairingCode(code: String): EnrollmentSession?

    /** 家长名下最近一条仍处于 PENDING 的会话（同一家长同时只应有一个进行中的接入） */
    fun findFirstByUserIdAndStateOrderByCreatedAtDesc(userId: String, state: String): EnrollmentSession?

    /** 家长名下最近一条会话（无论状态）：家长端查询接入进展的兜底入口 */
    fun findFirstByUserIdOrderByCreatedAtDesc(userId: String): EnrollmentSession?

    fun existsByPairingCode(code: String): Boolean

    /**
     * 清理已结束的历史会话（保留最近 [com.padguard.server.service.EnrollmentService] 定义的窗口）。
     *
     * 会话不清理会让短码唯一约束的碰撞概率随存量单调上升，最终表现为"生成接入码失败"。
     */
    // flushAutomatically：删除前先把上下文中未落库的会话变更写下去，
    // 否则"刚被标记为终态、尚未 flush"的会话会躲过这次清理条件。
    @Modifying(flushAutomatically = true)
    @Query("delete from EnrollmentSession s where s.state <> 'PENDING' and s.createdAt < :before")
    fun purgeTerminalBefore(@Param("before") before: Long): Int
}

@Repository
interface PolicyRepository : JpaRepository<Policy, String> {
    fun findFirstByDeviceIdOrderByVersionDesc(deviceId: String): Policy?
}

@Repository
interface CommandRepository : JpaRepository<Command, String> {
    fun findByMsgId(msgId: String): Command?

    /** ack 状态机（S6）：msgId + deviceId 双键匹配，跨设备伪造的回执查不到记录 */
    fun findByMsgIdAndDeviceId(msgId: String, deviceId: String): Command?

    fun findByDeviceIdOrderByCreatedAtDesc(deviceId: String): List<Command>

    /**
     * 指令历史整批清除（设备易主 / 解绑）。
     *
     * 指令里带着截图、锁屏、应用安装等下发内容与回执，跨账号复用时不清除等于把上任家长
     * 管控过这台平板的完整证据链交给下一任；同时也是本项目行数增长最快的表之一。
     */
    @Modifying
    @Query("delete from Command c where c.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 轮询下发专用：按创建时间**升序**（FIFO）。
     * 降级轮询通道必须保证先到先执行，避免用 Desc 查询时旧指令被新指令挤出批次上限。
     *
     * 注意：仅用于「确实需要该设备全部指令」的场景（如后台排查）。
     * 高频的轮询下发请走 [findPendingForPolling]——它在 SQL 层完成过滤与截断。
     */
    fun findByDeviceIdOrderByCreatedAtAsc(deviceId: String): List<Command>

    /**
     * 轮询下发（高频路径）：在 SQL 层完成「状态 + 未过期 + 游标」过滤并截断批次。
     *
     * 早期是 [findByDeviceIdOrderByCreatedAtAsc] 全量捞出该设备**全部历史指令**再在
     * 内存里 filter——指令表只增不删，孩子端每 3 秒轮询一次，随使用时间推移
     * 单次查询要扫描的行数持续线性增长，直接拖垮整个连接池（表现为家长端操作全部变慢）。
     * 配合 idx_commands_poll 复合索引，这里退化为一次索引范围扫描。
     */
    @Query(
        """
        select c from Command c
        where c.deviceId = :deviceId
          and c.status = :status
          and c.expiresAt > :now
          and c.createdAt >= :cursor
        order by c.createdAt asc
        """
    )
    fun findPendingForPolling(
        @Param("deviceId") deviceId: String,
        @Param("status") status: String,
        @Param("now") now: Long,
        @Param("cursor") cursor: Long,
        pageable: Pageable
    ): List<Command>

    /**
     * 过期回收：只取「仍是 PENDING 且已过期」的指令。
     * 语义与 [findPendingForPolling] 互补（未过期 vs 已过期），同样避免全量捞取。
     */
    @Query(
        """
        select c from Command c
        where c.deviceId = :deviceId
          and c.status = :status
          and c.expiresAt <= :now
        order by c.createdAt asc
        """
    )
    fun findExpiredPending(
        @Param("deviceId") deviceId: String,
        @Param("status") status: String,
        @Param("now") now: Long
    ): List<Command>
}

@Repository
interface UsageLogRepository : JpaRepository<UsageLog, String> {
    fun existsByLogId(logId: String): Boolean
    fun findByDeviceId(deviceId: String): List<UsageLog>
    fun findByDeviceIdAndTimestampBetween(deviceId: String, start: Long, end: Long): List<UsageLog>

    /**
     * 批量幂等查重：一次查询取回本批日志中已存在的 logId。
     *
     * 替代逐条 [existsByLogId]：孩子端是**批量**上报（单次可达上百条），
     * 逐条查重等于上百次 DB 往返，是"上报日志要 7 秒"的直接成因。
     * 只投影 logId 一列，不把 payload_json 大文本加载进内存。
     */
    @Query("select l.logId from UsageLog l where l.logId in :logIds")
    fun findExistingLogIds(@Param("logIds") logIds: Collection<String>): Set<String>

    /**
     * 逐行幂等 upsert（S8 批写竞态的降级路径）。
     *
     * 常规路径是「一次查重 + 一次批量 saveAll」；但查重与写入之间存在竞态窗口：
     * 同一 logId 的两次并发上报可能都通过查重，后提交者撞 unique 约束导致**整批**失败。
     * 命中约束时降级为本方法逐行 `ON CONFLICT DO NOTHING`（H2 2.2.x 与 PG16 均支持），
     * 只跳过真正已存在的行。仅竞态时触发，不影响常规批量性能。
     */
    @Modifying
    @Transactional
    @Query(
        value = "insert into usage_logs (id, device_id, log_id, type, \"timestamp\", payload_json, received_at) " +
                "values (:id, :deviceId, :logId, :type, :timestamp, :payloadJson, :receivedAt) " +
                "on conflict (log_id) do nothing",
        nativeQuery = true
    )
    fun insertIgnoreConflict(
        @Param("id") id: String,
        @Param("deviceId") deviceId: String,
        @Param("logId") logId: String,
        @Param("type") type: String?,
        @Param("timestamp") timestamp: Long?,
        @Param("payloadJson") payloadJson: String?,
        @Param("receivedAt") receivedAt: Long
    ): Int

    /**
     * 设备易主（孤儿回收 / 跨账号复用）或解绑时，清掉该设备的全部历史用量日志，
     * 避免新账号继承上一任记录。
     *
     * 走 JPQL 批量 delete：派生删除会先把该设备全部日志 SELECT 进内存再逐条 DELETE，
     * 用量日志是本库行数最多的表，逐条删会让"绑定"这一步卡成一次超长事务。
     */
    @Modifying
    @Query("delete from UsageLog l where l.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 保留期清理：删除早于 [threshold]（事件时间戳，epoch 毫秒）的用量日志。
     * 用量日志只增不删、体积最大，不清理会无限膨胀，也违背"不该长期保存历史"的诉求。
     * 配合 [com.padguard.server.config.SchemaIndexInitializer] 上的 `idx_usage_logs_ts` 索引，
     * 该 DELETE 走时间戳范围扫描，不会全表扫。
     */
    fun deleteByTimestampBefore(threshold: Long): Int
}

@Repository
interface AuditLogRepository : JpaRepository<AuditLog, String> {
    fun findByUserIdAndCreatedAtBetween(userId: String, start: Long, end: Long): List<AuditLog>
    fun findByDeviceIdAndCreatedAtBetween(deviceId: String, start: Long, end: Long): List<AuditLog>
    /** 后台审计视图：全量按时间倒序（数据量可控，取前 N 在 Service 层截断） */
    fun findAllByOrderByCreatedAtDesc(): List<AuditLog>

    /**
     * 分页查询（倒序 + 可选 action 过滤），供家长端审计页面使用。
     *
     * 早期实现是把该设备/用户的**全部**审计日志捞进内存再 sortedByDescending + drop + take：
     * 审计日志只增不删，这个"分页"会随数据增长越来越慢，且内存占用不可控。
     * 这里改为数据库侧排序 + 真正的分页（LIMIT/OFFSET），只取回当前页。
     */
    fun findByDeviceIdOrderByCreatedAtDesc(deviceId: String, pageable: Pageable): List<AuditLog>

    fun findByDeviceIdAndActionOrderByCreatedAtDesc(
        deviceId: String, action: String, pageable: Pageable
    ): List<AuditLog>

    fun findByUserIdOrderByCreatedAtDesc(userId: String, pageable: Pageable): List<AuditLog>

    fun findByUserIdAndActionOrderByCreatedAtDesc(
        userId: String, action: String, pageable: Pageable
    ): List<AuditLog>
}

@Repository
interface UrlCategoryRepository : JpaRepository<UrlCategory, String>

@Repository
interface SensitiveWordRepository : JpaRepository<SensitiveWord, String> {
    fun findByWord(word: String): SensitiveWord?
}

@Repository
interface DeviceEventRepository : JpaRepository<DeviceEvent, String> {
    fun existsByEventId(eventId: String): Boolean
    fun findByDeviceIdAndTimestampBetween(deviceId: String, start: Long, end: Long): List<DeviceEvent>

    @Modifying
    @Query("delete from DeviceEvent e where e.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 批量查重（与 [UsageLogRepository.findExistingLogIds] 同款）：
     * 只投影 eventId 一列，避免逐条 exists 造成的上百次 DB 往返。
     */
    @Query("select e.eventId from DeviceEvent e where e.eventId in :eventIds")
    fun findExistingEventIds(@Param("eventIds") eventIds: Collection<String>): Set<String>

    /**
     * 逐行幂等 upsert（S8，与 [UsageLogRepository.insertIgnoreConflict] 同款）：
     * event_id 竞态并发上报时整批撞 unique 约束，降级为逐行 ON CONFLICT DO NOTHING。
     */
    @Modifying
    @Transactional
    @Query(
        value = "insert into device_events (id, device_id, event_id, type, \"level\", detail_json, \"timestamp\") " +
                "values (:id, :deviceId, :eventId, :type, :level, :detailJson, :timestamp) " +
                "on conflict (event_id) do nothing",
        nativeQuery = true
    )
    fun insertIgnoreConflict(
        @Param("id") id: String,
        @Param("deviceId") deviceId: String,
        @Param("eventId") eventId: String,
        @Param("type") type: String?,
        @Param("level") level: String?,
        @Param("detailJson") detailJson: String?,
        @Param("timestamp") timestamp: Long?
    ): Int
}
