package com.padguard.server.repository

import com.padguard.server.domain.*
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface DeviceGroupRepository : JpaRepository<DeviceGroup, String> {
    fun findByOwnerUserId(ownerUserId: String): List<DeviceGroup>
    fun findBySceneType(sceneType: String): List<DeviceGroup>
}

@Repository
interface UploadedFileRepository : JpaRepository<UploadedFile, String> {
    @Modifying
    @Query("delete from UploadedFile f where f.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * E11（L-OPS-06）：保留期清理——取"设备上传且早于阈值"的文件记录。
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
     * E11（L-OPS-06）：保留期清理——先取出早于阈值的记录（要按 fileUrl 删对应文件），
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
     * E11（L-OPS-06）：定位轨迹保留期清理。
     * 轨迹 288 行/天/设备只增不删（~2.1GB/年/设备），是库里第二大增长源；
     * 且属儿童行踪隐私，保留 90 天足够家长回看，超期应当清除。
     * ts 可空（历史行可能缺定位时间戳），用 coalesce 回落 receivedAt。
     */
    @Modifying
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
     * 按「用户 + 设备」过滤（可选状态）。
     * 供告警列表页使用：此前是查出该用户全部告警后在内存里 filter deviceId，
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
     * E11（L-OPS-06）：保留期清理——取早于阈值的媒体任务（录屏/录音，url 指向 uploads 文件）。
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
    /** 按订阅状态计数（看板指标：生效中 / 试用） */
    fun countByStatus(status: String): Long
    fun findAllByOrderByExpiredAtDesc(): List<Subscription>
}

@Repository
interface PaymentOrderRepository : JpaRepository<PaymentOrder, String> {
    fun findByOrderNo(orderNo: String): PaymentOrder?
    fun findByUserIdOrderByCreatedAtDesc(userId: String, pageable: Pageable): List<PaymentOrder>
    fun findByUserIdAndStatus(userId: String, status: String): List<PaymentOrder>
    /** 全量订单（后台计费报表用），按创建时间倒序 */
    fun findAllByOrderByCreatedAtDesc(): List<PaymentOrder>
}
