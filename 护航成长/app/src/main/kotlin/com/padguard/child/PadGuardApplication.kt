package com.padguard.child

import android.app.Application
import android.os.Build
import com.padguard.child.hidden.AppHider
import com.padguard.child.service.GuardHeartbeat
import com.padguard.child.service.GuardService
import com.padguard.child.time.TimeOffsetPrefs
import com.padguard.child.time.TrustedTimePrefs
import com.padguard.core.common.TimeProvider
import com.padguard.core.engine.enforcer.setInstalledPackagesProvider
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

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

    @Inject lateinit var timeProvider: TimeProvider

    @Inject lateinit var appHider: AppHider

    override fun onCreate() {
        super.onCreate()
        AppForeground.install(this)
        // 「隐藏图标」已从 MainActivity 迁到 activity-alias 上（见 AppHider 类注释）。
        // 旧版本把 MainActivity 直接置 disabled，该状态跨升级保留；不纠正会出现
        // "桌面图标在、点了没反应"（目标组件被禁用）。放在最前面，保证任何入口都能用。
        appHider.migrateToAliasHiding()
        installAppCategoryProvider()
        seedClockOffset()
        restoreTrustedBaseline()
        ensureGuardAfterProcessRestart()
        // 注意：这里**不再**挂暖令牌监听（历史遗留已移除）。
        // 此前"每次 0→1 回到前台"都会尝试补 MediaProjection 凭据，而孩子每次打开
        // 被管控应用时拦截页也是本应用的 Activity —— 结果"每次违规/每次切页
        // 都可能弹一次投屏授权框"，与"装机时确认一次、日常无感"的预期相悖。
        // 令牌的取得只剩：装机向导的一次性授权 + 投影类功能下发时的按需引导。
    }

    /**
     * 进程级守护自愈：只要本机仍处于绑定态，**任何组件**被系统重建（Activity /
     * Receiver / Service）都会先走 Application.onCreate，在这里补一次幂等启动。
     *
     * ## 为什么不能只靠 MainActivity
     * 孩子端处于隐藏形态时 `MainActivity` 的 `hiddenEntry` 分支会自行 `finish()`；
     * 进程被国产 ROM 回收后，系统恢复的是任务栈顶的 `LockScreenActivity`（锁屏页）。
     * 此前启动守护的入口只有 MainActivity、绑定流程与若干后台 Receiver / Job / Alarm，
     * 而后者在 ROM 清除调度通道、或后台启动前台服务受限时会集体静默失败 ——
     * 实测表现为「平板亮着『今日额度已用完』锁屏页，服务端整夜收不到任何心跳，
     * 连跨日重置都不执行」。Application 是唯一覆盖所有组件重建路径的入口。
     *
     * ## 约束
     * 1. `:daemon` 进程同样执行 Application.onCreate，必须挡掉：它按心跳陈旧与否
     *    自行决定拉起时机，这里抢跑会破坏「未绑定不启服务」的边界；
     * 2. 绑定态读 `GuardHeartbeat` 的**文件**标记：跨进程可见、无需等 DataStore 加载，
     *    不引入启动竞态；未绑定绝不拉起守护（陈旧缓存策略会锁死无归属设备）；
     * 3. `GuardService.start` 幂等且自带 runCatching，后台启动 FGS 被 ROM 拒绝也不会崩，
     *    此时仍由锁屏页（前台 Activity 上下文）再补一次，双入口互不依赖。
     */
    private fun ensureGuardAfterProcessRestart() {
        val mainProcess = packageName
        val currentProcess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            // API 26/27 没有官方取进程名 API。退化成「按主进程处理」是安全的：
            // start() 幂等，最坏是多投递一次 onStartCommand（走 REASON_PROCESS_RESTART 空分支）。
            mainProcess
        }
        if (currentProcess != mainProcess) return
        if (!GuardHeartbeat.readGuardState(this)) return
        GuardService.start(this, GuardService.REASON_PROCESS_RESTART)
    }

    /**
     * 用上一次的 offset 给时钟打底。
     *
     * Hilt 在 `super.onCreate()` 里完成 Application 的字段注入，
     * 所以这里拿到的 [timeProvider] 一定非空。
     */
    private fun seedClockOffset() {
        runCatching { timeProvider.seedOffset(TimeOffsetPrefs.load(this)) }
    }

    /**
     * 用上一次落盘的护栏快照恢复时间篡改基线。
     *
     * 必须在 [seedClockOffset] 之后、首次 [com.padguard.core.common.TimeProvider.now]
     * 被用于管控判定之前完成：否则进程重启后首帧会退化为裸墙钟，孩子改系统时间即可在
     * 冷启动窗口绕过时段锁 / 宵禁（违反"离线时间管控必须本地独立生效"红线，见 R-P0-1）。
     *
     * 恢复的不只是"上次受信时刻"，还会按落盘时的单调/墙钟读数补偿进程停机期间
     * 的真实流逝 —— 只把时刻原样设回去会让离线设备重启后时间冻结在关机那一刻，
     * 白天被宵禁误锁、日切与额度错位，且联网前不会自愈。
     *
     * 若设备从未建立过锚点（空盘 / 旧版只有单值），TimeProvider 会分别退化为
     * 裸墙钟或旧兼容路径；后者在首个本地 tick 写出三元组后自动升级。
     */
    private fun restoreTrustedBaseline() {
        runCatching { timeProvider.restoreTrustedBaseline(TrustedTimePrefs.load(this)) }
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

    /** 前台回调列表。用并发集合：更有可能出现跨线程注册 / 触发。 */
    private val listeners: MutableList<() -> Unit> = java.util.concurrent.CopyOnWriteArrayList()

    @Volatile
    private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: android.app.Activity) {
                    val before = resumedCount
                    resumedCount++
                    // 0 → 1 才是"真正回到前台"（页面互跳时不重复通知）
                    if (before <= 0) notifyForeground()
                }

                // 必须与 onActivityResumed 严格配对（不能用 onStopped）：
                // A 跳 B 的顺序是 A.onPause → B.onResume → A.onStop，
                // 用 onStop 递减会让计数在 B 已在前台时被 A 的 stop 减成 0，
                // 于是"明明在前台"被误判成后台，该直接弹窗的场景退化成通知。
                // 反过来若像早期实现那样在 onStop 里跳过 isFinishing==false 的 Activity，
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

    /**
     * 订阅"本应用从后台回到前台"这一刻。
     *
     * 用途：Android 10 起，App 在后台**禁止读取位置**（无后台位置权限时系统会直接拒绝，
     * 见 [com.padguard.child.monitor.LocationCollector]）。因此后台期间收到的定位指令
     * 无法当场执行，只能先搁置，等界面回到前台时补做。
     * 没有这个通知，后台攒下的定位请求就只能等到家长下一次下发指令才被想起。
     *
     * 只在 **0 → 1** 的边沿触发：App 内部页面互跳时计数早就大于 0，不应重复触发。
     *
     * 返回**移除句柄**，调用方必须在自身 stop()/onDestroy() 里调用它。
     * 早期只提供 add、不提供 remove，于是每次守护服务重建（息屏被 ROM 杀、网络恢复广播、
     * onTaskRemoved 重启）都会往 [listeners] 里多挂一个闭包，永不释放；
     * 跑上一天后每次回前台会并发触发几十个协程去做同一件事，纯粹的空转与内存增长。
     */
    fun addForegroundListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun notifyForeground() {
        for (l in listeners) {
            runCatching { l() }.onFailure {
                com.padguard.core.common.Logger.w("AppForeground") { "foreground listener failed: $it" }
            }
        }
    }
}
