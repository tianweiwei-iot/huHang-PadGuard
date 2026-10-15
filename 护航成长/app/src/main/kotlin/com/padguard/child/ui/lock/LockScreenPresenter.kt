package com.padguard.child.ui.lock

import android.content.Context
import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.lock.LockController
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
 * 早期实现只有两条路：应用在前台时 `startActivity`，在后台时铺悬浮层；没有悬浮窗权限时
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
 *
 * ## 非 DO / 非设备管理员设备（绝大多数 C 端平板）的分水岭
 * 这类设备**没有系统级硬锁**：屏幕固定(Lock Task)只在 DO 白名单生效、`lockNow()` 需要
 * 设备管理员，二者皆无。于是 Activity 只是普通页面、会被手势滑走，后台拉起还被 ROM 拒绝；
 * 全屏通知在 targetSdk 34+ 又基本失效。此时应用内锁定**只能**靠悬浮窗这一层"软锁"——
 * 它盖在桌面之上、吃掉触摸，孩子滑不出去。因此对这类设备，本引擎不再把希望寄托在会被
 * ROM 拒绝的后台 Activity 上，而是**直接以悬浮窗为唯一锁定层**；只有当"既无硬锁、又无
 * 悬浮窗权限"时锁屏才真正失效，这时如实上报家长 HIGH 告警，而不是空转制造"已锁"假象。
 */
