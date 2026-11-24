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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UploadedFile f where f.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 保留期清理——取"设备上传且早于阈值"的文件记录。
     * 只清 deviceId 非空的（孩子端截屏/录屏/日志导出）：家长上传的发布素材/APK
     * 由 PublishedMessage / AppInstaller 引用，不在清理范围。
     */
    @Query("select f from UploadedFile f where f.deviceId is not null and f.createdAt < :ts")
    fun findDeviceFilesCreatedBefore(@Param("ts") ts: Long): List<UploadedFile>

    /**
     * 全量已登记的文件路径（只取 `stored_path` 一列）。
     *
     * 磁盘孤儿清扫要反查"盘上有、库里没有"的文件。逐个文件 `existsBy...` 会退化成 N 次
     * 往返，而一次顺序取单列在几千~几万行规模下只是几十 KB 内存，明显更划算。
     * 真到百万行级再谈分页/增量扫描（那时磁盘文件数同量级，扫目录本身也要换策略）。
     *
     * 返回**字符串列表**而不是 Set：`stored_path` 里既有纯文件名（新数据）也有绝对路径
     * （早期版本），归一化（取 `File(...).name`）必须交给调用方，否则会把历史文件误判成孤儿。
     */
    @Query("select f.storedPath from UploadedFile f")
    fun findAllStoredPaths(): List<String>
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Screenshot s where s.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 保留期清理——先取出早于阈值的记录（要按 fileUrl 删对应文件），
     * 再由调用方删文件 + 删记录。截图记录本身很小，但 fileUrl 指向的 jpg 不删
     * 就会在 uploads 里无限堆积（家长端『看屏』每点一次就是一张）。
     */
    @Query("select s from Screenshot s where s.receivedAt < :ts")
    fun findReceivedAtBefore(@Param("ts") ts: Long): List<Screenshot>

    /**
     * 条件收敛（第 7 批）：仅当截图**仍是 PENDING** 才置 EXPIRED。
     *
     * `ExpiredCommandSweeper` 早先用「findFirst → 判 PENDING → 改内存快照 → `save` 整行」，
     * 与同文件主指令已修掉的 F01 是同一个坑：两次查询之间孩子端上传成功把记录推到
     * READY 并填好 `fileUrl`，清扫器拿旧快照整行 merge 会把状态打回 EXPIRED、
     * 把 `fileUrl` 抹成 null —— 图片文件还在磁盘，记录却永远指不到了。
     * 用 `deviceId + shotId` 双键，避免跨设备误伤；影响 0 行即说明已被别人收敛，直接放弃。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
        "update Screenshot s set s.status = :expired " +
            "where s.deviceId = :deviceId and s.shotId = :shotId and s.status = :pending"
    )
    fun markExpiredIfPending(
        @Param("deviceId") deviceId: String,
        @Param("shotId") shotId: String,
        @Param("pending") pending: String,
        @Param("expired") expired: String
    ): Int
}

@Repository
interface LocationTrackRepository : JpaRepository<LocationTrack, String> {
    fun findByDeviceIdOrderByTsDesc(deviceId: String, pageable: Pageable): List<LocationTrack>

    /** 全量轨迹时间线：取最近 [since]（毫秒时间戳）之后的定位点，按时间倒序。 */
    fun findByDeviceIdAndTsGreaterThanEqualOrderByTsDesc(
        deviceId: String, since: Long, pageable: Pageable
    ): List<LocationTrack>

    /** 移动轨迹属儿童行踪隐私，设备易主 / 解绑时必须随设备一起清除。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from LocationTrack t where t.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /** 仅更新单条轨迹的文字地址，供 [com.padguard.server.service.LocationAddressBackfill] 异步回填，不触动经纬度/时间。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update LocationTrack t set t.address = :address where t.id = :id")
    fun updateAddressById(@Param("id") id: String, @Param("address") address: String)

    /**
     * 定位轨迹保留期清理。
     * 轨迹 288 行/天/设备只增不删（~2.1GB/年/设备），是库里第二大增长源；
     * 且属儿童行踪隐私，保留 90 天足够家长回看，超期应当清除。
     *
     * R41：判据必须是服务端落库时刻 `receivedAt`，**不能用客户端上报的 `ts`**。
     * `ts` 来自设备定位/系统时间：调到未来 → 轨迹永远不满足过期条件、表持续膨胀写满磁盘；
     * 调到过去 → 今天的真实行踪被当成 90 天前数据删掉，家长回看出现空洞。
     * 与 `UsageLogCleanup` / 截图清理统一口径：清理只看服务端赋值的时间列。
     *
     * 必须显式 @Transactional：`@Modifying` 查询方法不会由 Spring Data 自动开启事务，
     * 调用方（RetentionCleanup）也没有事务边界，否则运行期抛 TransactionRequiredException
     * 被 runCatching 吞成一条 warn —— 表现为"清理任务每天都跑，但表永远不减少"，
     * 最终写满磁盘导致 PG 拒写、全站瘫痪。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from LocationTrack t where t.receivedAt < :ts")
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Alert a where a.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 家长确认告警：仅 ACTIVE → ACKNOWLEDGED。
     *
     * 状态迁移必须带前驱条件（项目红线：终态不可回退靠 DB WHERE，不能"读实体+if+save"）：
     * 旧实现整行 save 可把已被孩子端自愈关掉的 CLOSED / 后台已处置的 RESOLVED 改回
     * ACKNOWLEDGED，家长端红点"死而复生"。0 行 = 已被处置，幂等无操作。
     * WHERE 带上 userId 双保险（调用方 requireOwned 已校验，这里防的是读后并发）。
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Alert a set a.status = 'ACKNOWLEDGED', a.acknowledgedAt = :at " +
            "where a.id = :id and a.userId = :userId and a.status = 'ACTIVE'"
    )
    fun acknowledgeIfActive(
        @Param("id") id: String, @Param("userId") userId: String, @Param("at") at: Long
    ): Int

    /**
     * 家长关闭告警：ACTIVE/ACKNOWLEDGED → CLOSED（含可选处理说明追加）。
     * RESOLVED（后台已处置）与 CLOSED 不可回退；0 行 = 已在终态，幂等无操作。
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Alert a set a.status = 'CLOSED', a.resolvedAt = :at " +
            "where a.id = :id and a.userId = :userId and a.status in ('ACTIVE', 'ACKNOWLEDGED')"
    )
    fun closeIfOpen(
        @Param("id") id: String, @Param("userId") userId: String, @Param("at") at: Long
    ): Int

    /** 关闭成功后追加处理说明（仅仍在 CLOSED 态时生效，纯展示字段，并发被后台改走则放弃追加）。 */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Alert a set a.message = concat(coalesce(a.message, ''), :suffix) where a.id = :id and a.status = 'CLOSED'")
    fun appendCloseNoteIfStillClosed(@Param("id") id: String, @Param("suffix") suffix: String): Int

    /**
     * 孩子端自愈关闭某 (deviceId, category) 下全部 ACTIVE 告警。
     *
     * 旧实现"查出 ACTIVE 快照→内存置 CLOSED→saveAll"与家长 acknowledge / 后台 closeAlert
     * 并发时互相整行覆盖（后提交者赢）。改为单条带状态条件的批量 UPDATE，原子且只动 ACTIVE。
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Alert a set a.status = 'CLOSED', a.resolvedAt = :at " +
            "where a.deviceId = :deviceId and a.category = :category and a.status = 'ACTIVE'"
    )
    fun closeActiveByCategory(
        @Param("deviceId") deviceId: String, @Param("category") category: String, @Param("at") at: Long
    ): Int

    /**
     * 后台处置告警：任意非 RESOLVED 态 → RESOLVED。
     * 已 RESOLVED 幂等（0 行），不可把终态改回其它状态；家长已 CLOSED 的允许升级为 RESOLVED
     * （后台处置语义上"更进一步"，且告警表只增不改历史状态字段以外信息）。
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update Alert a set a.status = 'RESOLVED', a.resolvedAt = :at " +
            "where a.id = :id and a.status <> 'RESOLVED'"
    )
    fun resolveIfNotResolved(@Param("id") id: String, @Param("at") at: Long): Int
}

@Repository
interface PublishedMessageRepository : JpaRepository<PublishedMessage, String> {
    fun findByDeviceIdOrderByPublishedAtDesc(deviceId: String, pageable: Pageable): List<PublishedMessage>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from GeofenceZone g where g.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface UnlockTicketRepository : JpaRepository<UnlockTicket, String> {
    fun findByIdAndDeviceId(id: String, deviceId: String): UnlockTicket?
    fun findByDeviceIdAndStatus(deviceId: String, status: String): List<UnlockTicket>

    /** 未结工单会一直占着家长端"待审批"列表，易主 / 解绑时连同设备一起作废。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UsageDelayTicket t where t.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface AppPolicyRepository : JpaRepository<AppPolicy, String> {
    fun findByDeviceId(deviceId: String): List<AppPolicy>
    fun findByDeviceIdAndPackageName(deviceId: String, packageName: String): AppPolicy?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TimeRestriction r where r.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface UsageSessionRepository : JpaRepository<UsageSession, String> {
    fun findByDeviceId(deviceId: String): UsageSession?

    /**
     * 延时申请的**并发闸门**：对当日计时行加 `PESSIMISTIC_WRITE` 行锁后再查待审工单。
     *
     * [com.padguard.server.service.UsageDelayService.request] 的「查 PENDING → 判空 →
     * 插工单」是 check-then-act。两个事务（孩子端网络重试 / 双通道上报）在 READ_COMMITTED
     * 下都读不到对方尚未提交的 PENDING 行，于是各插一张；家长连点同意就变成两笔可批准时长。
     * 根治一般靠 `(device_id, status)` 的部分唯一索引，但那需要改表结构（本轮未授权）。
     *
     * 这里改用**已有的、且 request 本来就会读的** `usage_sessions` 行做串行域：
     * `SELECT ... FOR UPDATE` 把同一设备的申请排成一列，锁随事务提交释放 —— 锁边界与
     * 事务边界天然一致，不会出现「JVM 锁在方法内、事务在锁外提交」那种看似加了锁、
     * 后到者仍读到旧快照的假修复。因为锁在数据库里，**多副本同样有效**，不依赖单副本假设。
     * 会话行不存在时两个事务都只会抛「今日计时尚未开始」，不会落工单，无需额外保护。
     *
     * 只在这一个入口使用：其它读路径（`start` / `reportRest` / `approve`）保持无锁读，
     * 避免把行锁扩散到心跳与额度累加的高频路径上。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UsageSession u where u.deviceId = :deviceId")
    fun findByDeviceIdForUpdate(@Param("deviceId") deviceId: String): UsageSession?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
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

    /**
     * 额度**已耗尽**后家长追加时长时，把耗尽期间的墙钟流逝从计时里剔除（重设计时起点）。
     *
     * ## 为什么需要它（"当天时间已达上限，申请解锁加时"场景）
     * [UsageSessionService.toDto] 用 `remaining = (quota + extended) * 60000 - (now - startEpoch) - rest`
     * 派生剩余，其中"已用"是**纯墙钟流逝**。额度用完后设备被锁，孩子在锁屏页等家长审批的这段时间
     * 仍在流逝：若 30 分钟后家长才批准 +30 分钟，`now - startEpoch` 比总额度多出 30 分钟，
     * 新增的 30 分钟会被这段历史超支**整笔吃掉**，`remainingMs` 仍是 0 ——
     * 表现为"总时长数字加了，孩子端剩余没变、临时解锁到期立刻又被锁回去"。
     *
     * 本方法在 [addExtendedMinutes] 之后调用，把 `startEpoch` 后移到
     * `now + rest - (quota + 加时前 extended) * minuteMs`，使新剩余精确等于本次追加的分钟数：
     * 锁屏等待（设备不可用）不属于"使用"，把它从计时里剔除与 [restAccumulatedMs] 的设计一致；
     * `usedMinutesIfActive` 也随之回到"加时前已用满的额度"，不会倒退到 0、不会凭空多送时长。
     *
     * ## 原子性 / 幂等
     * WHERE 里的"确实已耗尽"是**数据库侧判定**（不是读出来再 if），两个并发批准只会有一个影响 1 行：
     * 先赢者重设起点后，`now - startEpoch - rest` 变小，后到者的 WHERE 不再成立，天然幂等。
     * `dayKey` 一并入条件防止跨日会话被误写；`startEpoch is not null` 排除今日未起表。
     *
     * @param quotaMinutes 权威当日额度（调用方传实时解析值，不用实体里 start 时锁定的旧值，
     *                     否则家长当天改过额度会把起点推多/推少，等于白送或白扣时长）
     * @param minutes 本次**实际**追加的分钟数（命中上限被钳制后可能小于申请值）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update UsageSession u set
            u.startEpoch = :now + coalesce(u.restAccumulatedMs, 0)
                - (:quotaMinutes + coalesce(u.extendedMinutes, 0) - :minutes) * :minuteMs,
            u.updatedAt = :now
        where u.deviceId = :deviceId
          and u.dayKey = :dayKey
          and u.startEpoch is not null
          and (:now - u.startEpoch - coalesce(u.restAccumulatedMs, 0))
                > (:quotaMinutes + coalesce(u.extendedMinutes, 0) - :minutes) * :minuteMs
        """
    )
    fun rebaseExpiredSession(
        @Param("deviceId") deviceId: String,
        @Param("dayKey") dayKey: String,
        @Param("now") now: Long,
        @Param("quotaMinutes") quotaMinutes: Int,
        @Param("minutes") minutes: Int,
        @Param("minuteMs") minuteMs: Long
    ): Int

    /**
     * 护眼休息上报的**条件累加**（CAS），与 [addExtendedMinutes] 同一范式。
     *
     * `reportRest` 原本是「读实体 → 累加 restAccumulatedMs / 追加 restIds → save(整行)」。
     * 孩子端一次护眼休息结束就上报一次，网络重试、多段休息连续结束、家长端同时刷新
     * 都可能让两个请求读到同一份旧快照：后提交的整行把前一次累加与去重 id 一起覆盖掉，
     * 表现为「休息了却仍被计入额度」以及「同一个 restId 被重复扣减」。
     *
     * 把累加交给数据库，并把**读到的旧值同时作为写条件**：并发者的影响行数为 0，
     * 调用方重读重试即可，不会出现静默丢更新。`dayKey` 一并列进条件，防止跨日会话被误写。
     *
     * `coalesce` 是给历史行兜底：这两个列是后续迁移加上的，老行可能为 NULL。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update UsageSession u set
            u.restAccumulatedMs = coalesce(u.restAccumulatedMs, 0) + :delta,
            u.restIds = :newRestIds,
            u.updatedAt = :now
        where u.deviceId = :deviceId
          and u.dayKey = :dayKey
          and coalesce(u.restAccumulatedMs, 0) = :oldAccum
          and coalesce(u.restIds, '') = :oldRestIds
        """
    )
    fun appendRestIfUnchanged(
        @Param("deviceId") deviceId: String,
        @Param("dayKey") dayKey: String,
        @Param("delta") delta: Long,
        @Param("newRestIds") newRestIds: String,
        @Param("now") now: Long,
        @Param("oldAccum") oldAccum: Long,
        @Param("oldRestIds") oldRestIds: String
    ): Int

    /**
     * 首次开始计时的**幂等插入**。
     *
     * [UsageSessionService.start] 的旧实现用 `save(新实体)` 建行：`save` 对手动主键走
     * merge（先查再插），两个并发请求都查到「无行」时后提交者撞主键约束，整次请求 500。
     * 更糟的是它与下面的跨日重置共用「读-改-写」，会把别的请求刚写入的额度整行覆盖。
     *
     * `on conflict do nothing` 让「谁先插谁赢」，输家影响 0 行后重读即可，
     * 不需要吞异常（事务里吞约束异常会让 Hibernate Session 处于不确定状态，随后回滚）。
     *
     * **冲突目标必须省略**：本批在真实 H2 2.2.224 上实测 `on conflict (device_id) do nothing`
     * 直接抛 JdbcSQLSyntaxErrorException（H2 不支持带列的冲突目标，PG 支持）。无目标的
     * `on conflict do nothing` 在 H2（MODE=PostgreSQL）与 PG16 上都合法，且本表只有主键
     * 一个唯一约束，语义完全一致。`Repositories.insertIgnoreConflict` 的同型写法已一并修正。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        value = "insert into usage_sessions " +
                "(device_id, day_key, start_epoch, quota_minutes, extended_minutes, last_delay_epoch, " +
                " rest_accumulated_ms, rest_ids, created_at, updated_at) " +
                "values (:deviceId, :dayKey, :startEpoch, :quotaMinutes, 0, null, 0, '', :now, :now) " +
                "on conflict do nothing",
        nativeQuery = true
    )
    fun insertIfAbsent(
        @Param("deviceId") deviceId: String,
        @Param("dayKey") dayKey: String,
        @Param("startEpoch") startEpoch: Long,
        @Param("quotaMinutes") quotaMinutes: Int,
        @Param("now") now: Long
    ): Int

    /**
     * 跨日重置的**条件更新**，与 [addExtendedMinutes] / [appendRestIfUnchanged] 同一范式。
     *
     * 旧实现「读出行 → 判 dayKey → 改 7 个字段 → save(整行)」在日切瞬间有两个致命窗口：
     * 1. 双通道/网络重试让两个请求都读到昨天的快照，各自 `System.currentTimeMillis()`
     *    写回，后写者把计时起点往后推（重试一次白赚一段可用时长）；
     * 2. 家长刚批准的 `extendedMinutes`、孩子刚用 CAS 累加的 `restAccumulatedMs/restIds`
     *    被整行覆盖清零 —— 前面两批做的条件更新全部白做。
     *
     * WHERE 把「确实是旧会话」写成数据库侧的判定条件，行锁保证只有一个赢家；
     * 输家影响 0 行，调用方重读权威行即可，不需要重试（这不是丢更新，是正常的抢占结果）。
     * `dayKey is null` 是给加列前的历史行兜底，`startEpoch is null` 对应「今日已建行但没确认起点」。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update UsageSession u set
            u.dayKey = :dayKey,
            u.startEpoch = :startEpoch,
            u.quotaMinutes = :quotaMinutes,
            u.extendedMinutes = 0,
            u.lastDelayEpoch = null,
            u.restAccumulatedMs = 0,
            u.restIds = '',
            u.updatedAt = :now,
            u.createdAt = case when u.createdAt is null or u.createdAt = 0 then :now else u.createdAt end
        where u.deviceId = :deviceId
          and (u.dayKey is null or u.dayKey <> :dayKey or u.startEpoch is null)
        """
    )
    fun startFreshIfStale(
        @Param("deviceId") deviceId: String,
        @Param("dayKey") dayKey: String,
        @Param("startEpoch") startEpoch: Long,
        @Param("quotaMinutes") quotaMinutes: Int,
        @Param("now") now: Long
    ): Int
}

@Repository
interface DeviceSettingRepository : JpaRepository<DeviceSetting, String> {
    /**
     * 设备设置以 deviceId 为主键（含密码哈希、护眼/应用管控开关等）。
     * 易主时必须删除，重绑后由默认策略重建 —— 留下上任的"设备操作密码"等于给新家长一把解不开的锁。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from InstalledApp a where a.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 仍被应用台账引用的图标地址（保留期清理的"不可删清单"）。
     *
     * 图标文件也是 `uploaded_files` 里 `device_id` 非空的一行（L-19 修归属时改的），
     * 于是它天然落进了"设备上传文件超期即删"的范围。但图标与截图/录屏不同：
     * **它没有生命周期终点** —— 应用一直装着，图标就一直要在。
     * 一旦被按 90 天清掉，`installed_apps.icon_url` 就指向一个 404，
     * 而且**永远不会自愈**：下次上报图标内容没变（hash 相同），
     * `iconUpdate` 直接返回 null，不会重新落盘，家长端从此一片空白图标。
     */
    @Query("select a.iconUrl from InstalledApp a where a.iconUrl is not null")
    fun findAllIconUrls(): List<String>
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
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
    @Modifying(flushAutomatically = true, clearAutomatically = true)
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

