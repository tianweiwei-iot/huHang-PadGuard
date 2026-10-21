package com.padguard.child.ui.message

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padguard.child.ui.theme.PadGuardTheme
import com.padguard.core.common.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 信息发布「系统级置顶」渲染控制器（问题三·被锁屏遮挡的治本方案）。
 *
 * 普通 Activity（[MessageActivity]）受任务栈与锁屏 Activity 的前后顺序影响，
 * 在锁屏态可能被 [com.padguard.child.ui.lock.LockScreenActivity] 盖住，
 * 导致「孩子看不到家长发来的内容」。
 *
 * 本控制器改用 [WindowManager] 的 [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 * 窗口：该类型位于**所有 Activity（含锁屏、含系统 keyguard）之上**，真正满足
 * 「置于所有应用最顶层（含锁屏之上）」的强诉求。
 *
 * 权限与回退：
 * - 未授予 [android.Manifest.permission.SYSTEM_ALERT_WINDOW] 时无法添加悬浮窗，
 *   自动回退到 [MessageActivity.show]（方案 A，锁屏内承载），保证可用性；
 * - 任意异常均被收口，绝不因悬浮窗失败而丢失消息。
 */
@Singleton
class MessageOverlayController @Inject constructor(
    private val mediaDownloader: MediaDownloader
) {
    /** 系统级悬浮窗权限是否就绪 */
    fun hasOverlayPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * 展示一条家长下发的信息。
     *
     * @param blocking 是否霸屏：霸屏时铺满全屏、吞掉触摸、到点自动关闭；
     *                 非霸屏时仍置顶展示并提供「知道了」关闭入口（兜底 10s 自动消失，避免长时间遮挡）。
     */
    fun show(
        context: Context,
        title: String,
        body: String,
        durationSec: Int,
        blocking: Boolean,
        contentType: String,
        mediaUrl: String,
        mediaName: String
    ) {
        if (!hasOverlayPermission(context)) {
            // 方案 A 回退：无悬浮窗权限时交给普通 Activity（仍走 setShowWhenLocked 尽量置顶）
            MessageActivity.show(context, title, body, durationSec, blocking, contentType, mediaUrl, mediaName)
            return
        }
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                removeWindow()
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val composeView = ComposeView(appContext)
                composeView.setContent {
                    PadGuardTheme {
                        MessageOverlayContent(
                            title = title,
                            body = body,
                            blocking = blocking,
                            contentType = contentType,
                            mediaUrl = mediaUrl,
                            mediaName = mediaName,
                            downloader = mediaDownloader,
                            onDismiss = { removeWindow() }
                        )
                    }
                }
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE

                val flags = if (blocking) {
                    // 霸屏：聚焦 + 全屏沉浸 + 常亮，吞掉一切触摸
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                } else {
                    // 非霸屏：仍可聚焦（接收「知道了」点击），NOT_TOUCH_MODAL 让空白处触摸透传到下方应用
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                }

                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    flags,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.CENTER
                wm.addView(composeView, lp)
                windowView = composeView
                this.wm = wm
                // 霸屏到点自动关闭（非霸屏由「知道了」关闭，或兜底超时消失）
                if (blocking && durationSec > 0) {
                    handler.postDelayed({ removeWindow() }, durationSec * 1000L)
                } else if (!blocking && durationSec <= 0) {
                    handler.postDelayed({ removeWindow() }, NON_BLOCKING_TIMEOUT_MS)
                }
            } catch (t: Throwable) {
                Logger.e(TAG, t) { "message overlay show failed, fallback to activity" }
                runCatching {
                    MessageActivity.show(context, title, body, durationSec, blocking, contentType, mediaUrl, mediaName)
                }
            }
        }
    }

    /** 主动移除置顶窗口（如家长远程撤回 / 解锁） */
    fun dismiss() {
        Handler(Looper.getMainLooper()).post { removeWindow() }
    }

    private fun removeWindow() {
        val view = windowView ?: return
        runCatching { wm?.removeView(view) }
        windowView = null
        this.wm = null
    }

    @Volatile private var windowView: View? = null
    @Volatile private var wm: WindowManager? = null

    companion object {
        private const val TAG = "MsgOverlay"
        private const val NON_BLOCKING_TIMEOUT_MS = 10_000L
    }
}

/**
 * 置顶悬浮窗内的消息内容渲染。
 *
 * 复用 [RemoteImage] / [ResizableVideo] / [RemoteAudio] 统一的下载与解析路径（[MediaDownloader]），
 * 避免与弹窗各写一套；文案版式与 [com.padguard.child.ui.message.MessageActivity] 的弹窗保持一致。
 */
@Composable
internal fun MessageOverlayContent(
    title: String,
    body: String,
    blocking: Boolean,
    contentType: String,
    mediaUrl: String,
    mediaName: String,
    downloader: MediaDownloader,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 来源标识：让孩子一眼看出这是家长发来的，不是系统弹窗或广告
            Text(
                text = "来自家长的信息",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )

            val isFullBleedMedia = mediaUrl.isNotBlank() &&
                (contentType == "IMAGE" || contentType == "VIDEO")
            if (isFullBleedMedia) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when (contentType) {
                        "IMAGE" -> RemoteImage(mediaUrl, Modifier.fillMaxSize(), downloader = downloader)
                        "VIDEO" -> ResizableVideo(
                            url = mediaUrl,
                            modifier = Modifier.fillMaxSize(),
                            defaultFullscreen = true,
                            showControls = !blocking,
                            downloader = downloader
                        )
                    }
                }
                if (title.isNotBlank() && !isSourceLabel(title)) {
                    Text(
                        text = title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 120.dp, start = 24.dp, end = 24.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(min = 200.dp, max = 620.dp)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (title.isNotBlank() && !isSourceLabel(title)) {
                        Text(
                            text = title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (mediaUrl.isNotBlank() && contentType == "AUDIO") {
                        RemoteAudio(mediaUrl, mediaName, autoPlay = true, downloader = downloader)
                    } else if (body.isNotBlank()) {
                        Text(
                            text = body,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            if (body.isNotBlank() && isFullBleedMedia) {
                Text(
                    text = body,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 24.dp, end = 24.dp, bottom = 104.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            // 霸屏吞触摸；非霸屏给「知道了」关闭入口
            if (blocking) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(enabled = true, onClick = { /* 吞掉点击，不允许逃脱 */ })
                )
            } else {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 56.dp)
                ) { Text("知道了") }
            }
        }
    }
}
