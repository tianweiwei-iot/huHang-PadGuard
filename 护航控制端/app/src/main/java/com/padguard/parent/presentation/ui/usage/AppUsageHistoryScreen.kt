package com.padguard.presentation.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.padguard.domain.model.AppUsageHistoryEntry
import com.padguard.presentation.ui.components.AppIcon
import com.padguard.presentation.ui.components.SoftCard
import com.padguard.presentation.ui.components.SoftHeroCard
import com.padguard.domain.model.ReportPeriod
import com.padguard.presentation.util.TimeFormat
import com.padguard.presentation.viewmodel.AppUsageHistoryViewModel

/**
 * 应用使用记录子页（三级页面）
 *
 * 展示某应用在指定周期（按天/按周/按月）内每次"打开 → 关闭"的使用记录。
 * 每条记录一行：日期 | 开始时间 | 结束时间 | 时长
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUsageHistoryScreen(
    deviceId: String,
    packageName: String,
    appName: String,
    period: ReportPeriod,
    onBack: () -> Unit,
    viewModel: AppUsageHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(deviceId, packageName, period) {
        if (deviceId.isNotEmpty() && packageName.isNotEmpty()) {
            viewModel.load(deviceId, packageName, appName, period)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.appName.isNotEmpty()) "${uiState.appName} · 使用记录" else "使用记录",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SummaryCard(packageName = packageName, appName = uiState.appName, period = uiState.period, totalSeconds = uiState.totalSeconds, count = uiState.entries.size) }

            if (uiState.entries.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (uiState.isLoading) "加载中..." else (uiState.error ?: "暂无使用记录"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                items(uiState.entries) { entry ->
                    HistoryRow(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(packageName: String, appName: String, period: ReportPeriod, totalSeconds: Int, count: Int) {
    val periodLabel = when (period) {
        ReportPeriod.DAILY -> "今日"
        ReportPeriod.WEEKLY -> "本周"
        ReportPeriod.MONTHLY -> "本月"
    }
    SoftHeroCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                packageName = packageName,
                appName = appName,
                iconUrl = null,
                size = 48.dp
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (appName.isNotEmpty()) "$appName · $periodLabel" else periodLabel,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = TimeFormat.formatDuration(totalSeconds),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "共 $count 次打开使用",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: AppUsageHistoryEntry) {
    SoftCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.width(72.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = TimeFormat.formatFullDate(entry.date),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = TimeFormat.formatWeekday(entry.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = TimeFormat.formatClock(entry.startTime),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
            Text(
                text = " ~ ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                text = TimeFormat.formatClock(entry.endTime),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = TimeFormat.formatDuration(entry.usageSeconds),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.width(110.dp),
                textAlign = TextAlign.End
            )
        }
    }
}
