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
import com.padguard.core.engine.lock.LockController
import com.padguard.core.engine.lock.LockState
import dagger.hilt.android.AndroidEntryPoint
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

    private val handler = Handler(Looper.getMainLooper())

    /** 申请弹窗状态（Compose 层读写，Activity 只负责持有） */
    private var showRequestDialog by mutableStateOf(false)
    private var requestReason by mutableStateOf("")
    private var requestMinutes by mutableStateOf(30)
    private var requestHint by mutableStateOf<String?>(null)
    private var submitting by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        // 无解锁入口：拦截系统返回键，禁止逃逸。
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // 故意不调用 super，事件在此吞掉
                }
            }
        )

        val detail = intent?.getStringExtra(EXTRA_DETAIL).orEmpty()
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
        // 每次回到前台都重新确认一次锁定任务：系统可能在切后台时解除了固定。
        // startLockTask 幂等，重复调用无副作用（非白名单应用会抛异常，已用 runCatching 兜住）。
        enterLockTask()
        // 重新抢占时清掉待执行的复位动作，避免重复拉起造成闪屏
        handler.removeCallbacks(relaunchRunnable)
    }

    override fun onPause() {
        super.onPause()
        // 只有"仍处于锁定态却被切走"才需要抢回前台；
        // 正常解锁时 lockController 已清空原因，这里不会误拉起。
        if (stillLocked()) {
            handler.postDelayed(relaunchRunnable, RELAUNCH_DELAY_MS)
        }
    }

    private val relaunchRunnable = Runnable {
        if (!isFinishing && stillLocked()) {
            Logger.w(TAG) { "lock screen pushed to background while locked, re-arming" }
            show(this, currentDetail)
        }
    }

    private fun stillLocked(): Boolean = lockController.state.value.locked

    private fun enterLockTask() {
        LockTaskSupport.start(this)
    }

    private fun exitLockTask() {
        LockTaskSupport.stop(this)
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                GlyphBadge("锁", MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "设备已锁定",
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
                    text = "请遵守管控规则，到达允许时间后自动解锁。",
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

                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = { showRequestDialog = true },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = "申请解锁")
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
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LockScreenActivity"
        private const val EXTRA_DETAIL = "detail"

        /** 单次申请理由字数上限，与 UnlockRequestRepository 保持一致 */
        private const val MAX_REASON_CHARS = 200

        private val DURATION_OPTIONS = listOf(15, 30, 60)

        /** 被切走后重新抢占前台的延迟：太短会和系统动画打架，太长会留下可操作窗口 */
        private const val RELAUNCH_DELAY_MS = 800L

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
