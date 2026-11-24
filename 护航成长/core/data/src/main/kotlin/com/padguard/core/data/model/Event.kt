package com.padguard.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 行为日志（对应接口契约 §5.4）。
 * 本地落库时以 [logId] 做幂等，服务端重复上报安全。
 */
@Serializable
data class BehaviorLog(
    @SerialName("logId") val logId: String,
    @SerialName("type") val type: LogType,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("payload") val payload: Map<String, String> = emptyMap(),
    /** 是否已成功上报 */
    val uploaded: Boolean = false
)

@Serializable
enum class LogType {
    APP_USAGE,

    /**
     * 当日全局使用时长（累计值，非增量）。
     *
     * 为什么与 APP_USAGE 分开："今日总时长"来自 daily_usage（亮屏即累计），
     * 不依赖"使用情况访问"权限；而 APP_USAGE 依赖 UsageStats 前台探测，
     * 权限缺失时一条都发不出来 —— 若服务端只用 APP_USAGE 汇总，
     * 家长端"今日平板使用情况"在未授权设备上永远是 0（实际故障）。
     * 服务端统计总时长优先取 USAGE_TOTAL，没有时才回退累加 APP_USAGE。
     *
     * ## 上报语义（2026-10-03 修正）
     * 每条携带「截至当前的当天累计秒」(durationSec)，并带 `cumulative=true` 标记。
     * 同一天会收到多条（每 ~10s 一条），服务端按「当天取最大值」还原真实累计，天然幂等——
     * 彻底取代旧"增量 + 已上报量台账"模型（旧模型在重启/日切时基准丢失会重复累加，导致高估数倍）。
     * payload 约定：{ durationSec, dayKey, startAt, endAt, cumulative }
     */
    USAGE_TOTAL,
    APP_INSTALL,
    APP_UNINSTALL,
    URL_VISIT,
    URL_BLOCKED,
    SCREEN_ON_OFF,
    PERIPHERAL_CHANGE,
    SETTING_CHANGE_ATTEMPT,
    COMMAND_EXEC,
    DEVICE_BOOT,
    LOCATION,

    /**
     * 孩子端发起的临时解锁申请（契约扩展项，V1.1 未定义）。
     *
     * 复用日志队列而不是新开一个接口：申请本质上就是一条"需要送达服务端且允许延迟"的上行记录，
     * 日志队列已经解决了离线堆积、批量上报、logId 幂等这三件事，
     * 再造一套 pending 队列纯属重复劳动。服务端识别到该 type 后转为待审批工单即可。
     * payload 约定：{ requestId, packageName, appLabel, durationMinutes, reason }
     */
    UNLOCK_REQUEST,

    /**
     * 使用授权协议签署（说明书 §5.4 合规留存）。
     * 孩子点击「同意并授权」后写入，服务端据此向管控端展示「已阅已同意」。
     * payload 约定：{ version, signedAt, snMask }
     */
    AGREEMENT,

    /**
     * 通知代收（说明书 §6.9，默认关闭，须家长显式开启）。
     *
     * 仅采集「来源包名 + 通知标题」，明确**不含正文/内容**（隐私最小化）。
     * 数据经既有日志批量通道上行，服务端按 type 落库，家长端可在「通知记录」中查看。
     * 默认不开启：监听器仅在家长开启开关且已授予系统「通知使用权」后才采集，
     * 否则监听器虽可运行但静默放行（fail-closed）。
     * payload 约定：{ packageName, title, postedAt }
     */
    NOTIFICATION_COLLECTED
}

/**
 * 风险告警事件（对应接口契约 §4.4）。
 * HIGH 级事件走 MQTT QoS1 优先上报，不受批量日志窗口限制。
 */
@Serializable
data class RiskEvent(
    @SerialName("eventId") val eventId: String,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("type") val type: RiskType,
    @SerialName("level") val level: RiskLevel,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("detail") val detail: Map<String, String> = emptyMap()
)

