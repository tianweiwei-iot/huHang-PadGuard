package com.padguard.child.guard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.AppListMode
import com.padguard.core.data.model.policy.AppPolicy
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appGuardDataStore: DataStore<Preferences> by preferencesDataStore("padguard_app_guard")

/**
 * 应用管控策略的**本地镜像**，供无障碍服务同步读取。
 *
 * ## 为什么需要它
 * 没有 Device Owner 时，[com.padguard.core.engine.enforcer.AppPolicyEnforcer] 只能把
 * "隐藏/挂起"上报为 UNSUPPORTED —— 家长在 App 里配了名单，设备上却什么都没发生，
 * 这正是"应用管控形同虚设"的根源。真正能落地的是无障碍服务
 * （[OperationGuardAccessibilityService]），但它跑在无障碍回调里、不能挂起读 Room，
 * 所以需要一份**可同步读取**的策略副本。
 *
 * ## 与 Enforcer 的分工
 * - Enforcer：设备支持时（DO/PO）走系统能力（隐藏、挂起）；
 * - 本类：设备不支持时，把**同一份名单**交给无障碍服务做前台拦截。
 * 两边读同一个 [AppPolicy]，不会出现"App 里显示已管控、设备上却放行"的分裂。
 *
 * ## 两条必须守住的红线
 * **1. 防砖（[isSystemCritical]）**——白名单意味着"不在名单里的一律拦下"，
 * 一旦把桌面/输入法/拨号器/自家 App 拦掉，用户就输不了密码、回不了桌面、打不了急救电话，
 * 设备等于报废，而且管控端自己在这台设备上，连解除都做不到。
 * 这类包**强制放行，不受家长配置影响**。
 *
 * **2. 防绕过（[SECURITY_CENTER_PACKAGES]）**——各 ROM 的"安全中心/权限管理"能一键禁用本无障碍服务，
 * 它们必须始终受密码保护。否则孩子花 3 秒就能关掉闸门，管控形同虚设。
 *
 * 注意：系统设置与安装器**不在**强制放行名单里 —— 它们恰恰是必须拦的（改设置 / 装卸应用）。
 */
