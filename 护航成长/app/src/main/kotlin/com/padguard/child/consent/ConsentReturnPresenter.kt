package com.padguard.child.consent

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
 * 授权自动回前台引擎（"免手动返回"的核心）。
 *
 * ## 家长在系统设置页拨开关时本 App 处于后台，且开关变更**没有任何系统回调**
 * —— 既不能靠广播感知、也不能靠 onActivityResult 感知（设置页没有"完成"按钮）。
 * 因此这里用一条后台轮询守护：盯着"刚带家长去办的那一项"是否被授予，
 * 一旦检测到授予，立即把授权页拉回前台；授权页 onResume 后自动复检并跳下一项。
 * 整条链"拨一个开关就自动前进一个"，家长不必再手动按返回。
 *
 * ## 为什么用轮询而不是监听
 * AppOps（使用情况/悬浮窗/未知来源）与无障碍服务**从设计上就不对外发回调**，
 * 任何第三方 App 都收不到"开关被拨动"的事件。只能靠"周期性复检状态"这一唯一可靠手段，
 * 与 [com.padguard.child.consent.OneTimeConsentViewModel] 在 onResume 时的复检同源同思路，
 * 区别只在于：onResume 被动等家长回来，这里主动把家长"接"回来。
 *
 * ## 回前台为什么优先直接 startActivity（而不是全屏 Intent）
 * 设置页是授权页用 `ActivityResultLauncher` 拉起的、落在**本 App 自己的任务栈**里，
 * 于是"前台任务的返回栈中拥有本 App 的 Activity"这一条始终成立 —— 它正是
 * Android 10+ 后台启动限制的豁免条件之一；DO/PO 机型、以及已持悬浮窗
 * （`SYSTEM_ALERT_WINDOW`，装机流程第 ③ 步第一项就是它）时同样豁免。
 * 全屏 Intent 只作兜底：用户正在使用设备期间，系统 UI 多以悬浮通知呈现而不直接
 * Launch（AOSP《Full-screen intent limits》明示的边界），但它保证家长有一个
 * 一键回去的入口，不至于只剩物理返回键。
 *
 * ## 不会无限空转
 * 等授权与回前台各有预算上限，超时即停手：家长中途离开平板、或某项 ROM 根本不提供入口时，
 * 不占用 CPU、不耗电。超时后退回"手动按返回"的旧行为，流程不悬空。
 */
@Singleton
class ConsentReturnPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: ConsentReturnNotifier
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    /**
     * 开始盯着一组目标权限。任一项被授予即把授权页接回前台，随后由授权页自己的
     * onResume → `advance()` 调 [stop] 收尾。
     * 重复调用会先 [stop] 清掉上一条，保证同一时刻只有一组在盯。
     */
    fun watch(targets: Set<SpecialPermission>) {
        if (targets.isEmpty()) return
        startWatching(targets)
    }

    private fun startWatching(targets: Set<SpecialPermission>) {
        stop()
        watchJob = scope.launch {
            // ① 等家长在系统设置页里把开关拨开（AppOps/无障碍没有回调，只能轮询复检）
            if (!awaitGranted(targets)) return@launch
            // ② 已授予：把授权页拉回前台并接续下一项；拉不动就带着通知兜底持续重试
            bringBackConsentScreen(targets)
        }
    }

    /** 停止盯守（授权页已回前台 / 流程结束 / 切到非设置步骤时调用）。 */
    fun stop() {
        watchJob?.cancel()
        watchJob = null
    }

    /** 撤掉回前台通知（授权页已可见时调用，避免通知栏留一条过期提醒）。 */
    fun cancelNotification() = notifier.cancel()

    /** 轮询等待目标权限被授予。超时返回 false（调用方据此放弃自动回前台）。 */
    private suspend fun awaitGranted(targets: Set<SpecialPermission>): Boolean {
        var round = 0
        while (coroutineContext.isActive && round < MAX_ROUNDS) {
            if (targets.any { it.isGranted(context) }) {
                Logger.i(TAG) { "consent target granted (${targets.map { it.key }}), returning to consent screen" }
                return true
            }
            round++
            delay(POLL_INTERVAL_MS)
        }
        Logger.w(TAG) { "consent return watcher timed out after $MAX_ROUNDS rounds (manual back may be needed)" }
        return false
    }

    /**
     * 把授权页宿主拉回前台，直到它真的回到前台或超出预算。
     *
     * 每轮都先用 [AppForeground] 验证"界面是否真的回来了"再决定下一步 ——
     * 后台启动被系统静默丢弃时 `startActivity` 既不抛异常也没有任何回调，
     * 不验证就会把"以为拉回来了"当成成功，家长仍旧卡在设置页。
     */
    private suspend fun bringBackConsentScreen(targets: Set<SpecialPermission>) {
        var attempt = 0
        var notified = false
        while (coroutineContext.isActive && attempt < MAX_RETURN_ATTEMPTS) {
            if (AppForeground.isForeground) return // 授权页已可见，由它的 onResume 推进流水线
            attempt++
            if (tryDirectStart()) {
                // 给系统留出把任务栈带到前台的时间再下结论，避免误发通知
                delay(VERIFY_DELAY_MS)
                if (AppForeground.isForeground) return
            }
            // 直接启动没落地（ROM 把设置页开到独立任务栈等）：发一条可点的全屏通知兜底。
            // 只发一次——同一 ID 反复更新会让悬浮通知反复弹出，反而添乱。
            if (!notified) {
                notified = true
                notifier.postReturnToConsent(targets.firstOrNull()?.label)
            }
            delay(POLL_INTERVAL_MS)
        }
        Logger.w(TAG) { "consent screen did not return after $attempt attempts; notification stays as fallback" }
    }

    /**
     * 直接 `startActivity` 把授权页宿主带回前台。
     *
     * 落在本 App 任务栈内时命中 Android 10+ 后台启动豁免（见类注释），是最可靠的一条路；
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
        const val POLL_INTERVAL_MS = 600L

        /** 约 60 秒上限：足够等家长拨完一个开关，又不至于长期空转 */
        const val MAX_ROUNDS = 100

        /** 回前台重试预算：约 36 秒，覆盖"家长找到开关慢 + 直接启动偶发被丢弃" */
        const val MAX_RETURN_ATTEMPTS = 60

        /** 直接启动后等待界面就位的验证窗口 */
        const val VERIFY_DELAY_MS = 600L
    }
}
