package com.padguard.child.ui.message

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.padguard.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val MEDIA_TAG = "MessageMedia"

/**
 * 判断标题是否只是"来源标识"而非真正的消息标题。
 *
 * 家长端下发的 SHOW_MESSAGE 默认带 `title="来自家长的消息"`，与来源标识语义重复，
 * 详情/弹窗里不应再渲染一次，避免孩子看到两处"来自家长"的提示。
 */
internal fun isSourceLabel(title: String): Boolean {
    val t = title.trim()
    return t == "来自家长的信息" ||
        t == "来自家长的消息" ||
        t.equals("来自家长的信息", ignoreCase = true) ||
        t.equals("来自家长的消息", ignoreCase = true)
}

/**
 * 相对路径（以 `/` 开头）按 baseUrl 的 origin 拼成绝对地址；已是绝对地址则原样返回。
 *
 * 服务端 [com.padguard.server.service.FileStorageService] 默认返回相对路径
 * （如 `/v1/files/{id}`，单机部署下 `padguard.base-url` 留空），
 * 直接 `URL("/v1/files/...")` 会抛 `MalformedURLException`、素材静默加载失败。
 */
internal fun resolveAbsoluteUrl(raw: String, baseUrl: String): String {
    if (!raw.startsWith("/")) return raw
    val schemeSlash = baseUrl.indexOf("://")
    val start = if (schemeSlash >= 0) schemeSlash + 3 else 0
    val slash = baseUrl.indexOf('/', start)
    val origin = if (slash >= 0) baseUrl.substring(0, slash) else baseUrl
    return origin + raw
}

/**
 * 把远端素材下载到缓存目录；返回本地文件。
 *
 * 注意：传入的 [rawUrl] 应当是**已解析的绝对地址**（popup 在 onCreate 里拼过、
 * 详情页在 ViewModel 里用 baseUrl 拼过）。本函数内部再跑一次 [resolveAbsoluteUrl]
 * 仅作兜底（已是绝对地址会原样返回）。HTTP 非 2xx 一律抛异常，交由上层置"加载失败"，
 * 而不是写入一个空文件让孩子端看到一张空白。
 */
internal fun downloadToCache(context: Context, rawUrl: String): File {
    val url = resolveAbsoluteUrl(rawUrl, "")
    val out = File(context.cacheDir, "msg_" + url.hashCode().toString().replace("-", "n"))
    if (out.exists() && out.length() > 0) return out
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code !in 200..299) {
            Logger.w(MEDIA_TAG) { "素材下载失败 HTTP $code: $url" }
            throw java.io.IOException("HTTP $code")
        }
        conn.inputStream.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
    } finally {
        conn.disconnect()
    }
    return out
}

/**
 * 可缩放 / 可全屏的视频播放组件。
 *
 * ## 需求：发布视频默认全屏播放，支持调整窗口画面大小
 * - **默认全屏**：[defaultFullscreen]=true 时视频以 16:9 占满容器宽度（在已是全屏沉浸的
 *   霸屏页 / 详情页里即表现为"铺满可视区"的大画面），右上角提供「缩小」入口。
 * - **窗口缩放**：点「缩小」进入窗口模式后，视频收成一块可拖拽缩放的浮窗，
 *   右下角手柄按住拖动即可改宽高（160×120 ~ 640×480 之间），右上角「全屏」恢复铺满。
 *
 * 霸屏（blocking）期间整页触摸被吞，缩放手柄不可点属正常；非霸屏场景与「消息」详情页
 * 均可自由缩放。
 */
