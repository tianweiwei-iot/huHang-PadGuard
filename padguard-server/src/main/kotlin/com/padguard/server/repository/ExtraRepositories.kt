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
interface UploadedFileRepository : JpaRepository<UploadedFile, String>

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
}

@Repository
interface LocationTrackRepository : JpaRepository<LocationTrack, String> {
    fun findByDeviceIdOrderByTsDesc(deviceId: String, pageable: Pageable): List<LocationTrack>

    /** 移动轨迹属儿童行踪隐私，设备易主 / 解绑时必须随设备一起清除。 */
    @Modifying
    @Query("delete from LocationTrack t where t.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
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
interface GeofenceRepository : JpaRepository<Geofence, String> {
    /**
     * 围栏以 deviceId 为主键。易主时必须删除：把"上一个孩子的学校"留成新孩子的围栏，
     * 会让新家长收到完全错误的越界告警。重绑后由家长重新配置。
     */
    @Modifying
    @Query("delete from Geofence g where g.deviceId = :deviceId")
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