@Singleton
class AppGuardPolicyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge
) {

    private val store = context.appGuardDataStore
    private val packageManager: PackageManager get() = context.packageManager

    /**
     * 后台收集作用域：常驻订阅 DataStore，把最新策略快照写入 [cache]。
     * 这样主线程读 [snapshot] 是零阻塞的易变字段读取（见下方说明）。
     */
    private val policyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 一次订阅，终身生效：DataStore 每次写入都会重新发射，[cache] 始终是最新值。
        // 首帧在进程刚启动、收集器尚未发射时可能为 null（等价于"尚未下发策略"），
        // 属安全的放行态；收集器发射后即恢复正常。
        store.data
            .map { computeSnapshot(it) }
            .catch { emit(null) }
            .onEach { cache = it }
            .launchIn(policyScope)
    }

    private val KEY_MODE = stringPreferencesKey("guard_mode")
    private val KEY_WHITELIST = stringPreferencesKey("guard_whitelist")
    private val KEY_BLACKLIST = stringPreferencesKey("guard_blacklist")
    private val KEY_SYSTEM_RULES = stringPreferencesKey("guard_system_rules")
    private val KEY_LAUNCH_BLOCK = booleanPreferencesKey("guard_launch_block")
    private val KEY_REQUIRE_INSTALL = booleanPreferencesKey("guard_require_install")
    private val KEY_REQUIRE_UNINSTALL = booleanPreferencesKey("guard_require_uninstall")
    private val KEY_HAS_POLICY = booleanPreferencesKey("guard_has_policy")

    /** 同步缓存：无障碍回调在主线程，不能每次挂起读盘 */
    @Volatile
    private var cache: Snapshot? = null

    data class Snapshot(
        val mode: AppListMode,
        val whitelist: Set<String>,
        val blacklist: Set<String>,
        /** 系统应用精细规则：包名 -> ALLOW / BLOCK（优先于名单） */
        val systemAppRules: Map<String, String>,
        /** 打开受管控应用是否要密码（家长端开关，默认关 = 只拦安装/卸载） */
        val requirePasswordOnLaunch: Boolean,
        /** 无 DO 降级：安装确认页是否要密码（家长端开关，默认 true = 要） */
        val requirePasswordOnInstall: Boolean,
        /** 无 DO 降级：卸载确认页是否要密码（家长端开关，默认 true = 要） */
        val requirePasswordOnUninstall: Boolean
    )

    /**
     * 把服务端下发的策略镜像到本地。
     *
     * 由 [com.padguard.child.service.GuardService] 在策略变化时调用。
     * DO 设备同样写：家长可能在设备还没拿到 DO 时就配好了策略，之后拿到 DO 也不该丢配置。
     */
    suspend fun mirror(policy: AppPolicy) {
        val rules = policy.systemAppRules.mapValues { it.value.uppercase() }
        store.edit {
            it[KEY_HAS_POLICY] = true
            it[KEY_MODE] = policy.mode.name
            it[KEY_WHITELIST] = encodeList(policy.whitelist)
            it[KEY_BLACKLIST] = encodeList(policy.blacklist)
            it[KEY_SYSTEM_RULES] = JSONObject(rules).toString()
            it[KEY_LAUNCH_BLOCK] = policy.requirePasswordOnLaunch
            it[KEY_REQUIRE_INSTALL] = policy.installPolicy.requirePasswordOnInstall
            it[KEY_REQUIRE_UNINSTALL] = policy.installPolicy.requirePasswordOnUninstall
        }
        cache = null // 失效，下次同步读时重载
        Logger.i(TAG) {
            "app policy mirrored: mode=${policy.mode}, white=${policy.whitelist.size}, " +
                "black=${policy.blacklist.size}, launchBlock=${policy.requirePasswordOnLaunch}, " +
                "installPwd=${policy.installPolicy.requirePasswordOnInstall}, " +
                "uninstallPwd=${policy.installPolicy.requirePasswordOnUninstall}"
        }
    }

    /** 关闭管控 / 解绑时清空，恢复"完全不拦" */
    suspend fun clear() {
        store.edit { it.clear() }
        cache = null
        Logger.i(TAG) { "app policy mirror cleared" }
    }

    /**
     * 同步读取当前策略；从未下发过策略返回 null（= 不做任何拦截）。
     *
     * ## 关键修复（应用管控闪退根因）
     * 早期实现用 `runBlocking(Dispatchers.IO) { store.data.first() }` 在**主线程**同步读盘。
     * 无障碍服务的 `onAccessibilityEvent` 每次界面切换都会调用本方法，等于在主线程频繁
     * 阻塞磁盘读取——系统会把无障碍服务判定为无响应并杀掉进程，表现为
     * "打开任意应用都闪退 / 正常使用也闪退"（授权与否都走这条热路径，所以全都崩）。
     *
     * 改为：后台 [policyScope] 常驻订阅 DataStore，最新快照始终在 [cache] 里；
     * 本方法只做无锁的易变字段读取，主线程零阻塞。
     */
    fun snapshot(): Snapshot? = cache

    /** 由后台收集器调用的纯函数：把一次 DataStore 快照换算成内存策略副本 */
    private fun computeSnapshot(prefs: Preferences): Snapshot? {
        if (!(prefs[KEY_HAS_POLICY] ?: false)) return null
        val rules = runCatching {
            val obj = JSONObject(prefs[KEY_SYSTEM_RULES] ?: "{}")
            obj.keys().asSequence().associateWith { obj.getString(it).uppercase() }
        }.getOrDefault(emptyMap())
        return Snapshot(
            mode = runCatching { AppListMode.valueOf(prefs[KEY_MODE] ?: "BLACKLIST") }
                .getOrDefault(AppListMode.BLACKLIST),
            whitelist = decodeList(prefs[KEY_WHITELIST]),
            blacklist = decodeList(prefs[KEY_BLACKLIST]),
            systemAppRules = rules,
            requirePasswordOnLaunch = prefs[KEY_LAUNCH_BLOCK] ?: false,
            requirePasswordOnInstall = prefs[KEY_REQUIRE_INSTALL] ?: true,
            requirePasswordOnUninstall = prefs[KEY_REQUIRE_UNINSTALL] ?: true
        )
    }

    /**
     * 该包打开时是否需要操作密码闸门。
     *
     * 判定口径（两处调用方 [OperationGuardAccessibilityService.decide] 与
     * [com.padguard.child.KidHomeViewModel.needsLaunchGuard] 必须保持一致）：
     *
     * 1. **「打开应用也要密码」开启时 = 全量启动闸门**：除系统关键组件外，任意应用
     *    打开都需先输管理密码。这是家长最直观的预期（"应用需要密码才能使用"），
     *    不应依赖"是否已加入黑名单"——否则开关在默认黑名单模式下对绝大多数应用完全失效。
     * 2. **开关关闭时**：回落到名单口径（白名单外 / 黑名单内 ≈ 受管控），
     *    供 DO 模式隐藏/挂起与孩子端桌面锁图标展示使用。
     *
     * 调用方需先排除 [isSystemCritical] 与自家界面。
     */
    fun isControlled(pkg: String): Boolean {
        val s = snapshot() ?: return false
        // 已授权应用一律放行：这是「授权的可以正常使用」的底线。
        // 任何全局闸门（含「打开应用也要密码」）都不得误伤已授权应用，
        // 否则会退化成「无论授权与否打开都被弹回/闪退」的现场故障。
        if (isAuthorized(s, pkg)) return false
        // 全局启动闸门：仅对已授权之外的应用（即未授权应用）生效，
        // 让「打开应用也要密码」变成「未授权应用打开要密码」，而非「所有应用都要密码」。
        if (s.requirePasswordOnLaunch && !isSystemCritical(pkg)) return true
        // 精细规则优先：家长显式标了 ALLOW/BLOCK 时，名单口径让位于精细规则
        s.systemAppRules[pkg]?.let { return it == "BLOCK" }
        return when (s.mode) {
            AppListMode.WHITELIST -> pkg !in s.whitelist
            AppListMode.BLACKLIST -> pkg in s.blacklist
        }
    }

    /**
     * 家长是否"关闭了该应用的使用权限"（hidden）。
     *
     * 与 [isControlled] 的区别：这是"关闭权限"的专属语义，不依赖 [snapshot] 里的
     * 名单/黑名单，也**不受「打开应用也要密码」开关（[launchBlockEnabled]）约束**——
     * 关闭权限 = 打开就必须输管理密码，是无条件底线（DO 设备额外把图标真隐藏）。
     *
     * 数据来源是 [DeviceAdminBridge] 的本地意图记账（[DeviceAdminBridge.persistHidden]），
     * 由 [com.padguard.core.engine.command.CommandExecutor] 在收到 `SET_APP_HIDDEN` 命令时
     * 写入——非 DO 设备无法真隐藏，但意图照记，软拦截闸门据此生效。
     * 系统关键包（防砖名单）一律返回 false，避免把桌面/输入法/拨号器锁死。
     */
    fun isHiddenControlled(pkg: String): Boolean {
        if (isSystemCritical(pkg)) return false
        return admin.isHidden(pkg)
    }

    /**
     * 应用是否已授权（可正常使用）。
     *
     * - 白名单模式：在白名单内即授权；
     * - 黑名单模式：不在黑名单内即授权。
     *
     * 与 [isControlled] 共用，确保「授权放行 / 未授权拦截」口径在
     * 桌面可视网关（[com.padguard.child.KidHomeViewModel.needsLaunchGuard]）、
     * 无障碍软拦截（[OperationGuardAccessibilityService.decide]）、
     * DO 隐藏/挂起（[com.padguard.core.engine.enforcer.AppPolicyEnforcer]）三处完全一致，
     * 不会出现"App 里显示已授权、设备上却仍被拦"。
     */
    private fun isAuthorized(s: Snapshot, pkg: String): Boolean = when (s.mode) {
        AppListMode.WHITELIST -> pkg in s.whitelist
        AppListMode.BLACKLIST -> pkg !in s.blacklist
    }

    /**
     * 安装 / 卸载确认页是否要求管理密码（无障碍软拦截闸门）。
     *
     * 无 DO 时我们无法真正阻止安装卸载（系统限制），但可以在**确认界面**上要求密码；
     * 家长在控制端关闭对应开关后，连确认界面都不拦（表现为"安装/卸载免密"）。
     * 默认 true：核心安全能力，除非家长显式关闭，否则一律要密码。
     *
     * 注意：返回的是"确认页是否要密码"，与 DO 路径的 `allowUserInstall`
     * （系统级是否禁用安装）是两个独立概念，不可混用。
     */
    fun installNeedsPassword(): Boolean = snapshot()?.requirePasswordOnInstall ?: true

    fun uninstallNeedsPassword(): Boolean = snapshot()?.requirePasswordOnUninstall ?: true

    fun launchBlockEnabled(): Boolean = snapshot()?.requirePasswordOnLaunch ?: false

    /**
     * 非 DO 设备的「防卸载兜底」是否应启用。
     *
     * ## 行业做法对齐
     * 普通设备管理员(DEVICE_ADMIN)无法调用 `setUninstallBlocked`(那需要 DO/PO)，
     * 但 Android 在卸载本应用前必须先「取消设备管理员」——而
     * [com.padguard.child.receiver.PadGuardDeviceAdminReceiver.onDisableRequested] 返回警告
     * 会**实际阻止**该取消步骤(系统不显示确认按钮)，形成系统级双保险。
     * 本方法就是这层双保险的「启用判定」：仅在「非 Device Owner(否则 DISALLOW_APPS_CONTROL 已封)
     * + 设备管理器已激活(否则 onDisableRequested 不被调用)」时为 true。
     *
     * 无障碍服务据此再补一层「拦截设置 → 应用信息/卸载页」，让孩子即使没开操作密码也进不去卸载入口。
     * 未激活设备管理器(LEGACY)时返回 false —— 此时只能靠操作密码开启后的 [decide] 的 REASON_SETTINGS 兜底，
     * 所以部署/激活流程必须引导孩子激活设备管理器(既有引导已覆盖)。
     */
    fun selfProtectApplies(): Boolean = !admin.isDeviceOwner() && admin.isAdminActive()

    /**
     * 必须永远放行的包（防砖）。
     *
     * 含动态解析的 Launcher / 默认输入法 / 默认拨号器 —— 各 ROM 包名不同，
     * 硬编码必然漏（漏一个就是"打不了字"级别的事故）。
     */
    fun isSystemCritical(pkg: String): Boolean {
        if (pkg.startsWith(OWN_PACKAGE_PREFIX)) return true
        if (pkg in SYSTEM_URGENT) return true
        if ("systemui" in pkg.lowercase()) return true
        return isDefaultLauncher(pkg) || isDefaultInputMethod(pkg) || isDefaultDialer(pkg)
    }

    private fun isDefaultLauncher(pkg: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName == pkg
    }.getOrDefault(false)

    private fun isDefaultInputMethod(pkg: String): Boolean = runCatching {
        val ime = Settings.Secure.getString(context.contentResolver, "default_input_method")
        ime?.substringBefore('/') == pkg
    }.getOrDefault(false)

    private fun isDefaultDialer(pkg: String): Boolean = runCatching {
        // 只用 TelecomManager.defaultDialerPackage（API 23+）：
        // RoleManager 的 getRoleHolders 是 API 29+，本项目 minSdk 26，
        // 为一个"保护名单"引入版本分支与 @RequiresApi 不划算 ——
        // 漏判的后果只是"拨号器可能被要求输密码"，远比变砖风险低。
        val telecom = context.getSystemService(Context.TELECOM_SERVICE)
            as? android.telecom.TelecomManager
        telecom?.defaultDialerPackage == pkg
    }.getOrDefault(false)

    private fun encodeList(list: List<String>): String =
        JSONArray().apply { list.forEach { put(it) } }.toString()

    private fun decodeList(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .mapNotNullTo(mutableSetOf()) { arr.optString(it).takeIf(String::isNotBlank) }
        }.getOrDefault(emptySet())
    }

    companion object {
        private const val TAG = "AppGuardPolicyStore"
        private const val OWN_PACKAGE_PREFIX = "com.padguard.child"

        /**
         * 绝不能拦的系统组件。
         * 拦掉任何一个，用户就输不了密码 / 回不了桌面 / 打不了急救电话，设备等于变砖。
         * 注意这里**不含**系统设置与安装器 —— 它们恰恰是要拦的。
         */
        private val SYSTEM_URGENT = setOf(
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.dialer",
            "com.android.contacts",
            "com.android.providers.settings",
            "com.android.shell",
            "com.android.permissioncontroller"
        )

        /**
         * 各 ROM 的"安全中心 / 权限管理"，能一键禁用本无障碍服务 → 必须始终受密码保护。
         * 同时这些包也常承载"安装/卸载确认"，一并纳入。
         */
        val SECURITY_CENTER_PACKAGES = setOf(
            "com.miui.securitycenter",
            "com.miui.securityadd",
            "com.miui.permissionscenter",
            "com.vivo.permissionmanager",
            "com.vivo.safe",
            "com.huawei.systemmanager",
            "com.huawei.securitycenter",
            "com.coloros.safecenter",
            "com.coloros.securitypermission",
            "com.oppo.safe",
            "com.samsung.android.security.da.daagent",
            "com.oneplus.security"
        )

        /**
         * 是否是"安装 / 卸载确认"界面。
         * AOSP 与各 ROM 的安装器包名都含 `packageinstaller` 子串；
         * 部分 ROM（小米/华为）用自研安装器，靠安全中心包名兜底。
         */
        fun isInstallerPackage(pkg: String): Boolean {
            val lower = pkg.lowercase()
            return "packageinstaller" in lower ||
                "appinstall" in lower ||
                "packageinstaller" in lower ||
                pkg in SECURITY_CENTER_PACKAGES
        }
    }
}
