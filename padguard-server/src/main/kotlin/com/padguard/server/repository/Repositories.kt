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
     * 记录一次密码错误：**单条 UPDATE 完成「自增 / 达阈值则加锁并清零」**。
     *
     * 为什么不能写成「读出来 +1 再 save」：并发的多次错误会读到同一个旧值，后写覆盖先写，
     * 攻击者只要并发发请求就能让计数涨得比实际慢（甚至一直停在 1），阈值形同虚设。
     * 单条 UPDATE 让数据库行锁把自增串行化，这是计数类字段唯一可靠的做法。
     *
     * 两个 CASE 都必须用 coalesce 兜底：列是后加的，存量行为 NULL，
     * 而 `NULL + 1` 在 SQL 里仍是 NULL —— CASE 条件恒不成立、赋值也恒为 NULL，
     * 表现为「计数永远是 0、锁定永远不触发」，且只在有历史数据的库上出现。
     *
     * @return 受影响行数（手机号不存在时为 0）
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update User u set
          u.loginFailCount = case when coalesce(u.loginFailCount, 0) + 1 >= :maxAttempts then 0
                                 else coalesce(u.loginFailCount, 0) + 1 end,
          u.loginLockedUntil = case when coalesce(u.loginFailCount, 0) + 1 >= :maxAttempts then :lockUntil
                                   else u.loginLockedUntil end
        where u.phone = :phone
        """
    )
    fun registerLoginFailure(
        @Param("phone") phone: String,
        @Param("maxAttempts") maxAttempts: Int,
        @Param("lockUntil") lockUntil: Long
    ): Int

    /** 登录成功后清空失败计数与锁定 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.loginFailCount = 0, u.loginLockedUntil = null where u.phone = :phone")
    fun clearLoginFailure(@Param("phone") phone: String): Int

    /**
     * 只更新最近登录时刻。
     *
     * 不能再用 `save(user)`：那是把调用方**加载时刻的整行快照**写回数据库。
     * 若在这两个动作之间该账号在另一端点了登出（`token_version` +1），
     * 旧快照会把版本号写回旧值 —— 已吊销的令牌重新生效，登出等于没登出。
     * 单列 UPDATE 只碰这一列，不会覆盖任何并发写入。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.lastLoginAt = :ts where u.id = :id")
    fun touchLastLoginAt(@Param("id") id: String, @Param("ts") ts: Long): Int

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

    /** 权益闸门状态翻转（ACTIVE/SUSPENDED）：只碰一列，绝不整行 merge 覆盖并发绑定写回的 user_id/token */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update Device d set d.entitlementState = :state where d.id = :deviceId")
    fun updateEntitlementState(@Param("deviceId") deviceId: String, @Param("state") state: String): Int

    /**
     * 条件清除远程锁屏标记：只在当前确为锁定时命中，防止并发窗口内家长刚好手动解锁后
     * 被权益闸门"代理解锁"再发一条多余指令的竞态（条件 UPDATE 天然幂等）。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update Device d set d.remoteLocked = false where d.id = :deviceId and d.remoteLocked = true")
    fun clearRemoteLockedIfSet(@Param("deviceId") deviceId: String): Int

    /**
     * 撤回授权置位走 ComplianceService 内「load 实体 → 改 consentRevoked → save」语义，
     * 复用 FakeJpaRepository 既有 save；不在此声明 @Modifying 抽象方法，
     * 以免扩散到十余个测试桩（W2 已标记的 fake-implements-whole-repository 技术债）。
     * 该标志为低频用户动作（撤回/恢复授权），整行 save 的并发覆盖风险可接受，
     * 与 confirmAccountDeletion 对已注销账号的匿名化写法保持一致。
     */
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
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

    /**
     * 过期收敛专用 CAS（阶段 1 体检 F01）：只把**此刻仍是 PENDING** 的指令改成终态。
     *
     * [ExpiredCommandSweeper] 原先查出一快照后改内存实体再 `saveAll`：
     * 两次查询之间若回执（MQTT / HTTP 双通道）已把指令置为 SUCCESS，
     * 旧快照会把它覆盖回 EXPIRED，家长端看到「执行成功又变过期」。
     * 条件 UPDATE 把「读」与「写」合成一条原子语句，返回影响行数让调用方判断
     * 是否真的由自己完成了收敛（只有成功的那条才允许连带收敛截图记录）。
     *
     * 与下方补发刷新同一套写法：显式 `@Transactional`（Spring Data 不会自动为
     * `@Modifying` 开启写事务）+ `flushAutomatically/clearAutomatically`，
     * 避免持久化上下文里的旧实体在后续查询中被读回。
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        update Command c
           set c.status = :expired
         where c.id = :id
           and c.status = :pending
        """
    )
    fun markExpiredIfPending(
        @Param("id") id: String,
        @Param("pending") pending: String,
        @Param("expired") expired: String
    ): Int

    /**
     * 原子落地一条设备回执：仅当指令**仍处于非终态**时才写入，返回影响行数。
     *
     * 与 [markExpiredIfPending] 是对称的两侧：sweeper 用 `status = PENDING` 抢占过期结论，
     * 回执用 `status not in 终态` 抢占执行结论，两边都靠数据库行锁裁决，
     * 谁先提交谁赢，输家影响 0 行。改造前 `applyAck` 走「读实体 → 改字段 → save(整行)」，
     * 有两个真实后果：
     * 1. 双通道（MQTT + HTTP 长轮询）并发回执时，后提交的旧快照把先提交的 SUCCESS 覆盖回 FAILED；
     * 2. 更严重的是能把 sweeper 刚写入的 **EXPIRED 复活成 SUCCESS** —— 两个线程都读到 PENDING，
     *    sweeper 先置 EXPIRED，回执随后 merge 整行，家长端看到「已过期又执行成功」。
     *
     * 错误码 / 描述的合并由调用方按旧规则算好再传入（回执不带错误时沿用读到的旧值），
     * 保证单线程语义与改造前逐字一致；并发下由本条件更新决定唯一赢家。
     *
     * 条件刻意与调用方的读取键**同组**：`msgId + deviceId`（`msg_id` 带 unique 约束）。
     * 只按 msgId 会让别的设备伪造回执，按内部主键 `id` 则多一次「读出来的 id 是否可靠」
     * 的假设；双键与 `findByMsgIdAndDeviceId` 完全对齐，`deviceId` 由鉴权注入不可伪造。
     *
     * `clearAutomatically` 必须有：批量 UPDATE 绕过一级缓存，调用方在更新后只读
     * `cmd.type` 等已加载字段，不清会在同一事务内读回旧 status。
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        update Command c
           set c.status = :status,
               c.executedAt = :executedAt,
               c.errorCode = :errorCode,
               c.errorMessage = :errorMessage
         where c.msgId = :msgId
           and c.deviceId = :deviceId
           and c.status not in :terminal
        """
    )
    fun applyAckIfOpen(
        @Param("msgId") msgId: String,
        @Param("deviceId") deviceId: String,
        @Param("terminal") terminal: Collection<String>,
        @Param("status") status: String,
        @Param("executedAt") executedAt: Long?,
        @Param("errorCode") errorCode: String?,
        @Param("errorMessage") errorMessage: String?
    ): Int

    // ==================== W2：离线指令补发 ====================

    /**
     * 待补发指令：已过 5 分钟有效期、但仍在补发保留期内、次数未达上限、且在可补发白名单内。
     *
     * **不能用 [findPendingForPolling] 的游标**：这些指令的 createdAt 可能远早于终端
     * 「上次拉取」游标，带 `createdAt >= cursor` 会把离线期间的全部指令再次过滤掉，
     * 补发就成了空转。因此这里按 device + status + deadline 独立查询，按 createdAt 升序
     * 保证先下发的先补发（FIFO），并由 Pageable 在 SQL 层限流。
     *
     * 白名单由调用方传入（配置化），空集合时调用方必须直接跳过——JPQL `in ()` 在
     * 部分数据库上会生成非法 SQL。
     */
    @Query(
        """
        select c from Command c
        where c.deviceId = :deviceId
          and c.status = :status
          and c.expiresAt <= :now
          and c.redeliveryDeadlineAt > :now
          and c.deliveryCount < :maxAttempts
          and c.type in :types
        order by c.createdAt asc
        """
    )
    fun findRedeliverablePending(
        @Param("deviceId") deviceId: String,
        @Param("status") status: String,
        @Param("now") now: Long,
        @Param("maxAttempts") maxAttempts: Int,
        @Param("types") types: Collection<String>,
        pageable: Pageable
    ): List<Command>

    /**
     * 原子抢占一次补发权：只有**当前仍过期**的那一个请求能把 expires_at 推进到新窗口。
     *
     * 为什么不用「查出来改字段再 save」：孩子端 MQTT 与 HTTP 长轮询是双通道，
     * 设备重连瞬间常有多个轮询并发。两个线程各自 save 会互相覆盖 deliveryCount
     * （lost update），补发次数上限形同虚设，同一条指令也会被重复塞进多个响应。
     * 条件 UPDATE 把「判过期 + 推进窗口 + 计数」收进一条语句，由数据库行锁保证
     * 只有一个线程拿到 1；这是跨实例也成立的做法，不需要再引入一把进程内锁。
     *
     * `clearAutomatically` 必须有：批量 UPDATE 绕过一级缓存，不清的话同一事务内
     * 随后的查询会读回旧 expiresAt，把刚刷新过的指令再当过期指令处理一次。
     */
    /**
     * 必须显式 `@Transactional`：Spring Data **不会**自动为 `@Modifying` 查询开启写事务
     * （本项目 ExtraRepositories 已踩过同坑）。缺了它，`flushAutomatically = true`
     * 会在无事务线程上抛 `No EntityManager with actual transaction available`，
     * 且并发调用路径必现，表现为「补发功能整体不可用」。
     * 调用方已有事务时按 REQUIRED 加入，语义一致。
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        update Command c
           set c.expiresAt = :newExpiresAt,
               c.deliveryCount = c.deliveryCount + 1,
               c.lastDeliveryAt = :now
         where c.id = :id
           and c.status = :status
           and c.expiresAt <= :now
           and c.redeliveryDeadlineAt > :now
        """
    )
    fun tryRefreshForRedelivery(
        @Param("id") id: String,
        @Param("status") status: String,
        @Param("now") now: Long,
        @Param("newExpiresAt") newExpiresAt: Long
    ): Int
}

@Repository
interface UsageLogRepository : JpaRepository<UsageLog, String> {
    fun existsByLogId(logId: String): Boolean
    fun findByDeviceId(deviceId: String): List<UsageLog>
    fun findByDeviceIdAndTimestampBetween(deviceId: String, start: Long, end: Long): List<UsageLog>
    /** 数据导出：按服务端落库时刻倒序取最近 N 条，避免导出接口把百万行一次拉爆。 */
    fun findByDeviceIdOrderByReceivedAtDesc(deviceId: String, pageable: Pageable): List<UsageLog>

    /**
     * 按设备 + 类型拉取上报日志，按时间倒序，配合 [Pageable] 取最近 N 条。
     *
     * 用途：家长端「通知记录」展示孩子端代收的系统通知（type=NOTIFICATION_COLLECTED）。
     * 走 `idx_usage_logs_device_type` 索引下推过滤，避免把全量 payload 读进内存。
     */
    fun findByDeviceIdAndTypeOrderByTimestampDesc(deviceId: String, type: String, pageable: Pageable): List<UsageLog>

    /**
     * 分页读取指定设备、指定时间范围内的**用量类**日志（统计聚合专用）。
     *
     * 存在的唯一理由：`usage_logs` 单设备一个月约 26 万行，一次性 `findBy...Between` 会把
     * 全部 payload 文本读进内存，月视图一开就可能 OOM。这里把「类型过滤」下推到 SQL
     * （走 `idx_usage_logs_device_type`），并允许调用方按页推进、折叠完即丢弃。
     *
     * 必须带 `Pageable` 的显式排序（见 `UsageLogPager.SORT`），否则 OFFSET 分页在无确定顺序时
     * 会重复/漏读行，聚合结果会静默偏差。
     */
    fun findByDeviceIdAndTypeInAndTimestampBetween(
        deviceId: String,
        types: Collection<String>,
        start: Long,
        end: Long,
        pageable: Pageable
    ): List<UsageLog>

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
     * 命中约束时降级为本方法逐行 `ON CONFLICT DO NOTHING`，只跳过真正已存在的行。
     * 仅竞态时触发，不影响常规批量性能。
     *
     * **实测修正（第 4 批）**：原写法 `on conflict (log_id) do nothing` 在真实
     * H2 2.2.224（MODE=PostgreSQL）上抛 JdbcSQLSyntaxErrorException —— H2 不支持带列的
     * 冲突目标，只有 PG 支持。这条降级路径平常不触发，所以本地联调一直没暴露；一旦
     * 真撞上批写竞态，本该救场的语句反而是第二次报错。改成无目标的
     * `on conflict do nothing`，H2 与 PG16 都合法，且本表唯一约束只有主键 + log_id，
     * 冲突处理语义不变。
     */
    @Modifying
    @Transactional
    @Query(
        value = "insert into usage_logs (id, device_id, log_id, type, \"timestamp\", payload_json, received_at) " +
                "values (:id, :deviceId, :logId, :type, :timestamp, :payloadJson, :receivedAt) " +
                "on conflict do nothing",
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UsageLog l where l.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 保留期清理：删除**落库时刻**早于 [threshold] 的行为日志，**但排除
     * [protectedTypes] 里的合规凭证类**（如 AGREEMENT 使用授权协议签署记录）。
     *
     * ## 为什么必须排除
     * `usage_logs` 不是纯用量表，孩子端的 `LogType.AGREEMENT`（说明书 §5.4 合规留存）
     * 也走这条上行通道落在这里。合规凭证的价值恰在"事后可追溯"——若跟用量日志一起
     * 按 30 天 TTL 删掉，设备用满一个月后，"孩子是否签署过授权"就再也查不到，
     * 而家长端仍可能展示「已阅已同意」。清理不该把审计证据清掉。
     *
     * `l.type is null` 必须显式放行：SQL 的 `NOT IN` 对 NULL 结果为 UNKNOWN，
     * 若不加这一支，历史脏数据（type 为空）会永远删不掉，表重新无限膨胀。
     *
     * ## 判据必须是 `received_at` 而不是客户端上报的 `timestamp`（历史缺陷）
     * `timestamp` 来自上报体（`LogItem.timestamp`），是**孩子端可写的设备本地钟**，
     * 服务端从不钳制。用它当清理判据两个方向都会踩：
     * - 设备把时间调到未来（NTP 未同步 / 孩子主动改）→ 这期间所有日志 `timestamp > threshold`，
     *   **每天清理都跳过**，只增不删；`usage_logs` 是全库增长最快的表，写满磁盘会连带全站写入失败，
     *   同时儿童数据超保留期仍在库，合规面更糟；
     * - 设备把时间调到过去 → 上报当天就被当成 30 天前历史删掉，家长端『应用使用记录』凭空缺段；
     * - `timestamp` 为 NULL 时 `NULL < :threshold` 恒为 UNKNOWN，这些行**永远删不掉**。
     *
     * `received_at` 是服务端落库时刻（[com.padguard.server.service.IngestService] 两条写入路径
     * 都显式赋值），设备改不了，且与 [com.padguard.server.service.RetentionCleanup] 的
     * 媒体清理判据同一口径。存量行若 `received_at` 仍为默认 0（该列上线前的历史数据），
     * `0 < threshold` 成立 → 一并清掉，正是期望的"过期即删"。
     *
     * 配合 `idx_usage_logs_received_at` 索引，该 DELETE 走范围扫描，不会全表扫。
     *
     * 必须显式 @Transactional + @Modifying：批量 delete 无事务会抛
     * TransactionRequiredException，且被用法包裹在 runCatching 里只会落成一条 warn，
     * 表现为"清理天天跑、表却一天比一天大"。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
        "delete from UsageLog l where l.receivedAt < :threshold " +
                "and (l.type is null or l.type not in :protectedTypes)"
    )
    fun deleteExpiredExcept(
        @Param("threshold") threshold: Long,
        @Param("protectedTypes") protectedTypes: Collection<String>
    ): Int

    /**
     * 夜清分页第一步：只取主键，按服务端落库时间与受保护类型过滤。
     * 与 [deleteExpiredExcept] 同一 WHERE 口径，但用 Pageable 把单次事务影响行数钉死，
     * 避免百万行一次 DELETE 拖出长事务 / WAL 暴涨。
     */
    @Query(
        "select l.id from UsageLog l where l.receivedAt < :threshold " +
                "and (l.type is null or l.type not in :protectedTypes)"
    )
    fun findExpiredIds(
        @Param("threshold") threshold: Long,
        @Param("protectedTypes") protectedTypes: Collection<String>,
        pageable: Pageable
    ): List<String>

    /** 夜清分页第二步：按主键批量删除。Spring Data 每次调用各自一个小事务。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from UsageLog l where l.id in :ids")
    fun deleteByIds(@Param("ids") ids: Collection<String>): Int

    /** 查询某设备最近一条指定类型的行为日志（用于合规凭证「已阅已同意」回显）。 */
    fun findFirstByDeviceIdAndTypeOrderByTimestampDesc(
        deviceId: String, type: String
    ): UsageLog?
}

