package com.padguard.server.repository

import com.padguard.server.domain.*
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
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

    /**
     * 悲观行锁读，专供"令牌版本自增"使用。
     *
     * 登出 / 后台重置密码都是「读版本 → +1 → 写回」，并发点两次登出时两个事务读到同一个旧值、
     * 后写覆盖先写，第二次自增被吞掉 —— 表面上无害（版本仍比旧令牌大），
     * 但同一账号在多端同时登出时会让"最后一次登出前刚签发的令牌"漏过吊销。
     * 行锁把自增串行化，与订阅续费（[SubscriptionRepository.findByUserIdForUpdate]）同口径。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): User?

    /**
     * 后台家长列表：过滤与分页全部下推数据库。
     *
     * 改造前是 `findAll()` 取全表 → 内存过滤 → 内存分页，且每一行还要单独查一次订阅
     * 和一次设备数（N+1）。家长数到几千时，一次翻页要拉全表 + 上万次查询，
     * 后台在业务高峰期会直接把服务器拖慢，而运营只是翻了一页。
     *
     * 参数用空串而不是 null 表示"不过滤"：JPQL 里 `:param is null` 在部分数据库上
     * 无法推断参数类型（PostgreSQL 会报 could not determine data type），
     * 空串比较则总是安全的。
     *
     * 套餐过滤用 exists 子查询而非 join：一个家长理论上只有一条订阅，
     * exists 不会因数据异常（历史重复订阅）把同一家长放大成多行。
     */
    @Query(
        """
        select u from User u
        where (:status = '' or coalesce(u.status, 'active') = :status)
          and (
            :keyword = ''
            or lower(u.phone) like :kw
            or lower(coalesce(u.nickname, '')) like :kw
            or lower(u.id) like :kw
          )
          and (
            :planMode = ''
            or (:planMode = 'free' and not exists (
                  select 1 from Subscription s where s.userId = u.id
                    and upper(s.plan) in ('MONTHLY', 'QUARTERLY', 'YEARLY', 'PERMANENT')))
            or (:planMode = 'monthly' and exists (
                  select 1 from Subscription s where s.userId = u.id
                    and upper(s.plan) in ('MONTHLY', 'QUARTERLY')))
            or (:planMode = 'yearly' and exists (
                  select 1 from Subscription s where s.userId = u.id and upper(s.plan) = 'YEARLY'))
            or (:planMode = 'permanent' and exists (
                  select 1 from Subscription s where s.userId = u.id and upper(s.plan) = 'PERMANENT'))
          )
        order by u.createdAt desc
        """
    )
    fun searchParents(
        @Param("status") status: String,
        @Param("keyword") keyword: String,
        @Param("kw") kw: String,
        @Param("planMode") planMode: String,
        pageable: Pageable
    ): Page<User>

    /**
     * 按状态计数，**NULL 视作 active**。
     *
     * 仪表盘原来是把全表读进内存后 `count { (it.status ?: "active") == ... }`。
     * 迁移到这里时最容易被忽略的一点：派生查询 `countByStatus("active")` 与内存口径**不等价** ——
     * `status` 是后加的列，存量行是 NULL，SQL 里 `status = 'active'` 不会命中 NULL。
     * 直接用会得到"家长总数 1000、正常 980、冻结 0"这种自相矛盾的看板
     * （差的 20 个既不算正常也不算冻结），且只在有历史数据的库上出现。
     */
    @Query("select count(u) from User u where coalesce(u.status, 'active') = :status")
    fun countByStatusOrNullAsActive(@Param("status") status: String): Long

    /** 区域分布：按 region 分组计数（NULL / 空串由调用方归并为"未填写"） */
    @Query("select u.region, count(u) from User u group by u.region")
    fun countGroupedByRegion(): List<Array<Any?>>

    /**
     * 指定时刻之后创建的账号，**只取创建时间这一列**。
     *
     * 仪表盘的"近 7 天新增"在 Kotlin 侧按自然日分桶（时区口径必须服务端说了算，
     * 不能交给数据库的日期函数），但只需要时间戳本身。拉整行实体比拉一个 bigint 宽十几倍，
     * 而这些行随即被丢弃。
     */
    @Query("select u.createdAt from User u where u.createdAt >= :since")
    fun findCreatedAtSince(@Param("since") since: Long): List<Long>
}

