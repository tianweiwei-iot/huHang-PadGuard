package com.padguard.presentation.ui.realtime

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.domain.model.MessageContentType
import com.padguard.domain.model.PublishedMessage
import com.padguard.presentation.util.TimeFormat
import com.padguard.presentation.viewmodel.MessagePublishViewModel
import kotlinx.coroutines.launch
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // 真实本地素材选取：按内容类型给不同 MIME，让系统只展示可播/可看的文件。
    // 早期这里是写死的 mock 地址，孩子端拿到一个不存在的域名，表现为"发布成功却什么都没有"。
    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { readMedia(context, uri) }
                .onSuccess { (name, mime, bytes) ->
                    viewModel.attachMedia(bytes = bytes, fileName = name, contentType = mime)
                }
                .onFailure {
                    snackbarHostState.showSnackbar("读取素材失败：${it.message}")
                }
        }
    }

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
                uploading = uiState.isUploading,
                onTextChange = viewModel::updateText,
                onPickMedia = { mediaPicker.launch(mimeFor(uiState.contentType)) },
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
                enabled = uiState.canPublish && !uiState.isPublishing && !uiState.isUploading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (uiState.isPublishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(6.dp))
                // 发布按钮是这一页的主操作，字号同步放大
                Text(
                    if (uiState.fullScreen) "立即霸屏发布" else "立即发布",
                    fontSize = 18.sp
                )
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
    uploading: Boolean,
    onTextChange: (String) -> Unit,
    onPickMedia: () -> Unit,
    onClearMedia: () -> Unit
) {
    // 这一页是家长实际"写给孩子看"的地方：正文默认字号偏小在平板上很难看清，
    // 尤其是边想边写的时候。这里整体上调一档，输入框内文用 17sp。
    val editorTextStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp)

    RealtimeCard(title = "内容编辑") {
        if (contentType == MessageContentType.TEXT) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 8,
                textStyle = editorTextStyle,
                label = { Text("文字内容", fontSize = 15.sp) },
                placeholder = { Text("输入要发送到平板的内容", fontSize = 15.sp) }
            )
            return@RealtimeCard
        }

        if (uploading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text("素材上传中…", fontSize = 15.sp)
            }
        } else if (mediaName == null) {
            OutlinedButton(
                onClick = onPickMedia,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("从本机选择${contentType.label}素材", fontSize = 16.sp)
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = mediaName,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearMedia) { Text("移除", fontSize = 15.sp) }
                }
            }
            TextButton(onClick = onPickMedia, contentPadding = PaddingValues(0.dp)) {
                Text("重新选择素材", fontSize = 15.sp)
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            textStyle = editorTextStyle,
            label = { Text("附加说明", fontSize = 15.sp) },
            placeholder = { Text("可附加一句文字说明（选填）", fontSize = 15.sp) }
        )

        Text(
            text = "素材会从本机上传到服务端，孩子端收到后直接展示。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

/** 各内容类型对应的系统选择过滤器 */
private fun mimeFor(type: MessageContentType): String = when (type) {
    MessageContentType.TEXT -> "*/*"
    MessageContentType.IMAGE -> "image/*"
    MessageContentType.VIDEO -> "video/*"
    MessageContentType.AUDIO -> "audio/*"
}

/**
 * 读取所选素材：拿到字节、展示用文件名与真实 MIME。
 *
 * MIME 优先用 ContentResolver 查到的值，查不到再按扩展名兜底：
 * 部分文件管理器返回通配类型，原样上传后服务端存下的类型不可播，
 * 孩子端拿到类型错误的音频会直接播放失败且没有任何提示。
 */
private suspend fun readMedia(
    context: android.content.Context,
    uri: android.net.Uri
): Triple<String, String, ByteArray> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val resolver = context.contentResolver
    val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    } ?: "素材_${System.currentTimeMillis()}"

    val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() && it != "*/*" }
        ?: guessMime(name)

    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("无法读取所选文件")
    Triple(name, mime, bytes)
}

private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "mp4" -> "video/mp4"
    "3gp" -> "video/3gpp"
    "webm" -> "video/webm"
    "mp3" -> "audio/mpeg"
    "m4a", "aac" -> "audio/mp4"
    "wav" -> "audio/wav"
    "ogg" -> "audio/ogg"
    else -> "application/octet-stream"
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
