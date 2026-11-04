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
interface DeviceGroupRepository : JpaRepository<DeviceGroup, String> {
    fun findByOwnerUserId(ownerUserId: String): List<DeviceGroup>
    fun findBySceneType(sceneType: String): List<DeviceGroup>
}

@Repository
interface UploadedFileRepository : JpaRepository<UploadedFile, String> {
    /** 易主 / 解绑清理：先取记录拿 storedPath 删磁盘文件，再删记录（否则留下永远扫不到的孤儿文件）。 */
    fun findByDeviceId(deviceId: String): List<UploadedFile>

    @Modifying
    @Query("delete from UploadedFile f where f.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 保留期清理——取"设备上传且早于阈值"的文件记录。
     * 只清 deviceId 非空的（孩子端截屏/录屏/日志导出）：家长上传的发布素材/APK
     * 由 PublishedMessage / AppInstaller 引用，不在清理范围。
     */
    @Query("select f from UploadedFile f where f.deviceId is not null and f.createdAt < :ts")
    fun findDeviceFilesCreatedBefore(@Param("ts") ts: Long): List<UploadedFile>
}

@Repository
interface ScreenshotRepository : JpaRepository<Screenshot, String> {
    fun findByDeviceIdOrderByCapturedAtDesc(deviceId: String, pageable: Pageable): List<Screenshot>
    fun findFirstByDeviceIdAndShotId(deviceId: String, shotId: String): Screenshot?

    /**
     * 设备易主 / 解绑时整批清除该设备的截图记录。
     *
     * 走 JPQL 批量 delete（而非 Spring Data 派生删除）：后者要先 SELECT 出全部实体再逐条 DELETE，
     * 截图是本项目体积最大的私有数据，逐条删会在易主瞬间拖出一次长事务。
     */
    @Modifying
    @Query("delete from Screenshot s where s.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 保留期清理——先取出早于阈值的记录（要按 fileUrl 删对应文件），
     * 再由调用方删文件 + 删记录。截图记录本身很小，但 fileUrl 指向的 jpg 不删
     * 就会在 uploads 里无限堆积（家长端『看屏』每点一次就是一张）。
     */
    @Query("select s from Screenshot s where s.receivedAt < :ts")
    fun findReceivedAtBefore(@Param("ts") ts: Long): List<Screenshot>
}

@Repository
interface LocationTrackRepository : JpaRepository<LocationTrack, String> {
    fun findByDeviceIdOrderByTsDesc(deviceId: String, pageable: Pageable): List<LocationTrack>

    /** 全量轨迹时间线：取最近 [since]（毫秒时间戳）之后的定位点，按时间倒序。 */
    fun findByDeviceIdAndTsGreaterThanEqualOrderByTsDesc(
        deviceId: String, since: Long, pageable: Pageable
    ): List<LocationTrack>

    /** 移动轨迹属儿童行踪隐私，设备易主 / 解绑时必须随设备一起清除。 */
    @Modifying
    @Query("delete from LocationTrack t where t.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /** 仅更新单条轨迹的文字地址，供 [com.padguard.server.service.LocationAddressBackfill] 异步回填，不触动经纬度/时间。 */
    @Modifying
    @Query("update LocationTrack t set t.address = :address where t.id = :id")
    fun updateAddressById(@Param("id") id: String, @Param("address") address: String)

    /**
     * 定位轨迹保留期清理。
     * 轨迹 288 行/天/设备只增不删（~2.1GB/年/设备），是库里第二大增长源；
     * 且属儿童行踪隐私，保留 90 天足够家长回看，超期应当清除。
     * ts 可空（历史行可能缺定位时间戳），用 coalesce 回落 receivedAt。
     */
    /**
     * 保留期清理。
     *
     * 必须显式 @Transactional：`@Modifying` 查询方法不会由 Spring Data 自动开启事务，
     * 调用方（RetentionCleanup）也没有事务边界，否则运行期抛 TransactionRequiredException
     * 被 runCatching 吞成一条 warn —— 表现为"清理任务每天都跑，但表永远不减少"，
     * 最终写满磁盘导致 PG 拒写、全站瘫痪。
     */
    @Modifying
    @Transactional
    @Query("delete from LocationTrack t where coalesce(t.ts, t.receivedAt) < :ts")
    fun deleteByTsBefore(@Param("ts") ts: Long): Int
}

@Repository
interface AlertRepository : JpaRepository<Alert, String> {
    fun findByUserIdAndStatus(userId: String, status: String, pageable: Pageable): List<Alert>
    fun findByUserId(userId: String, pageable: Pageable): List<Alert>
    fun countByUserIdAndStatus(userId: String, status: String): Long
    /** 后台看板用：全量未处置告警数（不限定用户） */
    fun countByStatus(status: String): Long
    fun findByDeviceIdOrderByTriggeredAtDesc(deviceId: String): List<Alert>

