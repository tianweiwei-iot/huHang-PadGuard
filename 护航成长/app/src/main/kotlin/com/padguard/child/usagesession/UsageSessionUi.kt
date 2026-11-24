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
 * - 「计时开始」确认已统一收敛到全屏系统浮层 [UsageStartOverlay]（由 [com.padguard.child.service.GuardService]
 *   在 [UsageSessionManager.UiState.needsStartConfirm] 为真且亮屏/已绑定时拉起），本宿主不再重复渲染确认框；
 * - [UsageSessionManager.UiState.countdownActive] 为真 → 屏幕正上方显示 10 分钟倒计时条 + 延时申请。
 *
 * 放在 [com.padguard.child.ui.nav.PadGuardApp] 内、NavHost 之上，因此无论停在哪个 Tab 都能看到。
 */
@Composable
fun UsageSessionHost(viewModel: UsageSessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 「计时开始」确认已统一收敛到全屏系统浮层 UsageStartOverlay（由 GuardService 在
    // needsStartConfirm 为真且亮屏/已绑定时拉起），无论 App 是否在前台都能提示孩子确认。
    // 这里只承载屏幕正上方的倒计时浮层，不再重复渲染确认框（否则会与系统浮层叠两层）。
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
