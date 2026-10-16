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
 * 1. 禁用 [MainActivity] 的 LAUNCHER 组件 → 桌面图标消失（[PackageManager.DONT_KILL_APP]，不杀进程）；
 * 2. 仅当 [KidHomeActivity] **不是**系统当前默认桌面时才一并禁用其 HOME 组件。
 *    若它是默认桌面却强行禁用，设备会瞬间没有桌面入口 → 变砖，必须规避（见 [isDefaultHome]）。
 *
 * ## 揭示（家长入口）
 * 家长在悬浮卡上点按 → 密码验证 → [revealManagementUi] 重新点亮图标并启动 [MainActivity]，
 * 进入配置。配置结束（Activity 销毁）时由 [MainActivity] 调 [hideManagementUi] 重新隐藏，
 * 避免图标长期暴露给孩子。
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
    private val homeComponent = ComponentName(context, KidHomeActivity::class.java)

    /** 绑定完成后调用：隐藏管理控制台图标（KidHome 非默认桌面时才一并禁用，避免变砖）。 */
    fun hideManagementUi() {
        setComponentEnabled(mainComponent, enabled = false)
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
        // 还原 KidHome 备选桌面（即便之前被禁用），家长可重新选择其作为孩子桌面。
        setComponentEnabled(homeComponent, enabled = true)
        Logger.i(TAG) { "child-end management UI revealed" }
    }

    /** 当前管理控制台图标是否处于隐藏态。 */
    fun isManagementHidden(): Boolean =
        runCatching { pm.getComponentEnabledSetting(mainComponent) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_ENABLED) !=
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun setComponentEnabled(component: ComponentName, enabled: Boolean) {
        runCatching {
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            // DONT_KILL_APP：隐藏图标时当前可能正有界面在跑，绝不能杀进程。
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
    }
}
