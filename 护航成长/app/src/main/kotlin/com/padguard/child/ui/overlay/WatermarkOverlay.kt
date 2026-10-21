package com.padguard.child.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局水印覆盖层。
 *
 * ## 能力边界（必须说清楚）
 * Android 没有系统级"给所有窗口打水印"的能力。能做到的只有**叠加一个穿透触摸的悬浮窗**，
 * 因此它有两个天然局限，实现层面无法消除：
 * 1. 只覆盖本应用可见范围之上的那层，系统状态栏/导航栏区域盖不到；
 * 2. 设备进入真正的锁屏/关机界面时悬浮窗会被系统收走。
 *
 * 即便如此它仍有实用价值：孩子使用平板期间（管控真正生效的时段）水印始终可见，
 * 能明确"这台设备处于受控状态"，也便于截图溯源。
 *
 * ## 为什么必须是 TYPE_APPLICATION_OVERLAY
 * `TYPE_PHONE` / `TYPE_SYSTEM_ALERT` 在 API 26+ 已被废弃且**会被系统拒绝添加**，
 * 继续用只会在新设备上静默失效，是典型的"旧代码看起来能跑"陷阱。
 */
@Singleton
class WatermarkOverlay @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val windowManager: WindowManager? =
        ContextCompat.getSystemService(context, WindowManager::class.java)

    @Volatile private var view: TextView? = null

    fun isSupported(): Boolean {
        if (windowManager == null) return false
        // 悬浮窗权限需要用户在设置里手动打开，任何 ROM 都不会自动授予
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    /**
     * 显示水印。@return 是否真的显示了
     *
     * [position] / [alpha] 对应策略包 `kiosk.watermark.position / opacity`。
     * 这两个字段此前**反序列化后从未被使用**：无论家长在后台怎么调，
     * 水印都恒为左上角、固定 0.45 透明度 —— 典型的"配置了但不生效"。
     * 现在由策略运行时 [com.padguard.core.engine.enforcer.WatermarkRuntime] 驱动。
     *
     * 默认值刻意沿用旧的硬编码值，保证指令通道（SET_WATERMARK）的既有行为不回退。
     */
    fun show(content: String, position: String = "TOP", alpha: Float = TEXT_ALPHA): Boolean {
        if (!isSupported()) {
            Logger.w(TAG) { "overlay permission missing, watermark not shown" }
            return false
        }
        val safeContent = content.ifBlank { DEFAULT_CONTENT }
        val safeAlpha = alpha.coerceIn(MIN_ALPHA, MAX_ALPHA)

        // 已在显示时复用同一个 view：只更新内容与布局参数，
        // 避免重复 addView 触发 WindowManager 的"view has already been added"异常。
        val current = view
        if (current != null) {
            current.text = safeContent
            current.alpha = safeAlpha
            applyPosition(current, position)
            return true
        }

        val textView = TextView(context).apply {
            text = safeContent
            setTextColor(TEXT_COLOR)
            textSize = TEXT_SIZE_SP
            this.alpha = safeAlpha
            // 不拦截任何触摸：水印只是"看"的，不能影响正常操作
            isClickable = false
            isFocusable = false
        }

        val (gravity, offsetX, offsetY) = layoutFor(position)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            x = offsetX
            y = offsetY
        }

        return try {
            windowManager?.addView(textView, params)
            view = textView
            Logger.i(TAG) { "watermark shown: $safeContent pos=$position alpha=$safeAlpha" }
            true
        } catch (t: Throwable) {
            Logger.e(TAG, t) { "addView failed" }
            false
        }
    }

    /** 位置变更时就地更新布局，无需重建 view */
    private fun applyPosition(target: TextView, position: String) {
        val lp = target.layoutParams as? WindowManager.LayoutParams ?: return
        val (gravity, offsetX, offsetY) = layoutFor(position)
        if (lp.gravity == gravity && lp.x == offsetX && lp.y == offsetY) return
        lp.gravity = gravity
        lp.x = offsetX
        lp.y = offsetY
        runCatching { windowManager?.updateViewLayout(target, lp) }
            .onFailure { Logger.w(TAG) { "updateViewLayout failed: ${it.message}" } }
    }

    private fun layoutFor(position: String): Triple<Int, Int, Int> = when (position.uppercase()) {
        "BOTTOM" -> Triple(Gravity.BOTTOM or Gravity.START, MARGIN_PX, MARGIN_PX)
        "CENTER" -> Triple(Gravity.CENTER, 0, 0)
        else -> Triple(Gravity.TOP or Gravity.START, MARGIN_PX, MARGIN_PX)
    }

    fun hide() {
        val current = view ?: return
        view = null
        try {
            windowManager?.removeView(current)
            Logger.i(TAG) { "watermark hidden" }
        } catch (t: Throwable) {
            // 视图可能已被系统回收；这里失败无害，不需要向上抛
            Logger.w(TAG) { "removeView failed: ${t.message}" }
        }
    }

    companion object {
        private const val TAG = "WatermarkOverlay"
        private const val DEFAULT_CONTENT = "受管控设备"
        private const val TEXT_SIZE_SP = 13f
        private const val TEXT_ALPHA = 0.45f
        /** 与 KioskEnforcer 侧的钳制口径保持一致（太淡看不见、太浓挡内容） */
        private const val MIN_ALPHA = 0.05f
        private const val MAX_ALPHA = 0.8f
        private const val MARGIN_PX = 24
        private const val TEXT_COLOR = 0xFF333333.toInt()
    }
}
