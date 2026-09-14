package com.padguard.presentation.ui.realtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.domain.model.MessageContentType
import com.padguard.domain.model.PublishedMessage
import com.padguard.presentation.util.TimeFormat
import com.padguard.presentation.viewmodel.MessagePublishViewModel
import kotlin.math.roundToInt

private val DISPLAY_SECONDS_RANGE = 5f..300f

/**
 * 实时管控 · 信息发布
 *
 * 支持向被管控平板实时发布文字 / 图片 / 视频 / 声音，可设置显示时长并支持霸屏显示。
 * deviceId 由导航路由参数经 SavedStateHandle 注入 ViewModel。
 */
@Composable
fun MessagePublishScreen(
    onBack: () -> Unit,
    viewModel: MessagePublishViewModel = hiltViewModel()
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
        topBar = { RealtimeTopBar(title = "信息发布", onBack = onBack) },
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
            Text(
                text = "选择内容类型",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            SingleChoiceChips(
                options = MessageContentType.values().toList(),
                selected = uiState.contentType,
                labelOf = { it.label },
                onSelect = viewModel::selectContentType,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            PublishEditorCard(
                contentType = uiState.contentType,
                text = uiState.text,
                mediaName = uiState.mediaName,
                onTextChange = viewModel::updateText,
                onPickMedia = viewModel::pickMedia,
                onClearMedia = viewModel::clearMedia
            )

            DisplaySettingsCard(
                displaySeconds = uiState.displaySeconds,
                fullScreen = uiState.fullScreen,
                playAudio = uiState.playAudio,
                showAudioOption = uiState.contentType == MessageContentType.AUDIO ||
                    uiState.contentType == MessageContentType.VIDEO,
                onDisplaySecondsChange = viewModel::updateDisplaySeconds,
                onFullScreenChange = viewModel::toggleFullScreen,
                onPlayAudioChange = viewModel::togglePlayAudio
            )

            Button(
                onClick = viewModel::publish,
                enabled = uiState.canPublish && !uiState.isPublishing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (uiState.isPublishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (uiState.fullScreen) "立即霸屏发布" else "立即发布")
            }

            if (!uiState.canPublish) {
                Text(
                    text = if (uiState.contentType == MessageContentType.TEXT) {
                        "请先输入要发布的文字内容"
                    } else {
                        "请先选择待发布的${uiState.contentType.label}素材"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            PublishedHistoryCard(history = uiState.history)
        }
    }
}

@Composable
private fun PublishEditorCard(
    contentType: MessageContentType,
    text: String,
    mediaName: String?,
    onTextChange: (String) -> Unit,
    onPickMedia: () -> Unit,
    onClearMedia: () -> Unit
) {
    RealtimeCard(title = "内容编辑") {
        if (contentType == MessageContentType.TEXT) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                label = { Text("文字内容") },
                placeholder = { Text("输入要发送到平板的内容") }
            )
            return@RealtimeCard
        }

        if (mediaName == null) {
            OutlinedButton(
                onClick = onPickMedia,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("选择${contentType.label}素材")
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = mediaName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearMedia) { Text("移除") }
                }
            }
            TextButton(onClick = onPickMedia, contentPadding = PaddingValues(0.dp)) {
                Text("重新选择素材")
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            label = { Text("附加说明") },
            placeholder = { Text("可附加一句文字说明（选填）") }
        )

        if (mediaName == null) {
            Text(
                text = "Mock 阶段使用占位素材打通发布链路，接入后端后改为系统相册 / 文件选择器。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun DisplaySettingsCard(
    displaySeconds: Int,
    fullScreen: Boolean,
    playAudio: Boolean,
    showAudioOption: Boolean,
    onDisplaySecondsChange: (Int) -> Unit,
    onFullScreenChange: (Boolean) -> Unit,
    onPlayAudioChange: (Boolean) -> Unit
) {
    RealtimeCard(title = "显示设置") {
        SettingSliderRow(
            title = "平板端显示时长",
            valueText = TimeFormat.formatDuration(displaySeconds),
            value = displaySeconds.toFloat(),
            valueRange = DISPLAY_SECONDS_RANGE,
            steps = 58,
            onValueChange = { value -> onDisplaySecondsChange(value.roundToInt()) }
        )
        SettingSwitchRow(
            title = "霸屏显示",
            subtitle = "开启后将全屏覆盖平板画面，孩子无法忽略",
            checked = fullScreen,
            onCheckedChange = onFullScreenChange
        )
        if (showAudioOption) {
            SettingSwitchRow(
                title = "播放声音",
                checked = playAudio,
                onCheckedChange = onPlayAudioChange
            )
        }
    }
}

@Composable
private fun PublishedHistoryCard(history: List<PublishedMessage>) {
    RealtimeCard(title = "已发布记录") {
        if (history.isEmpty()) {
            Text(
                text = "暂无发布记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            return@RealtimeCard
        }
        history.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = messageTypeIcon(item.contentType),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(item.contentType.label)
                            append(" · 显示 ")
                            append(TimeFormat.formatDuration(item.displaySeconds))
                            if (item.fullScreen) append(" · 霸屏")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = TimeFormat.formatClockFromMillis(item.publishedAt),
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

private fun messageTypeIcon(type: MessageContentType): ImageVector = when (type) {
    MessageContentType.TEXT -> Icons.Default.TextFields
    MessageContentType.IMAGE -> Icons.Default.Image
    MessageContentType.VIDEO -> Icons.Default.Videocam
    MessageContentType.AUDIO -> Icons.Default.VolumeUp
}
