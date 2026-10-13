package com.padguard.server.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// ============ 控制端（家长） ============
data class LoginPasswordRequest(val phone: String, val password: String)
data class LoginSmsRequest(val phone: String, val smsCode: String)
data class SendSmsRequest(val phone: String)

/**
 * 操作密码闸门：家长端设置/清除「设备操作密码」的请求。
 *
 * - [password] 非空 → 设置密码（服务端按 SHA-256(明文) 的 Base64 哈希落库，明文不出服务端）；
 * - [clear] 为真 → 清除密码（设备恢复为「改动无需密码」）；
 * - 两者皆空/假 → 视为无操作。
 *
 * 校验：密码非空时长度须 ≥ [com.padguard.server.security.GuardPasswords.MIN_LENGTH]，
 * 否则返回 400，绝不接受弱口令。
 */
data class GuardPasswordRequest(
    val password: String? = null,
    val clear: Boolean = false
)

/**
 * 家长端手机号自助注册。
 *
 * 采用「手机号 + 密码」：本项目短信服务商尚未接入（发码是假的、验证码也不校验，
 * 即一条免密入口，已禁用 sms-login），故不引入短信验证码步骤，避免既不可信又留安全漏洞；
 * 密码登录走 [Passwords] 的哈希校验，是已验证的安全路径。手机号作为唯一登录标识（unique）。
 *
 * [nickname] 可选：不传则由服务端按「家长 + 手机号后四位」生成默认昵称。
 */
data class RegisterRequest(
    val phone: String,
    val password: String,
    val nickname: String? = null
)

data class UserDto(
    val id: String, val phone: String, val nickname: String?,
    val avatar: String?, val role: String, val sceneType: String
)

data class LoginResponse(val user: UserDto, val token: String, val refreshToken: String)
data class TokenResponse(val token: String, val refreshToken: String)

data class BindCodeResponse(val bindCode: String, val expiresAt: Long)

/**
 * 家长端轮询绑定码状态。
 *
 * [state] 三态：`PENDING` 尚未完成、`BOUND` 已绑定、`EXPIRED` 已过期或无效。
 * 区分三态而不是只回"用没用过"，是为了让家长端能分辨"孩子还没扫"与"码已失效"——
 * 否则家长会对着一个已经过期的码反复重试，却只看到一句无差别的"等待超时"。
 */
data class BindCodeStatusResponse(
    val state: String,
    /** 绑定到的设备 ID；`state=BOUND` 时必有值 */
    val deviceId: String?,
    val expiresAt: Long
)

// ---------------------------------------------------------------- 计费 / 订阅

/** 可购买的套餐（价格与设备上限来自配置，运营调价不需要改代码） */
data class PlanDto(
    val key: String,
    val label: String,
    val months: Int,
    /** 金额（分） */
    val amountFen: Int,
    /** 展示用价格，如 "¥38" */
    val priceText: String,
    val deviceLimit: Int
)

/**
 * 收银台上的一个支付渠道。
 *
 * [mock] 必须为**显式字段**而不是让客户端猜：当前所有渠道都是示例支付，
 * 若客户端默认"能选就是真的"，将来接真渠道时会把示例通道当成真支付上线。
 * 有了这个位，UI 可以直接打上"示例"角标，接入真实渠道时把它置 false 即可。
 */
data class PayChannelDto(
    val key: String,
    val label: String,
    /** 是否可用：false 时 UI 置灰（例如生产环境尚未接入） */
    val enabled: Boolean,
    /** 当前是否为示例支付（不产生真实扣款） */
    val mock: Boolean,
    /** 渠道说明，展示在收银台给家长看 */
    val note: String? = null
)

/**
 * 当前订阅状态。
 *
 * [active] 与 [paid] 是两个维度，必须分开：
 * 试用期内 active=true 但 paid=false，此时允许使用、但要持续提醒；
 * 客户端若只看 paid 就会在试用期把用户挡在门外，只看 active 又不会催续费。
 */
data class SubscriptionDto(
    val status: String,
    val plan: String,
    val planLabel: String,
    val deviceLimit: Int,
    val deviceUsed: Int,
    val startedAt: Long,
    val expiredAt: Long,
    /** 剩余天数，向下取整；已过期为 0 */
    val remainDays: Int,
    val paid: Boolean,
    val active: Boolean,
    val autoRenew: Boolean
)

/** 下单结果。[payParams] 为第三方支付所需的下单参数，后台开通渠道为 null。 */
data class CreateOrderResponse(
    val orderNo: String,
    val amountFen: Int,
    val channel: String,
    val payDeadline: Long,
    val payParams: Map<String, Any?>? = null
)

