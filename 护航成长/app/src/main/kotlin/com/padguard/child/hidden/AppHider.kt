package com.padguard.child.hidden

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.padguard.child.KidHomeActivity
import com.padguard.child.MainActivity
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 孩子端「隐藏 / 揭示」开关。
 *
 * ## 为什么需要它
 * 绑定完成后，孩子端应当**对正常使用场景隐形**——应用图标从桌面/抽屉消失，孩子无法直接打开
 * 管理控制台去篡改管控。但真正的管控引擎（GuardService 守护、锁屏、限额、无障碍拦截）仍在后台常驻，
 * 只通过一张悬浮状态卡向孩子展示「今日已用 / 剩余」这类基本信息。
 *
 * ## 隐藏做了什么
 * 1. 禁用**桌面图标别名** [LAUNCHER_ALIAS] 的 LAUNCHER 组件 → 桌面图标消失（[PackageManager.DONT_KILL_APP]）；
 * 2. 同时确保 [MainActivity] 本身处于 enabled——它是控制台真正的宿主，必须随时可被显式拉起；
 * 3. 仅当 [KidHomeActivity] **不是**系统当前默认桌面时才一并禁用其 HOME 组件。
 *    若它是默认桌面却强行禁用，设备会瞬间没有桌面入口 → 变砖，必须规避（见 [isDefaultHome]）。
 *
 * ## 为什么隐藏的是别名，不是 MainActivity（2026-11-18 事故）
 * 历史实现直接 `setComponentEnabledSetting(MainActivity, DISABLED)`。禁用「正在运行的那个
 * Activity」会让系统立刻 `finishDisabledPackageActivitiesLocked` **强杀它本身**，且连同同一
 * 任务栈里压在上面的系统设置页一起关掉。现场表现：家长在控制台点「去开启」授权电池优化 →
 * 跳到系统设置 → `onUserLeaveHint` 触发隐藏 → 控制台与设置页双双消失 = 看起来"闪退"，
 * 一项授权都授不上，返回键也回不到控制台。
 *
 * 改法：桌面图标迁移到 `activity-alias`（[LAUNCHER_ALIAS] → [MainActivity]）。隐藏只禁用别名，
 * 图标照样消失，但运行中的 MainActivity 与任务栈毫发无损，家长从系统设置页返回即可继续授权。
 *
 * ## 揭示（家长入口，两条互备通道）
 * 1. **设备在手边**：家长在悬浮卡上点按 → 密码验证 → [revealManagementUi] 重新点亮图标并启动
 *    [MainActivity]，进入配置。配置结束（Activity 销毁）时由 [MainActivity] 调 [hideManagementUi]
 *    重新隐藏，避免图标长期暴露给孩子。
 * 2. **设备不在手边 / 密码尚未下发**：家长端下发 `REVEAL_MANAGEMENT_UI` 远程指令，
 *    GuardService 收到后同样点亮图标并拉起管理台（免密码，指令通道已由服务端家长身份鉴权）。
 *
 * ## 状态是持久的
 * 组件开关写在系统 PMS 里，重启/覆盖安装后依旧生效；开机自启的 GuardService 会再次确认隐藏态，
 * 因此「隐藏」不会因为重启而失效。
 */
