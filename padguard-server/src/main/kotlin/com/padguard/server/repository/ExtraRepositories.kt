package com.padguard.server.repository

import com.padguard.server.domain.*
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
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
}

@Repository
interface LocationTrackRepository : JpaRepository<LocationTrack, String> {
    fun findByDeviceIdOrderByTsDesc(deviceId: String, pageable: Pageable): List<LocationTrack>
}

@Repository
interface AlertRepository : JpaRepository<Alert, String> {
    fun findByUserIdAndStatus(userId: String, status: String, pageable: Pageable): List<Alert>
    fun findByUserId(userId: String, pageable: Pageable): List<Alert>
    fun countByUserIdAndStatus(userId: String, status: String): Long
    fun findByDeviceIdOrderByTriggeredAtDesc(deviceId: String): List<Alert>
}

@Repository
interface PublishedMessageRepository : JpaRepository<PublishedMessage, String> {
    fun findByDeviceIdOrderByPublishedAtDesc(deviceId: String, pageable: Pageable): List<PublishedMessage>
}

@Repository
interface GeofenceRepository : JpaRepository<Geofence, String>

@Repository
interface UnlockTicketRepository : JpaRepository<UnlockTicket, String> {
    fun findByIdAndDeviceId(id: String, deviceId: String): UnlockTicket?
    fun findByDeviceIdAndStatus(deviceId: String, status: String): List<UnlockTicket>
}

@Repository
interface AppPolicyRepository : JpaRepository<AppPolicy, String> {
    fun findByDeviceId(deviceId: String): List<AppPolicy>
    fun findByDeviceIdAndPackageName(deviceId: String, packageName: String): AppPolicy?
}

@Repository
interface TimeRestrictionRepository : JpaRepository<TimeRestriction, String> {
    fun findByDeviceId(deviceId: String): List<TimeRestriction>
    fun deleteByDeviceIdAndId(deviceId: String, id: String)
}

@Repository
interface DeviceSettingRepository : JpaRepository<DeviceSetting, String>

@Repository
interface PolicyTemplateRepository : JpaRepository<PolicyTemplate, String> {
    fun findBySceneType(sceneType: String): List<PolicyTemplate>
}

@Repository
interface MediaTaskRepository : JpaRepository<MediaTask, String> {
    fun findByDeviceIdAndKindOrderByStartedAtDesc(deviceId: String, kind: String, pageable: Pageable): List<MediaTask>
}
