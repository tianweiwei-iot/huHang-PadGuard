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
    /** 邮箱（选填，后台可维护，用于运营联系家长） */
    @Column(name = "email") var email: String? = null,
    /** 区域 / 渠道标记（选填，运营分组用） */
    @Column(name = "region") var region: String? = null,
    /** 备注（选填，渠道 / 来源等） */
    @Column(name = "remark") var remark: String? = null,
    @Column(name = "role", nullable = false) var role: String = "",
    @Column(name = "scene_type", nullable = false) var sceneType: String = "",
    /**
     * 账号状态：active（正常）/ suspended（冻结）/ pending（待激活）。
     * 新增列；存量行由 ddl-auto=update 补为 NULL，读取时按 "active" 兜底，避免空指针。
     */
    @Column(name = "status") var status: String? = "active",
    /** 最近登录时刻（毫秒）；NULL 表示从未登录 */
    @Column(name = "last_login_at") var lastLoginAt: Long? = null,
    @Column(name = "created_at") var createdAt: Long = 0
)

/**
 * 家长账号状态的**唯一事实源**。
 *
 * 为什么要收敛成一处：冻结账号的判定分散在登录、令牌刷新、接口鉴权、订阅闸门四处，
 * 只要有一处漏判，冻结就是"后台显示已冻结、实际完全不生效"的摆设 ——
 * 家长端照常登录、照常使用全部权益。这正是本次修复的问题本身。
 * 四处判定现在统一调用 [isUsable]，口径不可能漂移。
 *
 * 空值按 active 兜底：`status` 列是后加的，存量行被 ddl-auto=update 补成 NULL，
 * 直接判等会把所有老账号都当成非活跃而集体锁死。
 */
object UserStatus {
    const val ACTIVE = "active"
    const val SUSPENDED = "suspended"
    const val PENDING = "pending"

    /** 归一化：空值 → active，其余转小写（历史数据里存在 "Active" 这类写法） */
    fun of(raw: String?): String = (raw?.takeIf { it.isNotBlank() } ?: ACTIVE).lowercase()

    /** 是否允许登录并使用权益 */
    fun isUsable(raw: String?): Boolean = of(raw) == ACTIVE

    fun isSuspended(raw: String?): Boolean = of(raw) == SUSPENDED
}

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
    /**
     * 最近一次孩子端 HTTP 请求的来源 IP（局域网发现用）。
     *
     * 之所以要单独记录而不是每次现算：家长端点"发现局域网设备"时，孩子平板并不会
     * 同时发一个请求过来，服务端无从知道它此刻的 IP —— 只能取它上次露面的地址。
     * 家庭部署场景下服务端与平板在同一个网段，这个地址就是可直连的地址。
     */
    @Column(name = "last_ip") var lastIp: String? = null,
    /** 最近活跃时刻（毫秒）。可空：老库该列可能为 NULL（与 remoteLocked/lastOnlineAt 一致）；映射到基元 Long 会抛异常，故用 Long?。 */
    @Column(name = "last_active_at") var lastActiveAt: Long? = null,
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
    /**
     * 未成年人模式分龄档位：UNDER_3 / BAND_3_8 / BAND_8_12 / BAND_12_16 / BAND_16_18。
     *
     * 与策略包里的 `minorMode.ageBand` 同源，这里冗余一份是为了让家长端
     * 拉设备列表就能直接展示"这台是几岁档"，不必先解一遍 policy JSON。
     * 可空：老库加列时已有行为 NULL（表示"未设置"，按 8–12 岁档回落）。
     */
    @Column(name = "age_band") var ageBand: String? = null,
    /**
     * 操作密码闸门：家长在家长端设置的操作密码哈希（SHA-256(明文) 的 Base64，无盐）。
     *
     * 三态语义（与服务端下发、孩子端落库口径完全一致）：
     * - `null`：从未设置过（孩子端按"未启用"忽略，不拦截）；
     * - `""`（空串非 null）：家长主动清除（孩子端清除本地密码）；
     * - 非空：已启用，哈希随策略包下发给客户端，明文永不出服务端。
     */
    @Column(name = "guard_password_hash") var guardPasswordHash: String? = null,
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
// 索引必须声明在实体上，不能只写在 Flyway 迁移里：
// local / home 部署用的是 ddl-auto=update（flyway.enabled=false），
// 迁移脚本根本不执行，等于线上查询全表扫描。这里声明后两种建表方式都能拿到索引。
// idx_commands_poll 覆盖轮询下发的完整过滤条件（device_id + status + expires_at + created_at），
// 让「孩子端每 3 秒拉一次指令」走索引定位而不是捞出该设备全部历史指令再内存过滤。
@Table(
    name = "commands",
    indexes = [
        Index(name = "idx_commands_device_id", columnList = "device_id"),
        Index(name = "idx_commands_poll", columnList = "device_id, status, expires_at, created_at")
    ]
)
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
    @Column(name = "executed_at") var executedAt: Long? = null,
    /**
     * 指令回执携带的错误码 / 错误描述（可观测性）。
     * 孩子端执行失败（REJECTED / UNSUPPORTED / FAILED）时会回传，
     * 服务端落库后家长端轮询 [getCommand] 即可看到"指令为什么没生效"。
     */
    @Column(name = "error_code") var errorCode: String? = null,
    @Column(name = "error_message", columnDefinition = "text") var errorMessage: String? = null
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
@Table(
    name = "device_events",
    indexes = [Index(name = "idx_device_events_device_ts", columnList = "device_id, timestamp")]
)
class DeviceEvent(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    @Column(name = "event_id", unique = true) var eventId: String = "",
    var type: String? = null,
    var level: String? = null,
    @Column(name = "detail_json", columnDefinition = "text") var detailJson: String? = null,
    var timestamp: Long? = null
)