@Repository
interface GuardianConsentRepository : JpaRepository<GuardianConsent, String> {
    fun findByUserIdAndDeviceIdOrderByCreatedAtDesc(userId: String, deviceId: String): List<GuardianConsent>
    fun findByUserIdOrderByCreatedAtDesc(userId: String): List<GuardianConsent>
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
interface AppealRepository : JpaRepository<Appeal, String> {
    fun findByUserIdOrderByCreatedAtDesc(userId: String, pageable: Pageable): List<Appeal>
    fun findByUserIdAndId(userId: String, id: String): Appeal?
}

@Repository
interface AccountDeletionRepository : JpaRepository<AccountDeletionRequest, String> {
    fun findByUserIdAndTicket(userId: String, ticket: String): AccountDeletionRequest?
    fun findByUserId(userId: String): List<AccountDeletionRequest>
}

/**
 * 家长登录会话（第三步安全：refresh token 轮换 + 单设备吊销）。
 * jti 唯一索引保证同一 refresh token 只对应一条有效会话；revoked 标记实现单设备踢下线。
 */
@Repository
interface UserSessionRepository : JpaRepository<UserSession, String> {
    fun findByUserIdAndRevokedFalse(userId: String): List<UserSession>
    fun findByRefreshJtiAndRevokedFalse(jti: String): UserSession?
    fun findByUserIdAndIdAndRevokedFalse(userId: String, id: String): UserSession?
    fun countByUserIdAndRevokedFalse(userId: String): Long

