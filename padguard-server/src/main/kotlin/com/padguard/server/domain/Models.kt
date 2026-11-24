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
    /**
     * 令牌版本号：登出 / 后台重置密码时 +1，签发时写进 JWT 的 `ver` 声明。
     *
     * JWT 是无状态的，"退出登录"本身没有任何服务端动作 —— 令牌在过期前一直有效。
     * 版本号是让"吊销"真正生效的最小代价方案：请求进来时拿令牌里的 ver 与这一列比对，
     * 落后即拒绝。历史令牌没有 ver 声明，按 0 处理，与本列默认值一致。
     *
     * 默认值必须写在注解上：生产（PostgreSQL + Flyway）由 V2026110301 建列为
     * `BIGINT NOT NULL DEFAULT 0`；单机/家庭部署走 ddl-auto=update（flyway 关闭），
     * Hibernate 补列时若没有默认值，只会加出一列**可空**的 bigint，存量行全被填成 NULL，
     * 而本字段是 Kotlin 非空基本类型 Long —— 读任一老账号即
     * `Null value was assigned to a property of primitive type`，
     * 表现为「所有存量家长账号登录 500」，新注册账号却完全正常（极难复现的升级故障）。
     *
     * 兜底还有一层：[com.padguard.server.config.SchemaColumnRepair] 启动时把已存在的 NULL 回填为 0，
     * 让**已经坏掉的库**也能自愈，而不只是保证新库不再坏。
     */
    @Column(name = "token_version")
    @ColumnDefault("0")
    var tokenVersion: Long = 0,
    /**
     * 连续密码错误次数。
     *
     * 从进程内 ConcurrentHashMap 搬到库里，是因为内存态有两个致命短板：
     * ① 重启即清零 —— 攻击者只要能诱发一次重启（或赶上一次发布）就能白拿 N 次重试额度；
     * ② 多实例各算各的 —— 5 次锁定在 3 个实例下实际是 15 次，扩缩容还会随机重置。
     * 落库后由数据库行锁保证自增不丢，与实例数无关。
     *
     * 用 `coalesce` 语义兜底：列是后加的，存量行为 NULL，读取方必须按 0 处理。
     */
    @Column(name = "login_fail_count") var loginFailCount: Int? = 0,
    /** 锁定截止时刻（毫秒）；NULL 或已过期表示未锁定 */
    @Column(name = "login_locked_until") var loginLockedUntil: Long? = null,
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
     * 订阅权益闸门状态（ACTIVE / SUSPENDED）。
     *
     * 修复"订阅到期后管控冻结在最后状态"的关键记账位：此前订阅只在**绑定时**校验一次
     * （ensureCanBindForChild），到期后孩子端继续执行最后一版策略——锁屏永远锁着、
     * 黑名单继续拦截，而家长端全部控制接口已被订阅拦截器挡住，平板只能等运营人工解锁。
     *
     * 闸门在**孩子端必经路径**（心跳 / 拉策略）上对比此列与实时权益：
     * 翻转沿触发一次"放开/恢复"策略重建（版本号前进，孩子端自动拉取）。
     * 写入走条件 UPDATE（updateEntitlementState），绝不整行 merge 覆盖并发绑定。
     *
     * 列定义里**刻意不写 `default 'ACTIVE'`**：写了会让 Hibernate 在 H2 上每次启动都发
     * `alter table ... alter column ... set data type varchar(20) default 'ACTIVE'`，
     * 而 H2 2.x 不支持"改类型 + 带默认值"的组合语法，直接报 42000 语法错误 ——
     * 结果是每启动一次就往 data/padguard.trace.db 里堆一段堆栈，真故障反而被淹没。
     * 默认值改由两处保证：Flyway 迁移 V2026110703 的 DDL DEFAULT，以及下面的 Kotlin 初值。
     * `length = 20` 必须保留：否则 Hibernate 会认为库里是 varchar(20)、实体是 varchar(255)
     * 而反复尝试加宽列。
     */
    @Column(name = "entitlement_state", length = 20)
    var entitlementState: String = "ACTIVE",
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
    /**
     * 家长/监护人「撤回授权」标记（PIPL 撤回同意）。
     *
     * 一旦为 true：① 服务端 [IngestService] 停止落库该设备的个人数据（用量/位置/截图等）；
     * ② 作为孩子端本地停采的信号源（设备下次拉策略/心跳即读到，离线也能在本地生效）。
     * 可空：老库加列时已有行为 NULL，非空 Boolean 会在读取时抛异常；DTO 出口兜底成 false。
     */
    @Column(name = "consent_revoked", columnDefinition = "boolean default false")
    var consentRevoked: Boolean? = false,
    @Column(name = "created_at") var createdAt: Long = 0
)

