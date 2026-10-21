package com.padguard.child.ui.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.util.DISPLAY_TIME_ZONE
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息详情。
 *
 * 家长发布的信息（文字 / 图片 / 视频 / 语音）都从这里查看：
 * - 视频默认全屏播放（[ResizableVideo] 的 [defaultFullscreen]=true），并支持窗口缩放；
 * - 图片 / 语音复用弹窗同一套渲染（[RemoteImage] / [RemoteAudio]）；
 * - 进入即标记已读（由 [MessageDetailViewModel.load] 调用 [com.padguard.core.data.repository.MessageRepository.markRead]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    messageId: String,
    onBack: () -> Unit,
    viewModel: MessageDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(messageId) { viewModel.load(messageId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "消息详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            val s = state
            if (s == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "消息不存在或已删除",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
                return@Surface
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (s.title.isNotBlank() && !isSourceLabel(s.title)) {
                    Text(
                        text = s.title,
                        style = MaterialTheme.typography.headlineSmall
                    )
                } else {
                    Text(
                        text = "来自家长的信息",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                Text(
                    text = formatTime(s.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )

                // ---------- 媒体区 ----------
                when (s.contentType) {
                    "IMAGE" -> if (s.mediaUrl.isNotBlank()) {
                        RemoteImage(url = s.mediaUrl, modifier = Modifier.fillMaxWidth(), downloader = viewModel.downloader)
                    }
                    "VIDEO" -> if (s.mediaUrl.isNotBlank()) {
                        ResizableVideo(
                            url = s.mediaUrl,
                            modifier = Modifier.fillMaxWidth(),
                            defaultFullscreen = true,
                            showControls = true,
                            downloader = viewModel.downloader
                        )
                    }
                    "AUDIO" -> if (s.mediaUrl.isNotBlank()) {
                        RemoteAudio(url = s.mediaUrl, name = s.mediaName, autoPlay = true, downloader = viewModel.downloader)
                    }
                }

                // ---------- 正文 ----------
                if (s.body.isNotBlank()) {
                    Text(
                        text = s.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// 显式钉死北京时间：孩子端设备时区被改到非北京时间时，
// 若跟随设备时区格式化，同一条消息的显示时间会与服务端记录（Asia/Shanghai）错位。
private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .apply { timeZone = DISPLAY_TIME_ZONE }
        .format(Date(millis))
