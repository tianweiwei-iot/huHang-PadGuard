package com.padguard.child.ui.message

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.padguard.core.common.Logger
import com.padguard.child.ui.lock.LockTaskSupport
import com.padguard.child.ui.theme.PadGuardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 管理员消息/公告全屏弹窗（[com.padguard.core.engine.command.EngineEffect.ShowMessage] 落地）。
 *
 * ## 霸屏（blocking=true）必须"真霸屏"
 * 旧实现里 `blocking` 只是让按钮文案从「知道了」变成「我已阅读」——
 * 孩子点一下就关掉了，所谓霸屏形同虚设。
 *
 * 现在霸屏语义是：**在 [durationSec] 内占满屏幕，不能退出、不能操作、不提供任何关闭入口**，
 * 到点由本页自行结束。落地手段与锁屏页一致（见 [com.padguard.child.ui.lock.LockScreenActivity]）：
 * ① `startLockTask()` 屏幕固定，禁掉手势导航/返回/最近任务；
 * ② 状态栏与导航栏沉浸隐藏；
 * ③ 返回键双保险拦截；
 * ④ `onPause` 抢占复位 —— 万一被系统或其他界面盖住，1 秒内重新拉回栈顶。
 *
 * ## 版式要求：「通知」在顶端偏中，正中心留给内容
 * 需求明确要求：顶部居中偏上放「通知」标识，屏幕正中间展示图片/视频/音频/文字，
 * 这样主内容不会被标题挤下去，看起来就是一块"公告板"而不是一个对话框。
 * 因此这里刻意不用居中 Column 堆叠（那会把标题推到中间），
 * 而是用 Box + align(TopCenter) 放通知条、align(Center) 放内容。
 */
class MessageActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private var titleText = ""
    private var bodyText = ""
    private var durationSec = 0
    private var blocking = false
    private var contentType = "TEXT"
    private var mediaUrl = ""
    private var mediaName = ""

    /** 已下载的本地素材文件（退出时清理，避免缓存目录堆积） */
    @Volatile private var downloadedMedia: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        titleText = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        bodyText = intent?.getStringExtra(EXTRA_BODY).orEmpty()
        durationSec = intent?.getIntExtra(EXTRA_DURATION, 0) ?: 0
        blocking = intent?.getBooleanExtra(EXTRA_BLOCKING, false) ?: false
        contentType = intent?.getStringExtra(EXTRA_CONTENT_TYPE) ?: "TEXT"
        mediaUrl = intent?.getStringExtra(EXTRA_MEDIA_URL).orEmpty()
        mediaName = intent?.getStringExtra(EXTRA_MEDIA_NAME).orEmpty()

        // 霸屏不提供任何关闭入口；非阻塞且有时长则到点自动关闭
        // 倒计时以「首次展示」为基准：被系统弹窗打断后 re-arm 重拉本页时不重置，
        // 否则只要来一个系统弹窗，霸屏就永远数不完（实测踩过）。
        val remainMs = beginSession(sessionKey(), durationSec)
        if (remainMs > 0) {
            handler.postDelayed({ finishSafely() }, remainMs)
        }

        // 霸屏期间拦截返回键
        if (blocking) {
            onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        // 吞掉，不允许逃脱
                    }
                }
            )
        }

        setContent {
            PadGuardTheme {
                MessageContent(
                    title = titleText,
                    body = bodyText,
                    blocking = blocking,
                    durationSec = durationSec,
                    contentType = contentType,
                    mediaUrl = mediaUrl,
                    mediaName = mediaName,
                    onDismiss = { finishSafely() }
                )
            }
        }
    }

    private fun showOverLockScreen() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onStart() {
        super.onStart()
        instance = this
    }

    override fun onResume() {
        super.onResume()
        if (blocking) LockTaskSupport.start(this)
        handler.removeCallbacks(rearmRunnable)
    }

    override fun onPause() {
        super.onPause()
        // 霸屏期间被切走 → 立即抢回前台。没有这一层，
        // 一次系统弹窗（比如"允许录制屏幕"）就能让霸屏名存实亡。
        if (blocking) handler.postDelayed(rearmRunnable, REARM_DELAY_MS)
    }

    private val rearmRunnable = Runnable {
        if (!isFinishing && blocking) {
            Logger.w(TAG) { "blocking message pushed to background, re-arming" }
            show(this, titleText, bodyText, durationSec, blocking, contentType, mediaUrl, mediaName)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!blocking) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_HOME ||
            keyCode == KeyEvent.KEYCODE_APP_SWITCH
        ) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun finishSafely() {
        endSession()
        if (blocking) LockTaskSupport.stop(this)
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        runCatching { downloadedMedia?.delete() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MessageActivity"

        // ==================== 霸屏会话（跨实例重建保持倒计时） ====================
        // re-arm 走 show() -> startActivity(CLEAR_TOP)。若目标页被系统回收重建，
        // onCreate 会重新执行：必须能识别「这是同一条消息的重新拉起」，
        // 否则每次重建都从头计时，霸屏就永远不会到点。
        @Volatile private var sessionKey: String = ""
        @Volatile private var deadlineAt: Long = 0L

        /** 返回距截止还剩多少毫秒；durationSec<=0 返回 0（不自动关闭）。 */
        private fun beginSession(key: String, durationSec: Int): Long {
            if (durationSec <= 0) return 0L
            val now = System.currentTimeMillis()
            if (key == sessionKey && deadlineAt > now) return deadlineAt - now
            sessionKey = key
            deadlineAt = now + durationSec * 1000L
            return durationSec * 1000L
        }

        private fun endSession() {
            sessionKey = ""
            deadlineAt = 0L
        }

        private fun MessageActivity.sessionKey(): String =
            listOf(titleText, bodyText, durationSec, blocking, contentType, mediaUrl, mediaName)
                .joinToString("\u0001")
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BODY = "body"
        private const val EXTRA_DURATION = "durationSec"
        private const val EXTRA_BLOCKING = "blocking"
        private const val EXTRA_CONTENT_TYPE = "contentType"
        private const val EXTRA_MEDIA_URL = "mediaUrl"
        private const val EXTRA_MEDIA_NAME = "mediaName"

        private const val REARM_DELAY_MS = 1000L

        @Volatile
        private var instance: MessageActivity? = null

        /** 供外部（如解锁指令）主动关闭霸屏页 */
        fun dismiss(context: Context) {
            endSession()
            instance?.let { activity ->
                LockTaskSupport.stop(activity)
                if (!activity.isFinishing) activity.finish()
            }
        }

        fun show(
            context: Context,
            title: String,
            body: String,
            durationSec: Int,
            blocking: Boolean,
            contentType: String = "TEXT",
            mediaUrl: String = "",
            mediaName: String = ""
        ) {
            val intent = Intent(context, MessageActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                putExtra(EXTRA_DURATION, durationSec)
                putExtra(EXTRA_BLOCKING, blocking)
                putExtra(EXTRA_CONTENT_TYPE, contentType)
                putExtra(EXTRA_MEDIA_URL, mediaUrl)
                putExtra(EXTRA_MEDIA_NAME, mediaName)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            runCatching { context.startActivity(intent) }
                .onFailure { Logger.e(TAG, it) { "failed to show message" } }
        }
    }
}

// ==================== UI ====================

/** 屏幕上部的来源标识文案：让孩子一眼看出这是家长发来的，不是系统弹窗或广告。 */
private const val SOURCE_LABEL = "来自家长的信息"

@Composable
private fun MessageContent(
    title: String,
    body: String,
    blocking: Boolean,
    durationSec: Int,
    contentType: String,
    mediaUrl: String,
    mediaName: String,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // ---------- 屏幕上部：来源标识「来自家长的信息」 ----------
            // 明确来源，避免孩子误以为是系统提示或某个 App 的广告弹窗。
            NoticeBadge(
                text = SOURCE_LABEL,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
            )

            // ---------- 屏幕正中央：消息框 ----------
            // 正文（及素材）统一装进一块圆角卡片里，视觉上就是"一块消息板"，
            // 而不是散落在屏幕中间的文字。
            MessageCard(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.88f)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (title.isNotBlank()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    when {
                        mediaUrl.isNotBlank() && contentType == "IMAGE" ->
                            RemoteImage(mediaUrl, Modifier.fillMaxWidth())

                        mediaUrl.isNotBlank() && contentType == "VIDEO" ->
                            RemoteVideo(
                                url = mediaUrl,
                                modifier = Modifier.fillMaxWidth().height(240.dp),
                                // 霸屏期间不给播放控制条：那等于给了孩子一个可点的出口
                                showControls = !blocking
                            )

                        mediaUrl.isNotBlank() && contentType == "AUDIO" ->
                            RemoteAudio(mediaUrl, mediaName, autoPlay = blocking)

                        else -> {
                            if (body.isNotBlank()) {
                                Text(
                                    text = body,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // 有素材时，正文作为补充说明放在素材下方
                    if (mediaUrl.isNotBlank() && body.isNotBlank()) {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            // ---------- 底部：非霸屏才给关闭入口 ----------
            if (!blocking) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (durationSec <= 0) {
                        TextButton(onClick = onDismiss) { Text(text = "知道了") }
                    }
                }
            } else {
                // 霸屏：不给任何按钮，只倒计时，到点自动关闭
                BlockingFooter(
                    durationSec = durationSec,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 40.dp)
                )
            }

            // ---------- 霸屏期间吞掉一切触摸 ----------
            // LockTask 挡得住导航手势与返回键，挡不住屏幕内的点击。
            // 覆盖一层透明可点击层把触摸全部消费掉，孩子点哪儿都不会有反应。
            if (blocking) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* 吞掉点击 */ }
                )
            }
        }
    }
}

