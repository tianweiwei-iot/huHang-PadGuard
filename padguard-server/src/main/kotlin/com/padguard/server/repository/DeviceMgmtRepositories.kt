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

    @Modifying
    @Query("delete from AppDistribution d where d.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface DevicePermissionPolicyRepository : JpaRepository<DevicePermissionPolicy, String> {
    fun findByDeviceId(deviceId: String): DevicePermissionPolicy?

    @Modifying
    @Query("delete from DevicePermissionPolicy p where p.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}

@Repository
interface DeviceFeaturePolicyRepository : JpaRepository<DeviceFeaturePolicy, String> {
    fun findByDeviceId(deviceId: String): DeviceFeaturePolicy?

    @Modifying
    @Query("delete from DeviceFeaturePolicy p where p.deviceId = :deviceId")
    fun deleteByDeviceId(@Param("deviceId") deviceId: String): Int
}
