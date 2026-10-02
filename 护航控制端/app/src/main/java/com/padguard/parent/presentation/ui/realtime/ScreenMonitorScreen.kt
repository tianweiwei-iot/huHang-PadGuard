package com.padguard.presentation.ui.realtime

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val toast = uiState.toast

    LaunchedEffect(toast) {
        if (toast != null) {
            snackbarHostState.showSnackbar(toast)
            viewModel.clearToast()
        }
    }

    // 离开页面即停止孩子端推流，避免后台无谓耗电
    DisposableEffect(Unit) { onDispose { viewModel.stopLive() } }

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
                liveConnected = uiState.liveConnected,
                liveHint = uiState.liveHint,
                updatedAtMillis = uiState.screenshot?.capturedAt,
                resolutionText = uiState.liveImage?.let { b -> "${b.width}×${b.height}" },
                isRecording = uiState.isRecording,
                recordingSeconds = uiState.recordingSeconds,
                onRefresh = viewModel::refreshScreenshot
            )

            ScreenActionRow(
                isRecording = uiState.isRecording,
                remoteLocked = uiState.remoteLocked,
                allowRemoteLock = uiState.settings.allowRemoteLock,
                onCapture = viewModel::captureScreenshot,
                onToggleRecord = viewModel::toggleRecording,
                onLock = viewModel::toggleRemoteLock
            )

            uiState.lastRecordSummary?.let { summary ->
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f)
                    )
                    // 录屏文件由孩子端异步上传，url 到位后才给出回放入口。
                    // /v1/files/{id} 无需鉴权，可直接交给系统播放器（浏览器/视频应用）。
                    if (!uiState.lastRecordUrl.isNullOrBlank()) {
                        OutlinedButton(onClick = { openUrl(context, uiState.lastRecordUrl!!) }) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("回放")
                        }
                    }
                }
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
    liveConnected: Boolean,
    liveHint: String?,
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
                    // 卡住时优先显示真实原因，而不是让家长对着"正在连接…"干等
                    Text(
                        text = liveHint
                            ?: if (fetchingFrame) "正在连接实时画面…" else "等待平板上传实时画面",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = if (liveHint != null) TextAlign.Center else TextAlign.Unspecified,
                        modifier = Modifier.padding(horizontal = 20.dp)
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

            // 左上角状态角标：录屏中（含计时） / 实时在线 / 连接中
            // 呼吸闪烁：绿点与录屏 REC·计时共用同一节奏，未连接时不闪，便于一眼识别"实时进行中"
            val blinkTransition = rememberInfiniteTransition(label = "statusBlink")
            val blinkAlpha by blinkTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 700, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "statusBlinkAlpha"
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                color = when {
                    isRecording -> PadGuardColors.WarningRed
                    liveConnected -> PadGuardColors.OnlineGreen
                    else -> Color(0x88000000)
                },
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    if (isRecording || liveConnected) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(PadGuardColors.OnlineGreen.copy(alpha = blinkAlpha))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (isRecording) {
                        Text(
                            text = "REC",
                            color = PadGuardColors.OnlineGreen.copy(alpha = blinkAlpha),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = TimeFormat.formatDuration(recordingSeconds),
                            color = PadGuardColors.OnlineGreen.copy(alpha = blinkAlpha),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = if (liveConnected) "LIVE" else "连接中",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
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
    remoteLocked: Boolean,
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
        // 锁屏 / 解锁是同一个按钮的两个方向。
        // 只做"锁屏"的话，孩子端锁上之后界面上再也找不到解锁入口，
        // 家长等于把自己关在门外，只能干等孩子端策略自行恢复。
        OutlinedButton(
            onClick = onLock,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            // 解锁必须始终可用：设备正锁着时若因开关关闭而点不了，就再也解不开了
            enabled = remoteLocked || allowRemoteLock,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (remoteLocked) PadGuardColors.WarningRed else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                if (remoteLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (remoteLocked) "解锁" else "锁屏")
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

/**
 * 用系统播放器打开录屏文件。
 *
 * 服务端 `/v1/files/{id}` 无需鉴权，可直接交给外部应用，不必自己下载再配 FileProvider。
 * 找不到可处理的应用时降级为浏览器打开，至少不会点了没反应。
 */
private fun openUrl(context: android.content.Context, url: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
    runCatching {
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