/** 屏幕正中央的消息框：圆角卡片 + 描边 + 内部留白。 */
@Composable
private fun MessageCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        )
    ) {
        Box(modifier = Modifier.padding(24.dp)) { content() }
    }
}

/** 霸屏倒计时脚注：让"还要等多久"可见，避免孩子反复尝试操作。 */
@Composable
private fun BlockingFooter(durationSec: Int, modifier: Modifier = Modifier) {
    if (durationSec <= 0) {
        Text(
            text = "信息发布中，请稍候…",
            modifier = modifier,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
        return
    }
    var remain by remember(durationSec) { mutableIntStateOf(durationSec) }
    LaunchedEffect(durationSec) {
        while (remain > 0) {
            kotlinx.coroutines.delay(1000)
            remain--
        }
    }
    Text(
        text = if (remain > 0) "信息发布中，${remain} 秒后可继续操作" else "信息发布中，请稍候…",
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
    )
}

/** 屏幕上部的来源标识条 */
@Composable
private fun NoticeBadge(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun RemoteImage(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    DisposableEffect(url) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            val file = runCatching { downloadToCache(context, url) }.getOrNull()
            val bmp = file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
            withContext(Dispatchers.Main) {
                if (bmp != null) bitmap = bmp else failed = true
            }
        }
        onDispose { job.cancel() }
    }

    when {
        bitmap != null -> Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "信息发布图片",
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
        failed -> Text("图片加载失败", color = MaterialTheme.colorScheme.error)
        else -> Box(modifier = modifier.height(200.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun RemoteVideo(url: String, modifier: Modifier = Modifier, showControls: Boolean = true) {
    val context = LocalContext.current
    var localPath by remember(url) { mutableStateOf<String?>(null) }

    DisposableEffect(url) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            val file = runCatching { downloadToCache(context, url) }.getOrNull()
            withContext(Dispatchers.Main) { localPath = file?.absolutePath }
        }
        onDispose { job.cancel() }
    }

    if (localPath == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
        return
    }

    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(Uri.parse(localPath))
                // 霸屏时不挂 MediaController：控制条本身就是可点击的交互入口
                if (showControls) setMediaController(MediaController(ctx))
                setOnPreparedListener { it.isLooping = true; start() }
                setOnErrorListener { _, _, _ -> true }
            }
        },
        modifier = modifier
    )
}

@Composable
private fun RemoteAudio(url: String, name: String, autoPlay: Boolean) {
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

/** 把远端素材下载到缓存目录；返回本地文件 */
private fun downloadToCache(context: Context, url: String): File {
    val out = File(context.cacheDir, "msg_" + url.hashCode().toString().replace("-", "n"))
    if (out.exists() && out.length() > 0) return out
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.inputStream.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
    } finally {
        conn.disconnect()
    }
    return out
}
