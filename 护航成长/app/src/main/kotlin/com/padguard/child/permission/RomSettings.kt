package com.padguard.child.permission

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.padguard.core.common.Logger

/**
 * 国产 ROM 适配层（授权专项「适配所有厂商」）。
 *
 * ## 立场：适配 ≠ 硬跳私有页
 * 厂商私有设置页的 ComponentName 是**未公开的实现细节**：同一个品牌不同 ROM 大版本、
 * 甚至同一版本的地区包都可能改名或整体移除。硬写死一个 Activity 去 `startActivity`，
 * 在 A 机型能开、B 机型就抛 ActivityNotFoundException（授权页当场崩）或落到一个
 * 完全不同的页面（家长找不到开关）。
 *
 * 因此本层遵守三条纪律：
 * 1. **每个候选都先 `resolveActivity` 校验**，不可解析就换下一个，绝不硬 launch；
 * 2. **降级链一定以官方 Action / 应用详情页收尾**，任何厂商（含未知品牌、原生 Android、
 *    未来新版本）至少有一个能打开的页面，不会出现「点了没反应」；
 * 3. **私有页只作为"更精准"的候选**，它不可解析时自动退回官方页，功能不打折。
 *
 * ## 与现有 `SpecialPermission.openSettings` 的关系
 * 原有实现是「带 data 的官方页 → 不带 data 的官方列表页」两级降级。
 * 这里把它扩展成四级：`官方专属页 → 厂商私有页（仅适用项）→ 官方列表页 → 应用详情页`。
 * 所有 Intent 一律**不加 FLAG_ACTIVITY_NEW_TASK**：授权页用
 * `registerForActivityResult(StartActivityForResult)` 拉起，必须落在本 App 任务栈内，
 * 否则系统会立刻回传 RESULT_CANCELED 且设置页打不开（历史事故根因）。
 */
object RomSettings {

    /** 归一化后的厂商族。新增品牌只需在这里加一档 + 在 [privateComponents] 补候选。 */
    enum class Vendor {
        HUAWEI, HONOR, XIAOMI, OPPO, VIVO, SAMSUNG, MEIZU, LENOVO, GENERIC
    }

    /**
     * 厂商识别（纯函数，可在 JVM 单测里覆盖全矩阵）。
     *
     * 为什么同时看 `MANUFACTURER` 与 `BRAND`：部分 ROM 的 MANUFACTURER 是代工厂名
     * （如 `HUAWEI` 而 BRAND 是 `HONOR`），只看一个会把荣耀误判成华为、把 Redmi 误判成
     * 未知品牌，进而选错私有页。BRAND 优先于 MANUFACTURER 匹配更细的子品牌。
     */
    fun vendorOf(manufacturer: String?, brand: String?): Vendor {
        val b = brand.orEmpty().lowercase()
        val m = manufacturer.orEmpty().lowercase()
        val s = "$b $m"
        return when {
            "honor" in s -> Vendor.HONOR
            "huawei" in s || "harmony" in s -> Vendor.HUAWEI
            "redmi" in s || "poco" in s || "xiaomi" in s || "miui" in s || "hyperos" in s ->
                Vendor.XIAOMI
            "oneplus" in s || "oppo" in s || "realme" in s || "coloros" in s -> Vendor.OPPO
            "iqoo" in s || "vivo" in s || "funtouch" in s || "originos" in s -> Vendor.VIVO
            "samsung" in s -> Vendor.SAMSUNG
            "meizu" in s || "flyme" in s -> Vendor.MEIZU
            "lenovo" in s || "motorola" in s -> Vendor.LENOVO
            else -> Vendor.GENERIC
        }
    }

    /**
     * 「防清理」厂商指引：把本应用从 ROM 的「一键清理 / 清理加速」中排除出来的
     * 官方白名单设置步骤（**纯函数**，可在 JVM 单测里覆盖全厂商矩阵）。
     *
     * ## 为什么是文字指引而不是跳转设置页
     * 2026-11-17 事故：孩子在 EMUI 10 上点最近任务的「一键清理」，无障碍服务被
     * 停用，管控裸奔。ROM 对清理白名单应用会**跳过**——但这个白名单没有任何
     * 统一的系统页面（华为是最近任务卡片上的锁手势 + 启动管理，小米是卡片锁定 +
     * 省电策略），Android 也不提供任何查询接口。唯一能做的是把步骤讲清楚，让
     * 家长在装机这一趟顺手做完。
     *
     * ## 返回 null 的语义
     * 没有把握给出准确步骤的厂商返回 null（宁可不给指引也不给错误指引），
     * 调用方据此不拼接任何额外文案，装机清单保持原样。
     */
    fun cleanupShieldHint(vendor: Vendor): String? = when (vendor) {
        Vendor.HUAWEI, Vendor.HONOR ->
            "防清理设置（完成后「一键清理」会跳过本应用，无障碍服务不会被清除）：" +
                "① 在启动管理中把本应用设为允许自启动 + 后台运行；" +
                "② 回到桌面后上滑进入最近任务，下拉本应用卡片并点击锁图标加锁"
        Vendor.XIAOMI ->
            "防清理设置（完成后清理加速会跳过本应用）：" +
                "① 在自启动中允许本应用，并把省电策略设为「无限制」；" +
                "② 进入最近任务，长按本应用卡片选择「锁定」"
        Vendor.OPPO ->
            "防清理设置：在耗电保护 / 应用速冻中允许本应用后台运行，" +
                "并在最近任务中锁定本应用卡片，清理时即会跳过"
        Vendor.VIVO ->
            "防清理设置：在后台高耗电中允许本应用，" +
                "并在最近任务中下拉锁定本应用卡片，清理时即会跳过"
        Vendor.SAMSUNG, Vendor.MEIZU, Vendor.LENOVO, Vendor.GENERIC -> null
    }

