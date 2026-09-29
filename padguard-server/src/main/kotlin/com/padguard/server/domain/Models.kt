package com.padguard.server.domain

import jakarta.persistence.*

@Entity
@Table(name = "users")
class User(
    @Id var id: String = "",
    @Column(name = "phone", unique = true, nullable = false) var phone: String = "",
    @Column(name = "password_hash", nullable = false) var passwordHash: String = "",
    var nickname: String? = null,
    var avatar: String? = null,
    @Column(name = "role", nullable = false) var role: String = "",
    @Column(name = "scene_type", nullable = false) var sceneType: String = "",
    @Column(name = "created_at") var createdAt: Long = 0
)

@Entity
@Table(name = "devices")
class Device(
    @Id var id: String = "",                 // = deviceId (服务端下发 UUID)
    @Column(name = "user_id") var userId: String? = null,
    var name: String? = null,
    /** 孩子端自定义资料：姓名（对应"孩子姓名"） */
    @Column(name = "child_name") var childName: String? = null,
    /** 孩子端自定义资料：昵称 */
    @Column(name = "child_nickname") var childNickname: String? = null,
    /** 孩子端自定义资料：头像 URL（由 /device/avatar 上传后返回） */
    @Column(name = "child_avatar") var childAvatar: String? = null,
    @Column(name = "device_sn") var deviceSn: String? = null,
    var fingerprint: String? = null,
    var model: String? = null,
    var brand: String? = null,
    @Column(name = "os_version") var osVersion: String? = null,
    @Column(name = "sdk_int") var sdkInt: Int? = null,
    @Column(name = "app_version") var appVersion: String? = null,
    @Column(name = "control_mode") var controlMode: String? = null,
    @Column(name = "scene_mode") var sceneMode: String? = null,
    var scene: String? = null,
    @Column(name = "online_status") var onlineStatus: String = "OFFLINE",
    @Column(name = "last_online_at") var lastOnlineAt: Long? = null,
    @Column(name = "battery_level") var batteryLevel: Int? = null,
    var latitude: Double? = null,
    var longitude: Double? = null,
    @Column(name = "group_id") var groupId: String? = null,
    @Column(name = "policy_version") var policyVersion: Int = 0,
    @Column(name = "hmac_secret") var hmacSecret: String = "",
    @Column(name = "device_token_hash") var deviceTokenHash: String = "",
    @Column(name = "mqtt_password") var mqttPassword: String = "",
    /**
     * 家长是否仍处于「远程锁屏」状态。
     *
     * 必须有这个字段：远程锁屏是一次性的指令下发，服务端原本不留痕，
     * 家长端就无从知道自己刚才到底锁上了没有 —— 按钮只能一直显示"锁屏"，
     * 孩子锁着却找不到解锁入口。有了它，按钮才能如实切换成"解锁"。
     *
     * 只表示**家长主动发起**的远程锁屏，不参与因限额/时段触发的自动锁屏：
     * 后者由孩子端本地策略自行恢复，家长不该（也无法）用远程解锁去覆盖它。
     */
    // 可空：ddl-auto 给老库加列时已有行是 NULL，非空 Boolean 会在读取时直接抛
    // "Can not set boolean field to null value"，整个应用起不来。DTO 出口处再兜底成 false。
    @Column(name = "remote_locked", columnDefinition = "boolean default false")
    var remoteLocked: Boolean? = false,
    @Column(name = "created_at") var createdAt: Long = 0
)

@Entity
@Table(name = "bind_codes")
class BindCode(
    @Id var code: String = "",
    @Column(name = "user_id") var userId: String = "",
    @Column(name = "device_id") var deviceId: String? = null,
    @Column(name = "expires_at") var expiresAt: Long = 0,
    var used: Boolean = false,
    @Column(name = "created_at") var createdAt: Long = 0
)

@Entity
@Table(name = "policies")
class Policy(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    var version: Int = 0,
    @Column(name = "package_json", columnDefinition = "text") var packageJson: String? = null,
    var scene: String? = null,
    @Column(name = "effective_from") var effectiveFrom: Long? = null,
    @Column(name = "effective_to") var effectiveTo: Long? = null,
    @Column(name = "updated_at") var updatedAt: Long = 0
)

@Entity
@Table(name = "commands")
class Command(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "msg_id", unique = true) var msgId: String = "",
    var type: String = "",
    var priority: String? = null,
    @Column(name = "payload_json", columnDefinition = "text") var payloadJson: String? = null,
    var signature: String? = null,
    var status: String = "PENDING",
    @Column(name = "expires_at") var expiresAt: Long = 0,
    @Column(name = "created_at") var createdAt: Long = 0,
    @Column(name = "executed_at") var executedAt: Long? = null
)

@Entity
@Table(name = "usage_logs")
class UsageLog(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "log_id", unique = true) var logId: String = "",
    var type: String? = null,
    var timestamp: Long? = null,
    @Column(name = "payload_json", columnDefinition = "text") var payloadJson: String? = null,
    @Column(name = "received_at") var receivedAt: Long = 0
)

@Entity
@Table(name = "device_events")
class DeviceEvent(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "event_id", unique = true) var eventId: String = "",
    var type: String? = null,
    var level: String? = null,
    @Column(name = "detail_json", columnDefinition = "text") var detailJson: String? = null,
    var timestamp: Long? = null
)
