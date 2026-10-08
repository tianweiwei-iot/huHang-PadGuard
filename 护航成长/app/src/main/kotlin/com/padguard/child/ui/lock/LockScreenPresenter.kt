package com.padguard.child.ui.lock

import android.content.Context
import com.padguard.child.AppForeground
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 锁屏呈现的统一入口。
 *
 * ## 为什么不能让调用方直接 `LockScreenActivity.show`
 * 锁屏有两条来源：策略引擎的时长/时段判定（[com.padguard.child.service.GuardService.reconcileLockScreen]）
 * 与家长下发的远程锁屏指令（EngineEffect.ShowLockScreen）。
 * 两处各自决定"弹 Activity 还是铺悬浮层"，迟早会出现一边弹了 Activity、另一边还挂着悬浮层的
 * 双重锁定（屏幕发暗、返回键要多按几次才能退出）。
 * 收敛到这里之后，判定规则只有一份。
 *
 * ## 判定规则
 * - **应用在前台** → 走 [LockScreenActivity]：体验一致，且能启动 Lock Task 固定屏幕；
 * - **应用在后台** → 走 [LockScreenOverlay]：Android 10+ 禁止后台启动 Activity，
 *   此时 startActivity 会被系统静默丢弃，只有悬浮层能真正拦住孩子；
 * - **没有悬浮窗权限** → 仍然尝试 Activity（可能被系统拦下，但这是唯一剩下的手段），
 *   并把失败明确记日志，便于排查"为什么这台设备锁不住"。
 */
@Singleton
class LockScreenPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val overlay: LockScreenOverlay
) {

    @Volatile
    private var mode: Mode = Mode.NONE

    fun show(detail: String) {
        if (mode == Mode.ACTIVITY && AppForeground.isForeground) {
            // 已经是 Activity 且仍在前台：只刷新原因，不重复 startActivity（会闪一下）
            LockScreenActivity.show(context, detail)
            return
        }
        if (mode == Mode.OVERLAY && overlay.isShowing()) {
            overlay.show(detail)
            return
        }

        if (AppForeground.isForeground) {
            overlay.dismiss()
            LockScreenActivity.show(context, detail)
            mode = Mode.ACTIVITY
            Logger.i(TAG) { "lock screen via activity (foreground)" }
        } else if (overlay.canDrawOverlay()) {
            LockScreenActivity.dismiss(context)
            overlay.show(detail)
            mode = Mode.OVERLAY
            Logger.i(TAG) { "lock screen via overlay (background)" }
        } else {
            // 无悬浮窗权限：只能试 Activity，并明确记录——这是本机锁不住时的首要排查线索
            LockScreenActivity.show(context, detail)
            mode = Mode.ACTIVITY
            Logger.w(TAG) { "lock screen via activity without overlay permission: may be blocked by system" }
        }
    }

    fun dismiss() {
        if (mode == Mode.NONE) {
            // 仍要兜底收一遍：服务重启后 mode 丢失，但界面/悬浮层可能还挂着
            LockScreenActivity.dismiss(context)
            overlay.dismiss()
            return
        }
        LockScreenActivity.dismiss(context)
        overlay.dismiss()
        mode = Mode.NONE
    }

    private enum class Mode { NONE, ACTIVITY, OVERLAY }

    private companion object {
        const val TAG = "LockScreenPresenter"
    }
}
