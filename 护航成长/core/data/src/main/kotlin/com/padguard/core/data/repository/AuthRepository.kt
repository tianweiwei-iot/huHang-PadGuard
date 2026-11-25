package com.padguard.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.padguard.core.data.di.AuthDataStore
import com.padguard.core.common.Logger
import com.padguard.core.data.db.PadGuardDatabase
import com.padguard.core.data.model.BindResult
import com.padguard.core.data.model.policy.ManagementMode
import com.padguard.core.data.crypto.CredentialCodec
import com.padguard.core.data.crypto.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「需要重新授权」的来源标识（单一事实源，避免各模块手写字符串漂移）。
 *
 * 纪律：**谁置位谁复位**。任何自检恢复时只清自己名下那条来源，绝不越权清掉
 * 别人的引导请求；只有引导流程真正走完（[AuthRepository.clearAllReauthRequired]）才整表收口。
 */
object ReauthSource {
    /** 30 分钟一轮的全量授权自检（DA / 运行时权限 / 特殊权限「曾授予又丢」）。 */
    const val KEEPER = "keeper"

    /** 卸载防护自检（三层全空，12h 节流的「把家长带回装机引导」）。 */
    const val UNINSTALL_SHIELD = "uninstallShield"

    /** 家长在授权总览 / 权限引导页主动点「一键补齐」。 */
    const val MANUAL_REVIEW = "manualReview"

    /** 5 分钟一轮的权限快检命名空间：后接 SpecialPermission.key / notificationListener。 */
    const val PERM_HEALTH_PREFIX = "permHealth:"
}

/**
 * 纯函数：来源集合非空、或旧版遗留 bool 仍为 true，都应进入授权引导。
 * 抽出来供 JVM 单测锁定「多来源互不踩踏」与「老设备升级兼容」两条语义。
 */
fun reauthRequiredOf(sources: Set<String>, legacyFlag: Boolean): Boolean =
    sources.isNotEmpty() || legacyFlag

/**
 * 装机首启「管控模式」读取态。
 *
 * 不能直接用 `Flow<ManagementMode?>` 驱动入口分流：DataStore 里**键不存在**（家长尚未选）
 * 与 **Flow 首帧还没读到** 都表现为 null，用 `collectAsState(initial = null)` 无法区分，
 * 首帧就会误弹选择页（R36 抢跑问题的同型）。把"已读到"显式包进对象：
 * - Compose 侧 `initial = null` → 尚未读到 → Splash；
 * - 读到后 [mode] 仍为 null → 家长确实没选过 → 弹二选一页。
 */
data class ManagementModeState(val mode: ManagementMode?)

/**
 * 协议硬闸门纯函数（法务 v2.0 整改，PIPL 第 17/14 条）。
 *
 * 「设备已绑定」只说明设备归属某个家长账号，**不等于**监护人已阅读并同意本协议：
 * 装机流水线的权限清单可以因补授权/自愈而跑完，却从未有人对协议正文作出明示同意。
 * 历史上 GuardService 只认 [isBound]，于是在"先授权、后看协议"的形态下，
 * 采集链路在协议弹窗出现之前就已经跑起来 —— 与隐私政策"明示同意后方可启用"直接冲突。
 *
 * 两个条件必须同时成立才允许启动采集/管控；任一为假即 fail-closed。
 * 抽成顶层纯函数供 JVM 单测锁定语义，服务侧只从 [AuthRepository.isBound] 与
 * [AuthRepository.isAgreementAccepted] 取数，不新增第三份状态源。
 */
fun guardActive(bound: Boolean, agreementAccepted: Boolean): Boolean = bound && agreementAccepted

/**
 * 是否释放设备管理员 / Device Owner 身份：**只有真解绑才可以**。
 *
 * 协议升版后"暂未重新同意"同样会关闭 [guardActive] 总闸，但那不是解绑。
 * DO/设备管理员一经 relinquish 通常必须恢复出厂才能重新预置，属于不可逆降级；
 * 若把"未同意"照搬解绑清理路径，一次协议文案更新就会永久拆掉硬管控。
 * 因此把判据抽成纯函数并钉进契约测试。
 */
fun shouldReleaseControl(bound: Boolean): Boolean = !bound

/**
 * 设备绑定凭据仓库。
 *
 * [hmacSecret] 是指令签名校验的核心密钥，仅存于本地，永不参与任何上行请求。
 */