    fun vendorOfDevice(): Vendor = vendorOf(Build.MANUFACTURER, Build.BRAND)

    /**
     * 「迷路型」设置页的分步操作指引（**纯函数**，可在 JVM 单测里覆盖全矩阵）。
     *
     * ## 为什么必须有
     * 直达开关页的项（悬浮窗 / 闹钟和提醒 / 通知，官方 Intent 带 package data
     * 直接落到本应用的开关行）不需要指引；但**列表型**页面——无障碍、电池优化、
     * 使用情况访问、安装未知应用——系统只给一个几十项的列表，家长要在里面
     * 自己找到「护航成长」，各厂商还各自改名。真机反馈"从电池优化和无障碍开始
     * 授权过程不流畅、逻辑不清"，主因就是**进了设置页不知道去哪找开关**。
     * 跳转前把步骤讲清楚，是这一环唯一能做的事。
     *
     * ## 返回 null 的语义
     * 直达开关页、不需要找路径的项返回 null（调用方不显示指引卡）。
     * 有把握给出准确路径的厂商给厂商专属路径，没有把握的给通用路径——
     * 与 [cleanupShieldHint] 同一条纪律：宁可不给，不给错的。
     */
    fun settingsStepsHint(vendor: Vendor, key: String): String? = when (key) {
        SpecialPermission.KEY_ACCESSIBILITY -> when (vendor) {
            Vendor.HUAWEI, Vendor.HONOR ->
                "操作步骤：在打开的页面进入「辅助功能」（部分机型叫「无障碍」）→" +
                    "「已下载的服务 / 已下载的应用」→ 点开「护航成长」→ 打开开关 →" +
                    "在系统风险提醒中点「允许」。风险提醒是系统对一切无障碍服务的标准提示，属正常现象"
            Vendor.XIAOMI ->
                "操作步骤：在打开的页面进入「更多设置 → 无障碍 → 已下载的应用」→" +
                    "点开「护航成长」→ 打开开关 → 点「确定」"
            else ->
                "操作步骤：在打开的「无障碍」页面找到「已下载的服务 / 已下载的应用」里的" +
                    "「护航成长」，点开并打开其开关，在系统提醒中确认"
        }
        SpecialPermission.KEY_BATTERY_OPTIMIZATION -> when (vendor) {
            Vendor.HUAWEI, Vendor.HONOR ->
                "操作步骤：在打开的电池/电源管理页找到「护航成长」，把它设为允许后台运行、" +
                    "不受限制；若该页找不到本应用，可返回后在 设置 → 应用 →「护航成长」→ 电池 中设置"
            Vendor.XIAOMI ->
                "操作步骤：找到「护航成长」，把省电策略设为「无限制」；" +
                    "若找不到，可返回后在 应用管理 →「护航成长」→ 省电策略 中设置"
            else ->
                "操作步骤：在打开的列表中找到「护航成长」，点开并选择「不允许 / 不优化」" +
                    "（即允许后台运行）"
        }
        SpecialPermission.KEY_USAGE_STATS ->
            "操作步骤：在打开的「使用情况访问」列表中点开「护航成长」，" +
                "打开「允许访问使用记录」开关"
        SpecialPermission.KEY_INSTALL_PACKAGES ->
            "操作步骤：在打开的列表中点开「护航成长」，" +
                "打开「允许来自此来源的应用 / 安装未知应用」开关"
        else -> null
    }

