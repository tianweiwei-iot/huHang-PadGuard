package com.padguard.child.ui.lock

import android.content.Context
import com.padguard.core.common.Logger
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
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
 * 锁屏呈现的统一入口 —— 也是「锁屏必达」的升级引擎。
 *
 * ## 硬性目标
 * 只要管控系统安装成功，无论此刻平板停在哪个应用、哪个界面（含息屏），
 * 家长端下发锁屏指令后平板必须**立即**弹出锁定界面。
 *
 * ## 为什么过去做不到
 * 旧实现只有两条路：应用在前台时 `startActivity`，在后台时铺悬浮层；没有悬浮窗权限时
 * 仍去 `startActivity`。而 Android 10（API 29）起后台启动 Activity 会被系统**静默丢弃** ——
 * 于是"后台 + 无悬浮窗权限"这一最常见的组合下，锁屏指令等于石沉大海，
 * 家长端回执还是 SUCCESS，排查时日志里一条线索都没有。
 *
 * ## 现在的并行升级链（三条同时打，谁先生效算谁的）
 * 1. **悬浮层**：不受后台限制，是唯一"铺上去就一定看得见"的手段，作为兜底地板；
 * 2. **直接拉起**：前台或已有可见窗口时立刻生效，零延迟；
 * 3. **全屏 intent 通知**：由**系统**代拉 Activity，绕开后台启动限制（见 [LockScreenNotifier]）。
 *
 * 三条都打完仍没确认锁屏页真的到了前台，就进入**持续重试**：每隔 [RETRY_INTERVAL_MS]
 * 重新打一次（2 与 3 交替），直到 [LockScreenActivity.visible] 为真。
 * 这是必要的 —— 部分 ROM 会吃掉单次后台启动，只试一次就会被判定为"锁不住"。
 *
 * 若重试到 [LOCK_NOW_ROUND] 仍未成功，则动用设备管理员的 `lockNow()` 做系统级兜底：
 * 宁可看到系统锁屏，也绝不能出现"家长以为锁了、孩子照玩"的空洞。
 *
 * ## 为什么不用"先判断前台再选一条路"
 * 判断本身要读前台状态，而前台状态在指令到达的这一刻和真正执行的那一刻之间可能翻转；
 * 更关键的是，选路会让"某条路被 ROM 拦下"变成彻底无解。并行打 + 确认 + 重试，
 * 才能把"可能失败"收敛成"最坏也能锁住"。
 */