@Singleton
class LockScreenPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val overlay: LockScreenOverlay,
    private val notifier: LockScreenNotifier,
    private val admin: DeviceAdminBridge,
    private val lockController: LockController,
    private val logRepository: LogRepository,
    private val authRepository: AuthRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // 以「权威锁屏状态」为唯一真相：只要 lockController 判定未锁定，无论内存态
        // desiredDetail 是否残留、外部 dismiss() 是否曾被漏调，都强制收起浮窗与常驻通知。
        // 这从源头消除「锁屏浮窗被 reenforce() 基于陈旧内存态无限重铺为全屏常驻」的故障——
        // 那种常驻会全屏挡住所有界面并吃掉输入焦点，表现为「孩子端任何输入框都弹不出键盘」。
        scope.launch {
            lockController.state.collect { state ->
                if (!state.locked) {
                    desiredDetail = null
                    escalationJob?.cancel()
                    escalationJob = null
                    systemLockPushed = false
                    notifier.cancel()
                    LockScreenActivity.dismiss(context)
                    overlay.dismiss()
                }
            }
        }
    }

    /** 当前应处于锁定态的说明文案；非空即代表"应该锁着" */
    @Volatile
    private var desiredDetail: String? = null

    @Volatile
    private var escalationJob: Job? = null

    /** 本轮是否已打过系统级 lockNow()，避免反复灭屏 */
    @Volatile
    private var systemLockPushed = false

    /**
     * 是否存在「系统级硬锁」能力：设备所有者(DO) 或设备管理员已激活。
     *
     * 有它，[LockScreenActivity] 才能通过屏幕固定(Lock Task)或 [DeviceAdminBridge.lockNow]
     * 真正锁死屏幕（物理禁手势）。没有它（绝大多数 C 端平板，无法在不恢复出厂前提下成为 DO），
     * Activity 只是普通页面、会被手势滑走，后台拉起还被 ROM 拒绝；此时应用内锁定**只能**
     * 靠悬浮窗这一层"软锁"。这个判定是本次修复的分水岭：它决定了锁屏走哪条主干。
     */
    private fun hardLockAvailable(): Boolean = admin.can(Capability.LOCK_NOW)

    fun show(detail: String) {
        desiredDetail = detail
        if (!hardLockAvailable()) {
            // 非 DO / 非设备管理员设备：无系统级硬锁。
            // 悬浮窗是唯一可靠的"软锁"地板（盖在桌面之上、吃掉触摸，且从后台也能铺设，
            // 不受华为后台启动 Activity 限制）；锁屏 Activity 作为可见层兜底 —— 即便悬浮窗
            // 被 ROM 在后台隐藏，前台时仍能弹出锁定页，避免"彻底锁不住"的回归。
            // 二者互补：悬浮窗被隐藏时 Activity 顶上，Activity 被滑走时悬浮窗兜住。
            if (overlay.canDrawOverlay()) {
                overlay.show(detail)
            } else {
                // 连悬浮窗权限都没有：软锁完全失效，如实上报家长 HIGH 告警。
                reportUnprotected(detail)
            }
            // 锁屏 Activity 兜底：前台时即刻可见；后台由无障碍看门狗借特权重新顶回前台
            // （无障碍服务可后台启动 Activity，绕过华为后台限制）。不依赖它作为唯一手段
            // （旧版"可滑走"的根源），但绝不能彻底不弹。
            LockScreenActivity.show(context, detail)
            return
        }
        // 有硬锁能力：沿用「Activity 真锁 + 悬浮窗地板 + lockNow 兜底」原链路。
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
     * 锁定态自愈（由守护服务的周期自检、无障碍看门狗调用）。
     *
     * 升级链最多重试 [MAX_ROUNDS] 轮就收手，避免无限空转。但某些 ROM 只在特定时机
     * （例如屏幕刚点亮、刚回到桌面）才放行后台启动 —— 一轮没赶上不代表永远拉不起来。
     * 因此这里给一个低成本的重新确认。
     *
     * ## 非硬锁设备的分水岭
     * 没有系统级硬锁时，**悬浮窗在位即等于锁定成立**（它盖在桌面之上、吃掉触摸，孩子滑不出去），
     * 因此只要悬浮窗还在就立即返回，不重复去拉必被 ROM 拒绝的后台 Activity（那只会制造日志风暴）；
     * 悬浮窗缺失则补铺，仍缺失才上报"锁屏失效"告警。
     */
    fun reenforce() {
        val detail = desiredDetail ?: return
        if (!hardLockAvailable()) {
            if (overlay.canDrawOverlay() && !overlay.isShowing()) {
                Logger.i(TAG) { "lock screen (overlay-only) re-armed" }
                overlay.show(detail)
            } else if (!overlay.canDrawOverlay()) {
                reportUnprotected(detail)
            }
            // 兜底：锁屏 Activity 不在前台（后台被 ROM 隐藏 / 被滑走）时重新顶上来。
            // 无障碍上下文下可后台启动 Activity，是华为非 DO 设备把锁屏拉回前台的可靠手段。
            if (!LockScreenActivity.visible) {
                LockScreenActivity.show(context, detail)
            }
            return
        }
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

    /**
     * 上报「锁屏完全失效」告警（HIGH，家长端可见）。
     *
     * 触发条件：设备既无系统级硬锁（DO / 设备管理员），又无悬浮窗权限 —— 锁屏指令下达后
     * 孩子可以滑回桌面照常使用，管控形同虚设。这种"以为锁了其实没锁"的洞比任何漏拦都危险，
     * 必须让家长立刻知道，并引导其在装机流程中授予悬浮窗权限或激活设备管理员。
     *
     * 用 [UNPROTECTED_REPORT_INTERVAL_MS] 去抖：锁屏期间每 60s 至多报一次，避免反复刷同一条告警。
     */
    @Volatile
    private var lastUnprotectedReportedAt = 0L

    private fun reportUnprotected(lockDetail: String) {
        val now = System.currentTimeMillis()
        if (now - lastUnprotectedReportedAt < UNPROTECTED_REPORT_INTERVAL_MS) return
        lastUnprotectedReportedAt = now
        scope.launch {
            runCatching {
                logRepository.raise(
                    type = RiskType.LOCK_SCREEN_UNPROTECTED,
                    level = RiskLevel.HIGH,
                    detail = mapOf(
                        "note" to "设备无系统锁（非 DO / 设备管理员未激活）且无悬浮窗权限，" +
                            "锁屏指令无法生效，请引导孩子在装机流程中授予悬浮窗权限或激活设备管理员",
                        "lockDetail" to lockDetail
                    ),
                    deviceId = authRepository.getDeviceId()
                )
            }.onFailure { Logger.w(TAG, it) { "failed to raise lock-screen-unprotected alert" } }
        }
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

        /** 「锁屏失效」告警最小间隔：60s，避免锁屏期间反复刷同一条 HIGH 告警 */
        const val UNPROTECTED_REPORT_INTERVAL_MS = 60_000L
    }
}
