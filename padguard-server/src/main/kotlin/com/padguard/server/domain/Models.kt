package com.padguard.server.domain

import jakarta.persistence.*
import org.hibernate.annotations.ColumnDefault

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
    // ===== P3 守护健康度快照（孩子端心跳上报，服务端持久化）=====
    @Column(name = "daemon_alive") var daemonAlive: Boolean? = false,
    @Column(name = "a11y_alive") var a11yAlive: Boolean? = false,
    /** 孩子端上报的在线三态（ONLINE / MQTT_DOWN），与服务端自身 onlineStatus 区分展示 */
    @Column(name = "daemon_online_state") var daemonOnlineState: String? = null,
    @Column(name = "last_restart_reason") var lastRestartReason: String? = null,
    // 可空：与上方 daemonAlive / a11yAlive 等字段同理——ddl-auto 给老库加列时已有行是
    // NULL，非空 Int 在读取时会抛 "Can not set int field to null value"，导致设备列表 / 接入
    // 等所有加载 Device 的接口 500。DTO 出口处已按 Int? 处理，这里对齐即可。
    @Column(name = "guard_permission_version") var permissionVersion: Int? = 0,
    /** 守护进程最近一次上报 alive 的时间戳，用于 T3 失联判定的时效窗口 */
    @Column(name = "daemon_alive_at") var daemonAliveAt: Long? = null,
    /** T3 守护失联告警已推送标记，避免重复告警（守护恢复后由 applyHeartbeat 清零） */
    @Column(name = "daemon_lost_alerted", columnDefinition = "boolean default false")
    var daemonLostAlerted: Boolean? = false,

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

/**
 * 一次「设备接入」。
 *
 * 这是家长把一台平板纳管的**唯一入口状态机**：家长端发起 → 出示二维码/配对码 →
 * 孩子端消费 → 服务端落设备并回写 → 家长端收到结果。全程只有这一个实体在流转，
 * 二维码与配对码都只是它的呈现形式（[pairingCode] 的两种表达），
 * 因此"二维码里的码和显示的配对码不是同一个"在模型层面不可能发生。
 *
 * 状态机见 [com.padguard.server.service.EnrollmentService] 的 STATE_* 常量：
 * PENDING →（孩子端消费）→ BOUND；或（超时）→ EXPIRED；或（家长主动作废）→ CANCELLED。
 */
@Entity
@Table(name = "enrollment_sessions")
class EnrollmentSession(
    /** 会话身份：家长端订阅与服务端推送都以此为键，与短码解耦 */
    @Id var id: String = "",
    @Column(name = "user_id") var userId: String = "",
    /**
     * 孩子端输入/扫码用的 6 位短码。
     *
     * 对外仍是 6 位数字（孩子端契约 `BindRequest.bindCode` 不变，孩子端无需改动），
     * 但它现在是**会话的一个属性**而不是会话本身 —— 换码不影响会话身份，家长端仍订阅同一个 [id]。
     */
    @Column(name = "pairing_code") var pairingCode: String = "",
    var state: String = "PENDING",
    /** 绑定结果：设备台账归属在这里，不再回写到凭证上 */
    @Column(name = "device_id") var deviceId: String? = null,
    @Column(name = "expires_at") var expiresAt: Long = 0,
    @Column(name = "bound_at") var boundAt: Long? = null,
    @Column(name = "created_at") var createdAt: Long = 0,
    /**
     * 乐观锁版本号：**本次接入的所有写入都经由这个实体**，并发安全由它保证。
     *
     * 为什么必须有它（一个真实踩过并在线上复现的坑）：
     * 早期版本用一条 `@Modifying` 条件 UPDATE 抢占会话（`update ... set state='BOUND'
     * where pairing_code=? and state='PENDING'`），再由另一处 `save()` 回写绑定结果。
     * 两条写路径指向同一行，而 `@Modifying` 绕过持久化上下文——缓存里的实体仍是 PENDING，
     * 随后回写 `deviceId` 的 `save()` 在提交时做脏检查，把整行（含 state）写回库，
     * 于是数据库里刚被 SQL 改成 BOUND 的状态被覆盖回 PENDING。
     * 表现为：设备明明绑上了、deviceId 也回写了，家长端查询却永远停在"等待孩子扫码"。
     *
     * 加了它之后，抢占不再需要绕过实体的 SQL：状态迁移直接在实体上做，
     * 提交时 Hibernate 发出 `update ... where id=? and revision=?`，
     * 两个孩子端同时提交同一短码时，后提交者影响行数为 0 → 乐观锁冲突 → 整笔事务回滚，
     * 与原来那条条件 UPDATE 的排他性等价，但写路径只剩一条，不可能再互相覆盖。
     *
     * 默认值 0 必须写在注解上：本项目的两条部署路径都要能建出这一列 ——
     * 生产（PostgreSQL + Flyway）由 V2026101302 施加，单机/家庭部署（H2 + ddl-auto=update）
     * 由 Hibernate 补列。后者生成的是 `alter table ... add column revision bigint not null`，
     * 表里一旦已经有历史会话行，无默认值的 NOT NULL 加列会在启动时直接失败
     * （`NULL not allowed for column "revision"`），而失败被 Hibernate 记为 WARN 不阻断启动，
     * 于是列静默缺失、后续所有接入请求才报错 —— 属于要等到用户使用时才炸的延迟故障。
     */
    @Version
    @Column(name = "revision", nullable = false)
    @ColumnDefault("0")
    var revision: Long = 0
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