data class OrderDto(
    val orderNo: String,
    val plan: String,
    val planLabel: String,
    val amountFen: Int,
    val priceText: String,
    val channel: String,
    val status: String,
    val createdAt: Long,
    val paidAt: Long?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CreateOrderRequest(
    val plan: String,
    val channel: String? = null
)

/** 支付回调 / 后台开通：[orderNo] 必填，[transactionId] 为第三方流水号 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PayRequest(
    val orderNo: String,
    val transactionId: String? = null
)

data class DeviceDto(
    val id: String, val name: String?, val deviceId: String, val model: String?,
    val osVersion: String?, val appVersion: String?, val onlineStatus: String,
    val lastOnlineTime: Long?, val batteryLevel: Int?, val controlMode: String?,
    val sceneMode: String?, val groupId: String?, val groupName: String?,
    val sceneType: String?, val latitude: Double?, val longitude: Double?,
    /** 家长是否仍处于远程锁屏状态；驱动家长端「锁屏 / 解锁」按钮的切换 */
    val remoteLocked: Boolean = false,
    /** 孩子端自定义资料：姓名 */
    val childName: String? = null,
    /** 孩子端自定义资料：昵称 */
    val childNickname: String? = null,
    /** 孩子端自定义资料：头像 URL */
    val childAvatar: String? = null,
    /**
     * 未成年人模式分龄档位（UNDER_3 / BAND_3_8 / BAND_8_12 / BAND_12_16 / BAND_16_18）。
     * null = 家长尚未选择，端上按 8–12 岁档回落。
     */
    val ageBand: String? = null,
    /**
     * 设备操作密码是否已设置（由 [com.padguard.server.domain.ModelsKt] 的
     * `guardPasswordHash` 派生：非空即视为已设）。
     * 家长端「设备详情」与「应用管理」两处共用这一事实来源，
     * 不再依赖各页面易失的会话内乐观标记，避免冷启动时状态失真。
     */
    val guardPasswordSet: Boolean = false
)

data class LockRequest(val reason: String? = null)
data class ModeRequest(val mode: String)

/**
 * 未成年人模式设置（一键启动 / 切换龄档 / 家长豁免）。
 *
 * 除了 [enabled] 与 [ageBand]，其余字段全部可空 = "沿用该档位的合规默认值"。
 * 这样"一键开启"只需要 `{"enabled": true, "ageBand": "BAND_8_12"}` 两个字段，
 * 家长端无需先算出时长/宵禁再下发，也避免各家客户端各自实现一套默认值导致口径漂移。
 */
data class MinorModeRequest(
    val enabled: Boolean = true,
    /** UNDER_3 / BAND_3_8 / BAND_8_12 / BAND_12_16 / BAND_16_18 */
    val ageBand: String? = null,
    val curfewEnabled: Boolean? = null,
    /** "HH:mm" */
    val curfewStart: String? = null,
    /** "HH:mm" */
    val curfewEnd: String? = null,
    /** 每日总时长（分钟）；null = 沿用分龄默认 */
    val dailyLimitMinutes: Int? = null,
    /** 周末每日总时长（分钟）；null = 与工作日相同 */
    val weekendLimitMinutes: Int? = null,
    val continuousMinutes: Int? = null,
    val restMinutes: Int? = null,
    /**
     * 家长临时豁免分钟数（>0 时按当前时间累加写入 parentExemptUntil）。
     * 显式传 0 表示**取消豁免**（立刻收回，不必等到期）。
     * 豁免只压过宵禁与护眼，**不解除**每日总时长 —— 时长是合规硬约束。
     */
    val exemptMinutes: Int? = null
)

/** 限时解锁：durationMinutes 到点后自动恢复管控；packageName 留空表示整机放行 */
data class TempUnlockRequest(
    val durationMinutes: Int = 30,
    val packageName: String? = null,
    val reason: String? = null
)

/**
 * 受管控水印开关。
 *
 * [content] 留空时孩子端用默认文案（"受管控设备"）。
 * 长度上限在服务端截断而不是报错：水印是叠加在孩子屏幕上的悬浮文字，
 * 超长文案会盖住应用内容影响正常使用，而家长写长文案只是没意识到这一点，
 * 报错会让他以为功能坏了。
 */
data class WatermarkRequest(
    val enabled: Boolean = true,
    val content: String? = null
) {
    companion object {
        const val MAX_CONTENT_LENGTH = 30
    }
}

data class CommandDto(
    val msgId: String, val type: String, val status: String,
    val payload: Map<String, Any?>?, val createdAt: Long, val executedAt: Long?,
    /** 指令回执的错误码 / 描述，供家长端展示"为什么没生效" */
    val errorCode: String? = null,
    val errorMessage: String? = null
)

// ============ 被管控端（孩子） ============
@JsonIgnoreProperties(ignoreUnknown = true)
data class BindRequest(
    val bindCode: String,
    val deviceSn: String? = null,
    val fingerprint: String? = null,
    val model: String? = null,
    val brand: String? = null,
    val androidVersion: String? = null,
    val sdkInt: Int? = null,
    val controlMode: String? = null,
    val appVersion: String? = null
)

/**
 * 「账号密码绑定」请求：孩子端直接输入家长的手机号 + 密码完成绑定，
 * 供不方便扫码 / 配对码已过期的场景使用（说明书 §4.3 方式④）。
 *
 * 设备信息复用 [BindRequest]（`req` 字段），避免两处字段定义漂移。
 */
data class BindByAccountRequest(
    val phone: String,
    val password: String,
    val req: BindRequest
)

