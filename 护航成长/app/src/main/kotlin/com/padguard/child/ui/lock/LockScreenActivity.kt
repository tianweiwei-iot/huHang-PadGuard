package com.padguard.child.ui.lock

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.padguard.child.service.GuardService
import com.padguard.child.ui.components.GlyphBadge
import com.padguard.child.ui.theme.PadGuardTheme
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.repository.UnlockRequestRepository
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.lock.LockController
import com.padguard.core.engine.lock.LockReason
import com.padguard.core.transport.TransportSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 锁屏覆盖层（时段锁机 / 时长耗尽锁机 / 家长远程锁屏）。
 *
 * ## 为什么必须"真锁"（历史经验）
 * 早期实现只做了两件事：拦截返回键 + 不给关闭按钮。但它仍是一个**普通 Activity**，
 * 孩子从屏幕底部上滑（手势导航）就能回到桌面 —— 锁屏界面形同虚设。
 * 返回键拦不住手势导航，这是绝大多数"假锁屏"的通病。
 *
 * 现在的做法是四层叠加，缺一层就会被绕过：
 * 1. **Lock Task（屏幕固定）**：Device Owner 已把本应用加入 `setLockTaskPackages` 白名单，
 *    `startLockTask()` 后系统直接禁用手势导航/返回/最近任务/HOME，物理上无法滑走；
 * 2. **状态栏禁用**：DO 下 `setStatusBarDisabled(true)`，屏蔽下拉通知栏与快捷设置；
 * 3. **返回键双保险**：`OnBackPressedDispatcher` + `onKeyDown` 各拦一次（部分 ROM 走 key-down 派发）；
 * 4. **抢占式复位**：`onPause` 时若仍处于锁定态，800ms 后把自己重新拉到栈顶。
 *    用于兜底"锁屏被系统回收/被其它 Activity 盖住"这类极端情况。
 *
 * 非 DO / 非设备管理员设备上，以上四层都拦不住手势导航，此时由
 * [SystemKeyguardBridge.pushSystemLock] 压一次**系统 keyguard** 借系统之力封堵
 * （灭屏 + keyguard 禁手势），这就是用户认可的「锁屏并熄屏」效果——但**仅在设备无安全锁
 * （滑动/无锁）时**：安全锁（PIN/图案/密码）设备上 [SystemKeyguardBridge] 会拒绝下压，
 * 改由应用覆盖层兜底，以规避「解锁时应用撤不掉系统锁」的永久死锁（详见其 systemLockAllowed）。
 *
 * ## 关于"解锁申请"
 * 锁死不等于不给沟通渠道：孩子可以在锁屏页点「申请解锁」，填写理由与期望时长，
 * 申请经日志队列上行成服务端工单，家长在管控端看到后可忽略或去处理（解锁 / 调整时长）。
 * 没有这个出口，孩子只会去找家长当面要，或者尝试各种绕过手段。
 *
 * ## 三种打开模式（[MODE_LOCK] / [MODE_REQUEST] / [MODE_UNLOCKING]）
 * - **MODE_LOCK**：常规锁屏页，行为与历史版本完全一致（含压系统锁、熄屏、抢占复位）；
 * - **MODE_REQUEST**：为「申请解锁」而打开。keyguard 锁定态下系统**不允许显示输入法**，
 *   因此先 [SystemKeyguardBridge.requestDismiss] 撤掉系统锁再弹对话框，对话框一关闭
 *   立刻补压系统锁，恢复硬锁态（不给孩子留"申请到一半滑回家"的窗口）；
 * - **MODE_UNLOCKING**：一次性过渡页。家长解锁后，若系统锁是我们压的，必须由我们撤掉，
 *   否则孩子端"解锁了却毫无反应"（黑屏/系统锁屏依旧）。本模式点亮屏幕、撤 keyguard、
 *   然后自行退出，把设备交还桌面。
 */
@AndroidEntryPoint
class LockScreenActivity : ComponentActivity() {

    @Inject lateinit var unlockRequestRepository: UnlockRequestRepository
    @Inject lateinit var lockController: LockController
    @Inject lateinit var admin: DeviceAdminBridge
    @Inject lateinit var timeProvider: TimeProvider
    @Inject lateinit var keyguardBridge: SystemKeyguardBridge
    @Inject lateinit var transportSettings: TransportSettings

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 一次锁屏会话内是否已经调用过系统级 `lockNow()`。
     *
     * 抢占复位会反复 onCreate/startActivity，若每次都 lockNow() 会导致
     * 屏幕反复熄灭、孩子根本看不清锁屏页，也会被 ROM 判定为异常行为。
     * 因此只在"本次锁定刚开始"时压一次系统锁，后续复位不再重复。
     */
    private var systemLockPushed = false

    /** 申请弹窗状态（Compose 层读写，Activity 只负责持有） */
    private var showRequestDialog by mutableStateOf(false)
    private var requestReason by mutableStateOf("")
    private var requestMinutes by mutableStateOf(30)
    private var requestHint by mutableStateOf<String?>(null)
    private var submitting by mutableStateOf(false)

    /**
     * 锁屏原因（可刷新）。
     *
     * 家长可能连续下发不同原因的锁屏（先"该休息了"，再"作业先做完"）。
     * 用可观察状态而不是 onCreate 里的一次性局部变量，
     * 新的原因才能通过 [onNewIntent] 显示在已经存在的锁屏页上。
     */
    private var detailState by mutableStateOf("")

    /**
     * 护眼锁屏标记：主锁定原因是否为 [LockReason.EYE_CARE]。
     * 决定锁屏页走「护眼休息」主题（薄荷绿底、护眼绿「眼」图标、大字体、护眼文案）
     * 还是常规「锁定」主题（带锁图标、字体稍增、原有文案）。
     * 一次锁屏会话内原因不变，onCreate 赋一次即可。
     */
    private var isEyeCareState by mutableStateOf(false)

    /**
     * 护眼休息数字倒计时（剩余秒数）；-1 表示不显示。
     * 仅在主锁定原因为 [LockReason.EYE_CARE] 时刷新为正值，其余锁屏原因（时段 / 远程 /
     * 总时长等）保持 -1，避免对"下次解锁遥遥无期"的锁也显示倒计时。
     */
    private var countdownSeconds by mutableStateOf(-1)

    /**
     * 安全锁（PIN/图案/密码）提示：系统锁是我们压的且当前为安全锁时，
     * 点「申请解锁」会先弹出系统凭证界面，提前告知孩子，免得以为是故障。
     */
    private var secureKeyguardHint by mutableStateOf(false)

    /** 本次打开的模式，见类注释 */
    private var mode: Int = MODE_LOCK

    // ==================== 解锁 UX 状态 ====================

    /**
     * 解锁 UX 是否已启动。
     *
     * 幂等闸门：锁定态翻转会同时触发本页的 state 收集器与
     * [LockScreenPresenter] 的收集器（后者经静态 [dismiss] 进来），
     * 两条路径都可能尝试收尾，只允许第一条真正执行。
     */
    @Volatile
    private var unlockUxRunning = false

