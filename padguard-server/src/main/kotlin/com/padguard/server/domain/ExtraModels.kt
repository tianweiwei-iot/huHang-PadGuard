package com.padguard.server.domain

import jakarta.persistence.*

@Entity
@Table(name = "device_groups")
class DeviceGroup(
    @Id var id: String = "",
    var name: String = "",
    @Column(name = "scene_type") var sceneType: String = "",
    @Column(name = "owner_user_id") var ownerUserId: String? = null,
    @Column(name = "created_at") var createdAt: Long = 0
)

@Entity
@Table(name = "uploaded_files")
class UploadedFile(
    @Id var id: String = "",
    @Column(name = "content_type") var contentType: String = "",
    @Column(name = "stored_path") var storedPath: String = "",
    var size: Long = 0,
    @Column(name = "created_at") var createdAt: Long = 0
)

@Entity
@Table(name = "screenshots")
class Screenshot(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "shot_id") var shotId: String? = null,
    @Column(name = "trigger_type") var triggerType: String? = null,
    @Column(name = "file_url") var fileUrl: String? = null,
    @Column(name = "thumbnail_url") var thumbnailUrl: String? = null,
    var width: Int? = null,
    var height: Int? = null,
    @Column(name = "captured_at") var capturedAt: Long? = null,
    @Column(name = "received_at") var receivedAt: Long = 0,
    var status: String = "READY"
)

@Entity
@Table(name = "location_tracks")
class LocationTrack(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    var lat: Double? = null,
    var lng: Double? = null,
    var accuracy: Double? = null,
    var provider: String? = null,
    var ts: Long? = null,
    @Column(name = "received_at") var receivedAt: Long = 0
)

@Entity
@Table(name = "alerts")
class Alert(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "user_id") var userId: String? = null,
    var level: String = "INFO",
    var title: String = "",
    @Column(name = "message", columnDefinition = "text") var message: String? = null,
    var category: String? = null,
    var status: String = "ACTIVE",
    @Column(name = "detail_json", columnDefinition = "text") var detailJson: String? = null,
    @Column(name = "triggered_at") var triggeredAt: Long = 0,
    @Column(name = "acknowledged_at") var acknowledgedAt: Long? = null,
    @Column(name = "resolved_at") var resolvedAt: Long? = null
)

@Entity
@Table(name = "published_messages")
class PublishedMessage(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "content_type") var contentType: String = "",
    @Column(name = "text", columnDefinition = "text") var text: String? = null,
    @Column(name = "media_url") var mediaUrl: String? = null,
    @Column(name = "media_name") var mediaName: String? = null,
    @Column(name = "display_seconds") var displaySeconds: Int = 0,
    @Column(name = "full_screen") var fullScreen: Boolean = false,
    @Column(name = "play_audio") var playAudio: Boolean = false,
    @Column(name = "published_at") var publishedAt: Long = 0
)

@Entity
@Table(name = "geofences")
class Geofence(
    @Id @Column(name = "device_id") var deviceId: String = "",
    var enabled: Boolean = false,
    var name: String? = null,
    @Column(name = "center_lat") var centerLat: Double? = null,
    @Column(name = "center_lng") var centerLng: Double? = null,
    @Column(name = "radius_meters") var radiusMeters: Int? = null,
    @Column(name = "alert_on_exit") var alertOnExit: Boolean = true
)

@Entity
@Table(name = "unlock_tickets")
class UnlockTicket(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "user_id") var userId: String? = null,
    @Column(name = "package_name") var packageName: String? = null,
    @Column(name = "app_label") var appLabel: String? = null,
    @Column(name = "duration_minutes") var durationMinutes: Int? = null,
    @Column(name = "reason", columnDefinition = "text") var reason: String? = null,
    var status: String = "PENDING",
    @Column(name = "created_at") var createdAt: Long = 0,
    @Column(name = "resolved_at") var resolvedAt: Long? = null
)

@Entity
@Table(name = "app_policies")
class AppPolicy(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "package_name") var packageName: String = "",
    @Column(name = "app_name") var appName: String? = null,
    @Column(name = "is_blocked") var isBlocked: Boolean = false,
    @Column(name = "daily_limit_minutes") var dailyLimitMinutes: Int? = null
)

@Entity
@Table(name = "time_restrictions")
class TimeRestriction(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "day_of_week") var dayOfWeek: Int = 1,
    @Column(name = "start_time") var startTime: String = "",
    @Column(name = "end_time") var endTime: String = "",
    @Column(name = "max_minutes") var maxMinutes: Int? = null,
    @Column(name = "is_enabled") var isEnabled: Boolean = true
)

@Entity
@Table(name = "device_settings")
class DeviceSetting(
    @Id @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "auto_refresh_seconds") var autoRefreshSeconds: Int = 15,
    @Column(name = "high_definition") var highDefinition: Boolean = false,
    @Column(name = "record_resolution") var recordResolution: String = "HD_720P",
    @Column(name = "record_with_audio") var recordWithAudio: Boolean = true,
    @Column(name = "allow_remote_lock") var allowRemoteLock: Boolean = true,
    @Column(name = "browser_disabled") var browserDisabled: Boolean = false,
    @Column(name = "smart_shutdown_enabled") var smartShutdownEnabled: Boolean = false,
    @Column(name = "smart_shutdown_start") var smartShutdownStart: String? = null,
    @Column(name = "smart_shutdown_end") var smartShutdownEnd: String? = null,
    @Column(name = "daily_limit_minutes") var dailyLimitMinutes: Int = 120,
    @Column(name = "web_blocked_urls", columnDefinition = "text") var webBlockedUrls: String? = null
)

@Entity
@Table(name = "policy_templates")
class PolicyTemplate(
    @Id var id: String = "",
    var name: String = "",
    @Column(name = "description", columnDefinition = "text") var description: String? = null,
    @Column(name = "scene_type") var sceneType: String = "",
    var category: String? = null,
    @Column(name = "package_json", columnDefinition = "text") var packageJson: String? = null
)

@Entity
@Table(name = "media_tasks")
class MediaTask(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    var kind: String = "",
    var status: String = "PENDING",
    var url: String? = null,
    @Column(name = "mime_type") var mimeType: String? = null,
    var size: Long? = null,
    @Column(name = "duration_seconds") var durationSeconds: Int? = null,
    @Column(name = "started_at") var startedAt: Long = 0,
    @Column(name = "finished_at") var finishedAt: Long? = null
)