@Singleton
class AuthRepository @Inject constructor(
    @AuthDataStore private val store: DataStore<Preferences>,
    private val db: PadGuardDatabase,
    private val keystore: KeystoreManager
) {

    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_TOKEN = stringPreferencesKey("device_token")
        val MQTT_USERNAME = stringPreferencesKey("mqtt_username")
        val MQTT_PASSWORD = stringPreferencesKey("mqtt_password")
        val HMAC_SECRET = stringPreferencesKey("hmac_secret")
        val TOKEN_EXPIRES_AT = longPreferencesKey("token_expires_at")
        val BOUND_AT = longPreferencesKey("bound_at")
        val DEVICE_SN = stringPreferencesKey("device_sn")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val STUDENT_NAME = stringPreferencesKey("student_name")
        val TENANT_ID = stringPreferencesKey("tenant_id")
        val AGREEMENT_VERSION = stringPreferencesKey("agreement_version")
        // === 孩子端自定义个人资料（姓名见 STUDENT_NAME，昵称 / 头像另存）===
        val CHILD_NICKNAME = stringPreferencesKey("child_nickname")
        val CHILD_AVATAR = stringPreferencesKey("child_avatar")

        // === 被管控端登录态（复用家长家庭账号） ===
        val CHILD_PHONE = stringPreferencesKey("child_phone")
        val CHILD_TOKEN = stringPreferencesKey("child_token")
        val CHILD_USER_ID = stringPreferencesKey("child_user_id")
        val CHILD_LOGGED_IN = booleanPreferencesKey("child_logged_in")
        // === 账号密码记录（家长家庭账号）===
        // 安全设计：本端不再持久化家长账号密码明文。
        // 早期实现把密码明文写入 DataStore，root / 备份提取即可拿到家庭账号密码，等同于把家长端门户钥匙交给孩子。
        // 孩子端鉴权只依赖 [CHILD_TOKEN]（登录后下发，足够用），密码仅用于首登，
        // 因此这里仅保留「记住账号」语义，密码永不落库。键保留以便旧版本升级时干净迁移（clear 会一并清掉）。
        val CHILD_PASSWORD = stringPreferencesKey("child_password")
        val CHILD_REMEMBER = booleanPreferencesKey("child_remember")

        // === 权限引导完成态（初次打开一次性授予，后续登录不再重复） ===
        val PERMISSIONS_DONE = booleanPreferencesKey("permissions_done")

        /**
         * 「一次性授权」已完成的**版本号**（孩子端装机引导版本）。
         *
         * 为什么是版本号而不是布尔值：装机需要用户参与的清单会随功能演进变长
         * （例如后来新增了无障碍服务、屏幕采集）。布尔值一旦写过 true，
         * 老设备升级后就再也不会补齐新项——而缺失这些项的后果是**静默失效**
         * （密码闸门被绕过、实时看屏没画面），家长根本无从察觉。
         * 版本号语义与 Flyway 迁移一致：低于 [CONSENT_VERSION_CURRENT] 就补跑一次，
         * 跑完写到当前版本，**且补跑是幂等的**（已就绪的项不会重复打扰）。
         */
        val CONSENT_VERSION = intPreferencesKey("consent_version")

        /**
         * 家长已在授权流程中明确跳过设备管理员(DA)引导的标记。
         *
         * 入口分流在「DA 未激活」时会强制弹回一次性授权页（见 [com.padguard.child.MainActivity]），
         * 但家长若在该流程里主动跳过 DA（[com.padguard.child.consent.OneTimeConsentViewModel.finishAnyway]），
         * 置位本标记后不再强制，避免每次冷启动都卡在授权页；「我的」页仍保留手动开启入口与缺失提示。
         */
        val DEVICE_ADMIN_PROMPT_ACK = booleanPreferencesKey("device_admin_prompt_ack")

        /**
         * 授权回退自愈标记（**旧版遗留，只读兼容**）。
         *
         * 历史上它是一个单一 bool：任何自检发现异常都置 true，而
         * [com.padguard.child.permission.AuthorizationKeeper] 只要自己那一套权限全齐就无条件写 false。
         * 结果是「通知代收授权掉失」「卸载防护为 0」「家长点一键补齐」等与 keeper 维度无关的
         * 引导请求会被 30 分钟一轮的 keeper 静默吞掉（权限页点了没反应 / 掉了却不被拉回）。
         * 现改为 [REAUTH_SOURCES] 按来源记账；本键仅为老设备升级后不至于丢失既有引导请求而保留，
         * 新的写入方一律不再写它，[clearAllReauthRequired] 会把它一并复位。
         */
        val REAUTH_REQUIRED = booleanPreferencesKey("reauth_required")

        /**
         * 需要重新授权的**来源集合**。非空即表示入口分流应把家长带回一次性授权引导。
         *
         * 每个自检/入口只增删自己名下那一条来源，彼此不踩踏；引导流程真正走完才由
         * [clearAllReauthRequired] 权威收口。见 [ReauthSource]。
         */
        val REAUTH_SOURCES = stringSetPreferencesKey("reauth_sources")
        /** 家长已在引导页**显式确认已知风险仍要继续**、跳过「设备所有者(DO)」预置的标记（发布包强制首步用）。
         *
         * 见 [com.padguard.child.MainActivity]：发布包把 DO 预置设为装机第一道门，
         * 未开 DO 时防卸载 / 静默安装 / 禁止恢复出厂等核心承诺在系统层面全部不可用，
         * "孩子可自卸"即成立。仅当家长在引导页明确确认「已知风险仍要继续」才置位本标记，
         * 正常部署（恢复出厂 + 扫码 / adb 预置）不会触发它，DO 始终就位。
         */
        val DEVICE_OWNER_PROMPT_ACK = booleanPreferencesKey("device_owner_prompt_ack")

        /**
         * 家长在装机首启二选一的**管控模式**（硬 / 软），与设备实际权限等级 ControlMode 解耦。
         *
         * 键不存在（null）表示尚未选择，入口分流据此先弹选择页；一旦选择即长期保留，
         * 解绑（[clearDeviceBinding]）不清除——它是设备级选择而非家庭账号数据。
         * 只有整机清空（[clear]：卸载 / 恢复出厂 / 换家庭）才会重置，下次装机重新征求。
         * 切换模式只改本键，不动两档各自的授权与策略配置，做到"互不影响"。
         */
        val MANAGEMENT_MODE = stringPreferencesKey("management_mode")
    }

    /** 当前生效的授权协议版本（说明书 §5.4 / §4.6）。协议正文迭代时升版即可。 */
    val deviceId: Flow<String> = preferences()
        .map { it[Keys.DEVICE_ID].orEmpty() }

    val isBound: Flow<Boolean> = deviceId.map { it.isNotBlank() }

    /** 已同意的协议版本；为空表示尚未同意（受限预览模式）。 */
    val agreementVersion: Flow<String> = preferences()
        .map { it[Keys.AGREEMENT_VERSION].orEmpty() }

    /** 是否已同意当前版本协议 —— 入口门禁据此决定是否弹授权弹窗。 */
    val isAgreementAccepted: Flow<Boolean> =
        agreementVersion.map { it == CURRENT_AGREEMENT_VERSION }

    /** 被管控端是否已用家庭账号登录（驱动入口分流：未登录→登录页）。 */
    val isChildLoggedIn: Flow<Boolean> = preferences()
        .map { it[Keys.CHILD_LOGGED_IN] == true }

    /** 权限引导是否已完成（初次打开一次性授予，后续登录不再重复）。 */
    val isPermissionsDone: Flow<Boolean> = preferences()
        .map { it[Keys.PERMISSIONS_DONE] == true }

    /** 已登录的家庭账号手机号（仅用于展示）。 */
    val childPhone: Flow<String> = preferences()
        .map { it[Keys.CHILD_PHONE].orEmpty() }

    /** 已登录的家庭账号用户ID。 */
    val childUserId: Flow<String> = preferences()
        .map { it[Keys.CHILD_USER_ID].orEmpty() }

    val studentName: Flow<String> = preferences()
        .map { it[Keys.STUDENT_NAME].orEmpty() }

    val deviceSn: Flow<String> = preferences()
        .map { it[Keys.DEVICE_SN].orEmpty() }

    /** 自定义设备名（家长重命名下发 / 孩子端本地修改都会写入此处，驱动「我的」页展示） */
    val deviceName: Flow<String> = preferences()
        .map { it[Keys.DEVICE_NAME].orEmpty() }

    suspend fun getDeviceId(): String = deviceId.first()

    suspend fun getDeviceToken(): String =
        dec(preferences().first()[Keys.DEVICE_TOKEN].orEmpty())

    suspend fun getMqttUsername(): String =
        dec(preferences().first()[Keys.MQTT_USERNAME].orEmpty())

    suspend fun getMqttPassword(): String =
        dec(preferences().first()[Keys.MQTT_PASSWORD].orEmpty())

    suspend fun getHmacSecret(): String =
        dec(preferences().first()[Keys.HMAC_SECRET].orEmpty())

    /**
     * 敏感凭据落库加密：经 Android Keystore(AES/GCM) 加密为 Base64。
     * 空串原样返回；加密异常（极老旧设备 Keystore 不可用）**绝不降级为明文**——
     * 明文落盘会把 hmacSecret 等指令签名密钥暴露给 root / 备份提取，构成伪造指令风险。
     * 改为返回特殊标记，[dec] 据此按"密钥丢失"返回空串，使设备安全回到需重绑状态
     * （fail-closed，而非默默泄露密钥）。
     */
    private fun enc(plain: String): String {
        if (plain.isEmpty()) return plain
        return runCatching { keystore.encryptToBase64(plain) }
            .onFailure { Logger.w(TAG) { "encrypt credential failed (Keystore unavailable?); refusing plaintext fallback to avoid leaking secret" } }
            .getOrElse { ENC_FAILED_MARKER }
    }

    /**
     * 敏感凭据读取解密。解密失败时**必须区分三种来源**，不能一概回退原文：
     *
     * 1. 解密成功 → 当前格式的加密凭据，直接用。
     * 2. 解密失败 + [CredentialCodec.looksLikeCipherText] 为真 → **Keystore 密钥已销毁**
     *    （卸载重装后恢复数据 / 换机备份 / 恢复出厂，密钥与安装态绑定）。
     *    旧实现在这里把 Base64 密文当成 deviceToken 发出去，服务端判 TOKEN_INVALID 打一串
     *    401；hmacSecret 同时变成密文，指令签名全错。家长端看到的就是「重装后一直连不上」。
     *    现在返回空串：[CredentialStore][com.padguard.core.transport.http.CredentialStore]
     *    的 isBound 会如实变 false，不再拿坏令牌空转请求。
     *    **绝不自动清持久层**——Keystore 在锁屏/系统升级期间可能只是临时不可用，静默清绑定
     *    会制造另一种「自动退出」；密钥恢复后本方法自然重新可用，确需重绑时由家长走绑定流程覆盖。
     * 3. 解密失败 + 非密文形态 → 11-01 前的老版本明文，原样返回完成平滑迁移，下次写入即加密。
     */
    private fun dec(cipher: String): String {
        // 加密阶段失败的标记：按密钥丢失处理返回空串，触发重绑，绝不泄露/误用。
        if (cipher == ENC_FAILED_MARKER) return ""
        return when (val r = CredentialCodec.resolve(cipher) { keystore.decryptFromBase64(it) }) {
            is CredentialCodec.Resolution.Plain -> r.value
            CredentialCodec.Resolution.Corrupted -> {
                Logger.w(TAG) {
                    "credential decrypt failed (Keystore key lost?); dropping cipher text instead of sending it as a token"
                }
                ""
            }
        }
    }

    suspend fun getTenantId(): String =
        preferences().first()[Keys.TENANT_ID].orEmpty()

    suspend fun getDeviceSn(): String =
        preferences().first()[Keys.DEVICE_SN].orEmpty()

    suspend fun getStudentName(): String =
        preferences().first()[Keys.STUDENT_NAME].orEmpty()

    /** 持久化自定义设备名（家长下发或孩子本地修改共用） */
    suspend fun saveDeviceName(name: String) {
        store.edit { it[Keys.DEVICE_NAME] = name }
    }

    /** 保存绑定结果。绑定成功后终端即进入受控态。敏感凭据经 Keystore 加密落库。 */
    suspend fun saveBindResult(result: BindResult, deviceSn: String) {
        store.edit { prefs ->
            prefs[Keys.DEVICE_ID] = result.deviceId
            prefs[Keys.DEVICE_TOKEN] = enc(result.deviceToken)
            prefs[Keys.MQTT_USERNAME] = enc(result.mqttUsername)
            prefs[Keys.MQTT_PASSWORD] = enc(result.mqttPassword)
            prefs[Keys.HMAC_SECRET] = enc(result.hmacSecret)
            prefs[Keys.TOKEN_EXPIRES_AT] = result.expiresAt
            prefs[Keys.BOUND_AT] = System.currentTimeMillis()
            prefs[Keys.DEVICE_SN] = deviceSn
            // 服务端绑定响应下发的多租户标识（契约 V1.1）：非空才写，避免用空串覆盖既有值。
            // baseUrl / mqttBroker 不在此落盘——它们由 TransportSettings 经 EndpointSafety 闸门统一持有与持久化，
            // 这里写会制造第二份真相源。
            if (result.tenantId.isNotBlank()) prefs[Keys.TENANT_ID] = result.tenantId
        }
    }

    suspend fun saveTenantId(tenantId: String) {
        store.edit { it[Keys.TENANT_ID] = tenantId }
    }

    suspend fun saveStudentName(name: String) {
        store.edit { it[Keys.STUDENT_NAME] = name }
    }

    /** 孩子端自定义昵称（实时同步至服务端 + 家长端）。 */
    val childNickname: Flow<String> = preferences()
        .map { it[Keys.CHILD_NICKNAME].orEmpty() }

    suspend fun saveChildNickname(nickname: String) {
        store.edit { it[Keys.CHILD_NICKNAME] = nickname }
    }

    /** 孩子端自定义头像 URL（由 /device/avatar 上传后返回，实时同步至服务端 + 家长端）。 */
    val childAvatar: Flow<String> = preferences()
        .map { it[Keys.CHILD_AVATAR].orEmpty() }

    suspend fun saveChildAvatar(url: String) {
        store.edit { it[Keys.CHILD_AVATAR] = url }
    }

    /** 持久化已同意的协议版本（同意授权后由 [PermissionGranter] 调用）。 */
    suspend fun saveAgreement(version: String = CURRENT_AGREEMENT_VERSION) {
        store.edit { it[Keys.AGREEMENT_VERSION] = version }
    }

    /** 保存家庭账号登录态（账号/令牌/用户ID），置 [Keys.CHILD_LOGGED_IN]=true。令牌经 Keystore 加密落库。 */
    suspend fun saveChildLogin(phone: String, token: String, userId: String) {
        store.edit {
            it[Keys.CHILD_PHONE] = phone
            it[Keys.CHILD_TOKEN] = enc(token)
            it[Keys.CHILD_USER_ID] = userId
            it[Keys.CHILD_LOGGED_IN] = true
        }
    }

    /**
     * 记录「记住账号」状态（登录页勾选时调用）。
     *
     * 安全设计：本方法不写入家长账号密码明文。
     * 仅记录手机号用于登录表单预填，密码永不持久化——孩子端鉴权依赖 [CHILD_TOKEN]，
     * 不需要本地密码；即使设备被 root / 备份提取，也拿不到家庭账号密码。
     * 升级前若已写过密码，[clearRememberedCredentials] / [clear] 会一并清除。
     */
    suspend fun saveChildCredentials(phone: String, password: String) {
        store.edit {
            it[Keys.CHILD_PHONE] = phone
            // password 故意不落库（见 Keys.CHILD_PASSWORD 说明）
            it[Keys.CHILD_REMEMBER] = true
        }
    }

    /** 已记录的账号 +（空）密码。密码不再持久化，第二个元素恒为空串。 */
    val rememberedCredentials: Flow<Pair<String, String>> =
        preferences()
            .map { it[Keys.CHILD_PHONE].orEmpty() to "" }

    /** 是否开启了「记住账号密码」。 */
    val isRemembered: Flow<Boolean> = preferences()
        .map { it[Keys.CHILD_REMEMBER] == true }

    /** 清除已记录的账号密码（只清记录，不退出登录、不影响已绑定设备）。 */
    suspend fun clearRememberedCredentials() {
        store.edit {
            it.remove(Keys.CHILD_PASSWORD)
            it[Keys.CHILD_REMEMBER] = false
        }
    }

    /**
     * 当前装机引导版本号。
     *
     * **每次往「一次性授权」清单里新增用户必须参与的项，都必须 +1**，
     * 否则存量设备不会补齐新增项（缺失表现为静默失效，家长无从察觉）。
     */
    companion object {
        private const val TAG = "AuthRepository"

        /**
         * 加密失败标记（非任何合法密文前缀，[dec] 据此与真实密文区分）。
         * 仅当 Keystore 加密抛异常时由 [enc] 写入，读取时按"密钥丢失"返回空串，
         * 设备安全回到需重绑状态，绝不把敏感凭据明文落盘。
         */
        private const val ENC_FAILED_MARKER = "__PADGUARD_ENC_FAILED__"

        /**
         * 读侧自愈订阅的重试间隔（见 [preferences]）：太短会在文件持续损坏时高频重读 IO，
         * 太长则恢复后要多等一会儿才追上最新值；5 秒对状态类数据足够灵敏。
         */
        private const val PREFS_READ_RETRY_MS = 5_000L

        /**
         * 当前生效的授权协议版本（说明书 §5.4 / §4.6）。协议正文迭代时升版即可。
         *
         * v2.0.0（2026-11-25 法务整改）：协议与隐私政策正文改为如实口径
         * （区分官方托管/家长自建、披露管理后台在 RBAC+审计下的客服运维访问、
         * 30/90 天保存期限、撤回同意路径）。**升版即强制重新征求同意**：
         * [isAgreementAccepted] 只认当前版本，老版本签署记录不再放行采集，
         * GuardService 的总闸会同步收起，直到监护人重新明示确认。
         */
        const val CURRENT_AGREEMENT_VERSION = "2.0.0"

        /**
         * 当前装机引导版本号。
         *
         * **每次往「一次性授权」清单里新增用户必须参与的项，都必须 +1**，
         * 否则存量设备不会补齐新增项（缺失表现为静默失效，家长无从察觉）。
         *
         * v2（授权专项）：清单新增「电池优化白名单 / 闹钟和提醒（精确闹钟） / 通知 /
         * 厂商自启动与后台弹出界面」四项。存量设备升级后 `isConsentDone` 变 false，
         * 入口分流会补跑一次引导；流水线按真实状态跳过已就绪项，只带家长办新增的几项，
         * 不会把已经开好的开关再拨一遍。
         */
        const val CONSENT_VERSION_CURRENT = 2
    }

    /**
     * 已完成的装机引导版本；0 表示从未走过引导。
     * 入口分流据此决定是否需要展示一次性授权页：低于 [CONSENT_VERSION_CURRENT] 就补一次。
     */
    val consentVersion: Flow<Int> = preferences()
        .map { it[Keys.CONSENT_VERSION] ?: 0 }

    /** 一次性授权是否已做到当前版本（幂等判定，直接驱动入口分流）。 */
    val isConsentDone: Flow<Boolean> = consentVersion.map { it >= CONSENT_VERSION_CURRENT }

    /** 标记一次性授权已完成到当前版本。 */
    suspend fun saveConsentDone(version: Int = CONSENT_VERSION_CURRENT) {
        store.edit {
            it[Keys.CONSENT_VERSION] = version
            it[Keys.PERMISSIONS_DONE] = true
        }
    }

    /**
     * 家长是否已明确跳过 DA 引导（见 [Keys.DEVICE_ADMIN_PROMPT_ACK]）。
     * 入口分流据此决定是否仍因「DA 未激活」强制弹回授权页。
     */
    val isDeviceAdminPromptAck: Flow<Boolean> = preferences()
        .map { it[Keys.DEVICE_ADMIN_PROMPT_ACK] ?: false }

    /** 标记家长已明确跳过 DA 引导，停止强制弹回授权流程。 */
    suspend fun setDeviceAdminPromptAck() {
        editSafely("set device admin prompt ack") { it[Keys.DEVICE_ADMIN_PROMPT_ACK] = true }
    }

    /**
     * 是否需要重新授权：按来源记账（见 [ReauthSource]），并兼容旧版遗留的单一 bool。
     * 入口分流只读这一个 Flow，改造对 MainActivity 透明。
     */
    val reauthRequired: Flow<Boolean> = preferences()
        .map { reauthRequiredOf(it[Keys.REAUTH_SOURCES].orEmpty(), it[Keys.REAUTH_REQUIRED] == true) }

    /** 置位一个来源（幂等）；[source] 必须是 [ReauthSource] 里的常量或带命名空间前缀的 key。 */
    suspend fun setReauthRequired(source: String) {
        require(source.isNotBlank()) { "reauth source must not be blank" }
        editSafely("set reauth required($source)") { prefs ->
            val current = prefs[Keys.REAUTH_SOURCES].orEmpty()
            if (source !in current) prefs[Keys.REAUTH_SOURCES] = current + source
        }
    }

    /**
     * 只清除指定来源，**其它来源分毫不动**。
     * 自检发现「自己那一项恢复了」时必须走这里，不能用 [clearAllReauthRequired]。
     */
    suspend fun clearReauthRequired(source: String) {
        editSafely("clear reauth required($source)") { prefs ->
            val current = prefs[Keys.REAUTH_SOURCES].orEmpty()
            if (source !in current) return@editSafely
            val next = current - source
            if (next.isEmpty()) prefs.remove(Keys.REAUTH_SOURCES)
            else prefs[Keys.REAUTH_SOURCES] = next
        }
    }

    /** 引导流程真正走完：权威收口，清掉全部来源与旧版遗留 bool。 */
    suspend fun clearAllReauthRequired() {
        editSafely("clear all reauth required") { prefs ->
            prefs.remove(Keys.REAUTH_SOURCES)
            prefs[Keys.REAUTH_REQUIRED] = false
        }
    }

    /**
     * 原子替换某命名空间下的全部来源，供「一轮自检批量对账」：
     * 本轮仍掉失的项留在集合里，已恢复 / 不再适用的项自动移除；其它命名空间不受影响。
     * 集合无变化时不写盘，避免 5 分钟一轮的快检产生无意义 IO。
     */
    suspend fun replaceReauthSources(prefix: String, activeKeys: Set<String>) {
        require(prefix.isNotBlank()) { "reauth namespace prefix must not be blank" }
        editSafely("replace reauth sources($prefix)") { prefs ->
            val current = prefs[Keys.REAUTH_SOURCES].orEmpty()
            val next = current.filterNot { it.startsWith(prefix) }.toSet() +
                activeKeys.map { prefix + it }
            if (next == current) return@editSafely
            if (next.isEmpty()) prefs.remove(Keys.REAUTH_SOURCES)
            else prefs[Keys.REAUTH_SOURCES] = next
        }
    }

    /**
     * 读侧统一收口（R43）：旧写法 `store.data.catch { emit(emptyPreferences()) }` 是**完结式降级**——
     * DataStore 读失败（文件损坏 / IO 异常）时发完一张空表，Flow 就此完结；长驻收集方
     * （入口分流、GuardService 等一直 collect 的调用点）从此**永久失聪**，绑定/登录/授权
     * 状态定格在失败瞬间的空表快照，之后的任何写入都收不到，只有杀进程重启才恢复。
     *
     * 现改为**自愈订阅循环**：
     * - 失败先发一张空表（保留旧语义——一次性读取 [first] 仍能拿到默认值）；
     * - 延时 [PREFS_READ_RETRY_MS] 后**重新订阅** store.data，IO 恢复即自动追上最新值；
     * - 持续失败时靠 [distinctUntilChanged] 去重，空表不会反复下发（下游状态不抖动）；
     * - 取消必须原样上抛（与 [editSafely] 同口径，禁止把结构化并发取消当读失败吞掉）。
     */
    private fun preferences(): Flow<Preferences> = flow {
        while (true) {
            try {
                store.data.collect { emit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, e) { "auth prefs read failed; resubscribing in ${PREFS_READ_RETRY_MS}ms" }
                emit(emptyPreferences())
                delay(PREFS_READ_RETRY_MS)
            }
        }
    }.distinctUntilChanged()

    /**
     * DataStore 写入统一收口（R37）：编辑失败按 warn 记录（保留旧 runCatching 语义），
     * 但**必须放行 [CancellationException]**——runCatching 会把协程取消当普通失败吞掉，
     * 已取消的协程继续执行后续逻辑（半取消状态下写盘 / 发通知），破坏结构化并发。
     */
    private suspend fun editSafely(what: String, block: (MutablePreferences) -> Unit) {
        try {
            store.edit(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG) { "$what failed" }
        }
    }

    /**
     * 家长是否已显式跳过「设备所有者(DO)」预置引导（发布包强制首步）。
     * 入口分流据此决定是否仍因「未成为 DO」强制停在预置引导页。
     */
    val isDeviceOwnerPromptAck: Flow<Boolean> = preferences()
        .map { it[Keys.DEVICE_OWNER_PROMPT_ACK] ?: false }

    /** 标记家长已显式跳过 DO 预置（已知风险仍要继续），停止强制预置页。 */
    suspend fun setDeviceOwnerPromptAck() {
        editSafely("set device owner prompt ack") { it[Keys.DEVICE_OWNER_PROMPT_ACK] = true }
    }

    /**
     * 撤销「已知风险跳过 DO 预置」的旧标记。
     *
     * 家长从软管控切回硬管控时必须调用：否则旧的 ack 会让入口分流直接跳过 DO 预置页，
     * 结果是"选了硬管控却什么都没发生"（设备非 DO、硬管控增强又被双闸门拦住）。
     * ack 表达的是"这次不预置了"，一旦重新选择硬管控，这个决定即作废。
     */
    suspend fun clearDeviceOwnerPromptAck() {
        editSafely("clear device owner prompt ack") { it.remove(Keys.DEVICE_OWNER_PROMPT_ACK) }
    }

    /**
     * 家长选择的管控模式；null = 尚未选择（装机首启需先弹二选一页）。
     *
     * 与 [isDeviceOwnerPromptAck] 是两件事：前者是"想用哪档"，后者是"旧版强制 DO 首步的风险放行"。
     * 新流程里选软管控直接落本键，不再复用 ack 标记表达模式选择。
     */
    val managementMode: Flow<ManagementMode?> = preferences()
        .map { ManagementMode.fromStorage(it[Keys.MANAGEMENT_MODE]) }

    /**
     * 带"已读到"语义的管控模式读取态，专供入口分流使用（见 [ManagementModeState]）。
     * 其他只关心当前值的订阅方（如硬管控闸门）直接用 [managementMode] 即可。
     */
    val managementState: Flow<ManagementModeState> = preferences()
        .map { ManagementModeState(ManagementMode.fromStorage(it[Keys.MANAGEMENT_MODE])) }

    /** 记录家长选择的管控模式；切换时只改本键，不动另一档的授权与配置。 */
    suspend fun setManagementMode(mode: ManagementMode) {
        editSafely("set management mode") { it[Keys.MANAGEMENT_MODE] = mode.storageValue }
    }

    /** 标记权限引导已完成，后续登录不再重复申请。 */
    suspend fun savePermissionsDone() {
        store.edit { it[Keys.PERMISSIONS_DONE] = true }
    }

    /**
     * 退出家庭账号登录。
     * 仅清登录态，保留已绑定设备与权限完成态——下次进入直接登录即可，
     * 不再重复走权限引导（[isPermissionsDone] 独立持久化）。
     */
    suspend fun childLogout() {
        store.edit {
            it.remove(Keys.CHILD_PHONE)
            it.remove(Keys.CHILD_TOKEN)
            it.remove(Keys.CHILD_USER_ID)
            it[Keys.CHILD_LOGGED_IN] = false
        }
    }

    /**
     * 只清"设备绑定"相关凭据，保留家庭账号登录态与权限完成态。
     *
     * 为什么必须有这个方法：远程解绑（家长端操作 / 服务端回收）后，孩子端应当**直接回到绑定页**，
     * 而不是把登录态一起清掉、逼孩子重新输入家长账号密码——那一步在平板上没法自助完成
     * （孩子通常不知道家长密码），设备会卡死在登录页再也绑不回来。
     * 登录态与绑定态是两件事，必须分开清。
     */
    suspend fun clearDeviceBinding() {
        store.edit {
            it.remove(Keys.DEVICE_ID)
            it.remove(Keys.DEVICE_TOKEN)
            it.remove(Keys.MQTT_USERNAME)
            it.remove(Keys.MQTT_PASSWORD)
            it.remove(Keys.HMAC_SECRET)
            it.remove(Keys.TOKEN_EXPIRES_AT)
            it.remove(Keys.BOUND_AT)
            it.remove(Keys.DEVICE_NAME)
            // 解绑时一并清掉旧版本升级可能残留的家长密码键，不留旧家庭凭据痕迹。
            it.remove(Keys.CHILD_PASSWORD)
        }
        // 解绑即清本机私有数据：Room 库存储了策略缓存、行为日志、指令记录、应用时长、
        // 风险/告警事件、解锁工单、协议同意、家长消息——这些都属于"旧家庭在本设备上的管控痕迹"，
        // 若不清除，平板转手后新家庭仍能从本地库读到上一家的行为日志与协议同意状态。
        wipePrivateDb()
    }

    /**
     * 解绑清理（全清）。
     * 注意：终端**无自主解绑权限**，本方法仅在收到服务端/管理员授权的解绑指令时调用。
     */
    suspend fun clear() {
        try {
            store.edit { it.clear() }
        } catch (e: IOException) {
            // DataStore 写入失败时重试一次，仍失败则记录日志交由上层处理
            store.edit { it.clear() }
        }
        // 全清场景（卸载 / 出厂重置）同样清 Room 私有表，避免残留旧家庭管控痕迹。
        wipePrivateDb()
    }

    /**
     * 清空本地 Room 管控数据库全部表。
     * 在 IO 线程执行（Room 的 clearAllTables 是阻塞调用）。
     * Room 表均位于 /data/data/com.padguard.child/databases 应用私有目录，
     * 清掉即"本机不再保留任何上一家管控痕迹"。失败不阻断解绑主流程（记日志交由上层）。
     */
    private suspend fun wipePrivateDb() {
        withContext(Dispatchers.IO) {
            runCatching { db.clearAllTables() }
                .onFailure { t -> Logger.w(TAG) { "wipe private db failed: ${t.message}" } }
        }
    }
}