@Singleton
class AppHider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val pm: PackageManager get() = context.packageManager

    private val mainComponent = ComponentName(context, MainActivity::class.java)

    /**
     * 桌面图标别名。用 `context.packageName` 拼而不是写死 `com.padguard.child`：
     * debug 包名带 `.debug` 后缀（见 applicationIdSuffix），写死会让 debug 包找不到该别名，
     * 隐藏/揭示全静默失效（runCatching 吞掉 NameNotFoundException）。
     */
    private val launcherComponent = ComponentName(context.packageName, LAUNCHER_ALIAS)

    private val homeComponent = ComponentName(context, KidHomeActivity::class.java)

    /**
     * 隐藏管理控制台图标（绑定完成后 / 家长配置结束退出时调用）。
     *
     * ## 为什么不再要求「已设密码才允许隐藏」
     * 旧实现担心：揭示的唯一入口是悬浮卡密码闸，未设密码时该闸放行失败，
     * 隐藏后家长会被锁在门外（hidden-but-unrecoverable）。现在揭示有了第二条
     * 与密码无关的通道——家长端 `REVEAL_MANAGEMENT_UI` 远程指令（见类注释），
     * 只要家长端可达就永远能把图标要回来，死局不复存在。
     *
     * 彻底隐藏换来的收益是硬的：桌面没有图标，孩子连「长按 → 卸载」的入口都摸不到，
     * 这正是卸载防护事故（长按即卸）后堵死入口的关键一步。代价是家长必须记住
     * 悬浮卡入口或远程指令入口，两者文档均已写明。
     */
    fun hideManagementUi() {
        // 先确保宿主 Activity 自身 enabled：旧版本是把 MainActivity 直接置 disabled 的，
        // 覆盖安装后该状态会持久留在 PMS 里。不纠正的话，显式 startActivity(MainActivity)
        // 会抛 ActivityNotFoundException（被 runCatching 吞掉，表现为"点揭示毫无反应"）。
        // 同时它也保证"隐藏"永远只影响图标，不影响控制台本体。
        setComponentEnabled(mainComponent, enabled = true)
        setComponentEnabled(launcherComponent, enabled = false)
        if (!isDefaultHome(homeComponent)) {
            setComponentEnabled(homeComponent, enabled = false)
        } else {
            Logger.w(TAG) { "KidHome is the default home, kept enabled to avoid bricking the device" }
        }
        Logger.i(TAG) { "child-end management UI hidden" }
    }

    /** 家长密码验证后调用：恢复管理控制台图标，供家长进入配置。 */
    fun revealManagementUi() {
        setComponentEnabled(mainComponent, enabled = true)
        setComponentEnabled(launcherComponent, enabled = true)
        // 还原 KidHome 备选桌面（即便之前被禁用），家长可重新选择其作为孩子桌面。
        setComponentEnabled(homeComponent, enabled = true)
        Logger.i(TAG) { "child-end management UI revealed" }
    }

    /**
     * 当前管理控制台图标是否处于隐藏态。
     *
     * 只认「显式禁用」两个状态：组件从未被写过时 PMS 返回 COMPONENT_ENABLED_STATE_DEFAULT，
     * 它表示**按清单声明**（别名 enabled=true），必须算作"可见"。旧写法拿 `!= ENABLED` 判定，
     * 会把刚装好、还没走过一次隐藏的设备误判成"已隐藏"。
     */
    fun isManagementHidden(): Boolean {
        val state = runCatching { pm.getComponentEnabledSetting(launcherComponent) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    }

    /**
     * 一次性迁移：把「隐藏」从 [MainActivity] 迁到桌面图标别名上。
     *
     * 旧版本把 MainActivity 组件本身置为 DISABLED，该状态存在系统 PMS 里、**跨版本升级保留**。
     * 升级到本版本后若不纠正：桌面图标（别名）能被解析，但它指向的目标 MainActivity 仍是
     * disabled → 点击图标直接 `ActivityNotFoundException`，表现为"图标在，点了没反应"。
     * 这里只做「重新点亮宿主」这一件最小的事，不动别名状态，因此不会影响当前的隐藏/揭示语义。
     */
    fun migrateToAliasHiding() {
        val state = runCatching { pm.getComponentEnabledSetting(mainComponent) }.getOrNull()
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        ) {
            setComponentEnabled(mainComponent, enabled = true)
            Logger.i(TAG) { "migrated: MainActivity re-enabled, hidden state now carried by launcher alias" }
        }
    }


    /**
     * 幂等写入：状态已经是目标值就不再调 PMS。
     *
     * 不是微优化——PMS 每次真正改变组件状态都会顺带
     * `clearPackagePersistentPreferredActivities(本包)`（见 2026-11-18 真机日志），
     * 而本包可能正是系统默认桌面（KidHomeActivity）。绑定/揭示每次重复写一遍，
     * 等于反复给"默认桌面"的选择做一次清理，属于自找的抖动源。
     */
    private fun setComponentEnabled(component: ComponentName, enabled: Boolean) {
        runCatching {
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (pm.getComponentEnabledSetting(component) == state) return@runCatching
            // DONT_KILL_APP：切换图标时当前可能正有界面在跑，绝不能杀进程。
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }.onFailure { Logger.e(TAG, it) { "setComponentEnabled $component=$enabled failed" } }
    }

    /**
     * 判断目标组件是否为系统当前默认桌面。
     *
     * 历史实现依赖 [PackageManager.getHomeActivities]，该方法在 API 33 已移除。
     * 这里改用 [PackageManager.resolveActivity] 直接取「当前会处理 HOME 意图的默认桌面组件」：
     * 返回 null（无默认、每次询问）或返回组件与 [target] 不匹配，均视为非默认，可安全禁用 KidHome；
     * 仅在确实命中时才保留，杜绝变砖。
     */
    private fun isDefaultHome(target: ComponentName): Boolean =
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolve = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            resolve?.activityInfo?.packageName == target.packageName &&
                resolve?.activityInfo?.name == target.className
        }.getOrDefault(false)

    companion object {
        private const val TAG = "AppHider"

        /**
         * 桌面图标别名，必须与 AndroidManifest 里 `<activity-alias android:name=".hidden.LauncherAlias">`
         * 完全一致。它只承载 LAUNCHER 类目，转发给 [MainActivity]。
         */
        const val LAUNCHER_ALIAS = "com.padguard.child.hidden.LauncherAlias"

        /**
         * 控制台任务栈的「底座」可能是这两个类名之一：
         * - 从桌面图标进入 → AMS 记录的是别名解析后的目标（[MainActivity]）；
         * - 某些版本/机型会保留 alias 自身的类名。
         * 判断「这条任务是不是控制台」时必须两者都认，否则 [com.padguard.child.consent.ConsentReturnPresenter]
         * 会把控制台任务认不出来，授权完回不到控制台（表现为"授完权停在设置页"）。
         */
        fun isConsoleTaskHost(className: String?): Boolean =
            className == MainActivity::class.java.name || className == LAUNCHER_ALIAS
    }
}
