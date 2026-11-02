package com.padguard.child.consent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.padguard.child.AppForeground
import com.padguard.child.MainActivity
import com.padguard.child.permission.SpecialPermission
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 授权自动回前台引擎（"拨完开关自动跳到下一项"的核心）。
 *
 * ## 它解决的到底是什么
 * 家长在系统设置页拨完「悬浮窗 / 使用情况访问 / 无障碍 / 未知来源」的开关后，
 * **系统不会回传任何东西**：没有 result 回调、没有广播、没有"完成"按钮。
 * App 此刻在后台，页面也不会自己回来。若没有本引擎，家长必须自己按返回键，
 * 而人的本能是"开完就等着"，于是流程就停在那儿 —— 这正是用户反馈的
 * "点了开关没自动跳到下一项授权"。
 *
 * 因此这里由后台盯守：轮询复检目标是否被授予 → 授予即把授权页接回前台 →
 * 授权页 `onResume` 复检并自动拉起下一项。
 *
 * ## 三个关键工程决策（都来自真机失败）
 *
 * ### ① 接回前台不能只靠 `startActivity`
 * Android 10+ 的后台启动限制下，`startActivity` 可能被系统**静默丢弃**：
 * 不抛异常、不回调，只看返回值会误判成功。因此按可靠性依次尝试三条路：
 * 1. `ActivityManager.appTasks[].moveToFront()`（仅需 REORDER_TASKS 普通权限，
 *    对自家任务最稳，不受"后台启动 Activity"限制）；
 * 2. `startActivity(MainActivity)` + `NEW_TASK|CLEAR_TOP`（设置页落在本任务栈内时成立）；
 * 3. 通知兜底（见 [ConsentReturnNotifier]）——前两条都被吞时，至少留一个一键入口。
 * 每一轮都用 [AppForeground] 验证"界面是否真的回来了"，不做"以为回来了"的判断。
 *
 * ### ② 等待窗口必须够长，且先快后慢
 * 旧实现固定 600ms × 100 轮 = 60 秒封顶。家长第一次装机要在一个陌生的设置页里
 * 找开关、还要看清文字，超过 60 秒非常常见；一超时就静默放弃，表现就是
 * "我明明开了，它就是不动"。现在改成：前 30 秒 500ms 快速轮询（家长就在手边），
 * 之后 1.2 秒慢速轮询，总预算 5 分钟；仍超时才发通知兜底，绝不无声无息。
 *
 * ### ③ 启动前不猜、启动后必验
 * 被系统丢弃的启动"既不抛异常也不回调"，所以宁可多验证一次，
 * 也不能把"以为拉回来了"当成成功——那会让家长永远停在设置页且没有任何提示。
 */