/**
 * 不满十四周岁监护人单独同意凭证（PIPL 第 29/31 条）。
 *
 * 只记录「家长申报出生日期 + 勾选单独同意 + 同意文案版本」的留痕，
 * **不是实名年龄核验结果**。设备/账号归属由 [GuardianConsentRepository] 查询时按 user_id 收口。
 */
@Entity
@Table(
    name = "guardian_consents",
    indexes = [Index(name = "idx_guardian_consents_user_device", columnList = "user_id, device_id")]
)
class GuardianConsent(
    @Id var id: String = "",
    @Column(name = "user_id") var userId: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    /** ISO 日期 yyyy-MM-dd；只做 <14 判定与留痕，不做实名核验。 */
    @Column(name = "child_birth_date") var childBirthDate: String = "",
    var consent: Boolean = false,
    /** 家长勾选的隐私政策/单独同意文案版本，便于事后还原"当时同意了什么"。 */
    @Column(name = "consent_text_version") var consentTextVersion: String = "",
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
// 与 V2026110701 的 Flyway 索引同名同列：local/home profile 关掉 Flyway、
// 用 ddl-auto=update 建表，索引只写在迁移脚本里在这些环境等于没有。
@Table(
    name = "policies",
    indexes = [Index(name = "idx_policies_device_version", columnList = "device_id, version DESC")]
)
class Policy(
    @Id var id: String = "",
    @Column(name = "device_id") var deviceId: String = "",
    var version: Int = 0,
    @Column(name = "package_json", columnDefinition = "text") var packageJson: String? = null,
    var scene: String? = null,
    @Column(name = "effective_from") var effectiveFrom: Long? = null,
    @Column(name = "effective_to") var effectiveTo: Long? = null,
    /**
     * 策略行来源：NORMAL（子表重建的常规策略）/ ROLLBACK（版本回滚产物）。
     * ROLLBACK 行会被 `PolicyExtensionService.ensureRebuilt` 跳过，否则历史快照
     * 会在设备下一次拉策略时被「子表 → 策略包」自愈覆盖，回滚变成假成功。
     * 默认值保持 NORMAL，存量行由 Flyway 的 DEFAULT 填充，无需回填。
     */
    @Column(name = "source") var source: String = "NORMAL",
    /** 该行是回滚产物时，记录被回滚到的历史版本号 */
    @Column(name = "rollback_of") var rollbackOf: Int? = null,
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
        Index(name = "idx_commands_poll", columnList = "device_id, status, expires_at, created_at"),
        // W2 补发查询：device_id + status + 仍在保留期内，按 created_at 做 FIFO。
        // 生产走 Flyway V2026110601，本地 create-drop 走这里，两处都声明才不会漏索引。
        Index(name = "idx_commands_redelivery", columnList = "device_id, status, redelivery_deadline_at, created_at")
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
    @Column(name = "error_message", columnDefinition = "text") var errorMessage: String? = null,

    // ---------- W2：离线指令补发 ----------
    /**
     * 已补发次数。原子条件 UPDATE 递增，达到配置上限后不再补发，
     * 由 ExpiredCommandSweeper 收敛为 EXPIRED，避免一条永远送不达的指令无限重放。
     */
    @Column(name = "delivery_count") var deliveryCount: Int = 0,
    /**
     * 补发保留期截止时刻 = createdAt + retention。
     *
     * **NULL 表示不参与补发**（存量数据、以及不在可补发白名单内的指令类型）。
     * 刻意用 NULL 而不是回填一个统一时间：上线瞬间把历史积压的旧解锁/采集指令
     * 全量重放是合规与业务双重事故，fail-closed 比"尽量送达"更重要。
     */
    @Column(name = "redelivery_deadline_at") var redeliveryDeadlineAt: Long? = null,
    /** 最近一次补发刷新时刻，供运维排查「这条指令被重放过几次/何时」 */
    @Column(name = "last_delivery_at") var lastDeliveryAt: Long? = null
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