@Composable
internal fun ResizableVideo(
    url: String,
    modifier: Modifier = Modifier,
    defaultFullscreen: Boolean = true,
    showControls: Boolean = true,
    onDuration: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    var localPath by remember(url) { mutableStateOf<String?>(null) }
    var error by remember(url) { mutableStateOf(false) }

    var fullscreen by remember { mutableStateOf(defaultFullscreen) }
    var boxW by remember { mutableStateOf(360.dp) }
    var boxH by remember { mutableStateOf(240.dp) }

    DisposableEffect(url) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            val file = runCatching { downloadToCache(context, url) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (file != null) localPath = file.absolutePath else error = true
            }
        }
        onDispose { job.cancel() }
    }

    when {
        localPath == null -> Box(
            modifier = modifier.fillMaxWidth().height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }

        error -> Text(
            text = "视频加载失败",
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth()
        )

        else -> {
            if (fullscreen) {
                // 不再强制 fillMaxWidth：由调用方决定铺满范围（信息发布弹窗传 fillMaxSize 铺满全屏，
                // 消息详情页传 fillMaxWidth 保持宽度铺满、高度按视频比例自适应）。
                Box(modifier = modifier) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(Uri.parse(localPath))
                                if (showControls) setMediaController(MediaController(ctx))
                                setOnPreparedListener {
                                    it.isLooping = true
                                    val dur = runCatching { it.duration / 1000 }.getOrDefault(0)
                                    if (dur > 0) onDuration(dur)
                                    start()
                                }
                                setOnErrorListener { _, _, _ -> true }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    ResizeToggle(
                        fullscreen = true,
                        onToggle = { fullscreen = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    )
                }
            } else {
                Box(
                    modifier = modifier
                        .size(boxW, boxH)
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(Uri.parse(localPath))
                                if (showControls) setMediaController(MediaController(ctx))
                                setOnPreparedListener {
                                    it.isLooping = true
                                    val dur = runCatching { it.duration / 1000 }.getOrDefault(0)
                                    if (dur > 0) onDuration(dur)
                                    start()
                                }
                                setOnErrorListener { _, _, _ -> true }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    ResizeToggle(
                        fullscreen = false,
                        onToggle = { fullscreen = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                    )
                    // 右下角拖拽缩放手柄
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(34.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(6.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    boxW = (boxW + dragAmount.x.dp).coerceIn(160.dp, 640.dp)
                                    boxH = (boxH + dragAmount.y.dp).coerceIn(120.dp, 480.dp)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⤡", color = Color.White, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

/**
 * 远端图片渲染（下载后解码为 Bitmap）。
 * 与弹窗共用 [downloadToCache]，避免重复实现；详情页也走同一套加载逻辑。
 */
@Composable
internal fun RemoteImage(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    DisposableEffect(url) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            val file = runCatching { downloadToCache(context, url) }.getOrNull()
            val bmp = file?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
            withContext(Dispatchers.Main) {
                if (bmp != null) bitmap = bmp else failed = true
            }
        }
        onDispose { job.cancel() }
    }

    when {
        bitmap != null -> androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "信息发布图片",
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
        failed -> Text("图片加载失败", color = MaterialTheme.colorScheme.error)
        else -> Box(modifier = modifier.height(200.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
    }
}

/**
 * 远端语音渲染（自动播放 + 时长回填）。
 * 详情页与弹窗共用，保证"孩子随时回看语音通知"也能正常播放。
 */
@Composable
internal fun RemoteAudio(url: String, name: String, autoPlay: Boolean, onDuration: (Int) -> Unit = {}) {
    val context = LocalContext.current
    var state by remember(url) { mutableStateOf(if (autoPlay) "播放中…" else "音频素材") }

    DisposableEffect(url) {
        val player = MediaPlayer()
        val job = CoroutineScope(Dispatchers.IO).launch {
            val file = runCatching { downloadToCache(context, url) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (file == null) {
                    state = "音频加载失败"
                } else {
                    runCatching {
                        player.setDataSource(file.absolutePath)
                        player.prepare()
                        if (autoPlay) player.start()
                        else state = "音频素材：${name.ifBlank { "未命名" }}"
                        val dur = runCatching { player.duration / 1000 }.getOrDefault(0)
                        if (dur > 0) onDuration(dur)
                    }.onFailure { state = "音频播放失败" }
                }
            }
        }
        onDispose {
            job.cancel()
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "♪",
            fontSize = 48.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (state == "播放中…") "正在播放：${name.ifBlank { "语音通知" }}" else state,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/** 全屏 / 缩小切换按钮（浮在视频右上角） */
@Composable
private fun ResizeToggle(
    fullscreen: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shadowElevation = 2.dp
    ) {
        IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = if (fullscreen) "缩小" else "全屏",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
