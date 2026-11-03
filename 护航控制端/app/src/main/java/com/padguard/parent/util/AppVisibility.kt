package com.padguard.parent.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级的"App 是否在前台"信号。
 *
 * ## 为什么需要它
 * 家长端有多条后台轮询（设备列表 10s、告警 15s、解锁申请 10s、延时 10s、应用列表 15s）。
 * 这些循环原先只判"有没有 token"，不判"用户是否还在看" —— App 切到后台、锁屏、
 * 甚至被划掉后进程短暂存活期间，它们仍在按原频率打服务端：
 * - 一个家长账号一天挂后台 10 小时 ≈ 3600 次设备列表 + 2400 次告警请求，全是空转；
 * - 服务端看到的是"在线用户持续高频请求"，限流阈值与容量规划都被这层噪声污染；
 * - 手机侧则是实打实的流量与唤醒耗电，属于用户可感知的副作用。
 *
 * ## 为什么不用 ProcessLifecycleOwner
 * 那需要额外引入 `lifecycle-process`。这里只要一个布尔量，用
 * [Application.registerActivityLifecycleCallbacks] 数"已 start 的 Activity 个数"即可，
 * 不引依赖、行为可预期（同一进程多个 Activity 也正确）。
 *
 * ## 语义约定
 * - `false` **只表示"没有可见界面"**，不表示"进程已死"；WS 长连接等推送通道不要据此关闭，
 *   否则家长在后台就收不到告警推送（那是另一个产品要求）。
 * - 恢复前台时状态立即变 `true`，轮询循环用 `collectLatest` 订阅即可"回到前台立刻刷一次"。
 */
object AppVisibility {

    private val _isForeground = MutableStateFlow(false)

    /** 前台状态流：true = 至少有一个 Activity 处于 started 状态 */
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    /** 已 started 的 Activity 计数。多 Activity 场景下不能只用 boolean 翻转 */
    private var startedCount = 0

    /** 在 Application.onCreate 中调用一次 */
    fun attach(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedCount++
                if (startedCount == 1) _isForeground.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                // 某些 ROM 在异常路径下可能重复回调，用 coerceAtLeast 防计数变负后被永久判为前台
                startedCount = (startedCount - 1).coerceAtLeast(0)
                if (startedCount == 0) _isForeground.value = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