@Serializable
enum class RiskType {
    UNINSTALL_ATTEMPT,
    FORCE_STOP_ATTEMPT,
    CLEAR_DATA_ATTEMPT,
    CLOCK_TAMPERING,
    ROOT_DETECTED,
    DEVELOPER_OPTIONS_ENABLED,
    USB_DEBUG_ENABLED,
    BLACKLIST_APP_LAUNCH,
    BLOCKED_URL_ACCESS,
    TIME_LIMIT_EXCEEDED,
    PERMISSION_REVOKED,
    POLICY_APPLY_FAILED,

    /**
     * 部分管控项因**本机能力不足**未能生效（预期内降级，非故障）。
     *
     * 与 [POLICY_APPLY_FAILED] 的区别必须分清，否则家长会被误导：
     * - `POLICY_APPLY_FAILED` = 该做的没做成（权限被拒、ROM 异常），需要排查；
     * - `POLICY_DEGRADED` = 这台机器本来就做不到（无设备所有者 → 不能隐藏/挂起应用、
     *   不能锁死设置；系统版本低 → 无对应 API）。策略本身没问题，换台机器就能生效。
     *
     * 为什么必须上行：`EnforceReport.unsupported` 此前只在本地日志里打一行，
     * 家长看到的是"策略已下发、设备在线"，实际有若干项一条都没执行，
     * 于是反复投诉"我明明禁了应用，孩子照样能开"而无从判断原因。
     * payload 约定：{ version, unsupportedCount, unsupported }
     */
    POLICY_DEGRADED,
    INVALID_SIGNATURE,
    HEARTBEAT_TIMEOUT,

    /** S12：无障碍服务被关闭 —— 设置页拦截 / 锁屏抢占依赖它，失活 = 拦截层整体失效 */
    ACCESSIBILITY_DISABLED,

    /** S12：使用情况访问权限被关闭 —— 应用用量采集失明，家长端时长统计失真 */
    USAGE_ACCESS_LOST,

    /**
     * S11：操作密码尚未设置 —— 闸门"建好了但没有锁芯"。
     * 无障碍拦截会把孩子顶离设置页（防先关无障碍再脱管），但没有密码就无法验证
     * 家长身份，家长自己改设置也要靠密码。这是配置未完成的信号，需提醒家长补设。
     */
    GUARD_PASSWORD_NOT_SET,

    /**
     * 锁屏完全失控（最高危）。
     *
     * 触发条件：设备既无系统级硬锁（非设备所有者、设备管理员未激活，于是 `lockNow()`
     * 与屏幕固定均不可用），又无悬浮窗权限（应用内锁定层 TYPE_APPLICATION_OVERLAY 铺不出来）。
     * 此时三条锁屏通道（悬浮窗 / 后台拉 Activity / 全屏通知）外加系统级 `lockNow` **全部失效**，
     * 家长下发锁屏指令后孩子可以滑回桌面照常使用 —— 管控形同虚设。
     *
     * 这种"以为锁了其实没锁"的洞比任何漏拦都危险，必须让家长立刻知道，并引导其在装机流程中
     * 授予悬浮窗权限或激活设备管理员。上层据此如实上报 HIGH 告警，不再静默空转。
     */
    LOCK_SCREEN_UNPROTECTED,

    /**
     * 关键权限被系统静默撤销（最高危，仅次于 LOCK_SCREEN_UNPROTECTED）。
     *
     * 触发场景：在系统设置里授予/切换某些特殊权限（如国产 ROM 的"后台弹出界面"
     * /悬浮窗）时，系统会强制重启本应用，重启后无障碍服务绑定被解除、MediaProjection
     * 录屏授权被清空——表现为"之前打开的无障碍、允许录屏等权限全部被取消"。
     * 若无障碍失效，设置/应用拦截层整体瘫痪；若录屏授权丢失，看屏/录屏下发即失败。
     * 必须让家长立刻知道并重新授权，而不是让孩子端静默处于"管控残缺"状态。
     */
    GUARD_PERMISSION_DROPPED,