    /**
     * 后台告警列表：只取最近 500 条。
     *
     * 告警表只增不减（每条异常一次），`findAll()` 会把整张表读进内存再逐条关联家长。
     * 上线半年后这个接口的耗时会随数据量线性增长，直到把后台拖垮 ——
     * 而运营真正关心的永远只是"最近发生了什么"。上限写死在仓储方法名里，
     * 避免调用方以后又加一条不带 limit 的路径。
     */
    fun findTop500ByOrderByTriggeredAtDesc(): List<Alert>

    /**
     * 按「用户 + 设备」过滤（可选状态）。
     * 供告警列表页使用：早期是查出该用户全部告警后在内存里 filter deviceId，
     * 会导致分页返回的条数不足一页（过滤发生在分页之后），且随告警累积越来越慢。
     */
    fun findByUserIdAndDeviceIdAndStatus(
        userId: String, deviceId: String, status: String, pageable: Pageable
    ): List<Alert>

    fun findByUserIdAndDeviceId(userId: String, deviceId: String, pageable: Pageable): List<Alert>

    /** 统计看板用：直接数出该用户在某设备上的未处置告警数，避免拉回全量行再 count */
    fun countByUserIdAndStatusAndDeviceId(userId: String, status: String, deviceId: String): Long

    /** 设备易主 / 解绑时清除该设备的告警，避免下一任家长看到上一任的告警历史。 */
    @Modifying
    @Query("delete from Alert a where a.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface PublishedMessageRepository : JpaRepository<PublishedMessage, String> {
    fun findByDeviceIdOrderByPublishedAtDesc(deviceId: String, pageable: Pageable): List<PublishedMessage>

    @Modifying
    @Query("delete from PublishedMessage m where m.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface GeofenceZoneRepository : JpaRepository<GeofenceZone, String> {
    /** 一台设备下的全部围栏，按创建时间正序（保证列表稳定、绘制顺序一致）。 */
    fun findByDeviceIdOrderByCreatedAtAsc(deviceId: String): List<GeofenceZone>

    /**
     * 围栏按 deviceId 归属。易主时必须删除：把"上一个孩子的学校"留成新孩子的围栏，
     * 会让新家长收到完全错误的越界告警。重绑后由家长重新配置。
     */
    @Modifying
    @Query("delete from GeofenceZone g where g.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface UnlockTicketRepository : JpaRepository<UnlockTicket, String> {
    fun findByIdAndDeviceId(id: String, deviceId: String): UnlockTicket?
    fun findByDeviceIdAndStatus(deviceId: String, status: String): List<UnlockTicket>

    /** 未结工单会一直占着家长端"待审批"列表，易主 / 解绑时连同设备一起作废。 */
    @Modifying
    @Query("delete from UnlockTicket t where t.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 原子抢占：把"仍可处理"的工单一次性改成 APPROVED。
     *
     * 为什么不能只靠 `ownedOpen()` 的检查：那是 check-then-act。家长双击"同意"、
     * 或客户端超时重试，两次请求都会先读到 PENDING，随后各自下发一条 TEMP_UNLOCK
     * （HIGH 优先级，孩子端两条都执行），durationMinutes 不同时还会互相覆盖。
     * 条件 UPDATE 把"判断 + 落库"压成一条语句，只有一个请求能影响 1 行；
     * 影响 0 行的那次直接放弃，不再下发指令。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update UnlockTicket t set t.status = 'APPROVED', t.durationMinutes = :duration, t.resolvedAt = :now
        where t.id = :id and t.status in ('PENDING', 'IGNORED')
        """
    )
    fun claimApprove(@Param("id") id: String, @Param("duration") duration: Int?, @Param("now") now: Long): Int

    /**
     * 原子驳回：只允许把"仍可处理"的工单改成 REJECTED。
     *
     * 与 [claimApprove] 同理：reject 若走"读实体→改 status→save"，并发窗口内可把
     * 刚被 claimApprove 抢占成功的 APPROVED 覆盖回 REJECTED —— 家长此时再点一次同意，
     * 又会通过 claimApprove 再次命中并下发第二条 TEMP_UNLOCK（双倍时长）。
     * 影响 0 行 = 工单已被并发请求处理，调用方放弃后续动作。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update UnlockTicket t set t.status = 'REJECTED', t.resolvedAt = :now
        where t.id = :id and t.status in ('PENDING', 'IGNORED')
        """
    )
    fun claimReject(@Param("id") id: String, @Param("now") now: Long): Int

    /**
     * 原子忽略：幂等（IGNORED→IGNORED 仍影响 1 行），但绝不触碰 APPROVED/REJECTED 终态。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update UnlockTicket t set t.status = 'IGNORED', t.resolvedAt = :now
        where t.id = :id and t.status in ('PENDING', 'IGNORED')
        """
    )
    fun claimDismiss(@Param("id") id: String, @Param("now") now: Long): Int
}

@Repository
interface UsageDelayRepository : JpaRepository<UsageDelayTicket, String> {
    fun findByIdAndDeviceId(id: String, deviceId: String): UsageDelayTicket?
    fun findByDeviceIdAndStatus(deviceId: String, status: String): List<UsageDelayTicket>

    /**
     * 单日已批准 + 待审批的延长分钟汇总：用于"申请即生效"改"审批后才生效"后的
     * 单日累计上限校验（[com.padguard.server.domain.UsageSession.MAX_DAILY_EXTEND_MINUTES]），
     * 防止孩子反复提交绕过上限。
     */
    @Query(
        """
        select coalesce(sum(t.minutes), 0) from UsageDelayTicket t
        where t.deviceId = :deviceId and t.status in ('PENDING', 'APPROVED')
        """
    )
    fun sumEffectiveMinutes(@Param("deviceId") deviceId: String): Int

    /**
     * 原子抢占延时工单（同 [UnlockTicketRepository.claimApprove]）。
     *
     * 这里的重复后果更直接：`approve` 会给孩子端累加额度，
     * 并发两次 = 凭空多一笔使用时长（额度直接决定锁机时间），
     * 而工单记录里只有一条 APPROVED，事后完全看不出多发了。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update UsageDelayTicket t set t.status = 'APPROVED', t.resolvedAt = :now
        where t.id = :id and t.status in ('PENDING', 'IGNORED')
        """
    )
    fun claimApprove(@Param("id") id: String, @Param("now") now: Long): Int

    /**
     * 原子驳回（同 [UnlockTicketRepository.claimReject]）：防止并发 reject 把已
     * claimApprove 的 APPROVED 覆盖回去、进而被二次批准双倍加额度。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update UsageDelayTicket t set t.status = 'REJECTED', t.resolvedAt = :now
        where t.id = :id and t.status in ('PENDING', 'IGNORED')
        """
    )
    fun claimReject(@Param("id") id: String, @Param("now") now: Long): Int

    /** 原子忽略：幂等，绝不触碰 APPROVED/REJECTED 终态。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update UsageDelayTicket t set t.status = 'IGNORED', t.resolvedAt = :now
        where t.id = :id and t.status in ('PENDING', 'IGNORED')
        """
    )
    fun claimDismiss(@Param("id") id: String, @Param("now") now: Long): Int

    /** 易主 / 解绑时连同设备一起作废未结工单。 */
    @Modifying
    @Query("delete from UsageDelayTicket t where t.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface AppPolicyRepository : JpaRepository<AppPolicy, String> {
    fun findByDeviceId(deviceId: String): List<AppPolicy>
    fun findByDeviceIdAndPackageName(deviceId: String, packageName: String): AppPolicy?

    @Modifying
    @Query("delete from AppPolicy p where p.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface TimeRestrictionRepository : JpaRepository<TimeRestriction, String> {
    fun findByDeviceId(deviceId: String): List<TimeRestriction>

    /**
     * 按"设备 + 条目 id"查，用于保存时的 upsert 判定。
     * 带上 deviceId 是必须的：只按 id 查的话，家长 A 可以拿家长 B 的条目 id 来改别人的规则。
     */
    fun findByDeviceIdAndId(deviceId: String, id: String): java.util.Optional<TimeRestriction>

    fun deleteByDeviceIdAndId(deviceId: String, id: String)

    @Modifying
    @Query("delete from TimeRestriction r where r.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface UsageSessionRepository : JpaRepository<UsageSession, String> {
    fun findByDeviceId(deviceId: String): UsageSession?

    @Modifying
    @Query("delete from UsageSession u where u.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 额度原子累加（`extended_minutes = extended_minutes + ?`）。
     *
     * 读出来 +1 再 save 的写法在并发下会丢更新：两个事务读到同一个旧值，
     * 后者覆盖前者，家长批了两次只延长一次。交给数据库做自增就没有这个窗口。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update UsageSession u set u.extendedMinutes = coalesce(u.extendedMinutes, 0) + :minutes,
            u.lastDelayEpoch = :now, u.updatedAt = :now
        where u.deviceId = :deviceId
          and coalesce(u.extendedMinutes, 0) + :minutes <= :maxExtendedMinutes
        """
    )
    fun addExtendedMinutes(
        @Param("deviceId") deviceId: String,
        @Param("minutes") minutes: Int,
        @Param("now") now: Long,
        @Param("maxExtendedMinutes") maxExtendedMinutes: Int
    ): Int
}

@Repository
interface DeviceSettingRepository : JpaRepository<DeviceSetting, String> {
    /**
     * 设备设置以 deviceId 为主键（含密码哈希、护眼/应用管控开关等）。
     * 易主时必须删除，重绑后由默认策略重建 —— 留下上任的"设备操作密码"等于给新家长一把解不开的锁。
     */
    @Modifying
    @Query("delete from DeviceSetting s where s.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface PolicyTemplateRepository : JpaRepository<PolicyTemplate, String> {
    fun findBySceneType(sceneType: String): List<PolicyTemplate>
}

@Repository
interface MediaTaskRepository : JpaRepository<MediaTask, String> {
    fun findByDeviceIdAndKindOrderByStartedAtDesc(deviceId: String, kind: String, pageable: Pageable): List<MediaTask>

    @Modifying
    @Query("delete from MediaTask t where t.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 保留期清理——取早于阈值的媒体任务（录屏/录音，url 指向 uploads 文件）。
     * 单个录屏可达 90MB，是 uploads 目录无限增长的主因；记录与文件必须一起清，
     * 只删文件会留孤儿记录（家长端列表点开是 404），只删记录则文件永远躺着。
     */
    @Query("select m from MediaTask m where m.startedAt < :ts")
    fun findStartedAtBefore(@Param("ts") ts: Long): List<MediaTask>
}

@Repository
interface InstalledAppRepository : JpaRepository<InstalledApp, String> {
    fun findByDeviceId(deviceId: String): List<InstalledApp>
    fun findByDeviceIdAndPackageName(deviceId: String, packageName: String): InstalledApp?
    fun findByDeviceIdAndInstalledTrue(deviceId: String): List<InstalledApp>

    /** 已装应用清单反映上任孩子的使用习惯，属隐私；易主时清除，重绑后由孩子端重新上报。 */
    @Modifying
    @Query("delete from InstalledApp a where a.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface SubscriptionRepository : JpaRepository<Subscription, String> {
    fun findByUserId(userId: String): Subscription?

    /**
     * 过期状态回写：**条件 UPDATE**，不是"读出来改字段再 save"。
     *
     * `statusOf` / `ensureActive` 都是无事务方法，`findByUserId` 返回的是**脱管快照**；
     * 旧实现判定过期后直接 `save(sub)`，JPA merge 会整行 UPDATE，把 `expired_at` 一起写回旧值。
     * 一旦与微信回调的 `extend`（延后到期日、置 ACTIVE）并发，就是"钱已收、订阅被打回过期"，
     * 而 `refreshStatus` 对 EXPIRED 早退，此后**永久锁死**，只能人工改库。
     *
     * 条件 UPDATE 只碰 status/updated_at 两列，并且只在"此刻仍过期、且仍是可用态"时才命中：
     * 并发续费把它改成 ACTIVE 后，本语句影响 0 行，绝不会覆盖刚写入的到期时间。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        update Subscription s set s.status = :expiredStatus, s.updatedAt = :now
        where s.userId = :userId and s.status in ('TRIAL', 'ACTIVE') and s.expiredAt < :now
        """
    )
    fun markExpiredIfStale(
        @Param("userId") userId: String,
        @Param("expiredStatus") expiredStatus: String,
        @Param("now") now: Long
    ): Int

    /** 批量取订阅：后台家长列表一页 N 个账号，逐条 findByUserId 就是 N 次往返（N+1） */
    fun findByUserIdIn(userIds: Collection<String>): List<Subscription>

    /**
     * 续费专用：悲观行锁读。
     *
     * extend 的"读 expiredAt → 算新到期 → save"是 check-then-act：同一家长两笔订单
     * 并发入账（重复拉起支付各付一笔）时两个事务都读到同一个旧 expiredAt，
     * 后提交者覆盖前者 —— 付两笔钱只延长一个月。行锁把续费串行化，天然按最新值累加。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.userId = :userId")
    fun findByUserIdForUpdate(@Param("userId") userId: String): Subscription?

    /** 按订阅状态计数（看板指标：生效中 / 试用） */
    fun countByStatus(status: String): Long
    fun findAllByOrderByExpiredAtDesc(): List<Subscription>

    /** 套餐分布：按 plan 分组计数（仪表盘饼图；原来读全表到内存里 groupBy） */
    @Query("select s.plan, count(s) from Subscription s group by s.plan")
    fun countGroupedByPlan(): List<Array<Any?>>

    /** 指定时刻之后创建的订阅，只取创建时间（仪表盘趋势分桶用） */
    @Query("select s.createdAt from Subscription s where s.createdAt >= :since")
    fun findCreatedAtSince(@Param("since") since: Long): List<Long>

    /**
     * 后台订阅列表：状态过滤与分页下推。
     *
     * 改造前是 `findAllByOrderByExpiredAtDesc()` 全表 → 内存过滤 → 内存分页，
     * 订阅表随家长增长只增不减，翻一页要把整表读进堆。
     * 状态参数用空串表示"不过滤"（与 [UserRepository.searchParents] 同口径）。
     */
    @Query(
        """
        select s from Subscription s
        where (:status = '' or lower(s.status) = :status)
        order by s.expiredAt desc
        """
    )
    fun searchSubscriptions(@Param("status") status: String, pageable: Pageable): Page<Subscription>
}

@Repository
interface PaymentOrderRepository : JpaRepository<PaymentOrder, String> {
    fun findByOrderNo(orderNo: String): PaymentOrder?
    fun findByUserIdOrderByCreatedAtDesc(userId: String, pageable: Pageable): List<PaymentOrder>
    fun findByUserIdAndStatus(userId: String, status: String): List<PaymentOrder>
    /** 全量订单（后台计费报表用），按创建时间倒序 */
    fun findAllByOrderByCreatedAtDesc(): List<PaymentOrder>

    /**
     * 后台订单列表：状态过滤与分页下推。
     *
     * 改造前 `findAllByOrderByCreatedAtDesc()` 全表 → 内存过滤 → 内存分页。
     * 订单表是只增不减的流水表，运营每翻一页都要把历史全部订单读进堆。
     */
    @Query(
        """
        select o from PaymentOrder o
        where (:status = '' or lower(o.status) = :status)
        order by o.createdAt desc
        """
    )
    fun searchOrders(@Param("status") status: String, pageable: Pageable): Page<PaymentOrder>

    /**
     * 指定时刻之后入账的**已支付**订单，只取（创建时间, 金额）两列。
     *
     * 仪表盘与计费页原来各自把订单全表拉进内存：一次 dashboard 请求甚至要拉两遍
     * （本月收入一遍、近 6 月序列又一遍）。两遍之间若有订单入账，
     * 页面就会出现"本月营收 ¥X，但营收曲线最后一点是 ¥Y"的自相矛盾 ——
     * 合并为一次窗口查询后，两个数字必然出自同一快照。
     *
     * 只取两列而不是实体：分桶在 Kotlin 侧按月做（时区口径必须服务端说了算，
     * 而 H2/MySQL/PostgreSQL 的按月日期函数互不兼容），但不需要订单的其余二十个字段。
     */
    @Query(
        "select o.createdAt, o.amountFen from PaymentOrder o " +
            "where o.status = :paidStatus and o.createdAt >= :since"
    )
    fun findPaidAmountsSince(
        @Param("paidStatus") paidStatus: String,
        @Param("since") since: Long
    ): List<Array<Any?>>

    /**
     * 原子抢占：仅当订单仍为 PENDING 时置为 PAID，返回受影响行数（1=抢占成功，0=已被处理）。
     *
     * 第八轮评估 P1-B：此前 markPaid 是"读状态→内存判断→写回"的 check-then-act，
     * 微信重复回调并发到达时两个事务都能通过检查，订阅被 extend 两次（多延长一个月）。
     * 改为条件 UPDATE 后，行锁保证同一订单只有一个事务能抢占成功——
     * 后到的事务在第一个事务提交前阻塞，提交后其 UPDATE 命中 0 行，走幂等返回。
     */
    @Modifying
    @Query(
        "update PaymentOrder o set o.status = :paidStatus, o.paidAt = :paidAt, o.transactionId = :txId " +
            "where o.orderNo = :orderNo and o.status = :pendingStatus"
    )
    fun claimPendingOrder(
        @Param("orderNo") orderNo: String,
        @Param("paidStatus") paidStatus: String,
        @Param("pendingStatus") pendingStatus: String,
        @Param("paidAt") paidAt: Long,
        @Param("txId") txId: String?
    ): Int

    /**
     * 原子超时关闭：仅当订单仍为 PENDING 且确已超时时置为 CLOSED。
     * 调用方（markPaid 超时分支）在独立事务中执行——本方法与"抛 BizException 回滚主事务"
     * 组合使用，CLOSED 才能真正落库（原先就地 save 后抛异常，回滚吞掉，状态永久停在 PENDING）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update PaymentOrder o set o.status = 'CLOSED'
        where o.orderNo = :orderNo and o.status = 'PENDING' and o.payDeadline < :now
        """
    )
    fun claimTimeoutClose(@Param("orderNo") orderNo: String, @Param("now") now: Long): Int

    /**
     * 无条件关闭仍处于待支付态的订单。
     *
     * 使用场景：本地已建单、但向微信下单失败（未配置 / 微信 5xx / 网络超时）。
     * 这种订单**永远不可能被支付**（用户拿不到 payParams），却会一直挂在后台"待支付"列表里，
     * 也会让运营误判"有家长下了单但没付"。与 [claimTimeoutClose] 的区别是不要求已过 payDeadline。
     *
     * 自带 `@Transactional`：调用方（createOrder）刻意不在事务里 —— 建单提交后立即调外网，
     * 失败再回来收口，两段各自成事务。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        update PaymentOrder o set o.status = :closedStatus
        where o.orderNo = :orderNo and o.status = :pendingStatus
        """
    )
    fun closeIfPending(
        @Param("orderNo") orderNo: String,
        @Param("closedStatus") closedStatus: String,
        @Param("pendingStatus") pendingStatus: String
    ): Int

    /**
     * 原子退款标记：仅当订单仍为 PAID 时置为 REFUNDED，返回受影响行数。
     * 幂等口径与 [claimPendingOrder] 相同：管理员退款申请与退款结果回调可能并发到达，
     * 只有第一个事务能把 PAID 翻转为 REFUNDED，后到的命中 0 行（视为重复确认，直接成功返回）。
     *
     * ## 为什么方法自带 `@Transactional`（而不是只靠调用方）
     * `@Modifying` 查询必须有事务上下文，否则抛 `TransactionRequiredException`。
     * 而管理端退款**不能**再把整段包进事务：它中间要调微信退款接口（HTTP，可能 10s+），
     * 事务一旦覆盖到那里，就是"持着数据库连接等外网"。因此退款流程被拆成
     * "事务外调微信 → 事务内原子标记"两段，标记这半段的事务由本注解自己开。
     * 外层若已有事务（如退款回调），REQUIRED 语义下会正常加入，行为不变。
     */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(
        "update PaymentOrder o set o.status = :refundedStatus where o.orderNo = :orderNo and o.status = :paidStatus"
    )
    fun markRefundedIfPaid(
        @Param("orderNo") orderNo: String,
        @Param("refundedStatus") refundedStatus: String,
        @Param("paidStatus") paidStatus: String
    ): Int
}