    /**
     * 厂商私有设置页候选（纯数据，`包名` → `完整类名`）。
     *
     * 每个权限附多条候选：同一厂商的不同 ROM 大版本入口不同，按顺序逐一探测。
     * 全部不可解析时由 [privateIntents] 返回空列表，调用方自动退回官方页 —— 这是
     * 「适配所有厂商」的兜底，未知品牌（含原生 Android / 模拟器）走 [Vendor.GENERIC] 空集，
     * 只用官方 Action，绝不因为不认识这台机器而崩或卡住。
     *
     * @param key [SpecialPermission.key]，只对确实存在厂商私有页的项返回非空
     */
    fun privateComponents(vendor: Vendor, key: String): List<Pair<String, String>> = when (key) {
        // 自启动 / 后台弹出界面 / 后台保活：各厂商的「权限管家」页
        SpecialPermission.KEY_OEM_BACKGROUND -> when (vendor) {
            Vendor.HUAWEI, Vendor.HONOR -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
            Vendor.XIAOMI -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.securitycenter" to "com.miui.appmanager.AppManagerActivity"
            )
            Vendor.OPPO -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity"
            )
            Vendor.VIVO -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.activity.BgStartUpManagerActivity"
            )
            Vendor.SAMSUNG -> listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
            Vendor.MEIZU -> listOf(
                "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
                "com.meizu.safe" to "com.meizu.safe.permission.PermissionMainActivity"
            )
            Vendor.LENOVO -> listOf(
                "com.lenovo.security" to "com.lenovo.security.purebackground.PureBackgroundActivity"
            )
            Vendor.GENERIC -> emptyList()
        }

        // 电池优化：部分 ROM 有比系统列表更直达的「后台高耗电 / 受保护应用」页
        SpecialPermission.KEY_BATTERY_OPTIMIZATION -> when (vendor) {
            Vendor.HUAWEI, Vendor.HONOR -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.power.ui.PowerManagementActivity"
            )
            Vendor.XIAOMI -> listOf(
                "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                "com.miui.securitycenter" to "com.miui.securitycenter.battery.BatteryActivity"
            )
            Vendor.SAMSUNG -> listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
            else -> emptyList()
        }

        else -> emptyList()
    }

    /**
     * 厂商私有页 Intent 列表。
     *
     * 带上三种常见的「目标应用」extra 键：多数厂商权限管家接受其中之一直接定位到
     * 本应用的开关页，不识别的 ROM 会忽略这些 extra、只显示列表 —— 两条路都可用。
     */
    fun privateIntents(context: Context, key: String): List<Intent> {
        val vendor = vendorOfDevice()
        return privateComponents(vendor, key).map { (pkg, cls) ->
            Intent().setClassName(pkg, cls).apply {
                putExtra("packageName", context.packageName)
                putExtra("extra_pkgname", context.packageName)
                putExtra("pkg", context.packageName)
            }
        }
    }

    /**
     * 通用兜底：应用详情页。
     *
     * 它是所有 Android 版本 / 所有厂商都存在的官方页面（`ACTION_APPLICATION_DETAILS_SETTINGS`
     * + package data），从那里能进「权限 / 通知 / 电池 / 特殊应用访问」的任意一项。
     * 有了这一级，任何新厂商、任何阉割版系统都不会出现「一个授权页都打不开」。
     */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))

    /**
     * 从候选链里挑出**第一个确实能被系统打开、且由系统应用提供**的 Intent。
     *
     * ## 为什么除了「可解析」还要判「谁提供的」
     * 官方设置页用的是公开 Action（`ACTION_MANAGE_OVERLAY_PERMISSION` 等），
     * 第三方应用也能注册同名 intent-filter。只看 `resolveActivity != null` 时，
     * 一旦某台设备上第三方页被解析成首选，授权向导就会把家长带去一个**假设置页**：
     * 轻则找不到开关卡住流程，重则诱导家长开启别的权限（钓鱼面）。
     * 系统设置页必然由系统应用（`FLAG_SYSTEM` / 更新过的系统应用）提供，
     * 因此把非系统候选**降级到最后**——正常 ROM 上行为完全不变。
     *
     * ## 为什么不是直接丢弃非系统候选
     * 极少数阉割 ROM 只留了厂商自家的（非 FLAG_SYSTEM 标记）设置实现，
     * 一刀切拒绝会变成「点了去开启却毫无反应」。所以：
     * 系统候选全不可用时，仍在这些已解析成功的候选里挑一个，并明确打 warn，
     * 便于真机回归时从日志定位。可用性优先于理论风险，但绝不静默。
     *
     * `resolveActivity` 在个别 ROM 上自身会抛异常，逐条 `runCatching` 吞掉即可。
     * 全部失败返回 null，由调用方给出「请手动到系统设置里找」的明确文案。
     */
    fun resolveFirst(context: Context, candidates: List<Intent>): Intent? {
        val pm = context.packageManager
        val nonSystem = mutableListOf<Intent>()
        for (candidate in candidates) {
            val info = runCatching { pm.resolveActivity(candidate, 0) }
                .onFailure { Logger.w(TAG) { "resolveActivity failed: ${it.message}" } }
                .getOrNull() ?: continue
            val flags = runCatching { info.activityInfo?.applicationInfo?.flags }.getOrNull() ?: 0
            val systemProvided = flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (!systemProvided) {
                Logger.w(TAG) {
                    "non-system settings handler demoted: " +
                        (candidate.action ?: candidate.component?.className ?: "?")
                }
                nonSystem += candidate
                continue
            }
            Logger.i(TAG) { "settings entry resolved: ${candidate.action ?: candidate.component?.className}" }
            return candidate
        }
        nonSystem.firstOrNull()?.let {
            Logger.w(TAG) { "no system settings handler; fall back to non-system candidate" }
            return it
        }
        Logger.w(TAG) { "no resolvable settings entry among ${candidates.size} candidates" }
        return null
    }

    private const val TAG = "RomSettings"
}