@Repository
interface DeviceRepository : JpaRepository<Device, String> {
    fun findByUserId(userId: String): List<Device>
    /** 已绑定设备数：订阅按设备数分档，绑定前要校验是否超限 */
    fun countByUserId(userId: String): Long
    /** 在线设备数（看板指标）；注意在线态由 DeviceService 惰性超时兜底，此处为近似计数 */
    fun countByOnlineStatus(onlineStatus: String): Long

    /** 某在线态的全部设备（在线态扫描 / 启动预热用，见 PresenceMonitorService） */
    fun findByOnlineStatus(onlineStatus: String): List<Device>

    /**
     * 处于指定在线态、且最后在线时间早于给定时刻的设备。
     *
     * 脱管告警用它把"已持续离线一段时间"的设备筛出来，
     * 避免把全表设备拉进内存再逐条比较（设备表只增不减）。
     */
    fun findByOnlineStatusAndLastOnlineAtLessThan(onlineStatus: String, ts: Long): List<Device>

    /** 按硬件序列号查设备（跨用户）：用于"孤儿设备回收"与重绑复用同一条台账 */
    fun findByDeviceSn(deviceSn: String): List<Device>

    /**
     * 局域网发现：取出指定时刻之后仍有 HTTP 活动的全部设备。
     *
     * 此处只按 `last_active_at` 做范围过滤（同网段等其余条件在 Service 层做，行数已经很少），
     * 避免把"网段比对"这种无法走索引的运算下沉成全表扫 —— 这张表随设备与时间持续增长。
     */
    fun findByLastActiveAtGreaterThan(since: Long): List<Device>

    /**
     * 悲观行锁读设备。用作"同一台设备的策略写入"的互斥量。
     *
     * 策略保存是「读最新版本 → +1 → 插入新行」的读改写：两个并发请求（家长连点开关、
     * 端上模板应用与设置保存撞车）会读到同一个旧版本、插出两条同号策略行，
     * `findFirstByDeviceIdOrderByVersionDesc` 取到哪条全凭数据库返回顺序 ——
     * 表现就是"改了设置但孩子端没变"，且没有任何报错。锁住设备行即可把这段串行化，
     * 锁在事务提交时自动释放，不依赖应用层锁（多实例部署同样成立）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): Device?

    /**
     * 按家长批量统计设备数（后台家长列表用）。
     *
     * 逐行 `countByUserId` 是 N+1：一页 20 个家长就是 20 次往返，
     * 加上每行还要查一次订阅，翻一页 40 次查询只为了显示两列数字。
     */
    @Query("select d.userId, count(d) from Device d where d.userId in :userIds group by d.userId")
    fun countGroupByUserIds(@Param("userIds") userIds: Collection<String>): List<Array<Any>>

    /**
     * 后台设备列表：关键字（设备号 / 家长手机号 / 家长昵称）与在线态均下推数据库。
     *
     * 改造前 `findAll()` 全表 + 内存过滤：设备表随绑定只增不减，
     * 一次翻页要把几万台设备读进堆里，还要为每台设备在内存里比对家长信息。
     *
     * 在线态的判定口径必须与 [com.padguard.server.service.online] 完全一致
     * （`ONLINE` + 最近心跳在 40 秒内），否则会出现"列表筛选在线，结果里却有离线设备"。
     */
    @Query(
        """
        select d from Device d
        where (
            :keyword = ''
            or lower(d.id) like :kw
            or exists (
                select 1 from User u where u.id = d.userId
                  and (lower(u.phone) like :kw or lower(coalesce(u.nickname, '')) like :kw))
          )
          and (
            :onlineMode = ''
            or (:onlineMode = '1' and d.onlineStatus = 'ONLINE'
                and d.lastOnlineAt is not null and d.lastOnlineAt >= :staleCut)
            or (:onlineMode = '0' and (
                d.onlineStatus is null or d.onlineStatus <> 'ONLINE'
                or d.lastOnlineAt is null or d.lastOnlineAt < :staleCut))
          )
        order by d.createdAt desc
        """
    )
    fun searchDevices(
        @Param("keyword") keyword: String,
        @Param("kw") kw: String,
        @Param("onlineMode") onlineMode: String,
        @Param("staleCut") staleCut: Long,
        pageable: Pageable
    ): Page<Device>