@Repository
interface UsageStartRequestRepository : JpaRepository<UsageStartRequest, String> {
    fun findByDeviceIdAndDayKey(deviceId: String, dayKey: String): List<UsageStartRequest>

    /**
     * 孩子确认「开始计时」：当日全部 PENDING 工单置 CONFIRMED（条件 UPDATE，终态不可回退）。
     *
     * 按设备+日期收敛而不是按 requestId：孩子可能从等待页按钮、日切浮层等多个入口确认，
     * 任何一条回传链路缺失都不该让工单悬空；同设备同日只允许一张有效工单（request 幂等保证），
     * 所以"全量置 CONFIRMED"与"按 requestId 置"在正常流下等价。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update UsageStartRequest r set r.status = 'CONFIRMED', r.confirmedAt = :now " +
            "where r.deviceId = :deviceId and r.dayKey = :dayKey and r.status = 'PENDING'"
    )
    fun confirmPendingToday(
        @Param("deviceId") deviceId: String,
        @Param("dayKey") dayKey: String,
        @Param("now") now: Long
    ): Int

    /**
     * 跨日作废：非今日的 PENDING 工单一律 EXPIRED（与延时工单同型——
     * 昨天发起没确认的，今天不能凭旧工单"续命"）。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update UsageStartRequest r set r.status = 'EXPIRED' " +
            "where r.deviceId = :deviceId and r.status = 'PENDING' and r.dayKey <> :today"
    )
    fun expireStale(
        @Param("deviceId") deviceId: String,
        @Param("today") today: String
    ): Int
}