@Singleton
class LockScreenPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val overlay: LockScreenOverlay,
    private val notifier: LockScreenNotifier,
    private val admin: DeviceAdminBridge
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前应处于锁定态的说明文案；非空即代表"应该锁着" */
    @Volatile
    private var desiredDetail: String? = null

    @Volatile
    private var escalationJob: Job? = null

    /** 本轮是否已打过系统级 lockNow()，避免反复灭屏 */
    @Volatile
    private var systemLockPushed = false

    fun show(detail: String) {
        desiredDetail = detail
        // 已经稳稳在前台了：只刷新原因，不重复走升级链（会闪一下）
        if (LockScreenActivity.visible) {
            LockScreenActivity.show(context, detail)
            overlay.dismiss()
            return
        }
        attemptOnce(detail)
        startEscalation()
    }

    fun dismiss() {
        desiredDetail = null
        escalationJob?.cancel()
        escalationJob = null
        systemLockPushed = false
        notifier.cancel()
        LockScreenActivity.dismiss(context)
        overlay.dismiss()
    }

    /**
     * 单次完整尝试：三条通道同时打。
     *
     * 顺序上有讲究：先铺悬浮层（后台唯一可靠），再直接拉起（前台零延迟），
     * 最后发全屏通知（由系统代拉）。三者互不冲突：
     * 悬浮层是地板，Activity 一旦到前台就会被 [reconcile] 撤掉。
     */
    private fun attemptOnce(detail: String) {
        overlay.show(detail)
        LockScreenActivity.show(context, detail)
        val posted = notifier.post(detail)
        // 全屏通知不可用（Android 14 起该权限可被撤销）且悬浮层也没铺上：
        // 此刻只剩系统级锁屏这一条路，必须立刻打，不能等到第 N 轮再兜底。
        if (!posted && !overlay.isShowing()) pushSystemLock()
    }

    /**
     * 持续重试：直到锁屏页真的到了前台，或重试上限到达（避免无限空转耗电）。
     */
    private fun startEscalation() {
        if (escalationJob?.isActive == true) return
        escalationJob = scope.launch {
            var round = 0
            while (isActive && desiredDetail != null && !LockScreenActivity.visible) {
                round++
                val detail = desiredDetail ?: break
                LockScreenActivity.show(context, detail)
                // 通知不必每轮都发：它本身是常驻的，重复 notify 只会刷新时间戳
                if (round == 1 || round % NOTIFY_EVERY_ROUNDS == 0) notifier.post(detail)
                if (round == LOCK_NOW_ROUND && !systemLockPushed) pushSystemLock()
                if (round >= MAX_ROUNDS) {
                    Logger.w(TAG) {
                        "lock screen not confirmed visible after $round attempts " +
                            "(activityVisible=${LockScreenActivity.visible}, overlay=${overlay.isShowing()})"
                    }
                    break
                }
                delay(RETRY_INTERVAL_MS)
            }
            reconcile()
        }
    }

    /**
     * 锁定态自愈（由守护服务的周期自检调用）。
     *
     * 升级链最多重试 [MAX_ROUNDS] 轮就收手，避免无限空转。但某些 ROM 只在特定时机
     * （例如屏幕刚点亮、刚回到桌面）才放行后台启动 —— 一轮没赶上不代表永远拉不起来。
     * 因此这里给一个低成本的重新确认：**只有当前既没有锁定页在前台、也没有悬浮层覆盖时**
     * 才重新打一轮，正常情况下是个空操作，不会重复发通知、也不会闪屏。
     */
    fun reenforce() {
        val detail = desiredDetail ?: return
        if (LockScreenActivity.visible || overlay.isShowing()) return
        Logger.i(TAG) { "lock screen lost coverage, re-arming escalation" }
        LockScreenActivity.show(context, detail)
        startEscalation()
    }

    /**
     * 收尾对账：真正的锁定页已经在前台时，撤掉悬浮层与常驻通知，
     * 避免"两层叠着"（屏幕发暗、返回键要多按几次才退得出去）。
     */
    private fun reconcile() {
        val detail = desiredDetail
        if (detail == null) {
            // 已解锁：全部收掉
            notifier.cancel()
            LockScreenActivity.dismiss(context)
            overlay.dismiss()
            return
        }
        if (LockScreenActivity.visible) {
            overlay.dismiss()
            notifier.cancel()
            Logger.i(TAG) { "lock screen confirmed on foreground" }
        } else {
            // 升级链走到头仍未拉起 Activity：保留悬浮层作为唯一可见的锁定界面，
            // 无悬浮窗权限时至少已打过系统级 lockNow()，不会出现"完全没锁"。
            Logger.w(TAG) { "lock screen degraded to overlay-only coverage" }
        }
    }

    /**
     * 系统级兜底（需设备管理员 / 设备所有者）。
     *
     * 只在升级链前几轮全部失败后才动用：`lockNow()` 会立刻灭屏并唤起系统锁屏，
     * 能借 keyguard 禁掉手势导航与最近任务，但代价是孩子会看到一次灭屏。
     * 因此它是"最后一道保险"，不是首选路径。
     */
    private fun pushSystemLock() {
        if (!admin.can(Capability.LOCK_NOW)) {
            Logger.w(TAG) { "no device admin: system-level lock unavailable" }
            return
        }
        systemLockPushed = true
        admin.lockNow()
        Logger.i(TAG) { "system lockNow() pushed as last resort" }
    }

    private companion object {
        const val TAG = "LockScreenPresenter"

        /** 重试间隔：250ms 与锁屏页自身的抢占复位同频，只够看到切换动画 */
        const val RETRY_INTERVAL_MS = 300L

        /** 重试上限：约 6 秒。再久既拉不回来也只会空耗电量 */
        const val MAX_ROUNDS = 20

        /** 第几轮动用系统级 lockNow 兜底 */
        const val LOCK_NOW_ROUND = 6

        /** 每隔几轮补发一次全屏通知 */
        const val NOTIFY_EVERY_ROUNDS = 4
    }
}
