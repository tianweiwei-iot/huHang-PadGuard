package com.padguard.child.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.ui.components.Glyph
import com.padguard.child.ui.components.GlyphKind

/**
 * 今日使用概览（首屏）。
 *
 * 视觉层次自上而下：
 * 1. 用户卡片：被管控人称呼 + 设备状态徽标
 * 2. 今日使用主区：已用 / 限额 进度 + 剩余时间
 * 3. 今日计划时间轴：可学习 / 锁机 时段
 * 4. 最近拦截：最近 5 条
 * 5. 申请临时解锁入口
 */
@Composable
fun HomeScreen(
    onRequestUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onRequestUnlock = onRequestUnlock,
        modifier = modifier
    )
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onRequestUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            UserCard(
                studentName = state.studentName,
                statusLabel = state.statusLabel,
                isOnline = state.isOnline
            )
            TodayUsageCard(
                usedMinutes = state.usedMinutes,
                quotaMinutes = state.quotaMinutes,
                remainingMinutes = state.remainingMinutes
            )
            ScheduleTimelineCard(
                items = state.scheduleItems,
                nowLabel = state.nowLabel
            )
            RecentBlocksCard(items = state.recentBlocks)
            UnlockRequestEntry(
                remainingMinutes = state.remainingMinutes,
                onRequestUnlock = onRequestUnlock
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun UserCard(
    studentName: String,
    statusLabel: String,
    isOnline: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = studentName.take(1).ifBlank { "孩" },
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (studentName.isBlank()) "未命名孩子" else studentName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "家长在管",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            StatusPill(label = statusLabel, isOnline = isOnline)
        }
    }
}

@Composable
private fun StatusPill(label: String, isOnline: Boolean) {
    val bg = if (isOnline) {
        MaterialTheme.colorScheme.primary
    } else {
        Color(0xFFB0BEC5)
    }
    Surface(
        color = bg,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
    }
}

@Composable
private fun TodayUsageCard(
    usedMinutes: Int,
    quotaMinutes: Int,
    remainingMinutes: Int
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "今日使用",
                style = MaterialTheme.typography.titleMedium
            )
            if (quotaMinutes <= 0) {
                Text(
                    text = "暂未设置使用限额",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                val progress = if (quotaMinutes > 0) {
                    (usedMinutes.toFloat() / quotaMinutes.toFloat()).coerceIn(0f, 1f)
                } else 0f
                Text(
                    text = "${usedMinutes} / ${quotaMinutes} 分钟",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )
                Text(
                    text = if (remainingMinutes > 0) "还可使用 $remainingMinutes 分钟" else "今日额度已用完",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ScheduleTimelineCard(
    items: List<ScheduleSlotUi>,
    nowLabel: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "今日计划", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "现在 $nowLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (items.isEmpty()) {
                Text(
                    text = "今日没有时段限制，可全天自由使用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                items.forEach { slot -> ScheduleSlotRow(slot = slot) }
            }
        }
    }
}

@Composable
private fun ScheduleSlotRow(slot: ScheduleSlotUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
        ) {
            Surface(
                color = if (slot.isLocked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.fillMaxSize()
            ) {}
        }
        Text(
            text = slot.timeRange,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = slot.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun RecentBlocksCard(items: List<RecentBlockUi>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "最近拦截", style = MaterialTheme.typography.titleMedium)
            if (items.isEmpty()) {
                Text(
                    text = "今天还没有拦截记录，做得很好！",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                items.forEach { block ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Glyph(kind = GlyphKind.BLOCK, size = 16.dp)
                        Text(
                            text = block.appName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = block.timeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnlockRequestEntry(
    remainingMinutes: Int,
    onRequestUnlock: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (remainingMinutes > 0) "还需要更多时间？" else "今天的时间已经用完了",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "可以向家长发起一次临时解锁申请，说明理由，等待同意后生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
            Button(
                onClick = onRequestUnlock,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("申请临时解锁")
            }
        }
    }
}
