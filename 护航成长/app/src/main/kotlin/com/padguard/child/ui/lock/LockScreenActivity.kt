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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.padguard.core.data.repository.UnlockRequestRepository
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.lock.LockController
import com.padguard.core.engine.lock.LockState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 锁屏覆盖层（时段锁机 / 时长耗尽锁机 / 家长远程锁屏）。
 *
 * ## 为什么必须"真锁"（历史教训）
 * 旧实现只做了两件事：拦截返回键 + 不给关闭按钮。但它仍是一个**普通 Activity**，
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        admin.refreshControlMode()
        // 屏蔽下拉通知栏与快捷设置：否则孩子能从通知栏进设置去关管控 / 卸载我们。
        // 抢占复位会反复进 onCreate，这里做成幂等的"置一次"，不会反复调用系统 API。
        applyStatusBarDisabled(true)
        pushSystemLockIfNeeded()

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
        val rawDetail = intent?.getStringExtra(EXTRA_DETAIL).orEmpty()
        val detail = rawDetail.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }.orEmpty()
        currentDetail = detail

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
                LockScreenContent(detail = detail)
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
        // 每次回到前台都重新确认一次锁定任务：系统可能在切后台时解除了固定。
        // startLockTask 幂等，重复调用无副作用（非白名单应用会抛异常，已用 runCatching 兜住）。
        enterLockTask()
        // 已经回到前台，撤掉持续抢占，避免重复拉起造成闪屏
        handler.removeCallbacks(relaunchRunnable)
    }

    override fun onStop() {
        resumed = false
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
            Logger.w(TAG) { "lock screen pushed to background while locked, re-arming" }
            show(this@LockScreenActivity, currentDetail)
            handler.postDelayed(this, RELAUNCH_RETRY_MS)
        }
    }

    /** 是否真正处于前台（由 onResume/onPause/onStop 维护） */
    @Volatile private var resumed = false

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
    private fun LockScreenContent(detail: String) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // 说明文案居中，「申请解锁」固定在页面底部：
            // 底部是拇指最舒服的位置，也避免孩子误以为"按钮就是主要内容"。
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp)
                        .padding(top = 32.dp, bottom = 132.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    GlyphBadge("锁", MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "平板已锁定，需解锁后才能继续使用",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(12.dp))
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        text = "除申请解锁外，当前无法进行其它操作。",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )

                    requestHint?.let { hint ->
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = hint,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
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
                        Text(text = "申请解锁")
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

        /** 当前运行中的实例，供 [dismiss] 直接 finish，避免再发一次 Intent */
        @Volatile
        private var instance: LockScreenActivity? = null

        /** 最近一次锁屏说明，抢占复位时原样带回，避免复位后原因丢失 */
        @Volatile
        private var currentDetail: String = ""

        fun show(context: Context, detail: String) {
            currentDetail = detail
            val intent = Intent(context, LockScreenActivity::class.java).apply {
                putExtra(EXTRA_DETAIL, detail)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            runCatching { context.startActivity(intent) }
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
