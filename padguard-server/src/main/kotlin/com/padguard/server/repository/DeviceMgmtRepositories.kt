package com.padguard.server.repository

import com.padguard.server.domain.AppCatalog
import com.padguard.server.domain.AppDistribution
import com.padguard.server.domain.DeviceFeaturePolicy
import com.padguard.server.domain.DevicePermissionPolicy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface AppCatalogRepository : JpaRepository<AppCatalog, String> {
    fun findByUserId(userId: String): List<AppCatalog>
    fun findByUserIdAndPackageName(userId: String, packageName: String): AppCatalog?
    /** 同一包名只保留最新一条（覆盖上传即升级物料），用于升级判定时取 max(versionCode) */
    fun findByUserIdAndPackageNameOrderByVersionCodeDesc(userId: String, packageName: String): List<AppCatalog>
}

@Repository
interface AppDistributionRepository : JpaRepository<AppDistribution, String> {
    fun findByUserIdAndDeviceId(userId: String, deviceId: String): List<AppDistribution>
    fun findByDeviceIdAndCatalogId(deviceId: String, catalogId: String): List<AppDistribution>

    /** R-P2-12：按指令回执收敛分发状态（双键匹配，防跨设备串改） */
    fun findByMsgIdAndDeviceId(msgId: String, deviceId: String): List<AppDistribution>

    /** R-P2-12：台账上报时取该设备所有未闭环的分发记录（PENDING/DISPATCHED/FAILED） */
    fun findByDeviceIdAndStatusIn(deviceId: String, statuses: Collection<String>): List<AppDistribution>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AppDistribution d where d.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int

    /**
     * 条件收敛回执（第 7 批）：`status <> SUCCESS` 才允许改写，让「单向、终态不可回退」
     * 从**代码注释**变成**数据库约束级事实**。
     *
     * 旧实现 `findByMsgIdAndDeviceId` 读脱管实体 → 改字段 → `save` 整行 merge；类注释里的
     * `if (record.status == SUCCESS) continue` 只是内存读判，挡不住并发：一条 MQTT QoS1
     * 迟到的 FAILED 回执读到旧 DISPATCHED 快照，会把另一路刚提交的 SUCCESS 覆盖回 FAILED。
     * 错误详情沿用原语义 —— 回执不带错误时**保留**旧值（`:hasError = 0` 走 `else d.errorCode`）。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
        "update AppDistribution d set d.status = :status, d.finishedAt = :finishedAt, " +
            "d.errorCode = case when :hasError = 1 then :errorCode else d.errorCode end, " +
            "d.errorMessage = case when :hasError = 1 then :errorMessage else d.errorMessage end " +
            "where d.id = :id and d.status <> :success"
    )
    fun applyAckIfNotSuccess(
        @Param("id") id: String,
        @Param("status") status: String,
        @Param("errorCode") errorCode: String?,
        @Param("errorMessage") errorMessage: String?,
        @Param("hasError") hasError: Int,
        @Param("finishedAt") finishedAt: Long?,
        @Param("success") success: String
    ): Int

    /**
     * 条件收敛台账成功（第 7 批）：只对仍处于开放态的记录写 SUCCESS。
     * 与 [applyAckIfNotSuccess] 互补，堵住「查到开放态 → 期间被回执改终态 → 整行覆盖」的窗口。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
        "update AppDistribution d set d.status = :success, d.finishedAt = :finishedAt, " +
            "d.errorCode = null, d.errorMessage = null " +
            "where d.id = :id and d.status in :openStatuses"
    )
    fun markSuccessIfOpen(
        @Param("id") id: String,
        @Param("finishedAt") finishedAt: Long,
        @Param("success") success: String,
        @Param("openStatuses") openStatuses: Collection<String>
    ): Int
}

@Repository
interface DevicePermissionPolicyRepository : JpaRepository<DevicePermissionPolicy, String> {
    fun findByDeviceId(deviceId: String): DevicePermissionPolicy?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DevicePermissionPolicy p where p.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface DeviceFeaturePolicyRepository : JpaRepository<DeviceFeaturePolicy, String> {
    fun findByDeviceId(deviceId: String): DeviceFeaturePolicy?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DeviceFeaturePolicy p where p.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}
