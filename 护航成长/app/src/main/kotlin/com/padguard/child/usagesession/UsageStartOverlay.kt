package com.padguard.child.usagesession

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「平板使用计时开始」确认浮层。
 *
 * ## 修复背景（产品级 bug）
 * 原实现把计时开始确认框（UsageStartDialog）塞在孩子端**前台 App UI**（UsageSessionHost）里。
 * 但孩子端日常以**后台保活**形态运行——孩子用的是其他 App、孩子端 App 不在前台，
 * 确认框永远不会被看到，于是「第二天亮屏没有任何提示开始计时、计时始终不启动」。
 *
 * 这里改为用系统浮层（TYPE_APPLICATION_OVERLAY，与玻璃球同源）弹出：
 * 无论孩子停留在哪个 App、App 是否在后台，确认框都在屏幕中央出现；点「开始计时」调用外部传入的
 * [onConfirm]（由 GuardService 注入 usageSessionManager.confirmStart）才真正锁定今日计时起点。
 * 这是「一亮屏开始计时」诉求落地的关键。
 *
 * ## 为何只注入 Context、确认动作由外部注入
 * 避免在本组件构造函数里直接注入 [UsageSessionManager]：Hilt/KSP 对新文件内同模块跨类注入偶发
 * 解析失败（error.NonExistentClass）。把确认动作以 lambda 形式由调用方（GuardService）传入，
 * 既解耦又绕开该 KSP 坑，确认逻辑仍走同一份 usageSessionManager。
 *
 * ## 为何用原生 View 而非 Compose
 * 浮层通过 WindowManager 挂到屏幕上、没有 Activity/LifecycleOwner 上下文；
 * 原生 LinearLayout/TextView/Button 在 WindowManager 下稳定可用（玻璃球即此方案），
 * ComposeView 承载 AlertDialog 在无 LifecycleOwner 时反而易渲染失败或崩溃，故此处用原生实现。
 *
 * ## 权限与降级
 * 浮层依赖 SYSTEM_ALERT_WINDOW：缺失时优雅跳过（仅记日志），不抛异常——
 * 该权限在装机引导页争取，缺失时表现为「没有计时开始提示」，但后台管控不受影响。
 */
@Singleton
class UsageStartOverlay @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var view: View? = null
    @Volatile private var onConfirm: (() -> Unit)? = null

    private val canDraw: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)

    /** 展示计时开始确认浮层（幂等：已展示则直接返回）。[confirmAction] 为点「开始计时」后的回调。 */
    fun show(confirmAction: () -> Unit) {
        if (!canDraw) {
            Logger.w(TAG) { "SYSTEM_ALERT_WINDOW not granted -> 无法弹出计时开始浮层" }
            return
        }
        if (view != null) return
        onConfirm = confirmAction
        mainHandler.post {
            runCatching {
                val v = buildView()
                view = v
                wm.addView(v, buildParams())
                Logger.i(TAG) { "计时开始浮层已展示" }
            }.onFailure { Logger.e(TAG, it) { "add 计时开始浮层失败" } }
        }
    }

    /** 移除计时开始确认浮层（幂等）。 */
    fun hide() {
        val v = view ?: return
        view = null
        onConfirm = null
        mainHandler.post {
            runCatching { wm.removeView(v) }
                .onFailure { Logger.w(TAG, it) { "remove 计时开始浮层失败" } }
        }
    }

    /** GuardService 销毁时调用。 */
    fun dispose() {
        hide()
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels * 0.86f).toInt().coerceAtMost(440)
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // 不加 NOT_FOCUSABLE：否则按钮无法点击；
            // 用 NOT_TOUCH_MODAL 让浮层区内可点、区外触摸透传给下层 App。
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
    }

    private fun buildView(): View {
        val dm = context.resources.displayMetrics
        val dip = dm.density

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * dip).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 16 * dip
            }
        }

        val title = TextView(context).apply {
            text = "平板使用计时开始"
            setTextColor(Color.parseColor("#212121"))
            textSize = 18f
            includeFontPadding = false
        }
        val body = TextView(context).apply {
            text = "系统已开始记录你的平板使用时间。到达今日限额后平板会自动进入管控，" +
                "剩余 10 分钟时可在屏幕顶部申请延时。请合理使用，保护视力与作息。"
            setTextColor(Color.parseColor("#616161"))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * dip).toInt()
                bottomMargin = (18 * dip).toInt()
            }
        }
        val btn = Button(context).apply {
            text = "开始计时"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1976D2"))
                cornerRadius = 10 * dip
            }
            setOnClickListener { onConfirm?.invoke() }
        }

        root.addView(title)
        root.addView(body)
        root.addView(btn)
        return root
    }

    companion object {
        private const val TAG = "UsageStartOverlay"
    }
}
