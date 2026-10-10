package com.padguard.child.permission

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.padguard.child.guard.GuardA11y
import com.padguard.core.common.Logger

/**
 * AppOps 特殊权限统一模型。
 *
 * ## 为什么必须单独拎出来
 * 这几项**不属于运行时权限**：既不出现在 `requestPermissions` 的系统弹窗里，
 * 也无法用 `checkSelfPermission` 查询，更关键的是——
 * **连 Device Owner 都无权代为授予**（`setPermissionGrantState` 只作用于危险权限，
 * 对 AppOps 直接返回 false）。唯一路径是用户在系统设置页亲手打开开关。
 *
 * 因此"一键获取全部权限"的真实边界是：运行时权限可以静默到位，
 * 这几项只能做到**一键跳到对应设置页 + 回来自动检测**，
 * 把原本"家长自己翻遍设置找一个开关"压缩成"跳过去、拨一下、返回"。
 *
 * ## 一次性把这些项收走，而不是用到时再补
 * 装机一次授权到位、之后不再二次打扰，是家长端的明确要求。
 * 因此这里**不区分"必做/可选"**：装机这一趟就把 [values] 全部拿到。
 * 若在装机时把悬浮窗、安装权限留到后面"按需补开"，家长就会在使用到
 * 水印、远程安装时才被第二次叫去系统设置——这正是要消灭的体验问题。
 * 代价只是装机时多拨两个开关，换来的是全流程再无授权中断。
 *
 * ## 缺这些项的后果是**静默失效**，这是最危险的地方
 * - [USAGE_STATS] 没开 → `ForegroundAppMonitor` 探不到前台应用 →
 *   应用时长统计恒为空、单应用限额永不触发、黑名单事后拦截失效，
 *   而家长端界面上看不到任何报错，只会觉得"限额设了没效果"；
 * - [OVERLAY] 没开 → `WatermarkOverlay.isSupported()` 返回 false →
 *   水印静默不显示，家长以为开了其实没有。
 *
 * 所以它们必须显式进入一键清单，而不是像以前那样被完全漏掉。
 */