    /**
     * 守护进程曾被系统清理/中断并已自动恢复（2026-11-17 EMUI 一键清理事故闭环，NORMAL）。
     *
     * 触发判定（[com.padguard.child.guard.CleanKillDetector]）：主进程每 15s 向
     * `guard_alive` 心跳文件原子写 elapsedRealtime；服务启动时若发现上次心跳就在
     * 10 分钟内而本进程是全新创建的 —— 上次进程是活着被系统杀掉的（一键清理 /
     * 清理加速 / 内存回收 / 滑除卡片 / 崩溃）。设备重启（单调钟归零）、新装、
     * 用户强停（stopped 态屏蔽一切自启，服务回不来）都不会误报。
     *
     * 为什么必须上行：ROM 清理白名单（华为启动管理 + 多任务卡片加锁）无查询接口，
     * 装机向导只能文字指引、无法校验家长是否真做完。本告警是「白名单是否生效」的
     * **唯一行为证据**——配好则永不出现，反复出现 = 防清理设置未生效，家长可据此
     * 补做设置，而不是等下一次清理把无障碍也带走（那时管控已裸奔过一轮）。
     * payload 约定：{ dayCount, gapSec, note }
     */
    GUARD_PROCESS_RECYCLED,

    /**
     * 守护服务申请前台服务（FGS）被系统拒绝，已降级为普通后台服务（高危，不再静默）。
     *
     * Android 12+ 从后台启动 FGS 会抛 ForegroundServiceStartNotAllowedException；
     * 非 DO / 被 ROM 拦截自启 / 电池优化生效时是真实高频路径。此时进程失去前台豁免，
     * 随时可被系统回收，锁屏/截屏/定位指令会石沉大海。旧实现只记日志、心跳照写，
     * 家长端看到"设备在线"却已处于随时掉线状态。必须上报告警让家长知情。
     */
    GUARD_FOREGROUND_DOWN,

    /**
     * 设备上新装了应用（R-P3-10）。
     *
     * 为什么必须上行：家长端此前只能看到"当前装了哪些应用"的台账快照，
     * 看不到"什么时候多出来的"。孩子趁家长不在时装一个游戏，只要家长没主动去翻列表，
     * 这件事就永远不会被知道 —— 而"新装即受管控"（R-P2-19）只保证它被拦，
     * 不保证家长知情。
     *
     * 数据来源是**台账差分**（[com.padguard.child.monitor.AppInventoryDeltaTracker]），
     * 不是广播监听：非 DO 设备无权监听 PACKAGE_ADDED 的全量事件，
     * 而台账本身每 30 分钟就要采一次，顺手差分零额外开销。
     * payload 约定：{ packageName, label }
     */
    APP_INSTALLED,

    /**
     * 设备上卸载了应用（R-P3-10）。
     *
     * 与 [UNINSTALL_ATTEMPT] 的区别必须分清：
     * - `UNINSTALL_ATTEMPT` = 有人试图卸载**本管控应用**（篡改信号，HIGH）；
     * - `APP_UNINSTALLED` = 某**普通应用**从设备上消失了（行为记录，NORMAL）。
     * 后者既是"孩子自己删了学习软件"的证据，也是"家长远程卸载已生效"的回执，
     * 两种都值得让家长看到。payload 约定：{ packageName, label }
     */
    APP_UNINSTALLED,

    /**
     * 调试版（debug-signed）应用在已绑定（生产）设备上运行（§4.3 项 3 的一半）。
     *
     * 为什么必须上报：release 部署才合规——debug 构建携带明文日志、可被调试、签名与
     * 生产证书不同，绝不应出现在真实受管孩子设备上。若运维/装机误把 debug 包刷到
     * 已绑定设备，家长端会据此标记「部署错配」，而不是以为一切正常。
     *
     * 口径：仅在 `isDebuggableBuild && 设备已绑定` 时触发；开发/未绑定设备（isDebuggableBuild
     * 为真但未绑定）属正常测试态，不告警。release 构建 `isDebuggableBuild=false`，永不触发——
     * 因此本检测**零误报**。
     *
     * 安装来源/重打包渠道校验（项 3 的另一半）**未在本端落地**：本应用经家长直装 APK 分发，
     * 对「非白名单渠道」上报会在正常安装时误报刷屏；且真正的重打包防护需服务端绑定握手校验签名
     * （跨端协议），本地不虚构该能力。
     */
    DEBUG_BUILD_ACTIVE,

