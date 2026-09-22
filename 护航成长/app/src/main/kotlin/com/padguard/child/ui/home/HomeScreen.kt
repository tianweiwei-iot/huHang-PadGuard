package com.padguard.child.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
                nowLabel = state.nowLabel,
                nowMinutes = state.nowMinutes
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

// ==================== 今日计划 · 立体游标卡尺时间轴 ====================

/** 尺身青绿渐变：左浅右深，满足"从左向右依次渐变加深" */
private val CaliperBeamLight = Color(0xFFB7F2DF)
private val CaliperBeamMid = Color(0xFF3FC0A2)
private val CaliperBeamDeep = Color(0xFF0A5F52)

/** 锁机时段：尺身上挖出的冷色凹槽，与"可用"的亮面形成量具刻度感 */
private val CaliperGroove = Color(0xFF0B2E2A)
private val CaliperJawLight = Color(0xFFF2FFFB)
private val CaliperJawDeep = Color(0xFF0A5F52)

private const val MINUTES_PER_DAY = 24 * 60

@Composable
private fun ScheduleTimelineCard(
    items: List<ScheduleSlotUi>,
    nowLabel: String,
    nowMinutes: Int
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
                CaliperTimeline(items = items, nowMinutes = nowMinutes)
                CaliperLegend(items = items)
            }
        }
    }
}

/**
 * 立体版游标卡尺时间轴（0:00 – 24:00）。
 *
 * 自上而下三件事：
 * - 尺身：整根横杆铺青绿横向渐变（左浅右深）+ 上高光/下阴影 + 落地投影，做出金属量具的立体感；
 * - 凹槽：锁机时段在尺身上以冷色深槽表示，"可用"则保持亮面 —— 一眼看出全天哪些时段被锁；
 * - 刻度与游标：整点短刻度、每 3 小时长刻度带数字；当前时刻是一枚贯通的游标尺框，
 *   读数见卡片右上角"现在 HH:MM"。
 */
@Composable
private fun CaliperTimeline(
    items: List<ScheduleSlotUi>,
    nowMinutes: Int
) {
    val beamTop = 8.dp
    val beamHeight = 20.dp
    val tickGap = 2.dp
    val majorTick = 9.dp
    val minorTick = 5.dp
    val labelTop = beamTop + beamHeight + tickGap + majorTick + 2.dp
    val totalHeight = labelTop + 16.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(totalHeight)
    ) {
        val width = maxWidth
        val tickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val top = beamTop.toPx()
            val h = beamHeight.toPx()
            val radius = CornerRadius(h / 2f)

            // 落地投影：让尺身"浮"在卡片上
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.10f),
                topLeft = Offset(0f, top + 2.dp.toPx()),
                size = Size(w, h),
                cornerRadius = radius
            )
            // 尺身：青绿渐变，从左向右依次加深
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(CaliperBeamLight, CaliperBeamMid, CaliperBeamDeep),
                    startX = 0f,
                    endX = w
                ),
                topLeft = Offset(0f, top),
                size = Size(w, h),
                cornerRadius = radius
            )
            // 立体高光/阴影：上半受光、下半转暗，形成圆柱感
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.45f),
                        Color.White.copy(alpha = 0.06f),
                        Color.Black.copy(alpha = 0.28f)
                    )
                ),
                topLeft = Offset(0f, top),
                size = Size(w, h),
                cornerRadius = radius
            )

            // 锁机时段凹槽
            lockedRanges(items).forEach { (start, end) ->
                val x0 = w * (start.toFloat() / MINUTES_PER_DAY)
                val x1 = w * (end.toFloat() / MINUTES_PER_DAY)
                val bandW = x1 - x0
                if (bandW > 1f) {
                    drawRoundRect(
                        color = CaliperGroove.copy(alpha = 0.92f),
                        topLeft = Offset(x0, top),
                        size = Size(bandW, h),
                        cornerRadius = CornerRadius(minOf(h / 2f, bandW / 2f))
                    )
                    // 凹槽内阴影，强化"凹进去"的立体关系
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)
                        ),
                        topLeft = Offset(x0, top),
                        size = Size(bandW, h),
                        cornerRadius = CornerRadius(minOf(h / 2f, bandW / 2f))
                    )
                }
            }

            // 刻度：整点短刻度，每 3 小时长刻度
            val tickBase = top + h + tickGap.toPx()
            for (minute in 0..MINUTES_PER_DAY step 60) {
                val x = w * (minute.toFloat() / MINUTES_PER_DAY)
                val isMajor = minute % 180 == 0
                val len = (if (isMajor) majorTick else minorTick).toPx()
                drawLine(
                    color = if (isMajor) tickColor.copy(alpha = 0.75f) else tickColor.copy(alpha = 0.35f),
                    start = Offset(x, tickBase),
                    end = Offset(x, tickBase + len),
                    strokeWidth = (if (isMajor) 1.4f else 0.9f).dp.toPx()
                )
            }

            // 游标：当前时刻的尺框 + 贯通指针
            if (nowMinutes in 0..MINUTES_PER_DAY) {
                val x = w * (nowMinutes.toFloat() / MINUTES_PER_DAY)
                val jawW = 11.dp.toPx()
                val jawH = h + 12.dp.toPx()
                val jawTop = top - 6.dp.toPx()

                drawLine(
                    color = Color.White.copy(alpha = 0.95f),
                    start = Offset(x, top - 2.dp.toPx()),
                    end = Offset(x, top + h + 2.dp.toPx()),
                    strokeWidth = 1.6.dp.toPx()
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(CaliperJawLight, CaliperJawDeep)),
                    topLeft = Offset(x - jawW / 2f, jawTop),
                    size = Size(jawW, jawH),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
                // 指向刻度的三角指针
                val pointer = Path().apply {
                    moveTo(x - 5.dp.toPx(), top + h)
                    lineTo(x + 5.dp.toPx(), top + h)
                    lineTo(x, top + h + 7.dp.toPx())
                    close()
                }
                drawPath(pointer, color = CaliperJawDeep)
            }
        }

        // 小时数字：精确定位到刻度正下方（每 3 小时一个）
        for (hour in 0..24 step 3) {
            Box(
                modifier = Modifier
                    .offset(x = width * (hour / 24f) - 16.dp, y = labelTop)
                    .width(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = hour.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

/** 把锁机时段摊平成 0..1440 的区间；跨零点拆成 [start,1440] + [0,end] 两段 */
private fun lockedRanges(items: List<ScheduleSlotUi>): List<Pair<Int, Int>> =
    items.filter { it.isLocked }.flatMap { slot ->
        val start = slot.startMinutes.coerceIn(0, MINUTES_PER_DAY)
        val end = slot.endMinutes.coerceIn(0, MINUTES_PER_DAY)
        when {
            slot.crossesMidnight || end < start -> listOf(start to MINUTES_PER_DAY, 0 to end)
            end > start -> listOf(start to end)
            else -> emptyList()
        }
    }

/** 图例：色块 + 时段 + 可用/休息（进行中的时段额外标注） */
@Composable
private fun CaliperLegend(items: List<ScheduleSlotUi>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { slot ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (slot.isLocked) CaliperGroove else CaliperBeamMid)
                )
                Text(
                    text = slot.timeRange,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                if (slot.isCurrent) {
                    Text(
                        text = "进行中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = slot.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
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
