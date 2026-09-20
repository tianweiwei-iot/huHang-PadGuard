package com.padguard.presentation.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.padguard.domain.model.AppUsage
import com.padguard.presentation.ui.components.AppIcon
import com.padguard.presentation.ui.components.SoftCard
import com.padguard.presentation.ui.components.SoftHeroCard
import com.padguard.presentation.ui.theme.OutlineSoft
import com.padguard.domain.model.DailyUsage
import com.padguard.domain.model.ReportPeriod
import com.padguard.domain.model.UsageStats
import com.padguard.presentation.util.TimeFormat
import com.padguard.presentation.viewmodel.TabletUsageDetailUiState
import com.padguard.presentation.viewmodel.TabletUsageDetailViewModel

/**
 * 平板使用详情页（二级页面）
 *
 * 支持按天 / 按周 / 按月 切换。
 *
 * 展示：
 * 1. 周期内总时长 / 剩余时长 / 周期限额（大圆环）
 * 2. 当前周期应用使用记录（按应用时长倒序）
 * 3. 周期内每日趋势
 * 4. 周期内每日明细
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletUsageDetailScreen(
    deviceId: String,
    onBack: () -> Unit,
    onAppClick: (packageName: String, appName: String, period: ReportPeriod) -> Unit = { _, _, _ -> },
    viewModel: TabletUsageDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(deviceId) {
        if (deviceId.isNotEmpty()) viewModel.load(deviceId)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("平板使用详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (deviceId.isEmpty()) {
            EmptyHint(
                text = "请先绑定设备",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 周期切换
            item { PeriodSwitcher(uiState.period, viewModel::setPeriod) }

            // 2. 顶部圆环
            item { UsageHeaderCard(uiState) }

            // 3. 应用使用记录
            item { SectionTitle(currentAppSectionTitle(uiState.period)) }
            item {
                AppUsageCardList(
                    apps = currentAppUsages(uiState),
                    onAppClick = { app ->
                        onAppClick(app.packageName, app.appName, uiState.period)
                    }
                )
            }

            // 4. 趋势图
            item { SectionTitle(currentTrendTitle(uiState.period)) }
            item { TrendChart(uiState.report?.dailyUsages) }

            // 5. 每日明细
            item { SectionTitle(currentDailyTitle(uiState.period)) }
            uiState.report?.dailyUsages?.let { days ->
                items(days.sortedByDescending { it.date }) { day ->
                    DailyUsageItem(day = day)
                }
            }
        }
    }
}

private fun currentAppSectionTitle(period: ReportPeriod): String = when (period) {
    ReportPeriod.DAILY -> "今日应用使用记录"
    ReportPeriod.WEEKLY -> "本周应用使用记录"
    ReportPeriod.MONTHLY -> "本月应用使用记录"
}

private fun currentTrendTitle(period: ReportPeriod): String = when (period) {
    ReportPeriod.DAILY -> "近 7 天趋势"
    ReportPeriod.WEEKLY -> "本周每日趋势"
    ReportPeriod.MONTHLY -> "本月每日趋势"
}

private fun currentDailyTitle(period: ReportPeriod): String = when (period) {
    ReportPeriod.DAILY -> "近 7 天每日明细"
    ReportPeriod.WEEKLY -> "本周每日明细"
    ReportPeriod.MONTHLY -> "本月每日明细"
}

private fun currentAppUsages(state: TabletUsageDetailUiState): List<AppUsage> = when (state.period) {
    ReportPeriod.DAILY -> state.todayStats?.appUsages ?: emptyList()
    else -> state.report?.topApps ?: emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSwitcher(period: ReportPeriod, onChange: (ReportPeriod) -> Unit) {
    val options = listOf(
        Triple(ReportPeriod.DAILY, "按天", Icons.Default.Today),
        Triple(ReportPeriod.WEEKLY, "按周", Icons.Default.DateRange),
        Triple(ReportPeriod.MONTHLY, "按月", Icons.Default.CalendarMonth)
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        options.forEachIndexed { index, (value, label, icon) ->
            SegmentedButton(
                selected = period == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun UsageHeaderCard(state: TabletUsageDetailUiState) {
    val usedSeconds = state.totalUsageSeconds
    val limitSeconds = state.periodLimitSeconds.coerceAtLeast(1)
    val fraction = (usedSeconds.toFloat() / limitSeconds.toFloat()).coerceIn(0f, 1f)
    val periodLabel = when (state.period) {
        ReportPeriod.DAILY -> "今日平板使用情况"
        ReportPeriod.WEEKLY -> "本周平板使用情况"
        ReportPeriod.MONTHLY -> "本月平板使用情况"
    }
    val periodLimitLabel = when (state.period) {
        ReportPeriod.DAILY -> "每日限额"
        ReportPeriod.WEEKLY -> "本周限额"
        ReportPeriod.MONTHLY -> "本月限额"
    }

    SoftHeroCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(120.dp),
                    strokeWidth = 12.dp,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = TimeFormat.formatDuration(usedSeconds),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "已用",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Spacer(modifier = Modifier.width(24.dp))
            Column {
                Text(
                    text = periodLabel,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (state.overLimit) {
                        "已超出 ${TimeFormat.formatDuration(state.overLimitSeconds)}"
                    } else {
                        "剩余 ${TimeFormat.formatDuration(state.remainingSeconds)}"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "$periodLimitLabel ${TimeFormat.formatDuration(limitSeconds)}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AppUsageCardList(
    apps: List<AppUsage>,
    onAppClick: (AppUsage) -> Unit
) {
    SoftCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        if (apps.isEmpty()) {
            Text(
                text = "暂无应用使用记录",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            return@SoftCard
        }
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            apps.sortedByDescending { app ->
                app.usageSeconds.takeIf { it > 0 } ?: (app.usageMinutes * 60)
            }.forEachIndexed { index, app ->
                AppUsageClickableRow(app = app, onClick = { onAppClick(app) })
                if (index < apps.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = OutlineSoft
                    )
                }
            }
        }
    }
}

@Composable
private fun AppUsageClickableRow(app: AppUsage, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(
            packageName = app.packageName,
            appName = app.appName,
            iconUrl = app.iconUrl,
            size = 40.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = buildString {
                if (app.startTime != null && app.endTime != null) {
                    append(TimeFormat.formatClock(app.startTime))
                    append(" ~ ")
                    append(TimeFormat.formatClock(app.endTime))
                }
            }
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1
                )
            }
        }
        Text(
            text = TimeFormat.formatAppUsageDuration(app.usageSeconds, app.usageMinutes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "查看使用记录",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun TrendChart(dailyUsages: List<DailyUsage>?) {
    SoftCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            if (dailyUsages.isNullOrEmpty()) {
                Text(
                    text = "暂无数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                val max = dailyUsages.maxOf { it.usageMinutes }.coerceAtLeast(1)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    dailyUsages.sortedBy { it.date }.forEach { day ->
                        val h = (day.usageMinutes.toFloat() / max * 120f).coerceAtLeast(6f)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .height(h.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(3.dp)
                                    )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = TimeFormat.formatShortDate(day.date),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyUsageItem(day: DailyUsage) {
    SoftCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = TimeFormat.formatFullDate(day.date),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (day.violationCount > 0) {
                    Text(
                        text = "违规 ${day.violationCount} 次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                text = TimeFormat.formatDurationFromMinutes(day.usageMinutes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
