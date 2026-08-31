package com.padguard.child.ui.message

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.padguard.child.ui.components.GlyphBadge
import com.padguard.child.ui.theme.PadGuardTheme

/**
 * 管理员消息/公告全屏弹窗（[EngineEffect.ShowMessage] 落地）。
 *
 * 设计依据（设计说明书「界面交互规范 · 消息通知弹窗」）：
 * - 全屏展示通知/公告，确保触达有效；
 * - `blocking=true`：必须手动「我已阅读」确认才关闭（支持手动确认已读）；
 * - `blocking=false` 且 `durationSec>0`：到时自动关闭；
 * - `blocking=false` 且 `durationSec<=0`：提供「知道了」按钮优雅关闭。
 *
 * 非阻塞消息用主线程 Handler 计时关闭，避免引入额外协程作用域。
 */
class MessageActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent?.getStringExtra(EXTRA_BODY).orEmpty()
        val durationSec = intent?.getIntExtra(EXTRA_DURATION, 0) ?: 0
        val blocking = intent?.getBooleanExtra(EXTRA_BLOCKING, false) ?: false

        if (!blocking && durationSec > 0) {
            handler.postDelayed({ if (!isFinishing) finish() }, durationSec * 1000L)
        }

        setContent {
            PadGuardTheme {
                MessageContent(
                    title = title,
                    body = body,
                    blocking = blocking,
                    durationSec = durationSec
                )
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Composable
    private fun MessageContent(
        title: String,
        body: String,
        blocking: Boolean,
        durationSec: Int
    ) {
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
                GlyphBadge("告", MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(24.dp))
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (body.isNotBlank()) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(28.dp))
                }

                when {
                    blocking -> Button(onClick = { finish() }) {
                        Text(text = "我已阅读")
                    }

                    durationSec <= 0 -> TextButton(onClick = { finish() }) {
                        Text(text = "知道了")
                    }
                    // 非阻塞且有倒计时：静默自动关闭，不显示按钮
                }
            }
        }
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BODY = "body"
        private const val EXTRA_DURATION = "durationSec"
        private const val EXTRA_BLOCKING = "blocking"

        fun show(
            context: Context,
            title: String,
            body: String,
            durationSec: Int,
            blocking: Boolean
        ) {
            val intent = Intent(context, MessageActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                putExtra(EXTRA_DURATION, durationSec)
                putExtra(EXTRA_BLOCKING, blocking)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
        }
    }
}
