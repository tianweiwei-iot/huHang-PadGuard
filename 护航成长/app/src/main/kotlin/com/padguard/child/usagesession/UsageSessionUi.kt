package com.padguard.child.usagesession

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * 使用计时会话宿主：覆盖在 Tab 之上的全局浮层。
 *
 * - [UsageSessionManager.UiState.needsStartConfirm] 为真 → 弹出"计时开始"确认框（不点确认不消失）；
 * - [UsageSessionManager.UiState.countdownActive] 为真 → 屏幕正上方显示 10 分钟倒计时条 + 延时申请。
 *
 * 放在 [com.padguard.child.ui.nav.PadGuardApp] 内、NavHost 之上，因此无论停在哪个 Tab 都能看到。
 */
@Composable
fun UsageSessionHost(viewModel: UsageSessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.needsStartConfirm) {
        UsageStartDialog(
            loading = state.loading,
            onConfirm = viewModel::confirmStart
        )
    }

    if (state.countdownActive) {
        CountdownBanner(
            liveRemainingMs = viewModel::liveRemainingMs,
            delayOptions = state.delayOptions,
            extendedMinutes = state.extendedMinutes,
            maxDailyExtendMinutes = state.maxDailyExtendMinutes,
            pendingDelayMinutes = state.pendingDelayMinutes,
            loading = state.loading,
            onExtend = viewModel::requestDelay
        )
    }
}

/**
 * "平板使用计时开始"确认框。
 *
 * 需求：孩子打开平板即开始计时，但必须经孩子确认后方才真正计时。
 * 因此这是模态框——不点"开始计时"就不会 dismiss，避免计时被绕过。
 */
@Composable
private fun UsageStartDialog(
    loading: Boolean,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* 必须孩子确认后才开始计时，禁止点外部/返回关闭 */ },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !loading) {
                Text(if (loading) "正在开始…" else "开始计时")
            }
        },
        title = { Text("平板使用计时开始") },
        text = {
            Text(
                "系统已开始记录你的平板使用时间。到达今日限额后平板会自动进入管控，" +
                    "剩余 10 分钟时可在屏幕顶部申请延时。请合理使用，保护视力与作息。"
            )
        },
        // 关键：禁用返回键与点击外部关闭，确保"确认"是计时起点
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    )
}

/**
 * 屏幕正上方倒计时浮层。
 *
 * - 实时显示 MM:SS 倒计时（每秒刷新，基于服务端基线与本地校准墙钟平滑插值）；
 * - 旁边"延时申请"按钮展开 4 档（15/30/45/60 分钟），点选即向服务端**发起审批工单**（不再立即生效）；
 * - 已存在待审核延时（[pendingDelayMinutes] > 0）时进入只读"待审核"态：隐藏申请入口，杜绝重复提交；
 * - 单日累计已达上限（默认 120 分钟）时隐藏档位，仅保留倒计时。
 *
 * 设计要点：申请是"提交工单"而非"立即延长"，因此 pending 期间不允许再发起，
 * 待家长审批通过后下一次 15s 会话轮询会带回新的 [extendedMinutes]，浮层自动恢复正常。
 */
@Composable
private fun CountdownBanner(
    liveRemainingMs: () -> Long,
    delayOptions: List<Int>,
    extendedMinutes: Int,
    maxDailyExtendMinutes: Int,
    pendingDelayMinutes: Int,
    loading: Boolean,
    onExtend: (Int) -> Unit
) {
    val pending = pendingDelayMinutes > 0

    // 1 秒一次的本地心跳，驱动倒计时数字平滑刷新（不依赖 15s 的会话刷新节奏）
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(1000)
        }
    }

    var showOptions by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // 用校准墙钟（timeProvider.now）与最近一次服务端基线插值，避免孩子改本地时间导致倒计时漂移；
        // tick 仅用于触发每秒重组。
        val liveRemaining = remember(tick) { liveRemainingMs() }
        val (mm, ss) = formatCountdown(liveRemaining)

        Card(
            modifier = Modifier
                .padding(top = 8.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(14.dp)),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "剩余 $mm:$ss",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "后进入管控",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )

                Spacer(Modifier.weight(1f))

                if (pending) {
                    // 已提交审批：展示待审状态并彻底禁用申请入口，避免绕过审核重复提交
                    Text(
                        text = "已申请 +${pendingDelayMinutes}分 · 待审核",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else {
                    TextButton(
                        onClick = { showOptions = !showOptions },
                        enabled = !loading
                    ) {
                        Text("延时申请")
                    }
                }
            }

            AnimatedVisibility(visible = showOptions && !pending) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val canExtend = extendedMinutes < maxDailyExtendMinutes
                    delayOptions.forEach { minutes ->
                        TextButton(
                            onClick = { onExtend(minutes) },
                            enabled = canExtend && !loading
                        ) {
                            Text("+${minutes}分")
                        }
                    }
                }
            }
        }
    }
}

/** 把毫秒格式化为 MM:SS（超过 99 分按 99:59 封顶显示，避免异常值撑破布局） */
private fun formatCountdown(ms: Long): Pair<String, String> {
    val totalSec = (ms / 1000L).coerceAtMost(99 * 60L + 59L)
    val m = (totalSec / 60L).toInt()
    val s = (totalSec % 60L).toInt()
    return "%02d".format(m) to "%02d".format(s)
}
