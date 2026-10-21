package com.padguard.child.ui.lock

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.padguard.child.R
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * "您当前无权限操作"提示覆盖层。
 *
 * ## 何时出现
 * 受控态（锁定 / 时间到）期间，孩子连续快速点击被管控应用达到阈值，
 * [com.padguard.child.guard.OperationGuardAccessibilityService] 不再反复把人顶回桌面
 * （那只会闪烁且给不出明确反馈），而是借本覆盖层给出一次清晰的文字提示，[AUTO_DISMISS_MS] 后自动消失。
 *
 * ## 为什么用悬浮窗而不是 Activity
 * 触发点在无障碍服务回调里，且要求"弹一下就走、不切断当前流程"，悬浮窗（TYPE_APPLICATION_OVERLAY）
 * 最轻量；复用装机流程已授予的 SYSTEM_ALERT_WINDOW 权限。
 * 若该权限缺失（极少数情况），本层静默失败，由调用方退回到"继续顶回桌面"的逻辑，不阻塞管控。
 *
 * ## 与历史锁屏悬浮窗的区别
 * 锁屏悬浮窗是"长期占用、吃掉触摸"的硬锁定层；本层是"瞬时、只读、不抢焦点"的提示，
 * 因此用 FLAG_NOT_FOCUSABLE，触摸直接透传到下层（孩子此时已被顶回桌面），不干扰交互。
 */
@Singleton
class NoPermissionOverlay @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var root: View? = null

    @Volatile
    private var dismissRunnable: Runnable? = null

    /** 展示提示；已展示时仅刷新自动消失计时（连续命中不会叠加多份）。 */
    fun show() {
        if (!canDrawOverlay()) {
            Logger.w(TAG) { "no-permission overlay skipped: SYSTEM_ALERT_WINDOW not granted" }
            return
        }
        runCatching {
            val view = root ?: buildView().also { root = it }
            if (view.parent == null) {
                wm.addView(view, params())
                Logger.i(TAG) { "no-permission overlay shown" }
            }
            // 重置自动消失计时：连续命中期间只刷新、不叠加。
            dismissRunnable?.let { handler.removeCallbacks(it) }
            val runnable = Runnable { dismiss() }.also { dismissRunnable = it }
            handler.postDelayed(runnable, AUTO_DISMISS_MS)
        }.onFailure {
            root = null
            Logger.w(TAG) { "failed to add no-permission overlay: ${it.message}" }
        }
    }

    /** 立即移除提示（弹窗后调用 / 解锁时调用）。 */
    fun dismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        val view = root ?: return
        root = null
        runCatching { wm.removeView(view) }
            .onFailure { Logger.w(TAG) { "failed to remove no-permission overlay: ${it.message}" } }
    }

    private fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        windowAnimations = 0
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    @Suppress("SetTextI18n")
    private fun buildView(): View {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).roundToInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // 半透明压暗背景：提示清晰但不完全遮蔽，孩子能意识到"操作被拦截"。
            setBackgroundColor(BG_COLOR)
            // 不抢焦点、不拦截触摸：孩子此时已被顶回桌面，触摸透传到底层即可。
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.no_permission_message)
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setPadding(pad, pad, pad, 0)
                }
            )
        }
    }

    private companion object {
        const val TAG = "NoPermissionOverlay"
        val BG_COLOR = "#CC1B1B1B".toColorInt()
        const val AUTO_DISMISS_MS = 2000L
    }
}