@Singleton
class ConsentReturnPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: ConsentReturnNotifier
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    /**
     * 开始盯守一个「已完成」判定；家长把对应授权办妥后，
     * 立即把授权页接回前台并自动接续下一项（见 [bringBackConsentScreen]）。
     *
     * 判定用 lambda 而不是权限枚举：设备管理员（[com.padguard.core.engine.admin.DeviceAdminBridge.isAdminActive]）、
     * 屏幕采集令牌（[com.padguard.child.capture.ScreenCaptureSession.hasToken]）这类
     * "非 SpecialPermission"的离屏授权也能复用同一套引擎。
     *
     * @param completion 判定目标授权是否已办妥（在后台线程周期性调用，须轻量、可重复）
     * @param label 刚办妥的授权名，用于兜底通知文案（如"设备管理员"）
     */
    fun watch(completion: () -> Boolean, label: String) {
        startWatching(completion, label)
    }

    /**
     * 兼容旧调用：盯一组系统设置类权限（使用情况 / 悬浮窗 / 无障碍 / 未知来源）。
     * 转译为完成判定后复用同一引擎。
     */
    fun watch(targets: Set<SpecialPermission>) {
        if (targets.isEmpty()) return
        watch({ targets.any { it.isGranted(context) } }, targets.firstOrNull()?.label ?: "授权")
    }

    private fun startWatching(completion: () -> Boolean, label: String) {
        stop()
        watchJob = scope.launch {
            // ① 等家长在系统页里把授权办妥（AppOps/无障碍/管理员激活都没有回调，只能轮询复检）
            if (!awaitGranted(completion, label)) {
                // 等太久仍未开：不作静默放弃，留一条可点的通知作为入口，同时撤掉后台轮询。
                // 界面已经在前台就不发（家长就在本页，再弹通知只会添乱）。
                Logger.w(TAG) { "consent target not granted in ${TOTAL_WAIT_MS / 1000}s, stop watching ($label)" }
                if (!AppForeground.isForeground) notifier.postReturnToConsent(label)
                return@launch
            }
            // ② 已办妥：把授权页接回前台并接续下一项；拉不动就带着通知兜底持续重试
            bringBackConsentScreen(label)
        }
    }

    /** 停止盯守（授权页已回前台 / 流程结束 / 切到非设置步骤时调用）。 */
    fun stop() {
        watchJob?.cancel()
        watchJob = null
    }

    /** 撤掉回前台通知（授权页已可见时调用，避免通知栏留一条过期提醒）。 */
    fun cancelNotification() = notifier.cancel()

    /**
     * 轮询等待目标授权办妥。先快后慢，总预算 [TOTAL_WAIT_MS]。
     *
     * 前 [FAST_WINDOW_MS] 用 [FAST_INTERVAL_MS]：家长此刻正停在那个开关上，
     * 拨完一秒内就该接回，迟滞感最明显；之后降到 [SLOW_INTERVAL_MS]：
     * 家长可能正在设置里找入口，长窗口才不会"人还没找到就放弃"。
     */
    private suspend fun awaitGranted(completion: () -> Boolean, label: String): Boolean {
        var elapsed = 0L
        while (coroutineContext.isActive && elapsed < TOTAL_WAIT_MS) {
            val granted = runCatching(completion).getOrDefault(false)
            if (granted) {
                Logger.i(TAG) { "consent target granted after ${elapsed}ms: $label" }
                return true
            }
            val interval = if (elapsed < FAST_WINDOW_MS) FAST_INTERVAL_MS else SLOW_INTERVAL_MS
            delay(interval)
            elapsed += interval
        }
        return false
    }

    /**
     * 把授权页宿主拉回前台。**职责只有一件事：把它拉回来一次，然后交出控制权。**
     *
     * ## 为什么"拉回来一次"就够，而且必须只拉一次
     * 授权页一旦 resumed，它自己的 onResume 会复检并**立刻拉起下一项**设置页。
     * 如果这里继续循环检查"现在是不是还在前台"，就会看到"刚跳去下一项 → 又变成后台"，
     * 误判成"没拉回来"，于是再拉一次 —— 家长刚打开的下一项设置页被当场弹掉，
     * 看上去就是"跳过去了又被弹回来"。因此判定标准是"曾经回到前台"，
     * 命中即收工（协程随 watchJob 结束）。
     */
    private suspend fun bringBackConsentScreen(label: String) {
        var notified = false
        var elapsed = 0L
        var attempt = 0
        while (coroutineContext.isActive && elapsed < RETURN_BUDGET_MS) {
            if (AppForeground.isForeground) return // 已在前台，交给它的 onResume 推进

            attempt++
            // 策略一：把自家任务整体提到前台（对后台启动限制免疫，优先尝试）
            if (moveOwnTaskToFront() && awaitForeground()) {
                Logger.i(TAG) { "consent screen returned via task-move (#$attempt, $label)" }
                return
            }

            // 策略二：直接启动授权页宿主（设置页落在本任务栈内时成立）
            if (tryDirectStart() && awaitForeground()) {
                Logger.i(TAG) { "consent screen returned via activity-start (#$attempt, $label)" }
                return
            }

            // 策略三：发一条可点的通知兜底。只发一次——同一 ID 反复更新会让通知反复弹出，反而添乱
            if (!notified) {
                notified = notifier.postReturnToConsent(label)
            }
            delay(ATTEMPT_INTERVAL_MS)
            elapsed += FOREGROUND_WINDOW_MS * 2 + ATTEMPT_INTERVAL_MS
        }
        Logger.w(TAG) {
            "consent screen did not return after $attempt attempts ($label); " +
                "notification stays as fallback (foreground=${AppForeground.isForeground})"
        }
    }

    /**
     * 等待"界面真的出现在前台"。
     *
     * 用窗口内轮询而不是单次 `delay` 后判断：任务切换的耗时在 200ms~1s 之间波动，
     * 固定等待要么误判失败（多拉一次，把刚打开的下一页弹掉），要么拖慢接续。
     */
    private suspend fun awaitForeground(): Boolean {
        var waited = 0L
        while (waited <= FOREGROUND_WINDOW_MS) {
            if (AppForeground.isForeground) return true
            delay(FOREGROUND_POLL_MS)
            waited += FOREGROUND_POLL_MS
        }
        return false
    }


    /**
     * 把本应用的任务栈整体提到前台。
     *
     * 比 `startActivity` 更可靠：`moveToFront` 只是调整任务栈顺序，不新建 Activity，
     * 因此不受 Android 10+「后台启动 Activity」的限制（只需 REORDER_TASKS 普通权限）。
     * 设置页若被 ROM 开到了**独立任务栈**（此时直接 startActivity 会被静默丢弃），
     * 这一条就是唯一能自动回去的路。
     */
    private fun moveOwnTaskToFront(): Boolean = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        // appTasks 只返回本应用自己的任务，无需任何特殊权限
        val tasks = am.appTasks.orEmpty()
        // 优先挑"根 Activity 是授权页宿主"的那个任务（孩子桌面等其它任务不参与）
        val target = tasks.firstOrNull {
            it.taskInfo.baseActivity?.className == MainActivity::class.java.name
        } ?: tasks.firstOrNull()
        if (target == null) {
            Logger.w(TAG) { "no own task found, cannot moveToFront" }
            return false
        }
        target.moveToFront()
        true
    }.onFailure { Logger.w(TAG) { "moveOwnTaskToFront failed: ${it.message}" } }
        .getOrDefault(false)

    /**
     * 直接 `startActivity` 把授权页宿主带回前台。
     *
     * 设置页用 `ActivityResultLauncher` 拉起、落在本 App 自己的任务栈里，
     * 于是"前台任务的返回栈中拥有本 App 的 Activity"这一豁免条件成立；
     * 被系统静默丢弃时返回 true 但界面不会出现，由 [bringBackConsentScreen] 的
     * [AppForeground] 验证兜底。
     */
    private fun tryDirectStart(): Boolean = runCatching {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        )
    }.onFailure { Logger.w(TAG) { "direct return-to-consent failed: ${it.message}" } }
        .isSuccess

    private companion object {
        const val TAG = "ConsentReturnPresenter"

        /** 快速轮询间隔：家长此刻就在开关旁边 */
        const val FAST_INTERVAL_MS = 500L

        /** 慢速轮询间隔：家长可能还在设置里找入口 */
        const val SLOW_INTERVAL_MS = 1_200L

        /** 快速轮询窗口 */
        const val FAST_WINDOW_MS = 30_000L

        /**
         * 总等待预算 5 分钟。
         * 旧实现 60 秒封顶，家长稍慢一点就"静默放弃"，是最直接的投诉来源。
         * 5 分钟内只做一次轻量状态查询（0.5~1.2 秒一次），代价可以忽略。
         */
        const val TOTAL_WAIT_MS = 5 * 60 * 1000L

        /** 接回前台的预算：覆盖"直接启动偶发被丢弃 + 系统把任务切回需要时间" */
        const val RETURN_BUDGET_MS = 60_000L

        /** 单次尝试后的间隔 */
        const val ATTEMPT_INTERVAL_MS = 600L

        /** 单次启动后等待"界面出现"的窗口 */
        const val FOREGROUND_WINDOW_MS = 1_000L

        /** 窗口内的检测粒度 */
        const val FOREGROUND_POLL_MS = 100L
    }
}