    /**
     * 在线设备数（与 [com.padguard.server.service.online] 同一口径）。
     *
     * 仪表盘原来读全表设备再逐条比对心跳，只为得到这一个数字。
     * `lastOnlineAt is not null` 不能省：NULL 在 SQL 里参与 `>=` 比较恒为 UNKNOWN，
     * 不加也恰好不会命中，但显式写出来是为了让"离线设备一定不计入"这件事可被审查。
     */
    @Query(
        "select count(d) from Device d where d.onlineStatus = 'ONLINE' " +
            "and d.lastOnlineAt is not null and d.lastOnlineAt >= :staleCut"
    )
    fun countOnlineSince(@Param("staleCut") staleCut: Long): Long

    /** 指定时刻之后创建的设备，只取创建时间（仪表盘趋势分桶用，见 UserRepository 同名方法） */
    @Query("select d.createdAt from Device d where d.createdAt >= :since")
    fun findCreatedAtSince(@Param("since") since: Long): List<Long>
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

    /**
     * 该设备的下一个策略版本号（`max(version) + 1`，空表为 1）。
     *
     * 由 [com.padguard.server.service.PolicyVersions.allocate] 在持有设备行锁后调用，
     * 因此"读 max"与"插入新行"之间不会有第二个写入者插进来。
     */
    @Query("select coalesce(max(p.version), 0) + 1 from Policy p where p.deviceId = :deviceId")
    fun nextVersion(@Param("deviceId") deviceId: String): Int

    /**
     * 策略包按 deviceId 归属，易主 / 解绑时必须清除：
     * 否则旧家庭的策略包（版本号更高）会在新家庭首次 rebuild 之前
     * 被 findFirstByDeviceIdOrderByVersionDesc 命中，把上任家庭的管控配置
     * （minorMode、curfew 等）短暂下发给新孩子。
     */
    @Modifying
    @Query("delete from Policy p where p.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
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
     *
     * 必须显式 @Transactional：派生 delete 方法不会自动获得事务，调用方
     * UsageLogCleanup 也没有事务边界，否则运行期 TransactionRequiredException
     * 被吞成 warn，用量日志永远不会被清理。
     */
    @Transactional
    fun deleteByTimestampBefore(threshold: Long): Int
}

@Repository
interface AuditLogRepository : JpaRepository<AuditLog, String> {
    fun findByUserIdAndCreatedAtBetween(userId: String, start: Long, end: Long): List<AuditLog>
    fun findByDeviceIdAndCreatedAtBetween(deviceId: String, start: Long, end: Long): List<AuditLog>
    /**
     * 后台审计视图：全量按时间倒序。
     *
     * **仅用于确实需要全部记录的场景**。后台列表请走下面的分页重载 ——
     * 审计日志是全项目增长最快的表之一，把它整表读进内存只为了展示最近 200 条，
     * 会让后台在运营一段时间后越来越慢，且慢得没有任何征兆。
     */
    fun findAllByOrderByCreatedAtDesc(): List<AuditLog>

    /**
     * 后台审计列表：数据库侧倒序 + LIMIT，只取回要展示的那一页。
     *
     * 返回 [Page] 而不是 [List]：`AuditPageDto` 要如实告诉前端"总共匹配多少条、
     * 是不是被截断了"，这需要 count，而 count 必须在过滤条件下算，不能拿"页大小"充数。
     */
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<AuditLog>

    /**
     * 按动作集合过滤 + 倒序 + LIMIT。
     *
     * 领域（parents / devices / ...）在库里没有单独一列，它是由 action 派生的。
     * 想在 SQL 里按领域过滤，只能把领域反向展开成 action 列表（见 `auditActionsOf`）。
     * 这正是把 `resolveAuditAction` 从 when 改成表驱动的原因：正反查同源，不会漂移。
     */
    fun findByActionInOrderByCreatedAtDesc(actions: Collection<String>, pageable: Pageable): Page<AuditLog>

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
