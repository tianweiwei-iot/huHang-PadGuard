package com.padguard.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.padguard.core.data.di.AuthDataStore
import com.padguard.core.common.Logger
import com.padguard.core.data.db.PadGuardDatabase
import com.padguard.core.data.model.BindResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备绑定凭据仓库。
 *
 * [hmacSecret] 是指令签名校验的核心密钥，仅存于本地，永不参与任何上行请求。
 */
@Singleton
class AuthRepository @Inject constructor(
    @AuthDataStore private val store: DataStore<Preferences>,
    private val db: PadGuardDatabase
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
         * 授权回退自愈标记。
         *
         * 一次性授权完成后，若孩子在系统设置里关掉某项关键权限（或国产 ROM 切换权限时
         * 强制重启 App 把无障碍/录屏授权清空），[com.padguard.child.permission.AuthorizationKeeper]
         * 会置位本标记，入口分流据此**自动把家长带回一次性授权引导流程重新勾选**，
         * 实现"授权不会自动丢失"——而不是像以前那样只在后台静默上报告警。
         * 引导流程走完（[com.padguard.child.consent.OneTimeConsentViewModel.finish] /
         * [com.padguard.child.consent.OneTimeConsentViewModel.finishAnyway]）即清掉。
         */
        val REAUTH_REQUIRED = booleanPreferencesKey("reauth_required")
    }

    /** 当前生效的授权协议版本（说明书 §5.4 / §4.6）。协议正文迭代时升版即可。 */
    val deviceId: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.DEVICE_ID].orEmpty() }

    val isBound: Flow<Boolean> = deviceId.map { it.isNotBlank() }

    /** 已同意的协议版本；为空表示尚未同意（受限预览模式）。 */
    val agreementVersion: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.AGREEMENT_VERSION].orEmpty() }

    /** 是否已同意当前版本协议 —— 入口门禁据此决定是否弹授权弹窗。 */
    val isAgreementAccepted: Flow<Boolean> =
        agreementVersion.map { it == CURRENT_AGREEMENT_VERSION }

    /** 被管控端是否已用家庭账号登录（驱动入口分流：未登录→登录页）。 */
    val isChildLoggedIn: Flow<Boolean> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.CHILD_LOGGED_IN] == true }

    /** 权限引导是否已完成（初次打开一次性授予，后续登录不再重复）。 */
    val isPermissionsDone: Flow<Boolean> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.PERMISSIONS_DONE] == true }

    /** 已登录的家庭账号手机号（仅用于展示）。 */
    val childPhone: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.CHILD_PHONE].orEmpty() }

    /** 已登录的家庭账号用户ID。 */
    val childUserId: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.CHILD_USER_ID].orEmpty() }

    val studentName: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.STUDENT_NAME].orEmpty() }

    val deviceSn: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.DEVICE_SN].orEmpty() }

    /** 自定义设备名（家长重命名下发 / 孩子端本地修改都会写入此处，驱动「我的」页展示） */
    val deviceName: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.DEVICE_NAME].orEmpty() }

    suspend fun getDeviceId(): String = deviceId.first()

    suspend fun getDeviceToken(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.DEVICE_TOKEN].orEmpty()

    suspend fun getMqttUsername(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.MQTT_USERNAME].orEmpty()

    suspend fun getMqttPassword(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.MQTT_PASSWORD].orEmpty()

    suspend fun getHmacSecret(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.HMAC_SECRET].orEmpty()

    suspend fun getTenantId(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.TENANT_ID].orEmpty()

    suspend fun getDeviceSn(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.DEVICE_SN].orEmpty()

    suspend fun getStudentName(): String =
        store.data.catch { emit(emptyPreferences()) }.first()[Keys.STUDENT_NAME].orEmpty()

    /** 持久化自定义设备名（家长下发或孩子本地修改共用） */
    suspend fun saveDeviceName(name: String) {
        store.edit { it[Keys.DEVICE_NAME] = name }
    }

    /** 保存绑定结果。绑定成功后终端即进入受控态。 */
    suspend fun saveBindResult(result: BindResult, deviceSn: String) {
        store.edit { prefs ->
            prefs[Keys.DEVICE_ID] = result.deviceId
            prefs[Keys.DEVICE_TOKEN] = result.deviceToken
            prefs[Keys.MQTT_USERNAME] = result.mqttUsername
            prefs[Keys.MQTT_PASSWORD] = result.mqttPassword
            prefs[Keys.HMAC_SECRET] = result.hmacSecret
            prefs[Keys.TOKEN_EXPIRES_AT] = result.expiresAt
            prefs[Keys.BOUND_AT] = System.currentTimeMillis()
            prefs[Keys.DEVICE_SN] = deviceSn
        }
    }

    suspend fun saveTenantId(tenantId: String) {
        store.edit { it[Keys.TENANT_ID] = tenantId }
    }

    suspend fun saveStudentName(name: String) {
        store.edit { it[Keys.STUDENT_NAME] = name }
    }

    /** 孩子端自定义昵称（实时同步至服务端 + 家长端）。 */
    val childNickname: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.CHILD_NICKNAME].orEmpty() }

    suspend fun saveChildNickname(nickname: String) {
        store.edit { it[Keys.CHILD_NICKNAME] = nickname }
    }

    /** 孩子端自定义头像 URL（由 /device/avatar 上传后返回，实时同步至服务端 + 家长端）。 */
    val childAvatar: Flow<String> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.CHILD_AVATAR].orEmpty() }

    suspend fun saveChildAvatar(url: String) {
        store.edit { it[Keys.CHILD_AVATAR] = url }
    }

    /** 持久化已同意的协议版本（同意授权后由 [PermissionGranter] 调用）。 */
    suspend fun saveAgreement(version: String = CURRENT_AGREEMENT_VERSION) {
        store.edit { it[Keys.AGREEMENT_VERSION] = version }
    }

    /** 保存家庭账号登录态（账号/令牌/用户ID），置 [Keys.CHILD_LOGGED_IN]=true。 */
    suspend fun saveChildLogin(phone: String, token: String, userId: String) {
        store.edit {
            it[Keys.CHILD_PHONE] = phone
            it[Keys.CHILD_TOKEN] = token
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
        store.data.catch { emit(emptyPreferences()) }
            .map { it[Keys.CHILD_PHONE].orEmpty() to "" }

    /** 是否开启了「记住账号密码」。 */
    val isRemembered: Flow<Boolean> = store.data.catch { emit(emptyPreferences()) }
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

        /** 当前生效的授权协议版本（说明书 §5.4 / §4.6）。协议正文迭代时升版即可。 */
        const val CURRENT_AGREEMENT_VERSION = "1.0.0"

        /**
         * 当前装机引导版本号。
         *
         * **每次往「一次性授权」清单里新增用户必须参与的项，都必须 +1**，
         * 否则存量设备不会补齐新增项（缺失表现为静默失效，家长无从察觉）。
         */
        const val CONSENT_VERSION_CURRENT = 1
    }

    /**
     * 已完成的装机引导版本；0 表示从未走过引导。
     * 入口分流据此决定是否需要展示一次性授权页：低于 [CONSENT_VERSION_CURRENT] 就补一次。
     */
    val consentVersion: Flow<Int> = store.data.catch { emit(emptyPreferences()) }
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
    val isDeviceAdminPromptAck: Flow<Boolean> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.DEVICE_ADMIN_PROMPT_ACK] ?: false }

    /** 标记家长已明确跳过 DA 引导，停止强制弹回授权流程。 */
    suspend fun setDeviceAdminPromptAck() {
        runCatching { store.edit { it[Keys.DEVICE_ADMIN_PROMPT_ACK] = true } }
            .onFailure { Logger.w(TAG) { "set device admin prompt ack failed" } }
    }

    /** 授权回退自愈标记：是否有项在装机后被关掉、需家长重新勾选（见 [Keys.REAUTH_REQUIRED]）。 */
    val reauthRequired: Flow<Boolean> = store.data.catch { emit(emptyPreferences()) }
        .map { it[Keys.REAUTH_REQUIRED] == true }

    /** 置位"需重新授权"标记，触发入口分流把家长带回引导流程。 */
    suspend fun setReauthRequired() {
        runCatching { store.edit { it[Keys.REAUTH_REQUIRED] = true } }
            .onFailure { Logger.w(TAG) { "set reauth required failed" } }
    }

    /** 清除"需重新授权"标记（引导流程完成或所有项已就绪时调用）。 */
    suspend fun clearReauthRequired() {
        runCatching { store.edit { it[Keys.REAUTH_REQUIRED] = false } }
            .onFailure { Logger.w(TAG) { "clear reauth required failed" } }
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
