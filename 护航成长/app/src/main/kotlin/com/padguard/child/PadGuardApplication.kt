package com.padguard.child

import android.app.Application
import com.padguard.core.engine.enforcer.setInstalledPackagesProvider
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口。
 *
 * 这里额外注册了一个前后台观察器（[AppForeground]）：
 * Android 10+ 禁止后台启动 Activity，屏幕采集授权页能否直接弹出，
 * 取决于"此刻有没有本应用的界面在前台"。没有这个判断就只能一律退化成通知引导，
 * 而孩子正在用平板时，明明可以直接弹框却还要他去通知栏点一次，体验差得多。
 */
@HiltAndroidApp
class PadGuardApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppForeground.install(this)
        installAppCategoryProvider()
    }

    /**
     * 给内容分类管控注入"已安装应用清单"。
     *
     * 未成年人模式要按龄档禁掉游戏 / 短视频 / 直播 / 陌生人社交，
     * 规则落到设备上最终必须是**包名**。分类目录只收录了常见应用，
     * 孩子新装的一个冷门游戏不在目录里，就得靠这里实时查到的清单补上。
     *
     * 每次调用现查而不是启动时缓存一份：孩子随时可能装新应用，
     * 缓存会让"刚装的游戏"在下次重启前一直不受管控。
     * 查询走 `MATCH_UNINSTALLED_PACKAGES` 之外的默认集合即可 ——
     * 已卸载的应用本来也不需要管控。
     */
    private fun installAppCategoryProvider() {
        setInstalledPackagesProvider {
            runCatching {
                packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
            }.getOrDefault(emptySet())
        }
    }
}

/**
 * 极简前后台标记。
 *
 * 只关心"当前有没有本应用的 Activity 处于 resumed"，不追踪具体是哪个页面 ——
 * 调用方（[com.padguard.child.capture.CaptureConsentPrompter]）只需要这个二值判断。
 *
 * 用 resumed 计数而不是单个布尔量：拦截页、锁屏页、申请页之间跳转时
 * 会出现「新页面 onResume 早于旧页面 onStop」的重叠窗口，
 * 单个布尔量会在这一瞬间被置回后台，导致本可以直接弹窗的场景退化成通知。
 */
object AppForeground {

    @Volatile
    private var resumedCount = 0

    @Volatile
    private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: android.app.Activity) {
                    resumedCount++
                }

                // 必须与 onActivityResumed 严格配对（不能用 onStopped）：
                // A 跳 B 的顺序是 A.onPause → B.onResume → A.onStop，
                // 用 onStop 递减会让计数在 B 已在前台时被 A 的 stop 减成 0，
                // 于是"明明在前台"被误判成后台，该直接弹窗的场景退化成通知。
                // 反过来若像旧实现那样在 onStop 里跳过 isFinishing==false 的 Activity，
                // 按 Home 键（Activity 不 finishing）就永远不递减 ——
                // 计数永久停在"前台"，后台启动授权页被系统静默丢弃，
                // 家长端的实时看屏从此再也连不上，且平板上毫无提示。
                override fun onActivityPaused(activity: android.app.Activity) {
                    resumedCount = (resumedCount - 1).coerceAtLeast(0)
                }

                override fun onActivityCreated(
                    activity: android.app.Activity,
                    savedInstanceState: android.os.Bundle?
                ) = Unit

                override fun onActivityStarted(activity: android.app.Activity) = Unit
                override fun onActivityStopped(activity: android.app.Activity) = Unit
                override fun onActivitySaveInstanceState(
                    activity: android.app.Activity,
                    outState: android.os.Bundle
                ) = Unit

                override fun onActivityDestroyed(activity: android.app.Activity) = Unit
            }
        )
    }

    /** 本应用是否已有界面在前台（此时允许直接启动 Activity） */
    val isForeground: Boolean get() = resumedCount > 0
}