/** 孩子端自定义设备名（凭设备令牌鉴权） */
data class DeviceNameRequest(val name: String)

/**
 * 孩子端自定义个人资料（凭设备令牌鉴权）。
 * 各字段独立可选：传 null / 空串表示不修改该项，便于孩子端逐项设置。
 */
data class DeviceProfileRequest(
    val childName: String? = null,
    val childNickname: String? = null,
    val childAvatar: String? = null
)

data class BindResult(
    val deviceId: String, val deviceToken: String, val mqttUsername: String,
    val mqttPassword: String, val hmacSecret: String, val expiresAt: Long
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LogItem(
    val logId: String, val type: String? = null, val timestamp: Long? = null,
    val payload: Map<String, Any?>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LogsRequest(val deviceId: String, val logs: List<LogItem>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocationPoint(
    val lat: Double? = null, val lng: Double? = null, val accuracy: Double? = null,
    val ts: Long? = null, val provider: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocationsRequest(val deviceId: String, val points: List<LocationPoint>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HeartbeatDto(
    val policyVersion: Int? = null,
    val transportMode: String? = null,
    val controlMode: String? = null,
    val battery: Map<String, Any?>? = null,
    val location: Map<String, Any?>? = null,
    val network: Map<String, Any?>? = null
)

// ============ MQTT 数据包 ============
@JsonIgnoreProperties(ignoreUnknown = true)
data class CommandPacket(
    val msgId: String, val type: String, val version: Int = 1,
    val timestamp: Long, val expiresAt: Long, val priority: String,
    val payload: Map<String, Any?>?, val signature: String
)

/**
 * 指令回执。
 *
 * **所有字段必须给默认值**：孩子端实际上传的是包装体 `{deviceId, acks:[...]}`，
 * 历史上这里按"裸 AckPacket"强解析，`msgId` 拿不到就抛
 * `MissingKotlinParameterException` → 整个 ack 接口 500。
 * 后果是**每一条指令的回执都失败**：服务端指令永远停在"已下发"，
 * 家长端表现为"锁屏 / 录屏 / 截屏指令发送失败"，而孩子端其实已经执行成功 ——
 * 这是最典型的一类"看起来是端的问题、实际是协议不匹配"的故障。
 *
 * 字段给默认值 + [JsonIgnoreProperties] 后，单条与批量两种载荷都能安全解析。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AckPacket(
    val msgId: String = "", val deviceId: String = "", val status: String = "",
    val executedAt: Long? = null, val errorCode: String? = null,
    val errorMessage: String? = null, val retryCount: Int = 0
)

data class EventPacket(
    val eventId: String, val deviceId: String, val type: String,
    val level: String, val timestamp: Long, val detail: Map<String, Any?>? = null
)

/**
 * 孩子端批量上报风险事件（HTTP 降级通道）。
 *
 * 元素复用 [EventPacket]：孩子端 `RiskEvent` 的字段（eventId/deviceId/type/level/
 * timestamp/detail）与它一一对应，不必再定义一套平行结构 —— 两套结构迟早漂移。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EventsRequest(val deviceId: String, val events: List<EventPacket>)

/**
 * 批量导入设备台账：单行（对应 Excel / CSV 的一行）。
 *
 * 服务端以 `deviceSn`（= 孩子端上报的硬件指纹）作为去重键，与正常绑定流程口径一致：
 * 只有同一台平板在不同入口进来时才应命中同一条设备记录。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceImportRowDto(
    val hardwareId: String = "",
    val alias: String? = null,
    val model: String? = null,
    val groupName: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceImportRequest(val rows: List<DeviceImportRowDto> = emptyList())

/**
 * 批量导入结果。
 *
 * 逐行独立处理，单行失败记录到 [failedRows] 而不影响其余行 ——
 * 一次导入几十台设备时，不能因为第 3 行编号为空就让后面 27 台全部没导入。
 */
data class DeviceImportResultDto(
    val totalCount: Int,
    val successCount: Int,
    val skippedCount: Int,
    val failedRows: List<String> = emptyList()
)

/**
 * 局域网里发现的一台待接入平板。
 *
 * `deviceId` 传的是**硬件编号**（deviceSn）而不是服务端台账 id：家长端后续拿着它来发起接入，
 * 服务端据此精确定位那条无主记录；给台账 id 反而多了一次无意义的转换，还可能因复用台账而串台。
 */
data class LanDeviceDto(
    val deviceId: String,
    val model: String?,
    val ipAddress: String,
    val osVersion: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LanBindRequest(
    val hardwareId: String = "",
    val alias: String? = null
)

/**
 * 护眼模式。
 *
 * `level` 为 1~5 档，服务端与孩子端共用同一区间：
 * 孩子端按它映射悬浮滤镜的暖色浓度，两端必须同源，否则会出现
 * "家长拉满档位但平板看不出变化"。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EyeProtectionRequest(
    val enabled: Boolean = false,
    val filterLevel: Int = 3
)

data class EyeProtectionDto(
    val enabled: Boolean,
    val level: Int
)
