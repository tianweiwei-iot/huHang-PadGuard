package com.padguard.presentation.ui.realtime

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.domain.model.RecordResolution
import com.padguard.domain.model.ScreenMonitorSettings
import com.padguard.domain.model.ScreenshotData
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.util.TimeFormat
import com.padguard.presentation.viewmodel.ScreenMonitorViewModel
import kotlin.math.roundToInt

private val REFRESH_SECONDS_RANGE = 1f..30f

/**
 * 实时管控 · 屏幕监控
 *
 * 实时显示被管控平板画面（Mock 占位渲染），支持截屏、录屏、锁屏与相关设置。
 * deviceId 由导航路由参数经 SavedStateHandle 注入 ViewModel，页面无需自行透传。
 */
@Composable
fun ScreenMonitorScreen(
    onBack: () -> Unit,
    viewModel: ScreenMonitorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val toast = uiState.toast

    LaunchedEffect(toast) {
        if (toast != null) {
            snackbarHostState.showSnackbar(toast)
            viewModel.clearToast()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { RealtimeTopBar(title = "屏幕监控", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LiveScreenCard(
                liveImage = uiState.liveImage?.asImageBitmap(),
                fetchingFrame = uiState.fetchingFrame,
                updatedAtMillis = uiState.screenshot?.capturedAt,
                resolutionText = uiState.screenshot?.let { s ->
                    if (s.width > 0 && s.height > 0) "${s.width}×${s.height}" else null
                },
                isRecording = uiState.isRecording,
                recordingSeconds = uiState.recordingSeconds,
                onRefresh = viewModel::refreshScreenshot
            )

            ScreenActionRow(
                isRecording = uiState.isRecording,
                allowRemoteLock = uiState.settings.allowRemoteLock,
                onCapture = viewModel::captureScreenshot,
                onToggleRecord = viewModel::toggleRecording,
                onLock = viewModel::lockScreen
            )

            uiState.lastRecordSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            ScreenSettingsCard(
                settings = uiState.settings,
                dirty = uiState.settingsDirty,
                onEdit = viewModel::editSettings,
                onSave = viewModel::saveSettings
            )

            ScreenshotHistoryCard(history = uiState.screenshotHistory)
        }
    }
}

/** 实时画面卡：有画面时渲染被控端最新截图，否则显示占位引导。 */
@Composable
private fun LiveScreenCard(
    liveImage: ImageBitmap?,
    fetchingFrame: Boolean,
    updatedAtMillis: Long?,
    resolutionText: String?,
    isRecording: Boolean,
    recordingSeconds: Int,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (liveImage != null) {
                Image(
                    bitmap = liveImage,
                    contentDescription = "平板实时画面",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                // 采集中的半透明遮罩，给家长"正在取新画面"的反馈
                if (fetchingFrame) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(
                                brush = Brush.linearGradient(listOf(Color(0xFF4A90D9), Color(0xFF07C160))),
                                shape = RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ScreenShare,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "平板实时画面",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (fetchingFrame) "正在获取画面…" else "等待被控端上传画面（首次需在被控端完成授权）",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (updatedAtMillis != null && updatedAtMillis > 0) {
                        Text(
                            text = "更新于 ${TimeFormat.formatClockFromMillis(updatedAtMillis)}",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            if (liveImage == null) {
                // 右上角手动刷新：占位态下家长可主动触发
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "刷新画面",
                        tint = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            // 左上角状态角标：录屏中（含计时） / 实时
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                color = if (isRecording) PadGuardColors.WarningRed else PadGuardColors.OnlineGreen,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = if (isRecording) "REC ${TimeFormat.formatDuration(recordingSeconds)}" else "LIVE",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "刷新画面",
                    tint = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun ScreenActionRow(
    isRecording: Boolean,
    allowRemoteLock: Boolean,
    onCapture: () -> Unit,
    onToggleRecord: () -> Unit,
    onLock: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onCapture,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("截屏")
        }
        FilledTonalButton(
            onClick = onToggleRecord,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = if (isRecording) {
                    PadGuardColors.WarningRed.copy(alpha = 0.15f)
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (isRecording) {
                    PadGuardColors.WarningRed
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            )
        ) {
            Icon(Icons.Default.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (isRecording) "停止" else "录屏")
        }
        OutlinedButton(
            onClick = onLock,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            enabled = allowRemoteLock
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("锁屏")
        }
    }
}

@Composable
private fun ScreenSettingsCard(
    settings: ScreenMonitorSettings,
    dirty: Boolean,
    onEdit: ((ScreenMonitorSettings) -> ScreenMonitorSettings) -> Unit,
    onSave: () -> Unit
) {
    RealtimeCard(title = "屏幕监控设置") {
        SettingSliderRow(
            title = "自动刷新间隔",
            valueText = TimeFormat.formatDuration(settings.autoRefreshSeconds),
            value = settings.autoRefreshSeconds.toFloat(),
            valueRange = REFRESH_SECONDS_RANGE,
            steps = (REFRESH_SECONDS_RANGE.endInclusive - REFRESH_SECONDS_RANGE.start).toInt() - 1,
            onValueChange = { value -> onEdit { it.copy(autoRefreshSeconds = value.roundToInt()) } }
        )

        SettingSwitchRow(
            title = "高清画面",
            subtitle = "开启后画面更清晰，流量消耗相应增加",
            checked = settings.highDefinition,
            onCheckedChange = { value -> onEdit { it.copy(highDefinition = value) } }
        )

        Text(
            text = "录屏画质",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
        SingleChoiceChips(
            options = RecordResolution.values().toList(),
            selected = settings.recordResolution,
            labelOf = { it.label },
            onSelect = { value -> onEdit { it.copy(recordResolution = value) } }
        )

        SettingSwitchRow(
            title = "录屏含设备声音",
            checked = settings.recordWithAudio,
            onCheckedChange = { value -> onEdit { it.copy(recordWithAudio = value) } }
        )

        SettingSwitchRow(
            title = "允许远程锁屏",
            checked = settings.allowRemoteLock,
            onCheckedChange = { value -> onEdit { it.copy(allowRemoteLock = value) } }
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dirty) {
                Text(
                    text = "设置已修改，待下发",
                    style = MaterialTheme.typography.labelSmall,
                    color = PadGuardColors.WarningRed
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onSave, enabled = dirty, shape = RoundedCornerShape(12.dp)) {
                Text("保存并下发")
            }
        }
    }
}

@Composable
private fun ScreenshotHistoryCard(history: List<ScreenshotData>) {
    RealtimeCard(title = "截屏历史") {
        if (history.isEmpty()) {
            Text(
                text = "暂无截屏记录，点击上方「截屏」即可留存当前画面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            return@RealtimeCard
        }
        history.forEachIndexed { index, shot ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = TimeFormat.formatClockFromMillis(shot.capturedAt),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${shot.width}×${shot.height}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (index != history.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
        }
    }
}
