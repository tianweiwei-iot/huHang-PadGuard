package com.padguard.child.consent

import android.content.Context
import android.content.Intent
import com.padguard.child.MainActivity
import com.padguard.child.permission.SpecialPermission
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * 一旦检测到授予，立即通过 [ConsentReturnNotifier] 把授权页拉回前台；
 * 授权页 onResume 后自动复检并跳下一项。整条链"拨一个开关就自动前进一个"，
 * 家长不必再手动按返回。
 *
 * ## 为什么用轮询而不是监听
 * AppOps（使用情况/悬浮窗/未知来源）与无障碍服务**从设计上就不对外发回调**，
 * 任何第三方 App 都收不到"开关被拨动"的事件。只能靠"周期性复检状态"这一唯一可靠手段，
 * 与 [com.padguard.child.consent.OneTimeConsentViewModel] 在 onResume 时的复检同源同思路，
 * 区别只在于：onResume 被动等家长回来，这里主动把家长"接"回来。
 *
 * ## 不会无限空转
 * 轮询上限 [MAX_ROUNDS] 后自动停手：家长中途离开平板、或某项 ROM 根本不提供入口时，
 * 不占用 CPU、不耗电。超时后退回"手动按返回"的旧行为，流程不悬空。
 */
@Singleton
class ConsentReturnPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: ConsentReturnNotifier
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var watchJob: Job? = null

    /**
     * 开始盯着一组目标权限。任一项被授予即触发一次回前台，随后自动停（不重复弹）。
     * 重复调用会先 [stop] 清掉上一条，保证同一时刻只有一组在盯。
     */
    fun watch(targets: Set<SpecialPermission>) {
        if (targets.isEmpty()) return
        stop()
        watchJob = scope.launch {
            var round = 0
            while (isActive && round < MAX_ROUNDS) {
                if (targets.any { it.isGranted(context) }) {
                    Logger.i(TAG) { "consent target granted (${targets.map { it.key }}), returning to consent screen" }
                    returnToConsent()
                    break
                }
                round++
                delay(POLL_INTERVAL_MS)
            }
            if (round >= MAX_ROUNDS) {
                Logger.w(TAG) { "consent return watcher timed out after $MAX_ROUNDS rounds (manual back may be needed)" }
            }
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
     * 把授权页宿主拉回前台。
     *
     * 优先走全屏 Intent（最稳，连"悬浮窗"这一项本身也能覆盖）；
     * 不可用时退化为直接 `startActivity` —— 此时若 App 已持有 SYSTEM_ALERT_WINDOW
     * （悬浮窗之后的各项），后台启动豁免同样生效，仍能自动返回；
     * 两者都不行（极罕见：FSI 被撤销且悬浮窗尚未授予）则退回手动返回，不崩。
     */
    private fun returnToConsent() {
        val posted = notifier.postReturnToConsent()
        if (!posted) {
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }
                )
            }.onFailure { Logger.w(TAG) { "direct return-to-consent failed (background launch blocked)" } }
        }
    }

    private companion object {
        const val TAG = "ConsentReturnPresenter"
        const val POLL_INTERVAL_MS = 600L

        /** 约 60 秒上限：足够等家长拨完一个开关，又不至于长期空转 */
        const val MAX_ROUNDS = 100
    }
}
