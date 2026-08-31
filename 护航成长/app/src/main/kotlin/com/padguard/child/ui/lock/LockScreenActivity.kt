package com.padguard.child.ui.lock

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.padguard.child.ui.components.GlyphBadge
import com.padguard.child.ui.theme.PadGuardTheme

/**
 * 锁屏覆盖层（时段锁机 / 时长耗尽锁机）。
 *
 * 设计依据（设计说明书「界面交互规范 · 锁屏界面」）：
 * - 展示管控说明、解禁时间、管理员提示；
 * - **无解锁入口、无绕过方式**：屏蔽系统返回键，不提供任何可关闭按钮，
 *   只能由 [GuardService] 在锁状态解除后调用 [dismiss] 关闭。
 *
 * 因为是从后台 [GuardService] 拉起，必须用 `FLAG_ACTIVITY_NEW_TASK`。
 * 锁屏场景下还要能盖在系统锁屏之上并点亮屏幕，故 `setShowWhenLocked` + `setTurnScreenOn`。
 */
class LockScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        // 无解锁入口：拦截系统返回键，禁止逃逸。
        // 用 OnBackPressedDispatcher 替代已废弃的 onBackPressed 覆写。
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
            }
        }
    }

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
        // 锁屏期间保持亮屏，避免刚点亮又熄掉让用户以为没锁
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 双保险：仍挡一次返回键（部分 ROM 走 key-down 派发）
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
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
            }
        }
    }

    companion object {
        private const val EXTRA_DETAIL = "detail"

        /** 当前运行中的实例，供 [dismiss] 直接 finish，避免再发一次 Intent */
        @Volatile
        private var instance: LockScreenActivity? = null

        fun show(context: Context, detail: String) {
            val intent = Intent(context, LockScreenActivity::class.java).apply {
                putExtra(EXTRA_DETAIL, detail)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
        }

        fun dismiss(context: Context) {
            instance?.let { if (!it.isFinishing) it.finish() }
        }
    }

    override fun onStart() {
        super.onStart()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
