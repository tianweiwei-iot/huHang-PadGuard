package com.padguard.child.glassball

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.usagesession.UsageSessionViewModel
import com.padguard.child.ui.theme.PadGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

/**
 * 玻璃球「双击 → 申请延时」对话框（2026-10-20 新增）。
 *
 * ## 链路（复用既有三端审批流，零服务端改动）
 * 孩子选档提交 → `UsageSessionManager.requestDelay` → 服务端仅落 PENDING 工单
 * → 家长端审批（消息中心全局弹窗 / 时间管控页内联卡片）→ 同意后
 * `session.extendedMinutes` 累加 → 孩子端 15s 会话轮询带回 → 玻璃球剩余秒级变大。
 *
 * ## 防绕过
 * - 待审核期间（`pendingDelayMinutes > 0`）档位置灰、只展示等待状态，杜绝重复提交；
 * - 单日累计上限（默认 120 分钟）由服务端强校验，这里同步置灰给用户明确反馈。
 */
@AndroidEntryPoint
class GlassBallDialogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PadGuardTheme {
                Surface {
                    DelayRequestDialog(
                        viewModel = hiltViewModel(),
                        onSubmitted = {
                            Toast.makeText(this, "已发送申请，等待家长确认", Toast.LENGTH_SHORT).show()
                            finish()
                        },
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun DelayRequestDialog(
    viewModel: UsageSessionViewModel,
    onSubmitted: () -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending = state.pendingDelayMinutes > 0
    val canExtend = state.extendedMinutes < state.maxDailyExtendMinutes

    // 秒级刷新本地插值剩余（与玻璃球同口径，不依赖 15s 会话轮询）
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(1000)
        }
    }
    val liveRemainingMs = remember(tick) { viewModel.liveRemainingMs() }

    var selected by remember { mutableIntStateOf(-1) }
    var submitted by remember { mutableStateOf(false) }

    // 提交结果处理：loading 落定且已有待审工单 → 成功（提示并关闭）；
    // error 非空 → 失败（保留对话框展示错误，允许改选重试）。
    LaunchedEffect(submitted, state.loading) {
        if (submitted && !state.loading) {
            when {
                state.pendingDelayMinutes > 0 -> onSubmitted()
                state.error != null -> submitted = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    if (selected > 0) {
                        submitted = true
                        viewModel.requestDelay(selected)
                    }
                },
                enabled = !pending && !state.loading && canExtend && selected > 0 && state.active
            ) {
                Text(if (state.loading) "提交中…" else "提交申请")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("申请延长使用时间") },
        text = {
            Column {
                if (!state.active) {
                    // 计时未开始：服务端会拒绝此类申请，先如实告知，不让孩子白试
                    Text(
                        text = "今日计时尚未开始，暂时无法申请延时。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text(
                        text = "剩余 ${formatMmSs(liveRemainingMs)}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    val usedMinutes = (state.quotaMinutes - liveRemainingMs / 60_000L).toInt()
                        .coerceAtLeast(0)
                    Text(
                        text = "今日已用 $usedMinutes 分钟" +
                            " · 已延时 ${state.extendedMinutes} 分钟 · 单日上限 ${state.maxDailyExtendMinutes} 分钟",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (pending) {
                    // 待审核只读态：与孩子端倒计时浮层、服务端单 PENDING 规则一致
                    Text(
                        text = "已申请 +${state.pendingDelayMinutes} 分钟，等待家长审核…",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "家长确认后时间立即生效，各端同步更新。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (state.active) {
                    Text("选择要延长的时长：", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.delayOptions.forEach { minutes ->
                            FilterChip(
                                selected = selected == minutes,
                                onClick = { selected = minutes },
                                enabled = canExtend && !state.loading,
                                label = { Text("+${minutes}分") }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "提交后需家长在家长端确认；家长同意后各端时间同步生效，" +
                            "拒绝则会收到「申请未通过」提醒。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.error?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "提交失败：$err",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}

/** mm:ss 格式化（超过 99 分钟按 99:59 封顶，防异常值撑破布局） */
private fun formatMmSs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceIn(0L, 99 * 60L + 59L)
    val m = (totalSec / 60L).toInt()
    val s = (totalSec % 60L).toInt()
    return "%02d:%02d".format(m, s)
}
