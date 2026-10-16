package com.padguard.child.hidden

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.padguard.child.R
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 悬浮状态卡控制器。
 *
 * ## 它是绑定后孩子唯一可见的孩子端界面
 * 应用图标隐藏后，孩子不再有可点的控制台；这里用一张 [WindowManager] 悬浮卡（TYPE_APPLICATION_OVERLAY）
 * 常驻展示「今日已用 / 剩余」，满足"只保留基本信息（使用时长）"的诉求。点按卡片即唤起家长验证入口。
 *
 * ## 主线程安全
 * `WindowManager.addView/removeView` 必须在主线程执行。本控制器从后台协程（GuardService 的 IO 作用域）
 * 被调用，因此所有窗口操作都通过 [Handler(Looper.getMainLooper())] 投递到主线程，避免 ViewRootImpl 异常。
 *
 * ## 优雅降级
 * 缺少 [android.Manifest.permission.SYSTEM_ALERT_WINDOW] 时不展示（仅记日志），绝不抛异常——
 * 该权限在装机引导页争取，缺失时表现为"没有悬浮卡"，不影响后台管控。
 */
@Singleton
class FloatingStatusController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageStatusProvider: UsageStatusProvider
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    // 悬浮卡「可见会话」专属作用域：每次 show() 创建、hide()/dispose() 回收。
    // 不复用单例级常驻作用域，避免 GuardService 被系统回收后由看门狗重启时，
    // 再次 show() 向已取消的作用域投递协程而抛 Cancelled 异常（见 dispose 说明）。
    @Volatile private var scope: CoroutineScope? = null

    @Volatile private var root: LinearLayout? = null
    @Volatile private var usedText: TextView? = null
    @Volatile private var remainingText: TextView? = null
    @Volatile private var statusJob: Job? = null

    private val canDraw: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)

    /** 展示悬浮卡（幂等：已展示则直接返回）。 */
    @Synchronized
    fun show() {
        if (!canDraw) {
            Logger.w(TAG) { "SYSTEM_ALERT_WINDOW not granted, skip floating card" }
            return
        }
        if (root != null) return
        val (layout, used, remaining) = buildView()
        root = layout
        usedText = used
        remainingText = remaining

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (24 * context.resources.displayMetrics.density).toInt()
        }

        mainHandler.post {
            runCatching { wm.addView(layout, params) }
                .onFailure { Logger.e(TAG, it) { "add floating view failed" } }
        }

        // 每次展示都新建一个短期作用域，确保旧会话被 hide() 回收后，重启（守护服务重建）仍可安全再展示。
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        statusJob = usageStatusProvider.status
            .onEach { update(it) }
            .launchIn(scope!!)
        Logger.i(TAG) { "floating usage card shown" }
    }

    /** 移除悬浮卡（幂等）。 */
    @Synchronized
    fun hide() {
        if (root == null) return
        val view = root
        statusJob?.cancel()
        statusJob = null
        // 回收本次「可见会话」的作用域，避免协程泄漏；下次 show() 会重建。
        scope?.cancel()
        scope = null
        root = null
        usedText = null
        remainingText = null
        mainHandler.post {
            runCatching { if (view != null) wm.removeView(view) }
                .onFailure { Logger.w(TAG, it) { "remove floating view failed" } }
        }
        Logger.i(TAG) { "floating usage card hidden" }
    }

    private fun update(status: UsageStatus) {
        val view = root ?: return
        view.post {
            usedText?.text = "今日已用 ${status.usedMinutes} 分钟"
            remainingText?.text = when {
                status.quotaMinutes <= 0 -> "未设置使用限额"
                status.remainingMinutes > 0 -> "剩余可用 ${status.remainingMinutes} 分钟"
                else -> "今日额度已用完"
            }
        }
    }

    /** 构建纯代码悬浮卡片（不引入 XML 布局，保持与现有 Compose 工程一致）。 */
    private fun buildView(): Triple<LinearLayout, TextView, TextView> {
        val density = context.resources.displayMetrics.density
        val padH = (18 * density).toInt()
        val padV = (12 * density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
            setBackgroundResource(R.drawable.floating_card_bg)
            // 点按卡片 → 唤起家长验证入口（悬浮卡是隐藏应用后唯一的家长触点）。
            setOnClickListener { openParentAction() }
            isClickable = true
            isFocusable = true
        }

        val title = TextView(context).apply {
            text = "护航成长"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0x99000000.toInt())
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        val used = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF222222.toInt())
        }
        val remaining = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF666666.toInt())
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        layout.addView(title)
        layout.addView(used)
        layout.addView(remaining)
        return Triple(layout, used, remaining)
    }

    private fun openParentAction() {
        val intent = Intent(context, FloatingActionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Logger.e(TAG, it) { "open FloatingActionActivity failed" } }
    }

    /**
     * 收起悬浮卡（GuardService 销毁时调用）。
     *
     * 仅做 hide()：取消数据观察协程并移除窗口。本控制器是应用级单例，其「可见会话」作用域
     * 已在 [hide] 中回收，因而守护服务被系统回收后由看门狗重启、再次 [show] 时仍能新建作用域、
     * 正常刷新——不会出现「向已取消作用域投递协程而崩溃」。
     */
    fun dispose() {
        hide()
    }

    companion object {
        private const val TAG = "FloatingStatusController"
    }
}
