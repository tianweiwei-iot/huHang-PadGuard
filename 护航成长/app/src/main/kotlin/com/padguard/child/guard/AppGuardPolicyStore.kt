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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    @ApplicationContext private val context: Context
) {

    private val store = context.appGuardDataStore
    private val packageManager: PackageManager get() = context.packageManager

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

    /** 同步读取当前策略；从未下发过策略返回 null（= 不做任何拦截） */
    fun snapshot(): Snapshot? {
        cache?.let { return it }
        return runCatching {
            runBlocking(Dispatchers.IO) {
                val prefs = store.data.first()
                if (!(prefs[KEY_HAS_POLICY] ?: false)) return@runBlocking null
                val rules = runCatching {
                    val obj = JSONObject(prefs[KEY_SYSTEM_RULES] ?: "{}")
                    obj.keys().asSequence().associateWith { obj.getString(it).uppercase() }
                }.getOrDefault(emptyMap())
                Snapshot(
                    mode = runCatching { AppListMode.valueOf(prefs[KEY_MODE] ?: "BLACKLIST") }
                        .getOrDefault(AppListMode.BLACKLIST),
                    whitelist = decodeList(prefs[KEY_WHITELIST]),
                    blacklist = decodeList(prefs[KEY_BLACKLIST]),
                    systemAppRules = rules,
                    requirePasswordOnLaunch = prefs[KEY_LAUNCH_BLOCK] ?: false,
                    requirePasswordOnInstall = prefs[KEY_REQUIRE_INSTALL] ?: true,
                    requirePasswordOnUninstall = prefs[KEY_REQUIRE_UNINSTALL] ?: true
                ).also { cache = it }
            }
        }.getOrNull()
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
        // 全局启动闸门优先：开关一开，任意非系统应用打开都要密码
        if (s.requirePasswordOnLaunch && !isSystemCritical(pkg)) return true
        // 精细规则优先：家长显式标了 ALLOW/BLOCK 时，名单口径让位于精细规则
        s.systemAppRules[pkg]?.let { return it == "BLOCK" }
        return when (s.mode) {
            AppListMode.WHITELIST -> pkg !in s.whitelist
            AppListMode.BLACKLIST -> pkg in s.blacklist
        }
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
