package com.padguard.child.ui.lock

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.padguard.core.engine.lock.LockState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
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
 * ## 关于"解锁申请"
 * 锁死不等于不给沟通渠道：孩子可以在锁屏页点「申请解锁」，填写理由与期望时长，
 * 申请经日志队列上行成服务端工单，家长在管控端看到后可忽略或去处理（解锁 / 调整时长）。
 * 没有这个出口，孩子只会去找家长当面要，或者尝试各种绕过手段。
 */
@AndroidEntryPoint
class LockScreenActivity : ComponentActivity() {

    @Inject lateinit var unlockRequestRepository: UnlockRequestRepository
    @Inject lateinit var lockController: LockController
    @Inject lateinit var admin: DeviceAdminBridge
    @Inject lateinit var timeProvider: TimeProvider

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        // 新一轮锁屏会话：复位抢占计数，给本次锁定一个完整的重试预算。
        relaunchCount = 0
        admin.refreshControlMode()
        // 屏蔽下拉通知栏与快捷设置：否则孩子能从通知栏进设置去关管控 / 卸载我们。
        // 抢占复位会反复进 onCreate，这里做成幂等的"置一次"，不会反复调用系统 API。
        applyStatusBarDisabled(true)
        pushSystemLockIfNeeded()
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
        lifecycleScope.launch {
            lockController.state.collect { state ->
                if (!state.locked) {
                    exitLockTask()
                    // 解锁必须恢复状态栏，否则家长会永久失去下拉通知栏 —— 这是严重副作用
                    applyStatusBarDisabled(false)
                    if (!isFinishing) finish()
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
        // 锁定页真正到达前台 —— 升级链据此停止重试，并撤掉悬浮层与常驻通知
        visible = true
        LockScreenNotifier.cancelFrom(this)
        // 每次回到前台都重新确认一次锁定任务：系统可能在切后台时解除了固定。
        // startLockTask 幂等，重复调用无副作用（非白名单应用会抛异常，已用 runCatching 兜住）。
        enterLockTask()
        // 已经回到前台，撤掉持续抢占，避免重复拉起造成闪屏
        handler.removeCallbacks(relaunchRunnable)
    }

    /**
     * 锁屏页已存在时收到新的锁屏指令（singleTask + SINGLE_TOP 会走到这里）。
     *
     * 必须重写：不重写的话新原因只会更新静态变量，界面上还是旧文案，
     * 表现为"家长改了锁屏原因，孩子看到的还是上一次那句"。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val detail = sanitizeDetail(intent.getStringExtra(EXTRA_DETAIL))
        currentDetail = detail
        detailState = detail
    }

    override fun onStop() {
        resumed = false
        visible = false
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        // 只有"仍处于锁定态却被切走"才需要抢回前台；
        // 正常解锁时 lockController 已清空原因，这里不会误拉起。
        resumed = false
        if (stillLocked()) {
            handler.postDelayed(relaunchRunnable, RELAUNCH_DELAY_MS)
        }
    }

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
                        "(background start blocked by ROM; watchdog / overlay floor will keep it covered)"
                }
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
     * 以抵消服务端时间偏移，与 [EyeCareEnforcer] 计算 `untilMillis` 的口径完全一致，
     * 否则会因偏移出现"倒计时先归零、过几秒才真正解锁"的观感错位。
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
     * 孩子看到的仍是"平板已锁定"而不是系统锁屏界面。
     */
    private fun pushSystemLockIfNeeded() {
        if (systemLockPushed) return
        // 已经是 DO：Lock Task 本身就是最强的封锁，再 lockNow() 只会多灭一次屏，无收益
        if (admin.isDeviceOwner()) return
        if (!admin.can(Capability.LOCK_NOW)) {
            Logger.w(TAG) { "no Device Admin active: system-level lock unavailable, lock screen can be swiped away" }
            return
        }
        admin.lockNow()
        systemLockPushed = true
        Logger.i(TAG) { "system lockNow() pushed (no Device Owner): gesture navigation blocked by keyguard" }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 双保险：仍挡一次返回键（部分 ROM 走 key-down 派发）
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        // 屏蔽最近任务键与 HOME（锁定任务模式下这两个本就无效，这里是 ROM 差异兜底）
        if (keyCode == KeyEvent.KEYCODE_APP_SWITCH || keyCode == KeyEvent.KEYCODE_HOME) return true
        return super.onKeyDown(keyCode, event)
    }

    // ==================== 解锁申请 ====================

    private fun submitUnlockRequest() {
        val reason = requestReason.trim()
        if (reason.isBlank()) {
            requestHint = "请填写申请理由"
            return
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
                showRequestDialog = false
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
                        onClick = { showRequestDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "申请解锁",
                            style = if (isEyeCare) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun UnlockRequestDialog() {
        AlertDialog(
            onDismissRequest = { if (!submitting) showRequestDialog = false },
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
                        modifier = Modifier.fillMaxWidth(),
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
                    onClick = { if (!submitting) showRequestDialog = false }
                ) { Text("取消") }
            }
        )
    }

    override fun onDestroy() {
        handler.removeCallbacks(relaunchRunnable)
        handler.removeCallbacks(countdownRunnable)
        // 兜底恢复：任何退出路径（含被系统回收）都不能把家长的状态栏永远留在禁用态
        applyStatusBarDisabled(false)
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LockScreenActivity"
        private const val EXTRA_DETAIL = "detail"

        /** 单次申请理由字数上限，与 UnlockRequestRepository 保持一致 */
        private const val MAX_REASON_CHARS = 200

        private val DURATION_OPTIONS = listOf(15, 30, 60)

        /**
         * 被切走后重新抢占前台的延迟。
         * 原为 800ms：孩子上滑后能看到近 1 秒的桌面，足以再点开一个应用，
         * 观感就是"锁屏能滑走"。压到 250ms，只够看到切换动画，来不及做任何操作。
         */
        private const val RELAUNCH_DELAY_MS = 250L

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
         * 服务端下发的 reason 可能是字面量 "null"（历史上下发过 {"reason":"null"}），
         * 直接显示会在锁屏页正中摆一个刺眼的 null，这里连同空串一起滤掉。
         */
        private fun sanitizeDetail(raw: String?): String =
            raw?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }.orEmpty()

        /** 构造锁屏页意图。全屏通知的 PendingIntent 与直接拉起共用同一份，避免两处 flags 漂移。 */
        fun intent(context: Context, detail: String): Intent =
            Intent(context, LockScreenActivity::class.java).apply {
                putExtra(EXTRA_DETAIL, detail)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }

        fun show(context: Context, detail: String) {
            currentDetail = detail
            runCatching { context.startActivity(intent(context, detail)) }
                .onFailure { Logger.e(TAG, it) { "failed to show lock screen" } }
        }

        fun dismiss(context: Context) {
            instance?.let { activity ->
                // 先解除屏幕固定，否则 finish() 后系统仍停留在锁定任务状态，桌面无法操作
                activity.exitLockTask()
                if (!activity.isFinishing) activity.finish()
            }
        }
    }
}
