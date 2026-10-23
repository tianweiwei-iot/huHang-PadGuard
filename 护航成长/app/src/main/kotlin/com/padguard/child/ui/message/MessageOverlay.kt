package com.padguard.child.ui.message

import android.content.Context
import com.padguard.core.common.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 信息发布渲染控制器（薄封装）。
 *
 * 早期版本曾用 [android.view.WindowManager] 的 [android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 * 直接挂一个 ComposeView 做「系统级置顶」。但该悬浮窗 Window 没有 Activity/Fragment，
 * ComposeView 在 onAttachedToWindow 时因找不到 ViewTreeLifecycleOwner 直接抛
 * IllegalStateException；且崩溃发生在 addView 之后的下一帧 Choreographer 回调里、
 * 外层 try/catch 兜不住，直接变成 FATAL 杀掉进程——这正是「家长发布视频后孩子端毫无反应」
 * 的根因（logcat 中 13:54:56 的 ViewTreeLifecycleOwner not found 即此）。
 *
 * 现统一委托给 [MessageActivity]：它内部通过 setShowWhenLocked + 全屏高优先级通知
 * (fullScreenIntent) 实现「锁屏/后台场景也能霸屏显示」，且本身是 ComponentActivity，
 * Lifecycle 正确、Compose 不会崩溃；图片/视频/音频渲染、真霸屏（LockTask）、到点自动关闭
 * 一应俱全。本类只做参数透传，不再自绘悬浮窗。
 */
@Singleton
class MessageOverlayController @Inject constructor() {

    /**
     * 展示一条家长下发的信息（弹窗并霸屏播放）。
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
        runCatching {
            MessageActivity.show(
                context = context,
                title = title,
                body = body,
                durationSec = durationSec,
                blocking = blocking,
                contentType = contentType,
                mediaUrl = mediaUrl,
                mediaName = mediaName
            )
        }.onFailure {
            Logger.e(TAG, it) { "failed to show parent message" }
        }
    }

    /** 主动关闭正在展示的信息（如家长远程撤回）。 */
    fun dismiss(context: Context) {
        runCatching { MessageActivity.dismiss(context) }
            .onFailure { Logger.e(TAG, it) { "failed to dismiss parent message" } }
    }

    companion object {
        private const val TAG = "MsgOverlay"
    }
}