enum class SpecialPermission(
    val key: String,
    val label: String,
    /** 缺失后果（家长看得懂的白话，不写技术名词） */
    val impact: String
) {
    OVERLAY(
        "overlay",
        "悬浮窗",
        "全局水印依赖它显示，未开启则水印不会出现"
    ),
    USAGE_STATS(
        "usageStats",
        "使用情况访问",
        "应用使用时长统计与单个应用限额都依赖它，未开启则限额设了不生效、用量恒为 0"
    ),
    /**
     * 操作闸门无障碍服务。
     *
     * 它不属于 AppOps，而是写在 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 里，
     * 但仍与上面几项**同构**：App 无法自开、DO 也无法代开，只有家长在系统设置页手动启用。
     * 因此归入同一枚举，一次性装机流程才能用同一套模型自动排队打开，
     * 而不是像以前那样单独藏在「我的」页里、等到家长配置应用管控时才发现没开。
     *
     * ## 跳转策略（务必稳定，不玩 ROM 黑魔法）
     * 无障碍详情页**不接受**让我们直接落到本应用服务开关的通用深链；各厂商 ROM 还各自改写了入口，
     * 靠 `ComponentName` 硬跳在 A 手机能开、B 手机就崩，正式产品不可依赖。
     * 因此只走官方 `ACTION_ACCESSIBILITY_SETTINGS`（落到无障碍列表），
     * 并以 `Intent.EXTRA_COMPONENT_NAME` 做**尽力深链**：原生/部分 ROM 会直接定位到本服务详情，
     * 识别不了的 ROM 静默降级为列表页——两条路都安全、都不会崩。
     * 真正的"免手动返回"由 [ConsentReturnPresenter] 的轮询守护 + 全屏 Intent 兜底，
     * 不靠深链，所以这里只需把家长"送到能找到开关的地方"。
     */
    ACCESSIBILITY(
        "accessibility",
        "无障碍服务（操作闸门）",
        "密码闸门的系统级拦截依赖它，未启用则孩子可从系统设置绕过管控、卸载本应用"
    ),
    INSTALL_PACKAGES(
        "installPackages",
        "安装未知应用",
        "家长端远程推送安装应用时需要；已是设备所有者则不需要（可静默安装）"
    );

    /**
     * 当前是否已授予。
     *
     * 每一项都要走各不相同的专用 API，没有统一的查询口径。
     */
    fun isGranted(context: Context): Boolean = when (this) {
        USAGE_STATS -> runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return@runCatching false
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return@runCatching false
            val uid = context.applicationInfo.uid
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        OVERLAY ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        INSTALL_PACKAGES -> runCatching {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                context.packageManager.canRequestPackageInstalls()
        }.getOrDefault(true)
        ACCESSIBILITY -> GuardA11y.isEnabled(context)
    }

    /**
     * 打开对应的系统授权页。
     *
     * **带上 package data 是关键**：不带的话系统会甩出一个几十项的全量应用列表，
     * 家长要在里面自己找「护航成长」，实际常常找不到就放弃了；
     * 带了 data，绝大多数 ROM 会直接落在本应用的开关页。
     *
     * 无障碍页例外：它不接受 package data，只能进「无障碍」总页后手工找到服务条目，
     * 这是系统限制，无法再优化。
     */
    fun settingsIntent(context: Context): Intent = when (this) {
        USAGE_STATS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
        OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(Uri.fromParts("package", context.packageName, null))
        INSTALL_PACKAGES -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.fromParts("package", context.packageName, null))
        ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            // 尽力深链到本应用无障碍服务详情（见枚举注释）；识别不了的 ROM 静默降级为列表页。
            putExtra(
                Intent.EXTRA_COMPONENT_NAME,
                ComponentName(
                    context.packageName,
                    "com.padguard.child.guard.OperationGuardAccessibilityService"
                ).flattenToString()
            )
        }
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        fun fromKey(key: String): SpecialPermission? = values().firstOrNull { it.key == key }

        /**
         * 当前缺失项（按声明顺序，便于 UI 稳定展示）。
         *
         * 一次性装机流程要的就是这份清单：装机这一趟把全部项收走，
         * 之后 App 内不再因为缺权限把家长叫去系统设置。
         *
         * @param hasOwnerPrivileges 已是 DO/PO 时不需要 [INSTALL_PACKAGES]：
         *   设备所有者走 PackageInstaller 静默安装，压根不请求这个权限，
         *   此时把它算作"缺失"只会让家长去做一次无意义的设置跳转。
         */
        fun missing(context: Context, hasOwnerPrivileges: Boolean): List<SpecialPermission> =
            values().filter { perm ->
                if (perm == INSTALL_PACKAGES && hasOwnerPrivileges) return@filter false
                !perm.isGranted(context)
            }

        /** 单项便捷的缺失判断，供状态横幅等非装机场景使用。 */
        fun isMissing(context: Context, perm: SpecialPermission, hasOwnerPrivileges: Boolean): Boolean =
            perm in missing(context, hasOwnerPrivileges)

        /**
         * 稳妥跳转到设置页。
         *
         * 个别 ROM 改过这些 Action（或不接受 package data 的 Uri），直接 startActivity 会抛
         * ActivityNotFoundException。这里逐层降级：带 data 的专属页 → 不带 data 的列表页。
         * 两条路都失败才返回 null，由调用方提示手动去设置里找，而不是静默无反应。
         */
        fun openSettings(context: Context, perm: SpecialPermission): Intent? {
            val withData = perm.settingsIntent(context)
            val probe = runCatching { withData.resolveActivity(context.packageManager) }.getOrNull()
            if (probe != null) {
                Logger.i(TAG) { "open settings for ${perm.key} (resolved: $probe)" }
                return withData
            }
            val fallback = when (perm) {
                USAGE_STATS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                INSTALL_PACKAGES -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val fbProbe = runCatching { fallback.resolveActivity(context.packageManager) }.getOrNull()
            if (fbProbe != null) {
                Logger.w(TAG) { "settings for ${perm.key} fell back to list page (ROM quirk)" }
                return fallback
            }
            Logger.e(TAG) { "no activity to open settings for ${perm.key}" }
            return null
        }

        private const val TAG = "SpecialPermission"
    }
}