    /**
     * 解锁 UX 启动时刻（墙钟）。
     *
     * 供 [dismiss] 判断"UX 是否卡死"：挂起等 onResume 却始终等不到时
     * （FSI 被 ROM 吞掉），超时兜底会重置闸门；此时间戳用于在早返回路径上
     * 识别"超时也没兜住"的极端情况并补拉一次，杜绝解锁指令永久空转。
     */
    @Volatile
    private var unlockUxStartedAt = 0L

    /** 解锁 UX 已启动但本页尚未 resumed（息屏 stopped / 凭证界面抢占）：挂起等 onResume */
    private var pendingUnlockUx = false

    /** 申请对话框已请求打开但本页尚未 resumed：挂起等 onResume（onNewIntent 早于 onResume 到达） */
    private var pendingRequestDialog = false

    /** 正在等待 requestDismissKeyguard 回调（凭证界面可能盖住本页，期间不得抢占/补压） */
    private var awaitingKeyguardDismiss = false

    /**
     * 是否为弹键盘临时撤掉了系统锁。
     *
     * 撤锁只是手段不是目的：对话框关闭 / 本页被切走 / 被回收时都必须补压回去，
     * 否则非 DO 设备上手势封锁失效，锁屏可被滑走。
     */
    private var keyguardDismissedForRequest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_LOCK)
        showOverLockScreen()
        // 新一轮锁屏会话：复位抢占计数，给本次锁定一个完整的重试预算。
        relaunchCount = 0
        admin.refreshControlMode()

        if (mode == MODE_UNLOCKING) {
            // 一次性过渡页：不做任何锁定态设置（不固定屏幕、不禁状态栏、不压系统锁），
            // 唯一职责是点亮屏幕 + 撤掉我们压的系统锁 + 自行退出。
            setupUnlockingPage()
            return
        }

        // 屏蔽下拉通知栏与快捷设置：否则孩子能从通知栏进设置去关管控 / 卸载我们。
        // 抢占复位会反复进 onCreate，这里做成幂等的"置一次"，不会反复调用系统 API。
        applyStatusBarDisabled(true)
        // MODE_REQUEST 是锁定进行中由悬浮层拉起的申请入口：系统锁已在位，
        // 再压一次只会多一次熄屏闪烁，仅当系统锁不在位时才补压。
        if (mode != MODE_REQUEST || !keyguardBridge.isKeyguardLocked()) {
            pushSystemLockIfNeeded()
        }
        // 护眼休息倒计时：每秒刷新一次；非 EYE_CARE 锁定时 updateCountdown 会维持 -1（不显示）
        handler.postDelayed(countdownRunnable, COUNTDOWN_INTERVAL_MS)

        // 无解锁入口：拦截系统返回键，禁止逃逸。
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // 故意不调用 super，事件在此吞掉
                }
            }
        )

        // 服务端下发的 reason 可能是字面量 "null"（历史上下发过 {"reason":"null"}），
        // 直接显示会在锁屏页正中摆一个刺眼的 null，这里连同空串一起滤掉。
        val detail = sanitizeDetail(intent?.getStringExtra(EXTRA_DETAIL))
        currentDetail = detail
        detailState = detail
        // 护眼强制休息锁屏采用专属主题；其余（远程/时段/总时长/限额）走通用带锁主题
        isEyeCareState = lockController.state.value.primaryReason == LockReason.EYE_CARE

        // 解锁即退出：不等外部调 dismiss()。
        // 屏幕固定/抢占复位都可能让外部拿不到本实例，只靠 dismiss() 会留下
        // "已经解锁了，锁屏页还挂着、平板仍不能操作"的死角。
        //
        // 但"退出"不等于"直接 finish"：系统 keyguard 是我们压下去的，解锁时必须由我们
        // 撤掉（requestDismissKeyguard），否则孩子端表现为"家长解锁了却毫无反应"——
        // 屏幕仍旧熄着、系统锁屏仍旧锁着。撤锁路径见 [beginUnlockUxIfNeeded]。
        //
        // 三态判定不能省：presenter 的收集器（经静态 dismiss）可能先一步启动 UX，
        // 此时本收集器必须认出"UX 正在进行"而不再直接 finish——否则页面会在撤锁前
        // 被抢先杀掉，系统锁依旧压着，正是本次要消灭的缺陷。
        lifecycleScope.launch {
            lockController.state.collect { state ->
                if (!state.locked) {
                    when {
                        unlockUxRunning -> Unit // UX 进行中，由它撤完锁再自行收尾
                        beginUnlockUxIfNeeded() -> Unit // UX 刚启动
                        else -> {
                            exitLockTask()
                            // 解锁必须恢复状态栏，否则家长会永久失去下拉通知栏 —— 这是严重副作用
                            applyStatusBarDisabled(false)
                            if (!isFinishing) finish()
                        }
                    }
                }
            }
        }

        setContent {
            PadGuardTheme {
                LockScreenContent(detail = detailState, isEyeCare = isEyeCareState)
                if (showRequestDialog) {
                    UnlockRequestDialog()
                }
            }
        }
    }

    /**
     * MODE_UNLOCKING 过渡页的最小装配。
     *
     * 不碰任何锁定态设置，只保证：窗口可见（撤锁调用需要 resumed 的 Activity）、
     * 屏幕点亮、状态翻转后自行退出。万一拉起时策略又重新锁上（如时段锁仍生效），
     * 1.5s 后自行收起，由 [LockScreenPresenter] 的升级链把锁屏页带回来。
     */
    private fun setupUnlockingPage() {
        lifecycleScope.launch {
            lockController.state.collect { state ->
                if (!state.locked) beginUnlockUxIfNeeded()
            }
        }
        handler.postDelayed(unlockingSelfCheck, UNLOCKING_SELF_CHECK_MS)
        setContent {
            PadGuardTheme {
                UnlockingContent()
            }
        }
    }

    /** 过渡页自检：若状态又被策略锁上，收起自己，把舞台还给锁屏升级链 */
    private val unlockingSelfCheck = object : Runnable {
        override fun run() {
            if (isFinishing) return
            if (stillLocked()) {
                Logger.i(TAG) { "unlocking page: lock re-asserted by policy, closing self" }
                finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 锁定任务模式下系统仍会显示状态栏（时间/电量），但会禁掉手势与下拉。
        // 这里再叠一层全屏，让锁屏页真正占满视觉区域，不留"看起来能滑"的错觉。
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onStart() {
        super.onStart()
        instance = this
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // 本页此刻真正占着屏幕 —— 悬浮地板必须让位（两层叠着会吃掉触摸与输入焦点）
        resumedNow = true
        // 锁定页真正到达前台 —— 升级链据此停止重试，并撤掉悬浮层与常驻通知
        visible = true
        LockScreenNotifier.cancelFrom(this)
        // 每次回到前台都重新确认一次锁定任务：系统可能在切后台时解除了固定。
        // startLockTask 幂等，重复调用无副作用（非白名单应用会抛异常，已用 runCatching 兜住）。
        if (mode != MODE_UNLOCKING) enterLockTask()
        // 已经回到前台，撤掉持续抢占，避免重复拉起造成闪屏
        handler.removeCallbacks(relaunchRunnable)
        // 本轮已确认"回到过前台"，抢占预算必须重新给一次。
        //
        // 不复位的话，息屏期间那 8 秒空转（后台启动被 ROM 静默丢弃，每次都白试）
        // 会把 [relaunchCount] 耗到上限；等孩子真正按电源键亮屏、再上滑逃逸时，
        // relaunchRunnable 第一次执行就撞上 MAX_RELAUNCH_ATTEMPTS 直接 return，
        // 锁屏页再也回不来 —— 这正是「亮屏后上滑就能进桌面」的直接成因。
        relaunchCount = 0
        // 安全锁设备上、且当前确实被系统凭据界面挡住时提示孩子先过验证。
        // 不再要求 wasPushedByUs()：我们已从不在安全锁设备上压系统锁，但设备休眠唤醒后
        // 仍可能呈现其自身的系统安全锁，此提示同样适用（如实告知，避免误以为故障）。
        secureKeyguardHint = mode != MODE_UNLOCKING &&
            keyguardBridge.isKeyguardSecure() &&
            keyguardBridge.isKeyguardLocked()
        // 息屏期间到来的解锁/申请意图，此刻才真正执行
        if (pendingUnlockUx) {
            pendingUnlockUx = false
            runUnlockUx()
        }
        if (pendingRequestDialog) {
            pendingRequestDialog = false
            openUnlockRequestDialog()
        }
        // 解锁自愈：存在"待撤的系统锁"（撤锁曾失败 / 进程重启后本页被任意原因带到前台）
        // 且 App 侧已解锁时，只要本页 resumed 就继续追 —— 这是解锁链最可靠的收尾点，
        // 不依赖指令送达时页面恰好可用。策略重锁的竞态由 show() 清标记 + stillLocked 双重防护。
        if (keyguardBridge.isUnlockPending() && !unlockUxRunning && !stillLocked()) {
            Logger.i(TAG) { "onResume: chasing pending system-keyguard unlock" }
            beginUnlockUxIfNeeded()
        }
        // 申请对话框开着，却被补压的系统锁重新盖住（孩子滑走后被抢占复位拉回来）：
        // 再撤一次锁，恢复可输入状态；撤锁失败（安全锁验证不过）就收起对话框并如实告知。
        if (showRequestDialog && !unlockUxRunning && !awaitingKeyguardDismiss &&
            keyguardBridge.isKeyguardLocked()
        ) {
            awaitingKeyguardDismiss = true
            keyguardBridge.requestDismiss(this) { ok ->
                awaitingKeyguardDismiss = false
                if (ok) {
                    keyguardDismissedForRequest = true
                } else {
                    closeRequestDialog()
                    requestHint = "系统锁屏验证未通过，暂时无法填写申请理由"
                }
            }
        }
    }

    /**
     * 锁屏页已存在时收到新的锁屏指令（singleTask + SINGLE_TOP 会走到这里）。
     *
     * 必须重写：不重写的话新原因只会更新静态变量，界面上还是旧文案，
     * 表现为"家长改了锁屏原因，孩子看到的还是上一次那句"。
     *
     * 同时承载两个新模式的再次投递：悬浮层拉起申请页（MODE_REQUEST）与
     * 解锁过渡页（MODE_UNLOCKING，经全屏 intent 由系统代拉到已存在的实例）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newMode = intent.getIntExtra(EXTRA_MODE, MODE_LOCK)
        if (newMode == MODE_REQUEST) {
            mode = MODE_REQUEST
            openUnlockRequestDialog()
        } else if (newMode == MODE_UNLOCKING) {
            mode = MODE_UNLOCKING
            beginUnlockUxIfNeeded()
        }
        val detail = sanitizeDetail(intent.getStringExtra(EXTRA_DETAIL))
        currentDetail = detail
        detailState = detail
    }

    override fun onStop() {
        resumed = false
        resumedNow = false
        visible = false
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        // 与 [visible] 分开维护：见伴生对象里 [resumedNow] 的注释。
        // 必须在 onPause 就落下 —— 孩子上滑逃逸的判定正是从这里开始的，
        // 等到 onStop 会晚几百毫秒，那段时间里地板收口会误判"本页还在前面"而不铺。
        resumedNow = false
        // 申请解锁期间为弹键盘撤掉了系统锁：此刻被切走（上滑回家/电源键熄屏/来电）
        // 必须补压回去，否则手势解禁、锁屏可被滑走。凭证界面（PIN 框）盖住本页时
        // 同样会走 onPause，那种情况正在等待用户验证，不能补压（awaitingKeyguardDismiss）。
        if (keyguardDismissedForRequest && !awaitingKeyguardDismiss && stillLocked()) {
            keyguardDismissedForRequest = false
            keyguardBridge.pushSystemLock()
        }
        // 只有"仍处于锁定态却被切走"才需要抢回前台；
        // 正常解锁时 lockController 已清空原因，这里不会误拉起。
        if (stillLocked() && !awaitingKeyguardDismiss) {
            // 上滑逃逸的正面拦截：被切走的**同一时刻**就安排抢占复位（relaunchRunnable），
            // 由锁屏页 Activity 重新拉回前台。
            //
            // 不能等后面那两次补救：① 250ms 后的 Activity 抢占，在 Android 10+ 上属于
            // 后台启动，绝大多数 ROM 会静默丢弃；② 15s 一轮的守护周期自检，太慢；
            // ③ 500ms 的锁屏看门狗依赖无障碍服务，未授予无障碍的设备根本没有这条兜底。
            //
            // 历史版本在此处立即铺「深灰悬浮窗 LockScreenOverlay」作为地板；该层在部分 ROM 上
            // 会出现后无法收回的死局，已按产品决策彻底删除。删除后逃逸拦截收口为「抢占复位 +
            // 无障碍看门狗 + 全屏 intent 通知」把锁屏页重新顶回前台。
            // 息屏时不触发抢占：亮屏时本页会自行 onResume 回来。
            // 等凭证界面回调期间（安全锁验证）也不抢占：那会让系统凭证框失去焦点。
            handler.postDelayed(relaunchRunnable, transportSettings.rearmDelayMs.value.toLong())
        }
    }

    /** 屏幕当前是否点亮（息屏时不铺悬浮地板，避免把它唤醒） */
    private fun isScreenOn(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true

    /**
     * 持续抢占：只要仍处于锁定态且没回到前台，就反复把自己拉回栈顶。
     *
     * 单次抢占是不够的 —— 部分 ROM 会限制后台启动 Activity，孩子上滑回桌面后
     * 一次拉起可能被系统吃掉，锁屏就再也回不来（表现同样是"锁了还能玩"）。
     * 因此这里在重新回到前台（onResume 撤掉回调）之前会持续重试。
     */
    private val relaunchRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || !stillLocked()) return
            if (resumed) return
            // 到达上限即停：非硬锁设备后台启动 Activity 必被 ROM 拒绝，继续重试只是刷日志，
            // 真正的兜底交给无障碍看门狗（可后台拉起）与悬浮窗地板（盖在桌面之上）。
            if (relaunchCount >= MAX_RELAUNCH_ATTEMPTS) {
                Logger.w(TAG) {
                    "lock screen relaunch aborted after $relaunchCount attempts " +
                        "(background start blocked by ROM; relying on watchdog/FSI to re-pull)"
                }
                // 放弃 Activity 抢占 ≠ 放弃锁定：历史版本在此处铺「深灰悬浮窗」兜底；该层已删除。
                // 现锁定覆盖力收口为「无障碍看门狗（可后台拉起 Activity）+ 全屏 intent 通知
                // （系统代拉）」把锁屏页重新顶回前台，避免裸桌面。
                return
            }
            relaunchCount++
            Logger.w(TAG) { "lock screen pushed to background while locked, re-arming ($relaunchCount/$MAX_RELAUNCH_ATTEMPTS)" }
            show(this@LockScreenActivity, currentDetail)
            handler.postDelayed(this, RELAUNCH_RETRY_MS)
        }
    }

    /**
     * 护眼休息数字倒计时：每秒刷新一次 [countdownSeconds]。
     *
     * 只给 [LockReason.EYE_CARE] 一种原因做倒计时——它的解锁时间是可预期的有限时长
     * （即家长设定的休息时长），倒计时才有意义；时段锁 / 远程锁 / 总时长锁的"下次解锁"
     * 可能远在数小时外，显示倒计时反而会让孩子误以为"还有 X 分钟就好"。
     *
     * 剩余时间用 [TimeProvider.now] 而非裸 `System.currentTimeMillis` 计算，
     * 以抵消服务端时间偏移，与 [com.padguard.core.engine.enforcer.EyeCareEnforcer]
     * 计算 `untilMillis` 的口径完全一致，否则会因偏移出现"倒计时先归零、过几秒才真正解锁"的观感错位。
     */
    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateCountdown()
            handler.postDelayed(this, COUNTDOWN_INTERVAL_MS)
        }
    }

    private fun updateCountdown() {
        val s = lockController.state.value
        if (s.primaryReason == LockReason.EYE_CARE && s.primaryUntilMillis > 0L) {
            val remainingSec = ((s.primaryUntilMillis - timeProvider.now()) / 1000L)
                .toInt()
                .coerceAtLeast(0)
            countdownSeconds = remainingSec
        } else {
            countdownSeconds = -1
        }
    }

    /** 把剩余秒数格式化为 MM:SS（分钟超过 99 也照常显示，如 90:00） */
    private fun formatCountdown(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    /** 是否真正处于前台（由 onResume/onPause/onStop 维护） */
    @Volatile private var resumed = false

    /** 抢占复位已尝试次数：到达 [MAX_RELAUNCH_ATTEMPTS] 上限即停，避免非硬锁设备上
     *  后台启动被 ROM 拒绝后无限刷「re-arming」日志（这正是此前日志风暴的诱因之一）。 */
    private var relaunchCount = 0

    private fun stillLocked(): Boolean = lockController.state.value.locked

    private fun enterLockTask() {
        LockTaskSupport.start(this)
    }

    private fun exitLockTask() {
        LockTaskSupport.stop(this)
    }

    /**
     * 禁用 / 恢复状态栏（需 Device Owner）。
     *
     * 没有 DO 时该能力不可用，属降级场景 —— 此时靠 [pushSystemLockIfNeeded] 的
     * 系统级锁屏与抢占复位兜底，不能因为拿不到 DO 就什么都不做。
     */
    private fun applyStatusBarDisabled(disabled: Boolean) {
        if (!admin.can(Capability.STATUS_BAR)) return
        admin.setStatusBarDisabled(disabled)
    }

    /**
     * 无 Device Owner 时的系统级兜底。
     *
     * `startLockTask()` 只有在 DO 白名单里才生效；没有 DO 时锁屏页就是一个普通 Activity，
     * 孩子从屏幕底部上滑（手势导航 HOME）即可回到桌面 —— 这正是"锁了还能滑走"的根因。
     *
     * 此时退而求其次调用设备管理器级别的 `lockNow()`：它会立刻灭屏并唤起系统锁屏（keyguard）。
     * 在 keyguard 之上，系统的 HOME 手势与最近任务均被禁用，等于借系统之力把手势逃逸堵住；
     * 我们的锁屏页通过 [showOverLockScreen] 的 setShowWhenLocked 显示在 keyguard 之上，
     * 孩子看到的仍是"平板已锁定"而不是系统锁屏界面。这就是用户认可的「锁屏并熄屏」。
     *
     * 注意：是否真的下压由 [SystemKeyguardBridge.pushSystemLock] 内部裁决——安全锁
     * （PIN/图案/密码）设备上它会拒绝（避免解锁死锁），此时本方法只是空转、锁屏改由
     * 应用锁屏页（LockScreenActivity）兜底，仅牺牲自动灭屏观感，绝不再产生"解不开"的死局。
     *
     * 压锁必须经 [SystemKeyguardBridge] 记账：解锁时据此判断该不该由我们撤 keyguard，
     * 否则家长端解锁只清了 App 内状态，系统锁与熄屏依旧——"解锁了却毫无反应"。
     */
    private fun pushSystemLockIfNeeded() {
        if (systemLockPushed) return
        // 已经是 DO：Lock Task 本身就是最强的封锁，再 lockNow() 只会多灭一次屏，无收益
        if (admin.isDeviceOwner()) return
        if (!admin.can(Capability.LOCK_NOW)) {
            Logger.w(TAG) { "no Device Admin active: system-level lock unavailable, lock screen can be swiped away" }
            return
        }
        if (keyguardBridge.pushSystemLock()) {
            systemLockPushed = true
            Logger.i(TAG) { "system lockNow() pushed (no Device Owner): gesture navigation blocked by keyguard" }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 双保险：仍挡一次返回键（部分 ROM 走 key-down 派发）
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        // 屏蔽最近任务键与 HOME（锁定任务模式下这两个本就无效，这里是 ROM 差异兜底）
        if (keyCode == KeyEvent.KEYCODE_APP_SWITCH || keyCode == KeyEvent.KEYCODE_HOME) return true
        return super.onKeyDown(keyCode, event)
    }

    // ==================== 解锁 UX（家长解锁后的可见收尾） ====================

    /**
     * 按需启动解锁 UX。
     *
     * @return true = 已接管收尾：撤完系统锁后自行 finish，**调用方不要再 finish**；
     *         false = 没有我们压的系统锁要撤，调用方按旧行为直接 finish。
     *
     * 入口即做两件防御（历史死锁的根治）：
     * 1. **落盘「待撤锁」意图**：此刻起哪怕进程立刻死掉，看门狗/周期自检也会接着追；
     * 2. **武装 5s 超时**：覆盖"挂起等 onResume 却永远等不到"（FSI 被 ROM 吞掉）的场景 ——
     *    旧实现只在真正执行撤锁后才设超时，pending 挂起态下没有任何兜底，
     *    解锁闸门一旦卡住就永久卡住（家长再点解锁也被 `dismiss()` 的早返回挡掉）。
     */
    private fun beginUnlockUxIfNeeded(): Boolean {
        if (unlockUxRunning) return false
        if (!keyguardBridge.wasPushedByUs()) return false
        // 策略重锁竞态：设备又该锁着了（时段锁/护眼锁在解锁后立刻成立）。
        // 此时绝不能撤系统锁 —— 非 DO 设备上手势封锁完全依赖 keyguard，撤了就是撕开一道缝。
        // 残留的待办标记一并清掉（重新上锁时 show() 本应已清，这里双保险）。
        if (stillLocked()) {
            keyguardBridge.clearUnlockPending()
            return false
        }
        unlockUxRunning = true
        unlockUxStartedAt = System.currentTimeMillis()
        keyguardBridge.markUnlockPending()
        handler.postDelayed(unlockUxTimeout, UNLOCK_UX_TIMEOUT_MS)
        if (resumed) runUnlockUx() else pendingUnlockUx = true
        return true
    }

    /**
     * 撤掉我们压下去的系统 keyguard，然后退出锁屏页。
     *
     * 这是「家长解锁 → 孩子端立即恢复可用」的最后一环：UNLOCK 指令只清 App 内锁定态，
     * 系统锁（灭屏 + keyguard）是我们压的，不解掉这一侧，孩子端永远"无反应"。
     *
     * 撤锁经 [SystemKeyguardBridge.ensureUnlocked] 收敛，**结果必须被尊重**：
     * - 成功 → 清记账 + 常规收尾；
     * - 失败（安全锁验证被取消 / ROM 不回调路径）→ **绝不清记账**。系统锁仍压着，
     *   清了下次解锁就没人知道该撤它（旧实现此处直接 finishAfterUnlock，
     *   是"解锁失败一次后永远解不开"的直接成因）。改为保留记账与待办、
     *   重置闸门并收起页面，把追击交给自愈触发点。
     *
     * 带回调超时保险：个别 ROM 可能既不回调也不撤锁，5s 后照常收尾，
     * 绝不让"已解锁的锁屏页"挂在栈顶（孩子会以为管控卡死）。
     */
    private fun runUnlockUx() {
        Logger.i(TAG) { "unlock ux: dismissing system keyguard pushed by us" }
        keyguardBridge.ensureUnlocked(this) { ok ->
            handler.removeCallbacks(unlockUxTimeout)
            if (ok) finishAfterUnlock() else onUnlockUxFailed()
        }
    }

    /**
     * 解锁 UX 失败收尾：保留记账与待办，重置闸门，收起页面。
     *
     * 页面走后，[SystemKeyguardBridge.isUnlockPending] 仍在，看门狗（500ms 一轮）、
     * 守护服务周期自检、进程启动都会继续追 —— 解锁因此不再依赖"这一次必须成功"。
     */
    private fun onUnlockUxFailed() {
        Logger.w(TAG) { "unlock ux failed, handing chase to self-healing triggers" }
        unlockUxRunning = false
        pendingUnlockUx = false
        if (!isFinishing) finish()
    }

    /** 撤锁回调超时兜底：ROM 不回调时也必须收尾，不能把已解锁的锁屏页挂成"卡死" */
    private val unlockUxTimeout = object : Runnable {
        override fun run() {
            if (isFinishing) return
            Logger.w(TAG) { "unlock ux timed out waiting for keyguard dismiss callback" }
            onUnlockUxFailed()
        }
    }

    /** 解锁收尾：撤锁记账、解除屏幕固定、恢复状态栏、退出。幂等，可重复调用。 */
    private fun finishAfterUnlock() {
        keyguardBridge.clearPushed()
        exitLockTask()
        // 解锁必须恢复状态栏，否则家长会永久失去下拉通知栏 —— 这是严重副作用
        applyStatusBarDisabled(false)
        if (!isFinishing) finish()
    }

    // ==================== 解锁申请 ====================

    /**
     * 打开解锁申请对话框（「申请解锁」按钮 / 悬浮层入口共用）。
     *
     * keyguard 锁定态下先撤系统锁：Android 平台限制——设备被 keyguard 锁定时，
     * 即使是显示在 keyguard 之上的窗口也**无法弹出输入法**（IME 被 keyguard 窗口压制）。
     * 这就是"孩子端无法输入解锁申请、没有软键盘"的根因。撤锁只是临时的：
     * 对话框一关闭（[closeRequestDialog]）立即补压，恢复「锁屏并熄屏」的硬锁态。
     */
    private fun openUnlockRequestDialog() {
        if (submitting) return
        requestHint = null
        if (!resumed) {
            // onNewIntent 早于 onResume 到达（后台投递）：挂起，等 resumed 再撤锁+弹框
            pendingRequestDialog = true
            return
        }
        if (keyguardBridge.isKeyguardLocked()) {
            awaitingKeyguardDismiss = true
            keyguardBridge.requestDismiss(this) { ok ->
                // 回调已到：无论成败都撤掉兜底定时器，避免重复触发
                handler.removeCallbacks(requestDismissFallback)
                if (!awaitingKeyguardDismiss) return@requestDismiss
                awaitingKeyguardDismiss = false
                if (ok) {
                    keyguardDismissedForRequest = true
                    showRequestDialog = true
                } else {
                    // 安全锁验证被取消/失败：如实告知，不假装能填
                    requestHint = "系统锁屏验证未通过，暂时无法填写申请理由"
                }
            }
            // 兜底：部分 ROM 上 KeyguardDismissCallback 永不回调（即便 keyguard 已被撤掉），
            // 若只依赖回调，对话框与键盘将永久不出现（这正是「键盘弹不出来」的根因之一）。
            // 以「权威的 keyguard 状态」为基准，超时后直接放行键盘。
            handler.postDelayed(requestDismissFallback, KEYGUARD_DISMISS_FALLBACK_MS)
        } else {
            showRequestDialog = true
        }
    }

    /**
     * 撤系统锁的兜底定时器。
     *
     * [SystemKeyguardBridge.requestDismiss] 的回调在部分定制 ROM 上不可靠（永不触发），
     * 导致申请解锁对话框与软键盘永远不出现。这里不依赖回调，转而轮询权威的 keyguard 状态：
     * - keyguard 已不在 → 撤锁成功只是回调丢失，直接弹框；
     * - 仍是安全锁 → 凭证界面还在等输入，再延长一轮等待；
     * - 非安全锁却仍锁着且回调丢失 → 直接弹框（无安全锁本无需凭证，IME 在对话框出现后立即可用）。
     */
    private val requestDismissFallback: Runnable = Runnable {
        if (!awaitingKeyguardDismiss) return@Runnable
        awaitingKeyguardDismiss = false
        if (!keyguardBridge.isKeyguardLocked()) {
            keyguardDismissedForRequest = true
            showRequestDialog = true
        } else if (keyguardBridge.isKeyguardSecure()) {
            // 安全锁仍在等凭证：再给一轮机会，避免误伤正在输入 PIN 的家长
            handler.postDelayed(requestDismissFallback, KEYGUARD_DISMISS_FALLBACK_MS)
        } else {
            keyguardDismissedForRequest = true
            showRequestDialog = true
        }
    }

    /**
     * 关闭申请对话框，并按需补压系统锁。
     *
     * 为弹键盘撤掉的系统锁必须补回去：非 DO 设备上手势封锁完全依赖 keyguard，
     * 不补压就等于给孩子留了一扇"申请到一半滑回家"的门。
     */
    private fun closeRequestDialog() {
        showRequestDialog = false
        // 对话框关闭即撤掉兜底定时器，避免残留回调在后续状态翻转时误弹
        handler.removeCallbacks(requestDismissFallback)
        if (keyguardDismissedForRequest && stillLocked()) {
            keyguardDismissedForRequest = false
            keyguardBridge.pushSystemLock()
        }
    }

    private fun submitUnlockRequest() {
        val reason = requestReason.trim()
        // 理由非强制：输入法在个别 ROM / 被 setShowWhenLocked 的 Activity 上仍可能弹不出键盘，
        // 若因"写不进理由"就硬拦截，会直接卡死整条解锁申请（孩子端永远收不到家长放行后的解锁）。
        // 故允许空理由提交（悬浮窗路径本就允许空理由），仅给出轻提示，不再 return 阻断。
        if (reason.isBlank()) {
            requestHint = "未填写理由（可留空），已为你提交申请"
        }
        if (submitting) return
        submitting = true
        requestHint = null
        lifecycleScope.launch {
            runCatching {
                // 先清掉过期申请，避免"上一次申请还在途"导致本次直接被禁用
                unlockRequestRepository.expireStale()
                unlockRequestRepository.submit(
                    packageName = "",          // 整机放行（时段锁/总时长锁），不是单应用
                    appLabel = "整台设备",
                    reasonText = reason,
                    durationMinutes = requestMinutes
                )
            }.onSuccess {
                Logger.i(TAG) { "unlock request submitted" }
                // 立刻催一次上报：申请是有时效的交互，等下一个 60s 上报窗口太慢
                GuardService.start(this@LockScreenActivity, GuardService.REASON_FLUSH)
                closeRequestDialog()
                requestReason = ""
                requestHint = "申请已发送，请等待家长处理"
            }.onFailure {
                Logger.e(TAG, it) { "submit unlock request failed" }
                requestHint = "发送失败，请稍后重试"
            }
            submitting = false
        }
    }

    @Composable
    private fun LockScreenContent(detail: String, isEyeCare: Boolean) {
        // 护眼主题：柔和薄荷绿渐变 + 深绿文字；其余锁屏沿用主题默认背景。
        val eyeBgBrush = Brush.verticalGradient(
            listOf(Color(0xFFE6F4EA), Color(0xFFCDE9D6))
        )
        val eyeOnSurface = Color(0xFF1B5E20)      // 深绿主文字，浅底高对比
        val eyeOnSurfaceDim = Color(0xFF2E7D32)    // 次深绿
        val eyeAccent = Color(0xFF43A047)          // 护眼绿图标

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (isEyeCare) Color.Transparent else MaterialTheme.colorScheme.background
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isEyeCare) Modifier.background(eyeBgBrush) else Modifier)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp)
                        .padding(top = 32.dp, bottom = 132.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 图标：护眼用「眼」字（护眼绿）；其余用「锁」字（主题强调色）
                    GlyphBadge(
                        char = if (isEyeCare) "眼" else "锁",
                        tint = if (isEyeCare) eyeAccent else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(24.dp))

                    // 主提示文案：护眼专属文案 + 大字体；其余保持原文案、字体稍增
                    Text(
                        text = if (isEyeCare) {
                            "为了保护您的视力，请稍作休息后再继续使用"
                        } else {
                            "平板已锁定，需解锁后才能继续使用"
                        },
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineLarge,
                        color = if (isEyeCare) eyeOnSurface else MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(12.dp))

                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            textAlign = TextAlign.Center,
                            style = if (isEyeCare) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.bodyLarge,
                            color = if (isEyeCare) eyeOnSurfaceDim
                            else MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    Text(
                        text = "除申请解锁外，当前无法进行其它操作。",
                        textAlign = TextAlign.Center,
                        style = if (isEyeCare) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyLarge,
                        color = (if (isEyeCare) eyeOnSurface else MaterialTheme.colorScheme.onBackground)
                            .copy(alpha = if (isEyeCare) 0.85f else 0.7f)
                    )

                    // 护眼休息倒计时：仅 EYE_CARE 锁定时 countdownSeconds >= 0。
                    // 数字从家长设定的休息时长（如 15 分钟 → 15:00）开始，逐秒递减到 00:00，
                    // 让孩子对"还要休息多久"有清晰预期，减少对抗情绪。
                    if (countdownSeconds >= 0) {
                        Spacer(Modifier.height(28.dp))
                        Text(
                            text = formatCountdown(countdownSeconds),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.displayLarge,
                            color = eyeAccent
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "护眼休息倒计时，结束后自动解锁",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium,
                            color = eyeOnSurfaceDim
                        )
                    }

                    requestHint?.let { hint ->
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = hint,
                            textAlign = TextAlign.Center,
                            style = if (isEyeCare) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.bodyLarge,
                            color = if (isEyeCare) eyeAccent else MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .padding(bottom = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = { openUnlockRequestDialog() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "申请解锁",
                            style = if (isEyeCare) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.labelLarge
                        )
                    }
                    if (secureKeyguardHint) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "填写申请前需先通过系统锁屏验证",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = (if (isEyeCare) eyeOnSurfaceDim
                            else MaterialTheme.colorScheme.onBackground).copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun UnlockRequestDialog() {
        // 对话框打开即聚焦输入框：keyguard 已撤，窗口拿到焦点时输入法会随焦点自动弹出，
        // 孩子不必再点一次输入框（少一步操作，就少一分"键盘怎么不出来"的困惑）。
        val focusRequester = remember { FocusRequester() }
        val hostView = LocalView.current
        LaunchedEffect(Unit) {
            // 1) 先请求焦点（Compose 常规路径）
            runCatching { focusRequester.requestFocus() }
            // 2) 显式兜底弹键盘：被 setShowWhenLocked 的 Activity 在部分 ROM 上仅靠 focusRequester
            //    不一定弹出输入法，必须显式 showSoftInput，否则孩子看到理由框却写不进字、
            //    申请流程卡死（"键盘弹不出 / 点了申请解锁却解不开"的根因之一）。
            runCatching {
                val imm = hostView.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager
                imm?.showSoftInput(hostView, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        AlertDialog(
            onDismissRequest = { if (!submitting) closeRequestDialog() },
            title = { Text("申请解锁") },
            text = {
                Column {
                    Text(
                        text = "填写申请理由，家长会收到并决定是否放行。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = requestReason,
                        onValueChange = {
                            if (it.length <= MAX_REASON_CHARS) requestReason = it
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        minLines = 3,
                        maxLines = 5,
                        label = { Text("申请理由") },
                        placeholder = { Text("例如：还有一道数学题需要用平板查资料") }
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "期望放行时长",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        for (option in DURATION_OPTIONS) {
                            val selected = requestMinutes == option
                            if (selected) {
                                Button(
                                    onClick = { requestMinutes = option },
                                    modifier = Modifier.height(40.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) { Text("${option}分钟") }
                            } else {
                                TextButton(
                                    onClick = { requestMinutes = option },
                                    modifier = Modifier.height(40.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                    )
                                ) { Text("${option}分钟") }
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { submitUnlockRequest() },
                    enabled = !submitting
                ) { Text(if (submitting) "发送中…" else "发送申请") }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!submitting) closeRequestDialog() }
                ) { Text("取消") }
            }
        )
    }

    /** MODE_UNLOCKING 过渡页内容：一句话说明，窗口可见即可（撤锁需要 resumed 的 Activity） */
    @Composable
    private fun UnlockingContent() {
        Surface(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GlyphBadge(char = "锁", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "家长已解锁，正在恢复…",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(relaunchRunnable)
        handler.removeCallbacks(countdownRunnable)
        handler.removeCallbacks(unlockingSelfCheck)
        handler.removeCallbacks(unlockUxTimeout)
        // 兜底恢复：任何退出路径（含被系统回收）都不能把家长的状态栏永远留在禁用态
        applyStatusBarDisabled(false)
        // 带着"已撤系统锁"状态被回收时补压回去：否则重启后手势解禁，锁屏可被滑走
        if (keyguardDismissedForRequest && stillLocked()) {
            keyguardDismissedForRequest = false
            keyguardBridge.pushSystemLock()
        }
        // 页面被回收而锁定态仍在（进程被系统清理 / 任务栈被清）：把锁交接给锁屏升级链
        // （无障碍看门狗 / 全屏 intent 通知会重新把锁屏页顶回前台），
        // 否则孩子回到的是一个既没有锁屏页、也没有浮层的裸桌面 —— 想开什么开什么。
        if (mode != MODE_UNLOCKING && stillLocked() && isScreenOn()) {
            LockScreenActivity.show(this, currentDetail)
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LockScreenActivity"
        private const val EXTRA_DETAIL = "detail"
        private const val EXTRA_MODE = "mode"

        /** 常规锁屏页（默认）。行为与历史版本一致：压系统锁、熄屏、抢占复位。 */
        const val MODE_LOCK = 0

        /** 申请解锁入口：先撤系统锁再弹对话框（keyguard 锁定态下 IME 无法显示），用完补压。 */
        const val MODE_REQUEST = 1

        /** 解锁过渡页：点亮屏幕 + 撤掉我们压的系统锁 + 自行退出，把设备交还桌面。 */
        const val MODE_UNLOCKING = 2

        /** 单次申请理由字数上限，与 UnlockRequestRepository 保持一致 */
        private const val MAX_REASON_CHARS = 200

        private val DURATION_OPTIONS = listOf(15, 30, 60)

        /**
         * 被切走后重新抢占前台的延迟。
         * 原为 800ms：孩子上滑后能看到近 1 秒的桌面，足以再点开一个应用，
         * 观感就是"锁屏能滑走"；后压到 250ms。
         * 现改为由 [com.padguard.core.transport.TransportSettings.rearmDelayMs] 运行时配置
         * （默认 16ms，详见 TransportSettings.DEFAULT_REARM_DELAY_MS），可在不发包的前提下
         * 把间隙压到接近 0。onPause 中读取该值作为 postDelayed 延迟。
         * 注意：仅 DO（设备所有者）模式下才能真正零间隙（无桌面可滑）。
         */

        /** 抢占未成功时的重试间隔：持续抢占直到真正回到前台 */
        private const val RELAUNCH_RETRY_MS = 500L

        /**
         * 抢占复位重试上限：约 8 秒（[RELAUNCH_RETRY_MS] × 本值）。
         * 非硬锁设备的后台启动被 ROM 拒绝是常态，到上限后停止自我重试，
         * 交由无障碍看门狗（可后台拉起 Activity）与悬浮窗地板兜底，避免无限刷日志。
         */
        private const val MAX_RELAUNCH_ATTEMPTS = 16

        /** 护眼休息倒计时刷新间隔：每秒一次，足够平滑又不浪费资源 */
        private const val COUNTDOWN_INTERVAL_MS = 1000L

        /**
         * 撤系统锁兜底等待：requestDismissKeyguard 回调在部分 ROM 上永不触发时的超时。
         * 800ms 足以覆盖正常「滑动/无锁」keyguard 被立即撤掉的场景，又不至于让家长等太久。
         */
        private const val KEYGUARD_DISMISS_FALLBACK_MS = 800L

        /**
         * 过渡页自检间隔：拉起时若策略又重新锁上（如时段锁仍生效），
         * 到点自行收起，把舞台还给锁屏升级链，避免"正在解锁"挂住不动。
         */
        private const val UNLOCKING_SELF_CHECK_MS = 1500L

        /**
         * 解锁 UX 超时：撤锁回调 5s 未到就照常收尾。
         * 个别 ROM 可能既不回调也不撤锁，此时"已解锁的锁屏页"绝不允许挂在栈顶。
         * 入口即武装（含 pending 挂起态），挂起等不到 onResume 时也能兜底重置。
         */
        private const val UNLOCK_UX_TIMEOUT_MS = 5000L

        /**
         * 解锁 UX 卡死判定：超过 5s 超时仍未 resumed，[dismiss] 的早返回路径上
         * 补拉一次过渡页。取 8s = 给超时兜底留 3s 余量，正常情况永远走不到这里。
         */
        private const val UNLOCK_UX_STUCK_MS = 8_000L

        /**
         * 解锁追逐最小间隔：5s。
         * 追逐由多个触发点共享（看门狗 500ms 一轮、周期自检、进程启动），
         * 且安全锁设备上每次追逐都会弹一次系统凭证界面，必须限频。
         */
        private const val CHASE_MIN_INTERVAL_MS = 5_000L

        /** 当前运行中的实例，供 [dismiss] 直接 finish，避免再发一次 Intent */
        @Volatile
        private var instance: LockScreenActivity? = null

        /** 最近一次锁屏说明，抢占复位时原样带回，避免复位后原因丢失 */
        @Volatile
        private var currentDetail: String = ""

        /**
         * 锁定页是否真的已经显示在前台。
         *
         * 升级链（[LockScreenPresenter]）用它判断"还要不要继续重试"：
         * 后台启动 Activity 被 ROM 吃掉时**没有任何异常**，只有这个前台回执能证明真的锁住了。
         */
        @Volatile
        var visible: Boolean = false
            private set

        /**
         * 锁定页此刻是否真的占着屏幕（resumed）。
         *
         * 与 [visible] 的区别（两者都对外可读，用途不同、不能合并）：
         * - [visible] 到 `onStop` 才落下 —— 升级链用它判断"还要不要继续重试"，
         *   晚一点落没有副作用，反而能避免自己拉自己时的抖动；
         * - 本标记在 `onPause` 就落下 —— 地板收口（[LockScreenPresenter.ensureCoverage]）
         *   用它判断"屏幕上到底有没有这一层"。晚落几百毫秒，恰好覆盖孩子上滑逃逸的
         *   判定窗口，会导致地板收口误判"锁定页还在前面"而不铺，屏幕裸奔。
         */
        @Volatile
        var resumedNow: Boolean = false
            private set

        /**
         * 服务端下发的 reason 可能是字面量 "null"（历史上下发过 {"reason":"null"}），
         * 直接显示会在锁屏页正中摆一个刺眼的 null，这里连同空串一起滤掉。
         */
        private fun sanitizeDetail(raw: String?): String =
            raw?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }.orEmpty()

        /** 构造锁屏页意图。全屏通知的 PendingIntent 与直接拉起共用同一份，避免两处 flags 漂移。 */
        fun intent(context: Context, detail: String, mode: Int = MODE_LOCK): Intent =
            Intent(context, LockScreenActivity::class.java).apply {
                putExtra(EXTRA_DETAIL, detail)
                putExtra(EXTRA_MODE, mode)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }

        fun show(context: Context, detail: String, mode: Int = MODE_LOCK) {
            currentDetail = detail
            runCatching { context.startActivity(intent(context, detail, mode)) }
                .onFailure { Logger.e(TAG, it) { "failed to show lock screen" } }
        }

        /**
         * 收起锁屏页 —— 家长解锁 / 自动解锁的统一出口。
         *
         * ## 为什么不能只 finish（本次缺陷 2 的根因）
         * 系统 keyguard 是我们压下去的（"锁屏并熄屏"）。只 finish 页面，系统锁与熄屏依旧，
         * 孩子端看起来就是"家长解锁了却毫无反应"。因此这里按场景分流：
         *
         * 1. **有存活实例**：交给它走解锁 UX（[beginUnlockUxIfNeeded]）——先撤系统锁再退出；
         *    实例处于息屏 stopped 时，经全屏 intent 由**系统**代拉回前台（绕过后台启动限制），
         *    onResume 后执行挂起的撤锁；
         * 2. **无存活实例但系统锁被我们压过**（进程被回收 / 页面被销毁而 keyguard 还在）：
         * 拉一个 [MODE_UNLOCKING] 一次性过渡页，点亮屏幕、撤 keyguard、自行退出；
         * 3. 其余（DO/无设备管理员，从未压系统锁）：与历史行为一致，直接 finish。
         */
        fun dismiss(context: Context) {
            // 记账先行：家长已解锁。系统锁若还在，把"待撤锁"意图落盘 ——
            // 哪怕页面不在、进程随后死掉、启动被 ROM 吞掉，自愈触发点仍会追到底。
            // 这是"解锁指令只许成功一次"语义的持久化：从此任何组件都能接着完成它。
            if (SystemKeyguardBridge.wasPushedByUs(context)) {
                SystemKeyguardBridge.markUnlockPending(context)
            }
            val activity = instance
            if (activity != null) {
                if (activity.unlockUxRunning) {
                    // UX 进行中，正常由它自行收尾。但若它卡在"从未回前台"且 5s 超时
                    // 也未兜住（极端 ROM：FSI 被吞 + 直接启动被吞），在此补拉一次
                    // 过渡页，绝不让解锁指令永久空转。
                    if (!activity.resumed &&
                        System.currentTimeMillis() - activity.unlockUxStartedAt > UNLOCK_UX_STUCK_MS
                    ) {
                        Logger.w(TAG) { "unlock ux stuck pending, re-launching unlocking page" }
                        launchUnlockingPage(context, currentDetail, MODE_UNLOCKING)
                    }
                    return
                }
                if (SystemKeyguardBridge.wasPushedByUs(context) && activity.beginUnlockUxIfNeeded()) {
                    // 已 resumed：撤锁正在执行；未 resumed（息屏 stopped）：经系统代拉带回前台
                    if (!activity.resumed) {
                        launchUnlockingPage(context, currentDetail, activity.mode)
                    }
                    return
                }
                // 先解除屏幕固定，否则 finish() 后系统仍停留在锁定任务状态，桌面无法操作
                activity.exitLockTask()
                if (!activity.isFinishing) activity.finish()
                return
            }
            if (SystemKeyguardBridge.wasPushedByUs(context)) {
                launchUnlockingPage(context, "", MODE_UNLOCKING)
            }
        }

        /**
         * 追逐一次待撤锁（无页面可用的场景：进程刚启动 / 守护服务周期自检 / 看门狗）。
         *
         * 与 [launchUnlockingPage] 同药方：先点屏，再全屏 intent 由系统代拉过渡页，
         * 失败降级直接启动。过渡页 resumed 后经 onResume 的 pending 追逐完成撤锁。
         *
         * 限频 [CHASE_MIN_INTERVAL_MS]：追逐可能被多个触发点同时调用
         * （看门狗 500ms 一轮），且安全锁设备上每次追逐都会弹出一次凭证界面，
         * 不限频会变成提示符风暴。
         */
        fun chaseUnlock(context: Context) {
            if (!SystemKeyguardBridge.isUnlockPending(context)) return
            val now = System.currentTimeMillis()
            if (now - lastChaseAt < CHASE_MIN_INTERVAL_MS) return
            lastChaseAt = now
            Logger.i(TAG) { "chasing pending system-keyguard unlock" }
            launchUnlockingPage(context, "", MODE_UNLOCKING)
        }

        /** [chaseUnlock] 的上一次执行时刻（进程内限频，无需持久化） */
        @Volatile
        private var lastChaseAt = 0L

        /**
         * 拉起解锁过渡页（点亮屏幕 + 撤系统锁 + 自退）。
         *
         * 优先走**全屏 intent**：由系统代拉 Activity，息屏/后台都不受启动限制
         * （AOSP《Full-screen intent limits》行为矩阵：解锁、锁屏、息屏三态均会 Launch FSI）。
         * 直接 startActivity 只作降级——个别 ROM 会静默丢弃后台启动。
         * 两条路都可能失败时，[SystemKeyguardBridge.wakeScreen] 已保证屏幕点亮，
         * 设备至少回到系统锁屏，孩子可自行进入（App 侧锁定已解除）。
         */
        private fun launchUnlockingPage(context: Context, detail: String, mode: Int) {
            SystemKeyguardBridge.wakeScreen(context)
            val posted = LockScreenNotifier.postUnlock(context, detail, mode)
            if (!posted) {
                runCatching { context.startActivity(intent(context, detail, mode)) }
                    .onFailure { Logger.e(TAG, it) { "failed to launch unlocking page" } }
            }
        }
    }
}