    /**
     * 整账号吊销全部会话（登出/改密/后台动作）。条件 UPDATE 只碰 revoked 标记，
     * 不覆盖并发写入的其他列；`flushAutomatically/clearAutomatically` 保证持久化上下文同步。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserSession s set s.revoked = true, s.revokedAt = :at where s.userId = :userId and s.revoked = false")
    fun revokeAllForUser(@Param("userId") userId: String, @Param("at") at: Long): Int

    /**
     * refresh token 原子轮换（防并发重放）。
     *
     * 条件 UPDATE 一次完成「认领旧 jti → 换成新 jti」：WHERE 同时钉住 `refreshJti=旧`、
     * `userId`（顺带做归属校验）与 `revoked=false`。DB 层保证同一枚旧 jti 只能被
     * 成功 UPDATE 一次（第二次影响行数为 0），杜绝"读 jti→校验→改字段→save"的
     * 读改写竞态——后者允许两请求都在 jti 被改前通过校验、各自签出一对新令牌，
     * 令 refresh token 的"单次使用"与单设备踢下线语义失效。
     *
     * 只改 jti/lastRefreshedAt 两列，不碰其它字段（并发登出可能正改 revoked）；
     * `flushAutomatically/clearAutomatically` 让持久化上下文与库同步，避免脏检查写回。
     * 返回值：1=轮换成功可签新令牌；0=旧 jti 已被并发轮换/吊销/归属不符，按失效拒绝。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update UserSession s set s.refreshJti = :newJti, s.lastRefreshedAt = :at " +
        "where s.refreshJti = :oldJti and s.userId = :userId and s.revoked = false")
    fun rotateRefreshJti(
        @Param("oldJti") oldJti: String,
        @Param("newJti") newJti: String,
        @Param("userId") userId: String,
        @Param("at") at: Long
    ): Int
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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
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
     *
     * **冲突目标必须省略**（第 5 批补齐）：第 4 批修 usage_logs / usage_sessions 两处时
     * 漏了这里，H2 2.2.224(MODE=PostgreSQL) 不支持 `on conflict (col)`，导致本该救场的
     * 降级语句自己再抛一次 `JdbcSQLSyntaxErrorException`。PG16 两种写法都支持，
     * 统一用无目标形式；`ScheduledTraceContractTest` 扫描全 repository 防止再漏。
     */
    @Modifying
    @Transactional
    @Query(
        value = "insert into device_events (id, device_id, event_id, type, \"level\", detail_json, \"timestamp\") " +
                "values (:id, :deviceId, :eventId, :type, :level, :detailJson, :timestamp) " +
                "on conflict do nothing",
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