    /**
     * 设备正通过代理 / VPN 联网（P0-3）。
     *
     * 导入自定义 CA 的主动中间人（Fiddler / Charles）常配合代理或 VPN 解密观测 HTTPS / MQTT 流量。
     * 本端已用证书固定阻断解密，但代理 / VPN 本身是"孩子在尝试绕过观测"的强信号，
     * 命中即上报，让家长立刻可见并提示检查网络环境。不主动阻断（避免误伤正常企业 / VPN 网络）。
     */
    PROXY_DETECTED,

    /**
     * 管控完整性丢失（P0-2）：设备曾成功绑定，但当前已无绑定凭据。
     *
     * 典型触发：孩子在设置里「清除应用数据」——凭据(hmacSecret)与本地策略缓存一并被抹掉，
     * 本地时长 / 应用管控瞬间全部失效，且因 hmacSecret 丢失无法再校验服务端指令。
     * 清数据后 AndroidKeyStore 的"曾绑定"标记仍在（清数据不删密钥库条目，API 24+），
     * 故可判定为「失管」而非「初次装机」，上报 HIGH 并驱动失效锁定，把"沉默逃逸"变成可见事件。
     */
    CONTROL_LOST,

    /**
     * 默认桌面被替换为第三方（P1-2）。
     *
     * 非 Device Owner 设备上，系统不强制常驻桌面，孩子可在设置里把默认桌面换成系统桌面或
     * 第三方 launcher，此后按 HOME 键即跳出管控。命中即上报 HIGH 并即时夺回焦点，
     * 让家长端可见、把"换桌面静默逃逸"变成可干预事件。治本仍需把设备设为 DO（见报告 P2）。
     *
     * payload 约定：{ homePackage }
     */
    HOME_CHANGED,

    /**
     * 网页过滤隧道应在跑却没在跑（A1-P0 看门狗）。
     *
     * 非 DO 设备下 Web 过滤是 DNS-only 的 VpnService：隧道一旦停止，设备改用系统 DNS，
     * 全部域名不再过滤（架构性 fail-open，无法根治，需 DO + 系统防火墙）。
     * 本告警是工程兜底——检测到"策略要求过滤但隧道没在跑"时立即上报，
     * 让家长端第一时间看到"网页防护掉了"，而不是让孩子端静默处于无过滤状态。
     *
     * 触发：主循环看门狗发现 `requiredBy(policy) && !isActive()` 且重启失败（如后台 FGS 限制）。
     * 不替代重启动作本身（看门狗仍会尽力 `start()`），只负责"尽力后仍失败→让家长知情"。
     */
    WEB_GUARD_DOWN
}

@Serializable
enum class RiskLevel { HIGH, NORMAL, INFO }

/**
 * 心跳包（对应接口契约 §4.3）
 *
 * 五项硬件字段改为**可空**：它们受策略开关 `monitoring.collectHardware` 门控
 * （见 [com.padguard.core.engine.enforcer.MonitoringRuntime.collectHardware]）。
 * 该开关此前在端上收得下、存得下，却没有任何一处读取它 —— 家长关掉"采集硬件信息"，
 * 设备照样每个心跳把电量、存储、内存、网络、屏幕状态全量上传，
 * 开关形同虚设。现在关闭时这些字段为 null，服务端对应字段本就可空，链路无需改动。
 *
 * 注意：`deviceId / timestamp / foregroundApp` 等**不属于**硬件采集范畴，
 * 不受该开关影响 —— 它们是心跳本身的可信性基础，缺了就无法判断设备是否在线。
 */
