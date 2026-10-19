package com.padguard.child.ui.lock

import android.content.Context
import android.os.PowerManager
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
    private val authRepository: AuthRepository,
    private val keyguardBridge: SystemKeyguardBridge
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
                } else if (desiredDetail == null) {
                    // 由未锁定 → 锁定首帧：立即渲染锁定层。即便 App 在后台，
                    // 悬浮窗也能铺上；下方 show() 内的系统级 lockNow 兜底同时触发，
                    // 实现"时间到立即锁屏"。desiredDetail 守卫确保每次锁定只触发一次。
                    show(state.primaryDetail)
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
     * 是否存在「系统级硬锁」能力：设备所有者(DO) 或设备管理员已激活，**且系统锁屏是安全锁**。
     *
     * 有它，[LockScreenActivity] 才能通过屏幕固定(Lock Task)或 [DeviceAdminBridge.lockNow]
     * 真正锁死屏幕（物理禁手势）。没有它（绝大多数 C 端平板，无法在不恢复出厂前提下成为 DO），
     * Activity 只是普通页面、会被手势滑走，后台拉起还被 ROM 拒绝；此时应用内锁定**只能**
     * 靠悬浮窗这一层"软锁"。这个判定是本次修复的分水岭：它决定了锁屏走哪条主干。
     *
     * ## 为什么必须叠加 [SystemKeyguardBridge.isKeyguardSecure]
     * `lockNow()` 压的是**系统 keyguard**，而 keyguard 的强度取决于设备有没有设置
     * PIN / 图案 / 密码：设了才需要凭证，没设就是一扇"滑动锁"——孩子上滑即解，
     * 等于什么都没锁。只判 `admin.can(LOCK_NOW)` 会把"能压锁"误当成"锁得住"，
     * 于是这类设备被错分到"硬锁主干"，从而拿不到悬浮窗地板（地板在硬锁分支里
     * 会被 [reconcile] / Activity 的 onResume 主动撤掉），最终表现为
     * 「家长锁屏平板的确熄屏了，孩子按电源键亮屏一滑就进桌面，还能操作应用」。
     *
     * 因此：**没有安全锁的设备一律视同无硬锁**，走悬浮窗地板主干。
     */
    private fun hardLockAvailable(): Boolean =
        admin.can(Capability.LOCK_NOW) && keyguardBridge.isKeyguardSecure()

    /** 屏幕当前是否点亮（息屏时不铺悬浮地板：它会带 FLAG_KEEP_SCREEN_ON，反而把屏幕唤醒） */
    private fun isScreenOn(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true

    /**
     * 锁定态下保证「屏幕上一定有一层」—— 锁定地板的唯一收口。
     *
     * ## 为什么需要它（本次缺陷的直接成因）
     * 过去地板只在"非硬锁分支"里补铺，且 Activity 一到前台就被撤掉；硬锁分支里
     * [reenforce] 只重拉 Activity。于是只要 Activity 被上滑带走，屏幕上就是光秃秃的桌面，
     * 没有任何一层兜住 —— 尤其在**无障碍服务未授予**的设备上（看门狗不存在），
     * 逃逸后 15s 内无人补救，孩子可以从容点开任意应用。
     *
     * ## 规则（与 Activity 互补，不是互斥）
     * - Activity 已在前台 → 撤掉地板（两层叠着会吃掉触摸与输入焦点，弹不出键盘）；
     * - Activity 不在前台且屏幕亮着 → 地板**必须**在位；
     * - 息屏 → 不动。此时孩子什么都看不到，且地板带 FLAG_KEEP_SCREEN_ON 会把屏幕点亮，
     *   破坏「锁屏并熄屏」这个用户认可的行为；亮屏时 Activity 会自行 onResume 回来。
     */
    private fun ensureCoverage(detail: String) {
        // 用 [LockScreenActivity.resumedNow] 而不是 [LockScreenActivity.visible]：
        // 后者到 onStop 才落，孩子上滑逃逸的判定窗口正好落在那几百毫秒里，
        // 用它会误判"锁定页还在前面"，从而撤掉本该铺上的地板。
        if (LockScreenActivity.resumedNow) {
            overlay.dismiss()
            return
        }
        if (!isScreenOn()) return
        if (overlay.canDrawOverlay()) {
            if (!overlay.isShowing()) {
                Logger.i(TAG) { "lock screen floor re-armed (activity not in foreground)" }
            }
            overlay.show(detail)
        } else {
            reportUnprotected(detail)
        }
    }

    fun show(detail: String) {
        desiredDetail = detail
        // 新锁会话使一切"待撤锁"意图失效：设备又被锁上了（可能是家长解锁后策略
        // 立刻重锁，如时段锁仍生效）。不清掉的话，自愈触发点会在锁定期内
        // 反复撤我们刚压下的系统锁，把硬锁态撕开一道缝。
        keyguardBridge.clearUnlockPending()
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
            // 非 DO 设备（无 LOCK_TASK 屏幕固定能力）下，悬浮窗是锁屏被划走后的唯一兜底地板，
            // 即便锁屏页此刻在前台也绝不能撤掉；只有 DO 的屏幕固定真正锁死手势时才冗余可撤。
            if (admin.can(Capability.LOCK_TASK)) overlay.dismiss()
            return
        }
        attemptOnce(detail)
        // 进入锁定态首帧立即唤起系统锁屏（keyguard）：即便 PadGuard 品牌锁屏 Activity 因后台限制
        // 未即时到前台，系统 keyguard 也已压下，孩子无法绕过——这是"时间到立即锁屏"的硬兜底。
        // 仅设备管理员 / 设备所有者可用，且受 systemLockPushed 守卫避免反复灭屏。
        if (admin.can(Capability.LOCK_NOW) && !systemLockPushed) pushSystemLock()
        startEscalation()
    }

    /**
     * 收起锁屏呈现（家长解锁 / 自动解锁 / 状态翻转到未锁定）。
     *
     * 真正的收尾分流在 [LockScreenActivity.dismiss]：若系统 keyguard 是我们压下去的
     * （"锁屏并熄屏"的那一侧），必须先撤锁再退出页面，否则孩子端表现为
     * "家长解锁了却毫无反应"——屏幕照旧熄着、系统锁屏照旧锁着。本方法只负责
     * 清调度状态与地板/通知，把"撤系统锁"这半边交给页面自己完成。
     */
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
        // 经地板收口而不是无条件 addView：已在前台的锁定页之上再铺一层会吃掉触摸与
        // 输入焦点（表现为"看得见却点不动、弹不出键盘"）；息屏时则不该用带
        // FLAG_KEEP_SCREEN_ON 的浮层把屏幕点亮。
        ensureCoverage(detail)
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
        // 权威状态优先于内存态：进程重启后 desiredDetail 为空，但锁定态已持久化
        // （LockController 的 REMOTE 态落盘）。若这里按旧写法直接 return，
        // 重启后到下一次指令到达之间锁定态就"有名无实"——界面上什么都没有。
        val state = lockController.state.value
        if (!state.locked) return
        val detail = desiredDetail ?: state.primaryDetail

        // 地板收口（不分硬锁与否）：Activity 不在前台就必须有一层盖住屏幕。
        // 这是本次缺陷的正面修复 —— 旧实现只在非硬锁分支补铺，硬锁分支漏了，
        // 于是"设备管理员已激活但未设系统密码"的伪硬锁设备滑走后无任何兜底。
        ensureCoverage(detail)

        if (LockScreenActivity.visible) return
        // Activity 兜底：前台时即刻可见；后台由无障碍看门狗借特权重新顶回前台
        // （无障碍服务可后台启动 Activity，绕过 ROM 限制）。绝不作为唯一手段。
        LockScreenActivity.show(context, detail)
        // 升级链只在真硬锁设备上有意义：全屏通知与 lockNow() 能借系统之力封手势；
        // 非硬锁设备上它们只会被 ROM 静默丢弃并刷日志，不重复自讨没趣。
        if (hardLockAvailable()) startEscalation()
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
            // 仅 DO（具备 LOCK_TASK 屏幕固定能力）时可撤悬浮窗：此时手势已被系统级固定封死，
            // 悬浮窗纯属冗余。非 DO 设备上它是「锁屏页被划走后」的唯一地板，撤掉即等于洞开。
            if (admin.can(Capability.LOCK_TASK)) overlay.dismiss()
            notifier.cancel()
            Logger.i(TAG) { "lock screen confirmed on foreground" }
        } else {
            // 升级链走到头仍未拉起 Activity：悬浮层是唯一可见的锁定界面，必须确保它在位
            // （旧实现只留一句日志，浮层若已被撤掉就成了"完全没锁"）。
            // 无悬浮窗权限时至少已打过系统级 lockNow()，并会在此触发失效告警。
            Logger.w(TAG) { "lock screen degraded to overlay-only coverage" }
            ensureCoverage(detail)
        }
    }

    /**
     * 系统级兜底（需设备管理员 / 设备所有者）。
     *
     * 只在升级链前几轮全部失败后才动用：`lockNow()` 会立刻灭屏并唤起系统锁屏，
     * 能借 keyguard 禁掉手势导航与最近任务，但代价是孩子会看到一次灭屏。
     * 因此它是"最后一道保险"，不是首选路径。
     *
     * 压锁必须经 [SystemKeyguardBridge] 记账：解锁时据此判断该不该由我们撤 keyguard，
     * 否则家长端解锁只清了 App 内状态，系统锁与熄屏依旧——"解锁了却毫无反应"。
     */
    private fun pushSystemLock() {
        if (!admin.can(Capability.LOCK_NOW)) {
            Logger.w(TAG) { "no device admin: system-level lock unavailable" }
            return
        }
        if (keyguardBridge.pushSystemLock()) {
            systemLockPushed = true
            Logger.i(TAG) { "system lockNow() pushed as last resort" }
        }
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
                        "note" to "锁屏覆盖层铺不开：本设备缺少悬浮窗权限（SYSTEM_ALERT_WINDOW）。" +
                            "请在装机流程中授予它；若设备尚未设置系统锁屏密码（PIN/图案/密码），" +
                            "请一并设置 —— 没有安全锁时 lockNow() 只能压出一滑就开的滑动锁，" +
                            "锁屏形同虚设（这是「锁屏后孩子上滑就能进桌面」最常见的成因）",
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
