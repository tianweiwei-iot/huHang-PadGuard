package com.padguard.child.guard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.toColorInt
import com.padguard.core.data.model.policy.EyeProtectionPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * 护眼滤镜：在屏幕上叠一层暖色半透明遮罩，降低蓝光观感。
 *
 * ## 为什么是悬浮遮罩，而不是调系统"护眼模式"
 * Android **没有公开 API** 可以调节屏幕色温 / 蓝光比例（那是各 ROM 私有实现，
 * 且大多藏在 Secure 设置里、需要 WRITE_SETTINGS 且机型差异极大）。
 * 悬浮遮罩是唯一跨机型一致、且不需要 Device Owner 的做法 ——
 * 本项目绝大多数设备跑在没有 DO 的降级态，只能走这条路。
 *
 * ## 权限
 * 需要 SYSTEM_ALERT_WINDOW（AppOps 特殊权限，必须在系统设置页由用户开启）。
 * 该权限已在一次性授权清单中收走（见 SpecialPermission.ACCESSIBILITY 同批清单），
 * 缺失时 [canDrawOverlay] 返回 false，由调用方走**明确失败**而不是静默无效。
 *
 * ## 为什么设置成"不可触摸、不可聚焦"
 * 这层 View 覆盖全屏且长期存在：只要它接收触摸或抢焦点，孩子就会遇到
 * "点了没反应 / 键盘弹不出来 / 输入法切不走"，比护眼本身严重得多。
 * 因此 FLAG_NOT_TOUCHABLE + NOT_FOCUSABLE + NOT_TOUCH_MODAL 三件套缺一不可。
 */
class EyeProtectionOverlay @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlay: View? = null

    /** 是否具备悬浮窗权限。没权限时静默失效会导致"家长开了护眼却毫无反应"，必须先问。 */
    fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)

    /**
     * 按策略显示 / 更新 / 移除遮罩。
     *
     * 幂等：连续用同一策略调用不会产生多层叠加（那会让屏幕越来越黄，
     * 表现为"每下发一次策略颜色就深一层"）。
     */
    @SuppressLint("RtlHardcoded")
    fun apply(policy: EyeProtectionPolicy) {
        if (!policy.enabled) {
            remove()
            return
        }
        if (!canDrawOverlay()) {
            // 明确失败：交给调用方上报 Unsupported，不能假装生效
            remove()
            return
        }
        val alpha = policy.tintAlpha
        val existing = overlay
        if (existing != null) {
            existing.alpha = alpha
            return
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            // 不参与动画：系统窗口动画会让这层遮罩出现闪烁
            windowAnimations = 0
        }

        val view = View(context).apply {
            // 暖橙色：对蓝光的观感抑制最明显，同时不至于把屏幕染成红色
            setBackgroundColor(WARM_TINT)
            this.alpha = alpha
            // 禁止它在无障碍 / 截图里被当作可交互元素
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        runCatching {
            wm.addView(view, params)
            overlay = view
        }.onFailure {
            // 部分 ROM 在后台或非前台任务栈里 addView 会抛 BadTokenException。
            // 这里吞掉是有意的：护眼失败不影响任何管控能力，重启服务会重试。
            overlay = null
        }
    }

    fun remove() {
        val view = overlay ?: return
        overlay = null
        runCatching { wm.removeView(view) }
    }

    private companion object {
        val WARM_TINT = "#FFB26A".toColorInt()
    }
}