@Serializable
data class Heartbeat(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("policyVersion") val policyVersion: Int,
    @SerialName("transportMode") val transportMode: String,
    @SerialName("controlMode") val controlMode: String,
    @SerialName("network") val network: NetworkInfo? = null,
    @SerialName("battery") val battery: BatteryInfo? = null,
    @SerialName("storage") val storage: StorageInfo? = null,
    @SerialName("memory") val memory: MemoryInfo? = null,
    @SerialName("screen") val screen: ScreenInfo? = null,
    @SerialName("foregroundApp") val foregroundApp: String,
    @SerialName("location") val location: LocationInfo? = null,
    @SerialName("pendingLogCount") val pendingLogCount: Int,
    // ============ 守护状态快照（P3 可观测性，2026-10-20 新增） ============
    // 全部带默认值：旧服务端（HeartbeatDto 已 @JsonIgnoreProperties(ignoreUnknown = true)）无需改动即可兼容；
    // 新字段随既有 15s 心跳通道上报，不新增网络量级。
    /** 独立守护进程(:daemon)是否存活（跨进程读 GuardHeartbeat 心跳文件判定，比进程内静态标志可靠） */
    @SerialName("daemonAlive") val daemonAlive: Boolean = false,
    /** 操作闸门无障碍服务是否真正在运行（开关打开且近期有心跳，见 GuardA11y.isAlive） */
    @SerialName("a11yAlive") val a11yAlive: Boolean = false,
    /** 在线三态：ONLINE / MQTT_DOWN（PROCESS_DOWN 由服务端据"无心跳"推断） */
    @SerialName("onlineState") val onlineState: String = "",
    /** 本服务最近一次被拉起的触发原因（boot/daemon/watchdog/alarm/screenOn/unknown），用于区分进程是被谁拉回 */
    @SerialName("lastRestartReason") val lastRestartReason: String = "",
    /** 权限自愈版本号：运行期危险权限被实际重新授予一次 +1，服务端据以识别"权限状态是否变化" */
    @SerialName("permissionVersion") val permissionVersion: Int = 0
)

@Serializable
data class NetworkInfo(
    @SerialName("type") val type: String,
    @SerialName("ssid") val ssid: String = "",
    @SerialName("signalLevel") val signalLevel: Int = 0,
    @SerialName("ip") val ip: String = ""
)

@Serializable
data class BatteryInfo(
    @SerialName("level") val level: Int,
    @SerialName("charging") val charging: Boolean,
    @SerialName("temperature") val temperature: Float = 0f
)

@Serializable
data class StorageInfo(
    @SerialName("totalMb") val totalMb: Long,
    @SerialName("availableMb") val availableMb: Long
)

@Serializable
data class MemoryInfo(
    @SerialName("totalMb") val totalMb: Long,
    @SerialName("availableMb") val availableMb: Long
)

@Serializable
data class ScreenInfo(
    @SerialName("on") val on: Boolean,
    @SerialName("brightness") val brightness: Int = -1
)

@Serializable
data class LocationInfo(
    @SerialName("lat") val lat: Double,
    @SerialName("lng") val lng: Double,
    @SerialName("accuracy") val accuracy: Float = 0f,
    @SerialName("ts") val ts: Long,
    @SerialName("provider") val provider: String = "",
    /**
     * true=当场向系统要到的新坐标；false=系统无新坐标、降级上传的缓存旧点。
     * 配合 [ts] 使用真实采集时间（fix.time）而非上报时刻，家长端据此判断位置是否新鲜，
     * 避免"历史位置被误显示为刚刚更新"。
     */
    @SerialName("fresh") val fresh: Boolean = true
)

/** 绑定响应（对应接口契约 §2.2） */
@Serializable
data class BindResult(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceToken") val deviceToken: String,
    @SerialName("mqttUsername") val mqttUsername: String,
    @SerialName("mqttPassword") val mqttPassword: String,
    @SerialName("hmacSecret") val hmacSecret: String,
    @SerialName("expiresAt") val expiresAt: Long = 0L,
    /**
     * 可选：服务端指定的接入点（契约 V1.1 新增，缺省沿用构建期配置）。
     * 私有化部署场景下同一个 APK 要能对接不同学校的服务器，
     * 由绑定响应下发接入点可避免为改域名重新发包。
     */
    @SerialName("baseUrl") val baseUrl: String = "",
    @SerialName("mqttBroker") val mqttBroker: String = "",
    /** 可选：多租户隔离标识，用于 MQTT topic 前缀（契约 §9.2 待服务端确认） */
    @SerialName("tenantId") val tenantId: String = ""
)

/** 单应用当日使用统计，dayKey 为 yyyyMMdd */
@Serializable
data class AppUsageStat(
    val packageName: String,
    val dayKey: String,
    val usedMs: Long,
    val launchCount: Int,
    val lastUpdateAt: Long,
    /** 当日首次使用的墙钟时间（家长端"开始使用时间"） */
    val firstUsedAt: Long = 0L,
    /** 当日最后一次使用的墙钟时间（家长端"停止使用时间"） */
    val lastUsedAt: Long = 0L
)
