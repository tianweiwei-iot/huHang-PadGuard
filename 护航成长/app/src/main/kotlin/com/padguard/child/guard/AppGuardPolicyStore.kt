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
import com.padguard.child.util.isOwnPackage
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.AppListMode
import com.padguard.core.data.model.policy.AppPolicy
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
    private var cache: AppGuardDecisions.Snapshot? = null

    /**
     * 常驻订阅 + 冷启动预热（R-P3-15）。
     *
     * ## 必须放在 `KEY_*` 之后
     * 下面的预热是**同步**调用 [computeSnapshot]，而它要读 `KEY_*` 这些属性。
     * Kotlin 的属性初始化与 init 块按声明顺序交错执行，若把 init 写在 `KEY_*` 之前，
     * 那几个 key 此刻还是 null（NPE）。因此这个 init 块必须位于全部 key 声明之后。
     *
     * ## 为什么冷启动要同步预热
     * 旧实现只靠协程订阅：进程刚起（无障碍服务被系统重新拉起是最常见场景）时，
     * `store.data` 的首次读盘还没完成，[cache] 就是 null，而 [snapshot] 返回 null 的语义是
     * 「从未下发过策略 → 不拦任何应用」。于是重启后的头几百毫秒内，孩子点开任何被禁应用
     * 都能长驱直入 —— 这正是 R-P3-15 描述的 fail-open 空窗，且**每次进程重启都会重现**。
     * 这里在构造期同步读一次上次镜像（≤ 几个 key 的小文件，一次性开销，不在热路径上），
     * 保证「进程一起来就已是上次的策略」，空的只剩真正的首次安装。
     */
    init {
        cache = runCatching {
            // 冷启动同步预载（R-P3-15：避免进程重启后头几百毫秒 fail-open）。
            // 用短超时封顶：极端慢盘/加密存储卡顿时，放弃同步预载、退回下方异步订阅路径，
            // 由 store.data 首次发射补齐——宁可短暂 fail-open，也不无限阻塞构造线程（冷启动 ANR 风险）。
            // 正常路径（≤ 几个 key 的小文件）远小于超时，行为与改造前一致。
            runBlocking {
                withTimeoutOrNull(PRELOAD_TIMEOUT_MS) { computeSnapshot(store.data.first()) }
            }
        }.onFailure {
            Logger.w(TAG, it) { "cold-start policy preload failed, keep null until first emission" }
        }.getOrNull()

        // 一次订阅，终身生效：DataStore 每次写入都会重新发射，[cache] 始终是最新值。
        store.data
            .map { computeSnapshot(it) }
            // 读盘异常时**绝不能** `emit(null)`：那等于把已生效的镜像抹成"未下发策略"，
            // 管控瞬间全放行（fail-open）。保留上一次已知良好的快照，等下一次成功发射再覆盖。
            .catch { Logger.w(TAG, it) { "policy stream failed, keep last known snapshot" } }
            .onEach { cache = it }
            .launchIn(policyScope)
    }

    /**
     * 把服务端下发的策略镜像到本地。
     *
     * 由 [com.padguard.child.service.GuardService] 在策略变化时调用。
     * DO 设备同样写：家长可能在设备还没拿到 DO 时就配好了策略，之后拿到 DO 也不该丢配置。
     */
    suspend fun mirror(policy: AppPolicy) {
        // 先用刚下发的策略构造好新快照，落盘成功后原子换上去（见方法末尾说明）。
        val next = snapshotOf(policy)
        store.edit {
            it[KEY_HAS_POLICY] = true
            it[KEY_MODE] = next.mode.name
            it[KEY_WHITELIST] = encodeList(policy.whitelist)
            it[KEY_BLACKLIST] = encodeList(policy.blacklist)
            it[KEY_SYSTEM_RULES] = JSONObject(next.systemAppRules).toString()
            it[KEY_LAUNCH_BLOCK] = next.requirePasswordOnLaunch
            it[KEY_REQUIRE_INSTALL] = next.requirePasswordOnInstall
            it[KEY_REQUIRE_UNINSTALL] = next.requirePasswordOnUninstall
        }
        // R-P3-15：**不要**再 `cache = null`。
        // 旧实现把内存镜像置空、等协程重新读盘再填回，中间存在一段"旧策略已抹掉、新策略没上来"的
        // 空窗；而 [snapshot] 返回 null 的语义是「从未下发过策略 → 什么都不拦」，
        // 空窗内的每一次 [isControlled]/[launchBlockEnabled]/[installNeedsPassword] 都会放行。
        // 现在磁盘写完就地换成新快照：读取端永远看到「旧策略 → 新策略」的连续序列，
        // 既没有 fail-open 空窗，也省掉一次多余的读盘。
        cache = next
        Logger.i(TAG) {
            "app policy mirrored: mode=${next.mode}, white=${next.whitelist.size}, " +
                "black=${next.blacklist.size}, launchBlock=${next.requirePasswordOnLaunch}, " +
                "installPwd=${next.requirePasswordOnInstall}, " +
                "uninstallPwd=${next.requirePasswordOnUninstall}"
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
    fun snapshot(): AppGuardDecisions.Snapshot? = cache

    /**
     * 基于当前策略快照构建纯函数决策器。
     *
     * 把"防砖判定"（[isSystemCritical]）与"取消授权账本"（[DeviceAdminBridge.isHidden]）
     * 作为外部事实注入，使 [AppGuardDecisions] 保持无 Android 依赖、可单测，
     * 同时与本地唯一真相保持一致。
     */
    fun decisions(): AppGuardDecisions? =
        cache?.let { AppGuardDecisions(it, ::isSystemCritical, admin::isHidden) }

    /**
     * 把一份**刚从服务端下发的** [AppPolicy] 直接换算成决策快照（不经过磁盘）。
     *
     * 与 [computeSnapshot] 必须保持同一口径 —— 两者分别服务"下发那一刻"与"进程重启后"，
     * 若字段映射或默认值不一致，就会出现"刚下发时拦、重启后放"的诡异分裂。
     * 因此这里的字段映射与默认值刻意与 [computeSnapshot] 逐项对齐。
     */
    private fun snapshotOf(policy: AppPolicy): AppGuardDecisions.Snapshot =
        AppGuardDecisions.Snapshot(
            mode = policy.mode,
            whitelist = policy.whitelist.toSet(),
            blacklist = policy.blacklist.toSet(),
            systemAppRules = policy.systemAppRules.mapValues { it.value.uppercase() },
            requirePasswordOnLaunch = policy.requirePasswordOnLaunch,
            requirePasswordOnInstall = policy.installPolicy.requirePasswordOnInstall,
            requirePasswordOnUninstall = policy.installPolicy.requirePasswordOnUninstall,
            isDeviceOwner = admin.isDeviceOwner()
        )

    /** 由后台收集器调用的纯函数：把一次 DataStore 快照换算成内存策略副本 */
    private fun computeSnapshot(prefs: Preferences): AppGuardDecisions.Snapshot? {
        if (!(prefs[KEY_HAS_POLICY] ?: false)) return null
        val rules = runCatching {
            val obj = JSONObject(prefs[KEY_SYSTEM_RULES] ?: "{}")
            obj.keys().asSequence().associateWith { obj.getString(it).uppercase() }
        }.getOrDefault(emptyMap())
        return AppGuardDecisions.Snapshot(
            mode = runCatching { AppListMode.valueOf(prefs[KEY_MODE] ?: "BLACKLIST") }
                .getOrDefault(AppListMode.BLACKLIST),
            whitelist = decodeList(prefs[KEY_WHITELIST]),
            blacklist = decodeList(prefs[KEY_BLACKLIST]),
            systemAppRules = rules,
            // 新增缺陷 O：必须与 [AppPolicy.requirePasswordOnLaunch] 的出厂默认值 **true** 对齐
            // （[snapshotOf] 直接取字段，key 缺失时读的就是模型默认值）。旧版读盘写死 `?: false`，
            // 于是旧版盘 / DataStore 部分损坏缺 key 时出现「刚下发时拦、重启后放」的分裂 ——
            // 正是 [snapshotOf] 注释声称要防的场景。缺 key 时取更严的默认值（fail-closed）。
            requirePasswordOnLaunch = prefs[KEY_LAUNCH_BLOCK] ?: true,
            requirePasswordOnInstall = prefs[KEY_REQUIRE_INSTALL] ?: true,
            requirePasswordOnUninstall = prefs[KEY_REQUIRE_UNINSTALL] ?: true,
            isDeviceOwner = admin.isDeviceOwner()
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
    /**
     * 该包是否受管控（需要密码门禁），判定口径经 [decisions] 收敛为单一真相。
     *
     * 注意：本方法只回答"名单/取消授权层面是否受控"，**不含「打开应用也要密码」全局闸门**——
     * 那一层由 [AppGuardDecisions.needsLaunchGuard] 在调用方统一施加，避免三处口径分叉。
     * 取消授权（hidden）在此即视为受控（最严），与桌面可见性（[isLaunchableByChild]）、
     * 软拦截（[OperationGuardAccessibilityService]）共用同一判定。
     *
     * 调用方（无障碍软拦截、桌面点开门禁）在此之前都已单独排除系统关键包与自身界面，
     * 因此这里不再重复排除。
     */
    fun isControlled(pkg: String): Boolean = decisions()?.isControlled(pkg) ?: false

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
     * 孩子端桌面是否应展示并允许启动该应用（**应用管控的权威入口判定**）。
     *
     * 收敛自 [AppGuardDecisions.isLaunchableByChild]：被取消授权 / 名单外的应用**在桌面直接不出现**，
     * 从源头减少触达机会，而不是"点了再弹框顶回"——
     * 后者在非 DO 设备上是可被最近任务 / 系统桌面绕过的（这正是"应用管控照样能打开"的成因之一）。
     *
     * - 系统关键包（防砖名单）→ 永远可启动，绝不隐藏；
     * - 取消授权（hidden）→ 不可启动（必须隐藏，否则孩子能看到图标）；
     * - 白 / 黑名单口径 → 名单外 / 黑名单内不可启动。
     *
     * 未下发策略（[decisions] 为 null）时返回 true（fail-open），保证桌面不会凭空空白、设备始终可用。
     */
    fun isLaunchableByChild(pkg: String): Boolean = decisions()?.isLaunchableByChild(pkg) ?: true

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

    /** 当前设备是否为 Device Owner（决定"打开应用也要密码"闸门口径，见 [AppGuardDecisions.needsLaunchGuard]）。 */
    fun isDeviceOwner(): Boolean = admin.isDeviceOwner()

    /**
     * 非 DO 设备的「防卸载兜底」是否应启用。
     *
     * ## 能力边界（务必如实理解，别高估）
     * 普通设备管理员(DEVICE_ADMIN)无法调用 `setUninstallBlocked`(那是 DO/PO 专属 API)，
     * 非 DO 形态下**没有任何系统级手段能真正封死卸载**。
     *
     * 真正能依赖的只有两点：
     * 1. 设备管理员激活期间，系统要求「先取消激活才能卸载」，把一步操作变成两步，
     *    从而为无障碍拦截创造窗口（此期间 adb 卸载也会直接失败）；
     * 2. [com.padguard.child.receiver.PadGuardDeviceAdminReceiver.onDisableRequested] 的返回值
     *    只是**警告文案**：AOSP 会照常显示「取消激活」按钮，用户点下去照样停用。
     *
     * 网传「返回警告即可阻止停用」依赖 5 秒 Activity 切换窗口期的漏洞
     * (连续锁屏 / 透明窗口劫持 / 阻塞返回)，Android 5.0+ 的 stopAppSwitch 已封堵，
     * 且属滥用行为、上架会被拒——**不要照抄**。
     *
     * 所以本方法只是这层兜底的「启用判定」：仅在「非 Device Owner + 设备管理器已激活」时为 true，
     * 真正起作用的是下面无障碍层的顶回与密码闸门，而不是某个"系统级双保险"。
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
        if (isOwnPackage(pkg)) return true
        if (pkg in SYSTEM_URGENT) return true
        // 注意：不能用子串 `"systemui" in pkg` 判定。系统 UI 标准包名恒为 `com.android.systemui`
        // （已在 SYSTEM_URGENT 精确集合内）；子串匹配会让任意含 "systemui" 的第三方包名
        // （如 `com.example.systemui.game`）被当成系统关键，从而绕过锁屏顶回 / 免密码门禁 / 隐藏名单，
        // 与 `isOwnPackage` 确立的"精确相等"红线同源越权。OEM 不会重命名框架级 systemui 组件，
        // 需要更广覆盖请往 SYSTEM_URGENT 加精确包名，而非放宽匹配。
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

        /**
         * 冷启动同步预载的最坏阻塞上限（毫秒）。
         * 仅用于封顶极端慢盘/加密存储卡顿的构造期阻塞，正常小文件读盘远小于此值。
         */
        private const val PRELOAD_TIMEOUT_MS = 300L

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
            // 第三个分支曾是 "packageinstaller" 的逐字复制（复制粘贴漏改），
            // 白白占掉一个匹配位却没覆盖任何新 ROM，已删除。
            return "packageinstaller" in lower ||
                "appinstall" in lower ||
                pkg in SECURITY_CENTER_PACKAGES
        }
    }
}
