package com.padguard.child.glassball

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.padguard.child.hidden.FloatingActionActivity
import com.padguard.child.hidden.UsageStatus
import com.padguard.child.hidden.UsageStatusProvider
import com.padguard.child.usagesession.UsageSessionManager
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 玻璃球悬浮控制器（2026-10-20 重设计，替代原「悬浮状态卡」）。
 *
 * ## 职责
 * - 以 [WindowManager] TYPE_APPLICATION_OVERLAY 把 [GlassBallView] 置于应用最顶层；
 * - 每秒刷新「已用 / 剩余」（与服务端权威基线同源，见 [UsageSessionManager]）；
 * - 拖拽移动 + 松手吸附最近屏幕边缘，位置按屏幕百分比持久化（旋转/换机不丢）；
 * - 单击 → 家长验证入口（[FloatingActionActivity]，隐藏形态下家长唯一触点）；
 * - 双击 → 申请延时对话框（[GlassBallDialogActivity]，走既有审批工单流）。
 *
 * ## 数据口径（与锁机判定、家长端看板同源，杜绝"球上还剩 10 分钟、设备却已锁"）
 * - 会话激活（孩子已确认计时）：剩余 = [UsageSessionManager.liveRemainingMs] 本地插值，
 *   基线来自服务端 `serverNow/startEpoch`；额度含已批准延时（`quota + extended`）；
 * - 会话未激活：回退策略额度 − UsageStats（与 [UsageStatusProvider] 同算法）。
 *
 * ## 主线程安全
 * `WindowManager.addView/updateViewLayout/removeView` 必须在主线程执行：
 * 本控制器由 GuardService（Dispatchers.Default 作用域）调用，全部窗口操作
 * 经 [mainHandler] 投递。
 *
 * ## 优雅降级
 * 缺少 SYSTEM_ALERT_WINDOW 时不展示（仅记日志），绝不抛异常 —— 该权限在装机引导页争取，
 * 缺失时表现为"没有玻璃球"，不影响后台管控。
 */
@Singleton
class GlassBallController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageSessionManager: UsageSessionManager,
    private val usageStatusProvider: UsageStatusProvider,
    private val timeProvider: TimeProvider
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 「可见会话」专属作用域：每次 show() 创建、hide()/dispose() 回收（同旧悬浮卡模式，防作用域取消后投递崩溃） */
    @Volatile private var scope: CoroutineScope? = null

    @Volatile private var ball: GlassBallView? = null
    @Volatile private var params: WindowManager.LayoutParams? = null
    private var tickerJob: Job? = null
    private var sessionJob: Job? = null
    private var statusJob: Job? = null

    /** 非激活会话的回退数据（UsageStatusProvider 分钟级） */
    @Volatile private var fallbackStatus: UsageStatus? = null

    private val canDraw: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)

    /** 展示玻璃球（幂等：已展示则直接返回）。 */
    @Synchronized
    fun show() {
        if (!canDraw) {
            Logger.w(TAG) { "SYSTEM_ALERT_WINDOW not granted, skip glass ball" }
            return
        }
        if (ball != null) return

        val view = GlassBallView(context)
        view.onSingleTap = { openParentGate() }
        view.onDoubleTap = { openDelayDialog() }
        // 增量回调：视图侧已完成基准换算，控制器只做窗口平移
        view.onDragDelta = { dx, dy -> onDragDelta(dx, dy) }
        view.onDragEnd = { onDragEnd() }
        ball = view

        val layoutParams = buildLayoutParams()
        params = layoutParams
        mainHandler.post {
            runCatching { wm.addView(view, layoutParams) }
                .onFailure { Logger.e(TAG, it) { "add glass ball failed" } }
        }

        // 每次展示新建作用域：旧会话被 hide() 回收后，守护服务被系统回收再由看门狗重启时
        // 再次 show() 仍可安全重建（不会向已取消的作用域投递协程）。
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // 会话状态变化立即刷新（含待审态、额度变化）。
        // 不去重（distinctUntilChanged 对 StateFlow 无效且已废弃）：StateFlow 本身对连续
        // 相同值不发射，且这里 renderNow 内部有字段级比较，重复渲染无副作用。
        sessionJob = usageSessionManager.state
            .onEach { renderNow() }
            .launchIn(scope!!)
        // 非激活会话的回退数据源
        statusJob = usageStatusProvider.status
            .onEach { fallbackStatus = it; renderNow() }
            .launchIn(scope!!)
        // 秒级心跳：驱动倒计时平滑刷新（不依赖 15s 的会话刷新节奏）
        tickerJob = scope!!.launch {
            while (isActive) {
                renderNow()
                delay(TICK_INTERVAL_MS)
            }
        }
        Logger.i(TAG) { "glass ball shown" }
    }

    /** 移除玻璃球（幂等）。 */
    @Synchronized
    fun hide() {
        if (ball == null) return
        val view = ball
        tickerJob?.cancel(); tickerJob = null
        sessionJob?.cancel(); sessionJob = null
        statusJob?.cancel(); statusJob = null
        scope?.cancel(); scope = null
        ball = null
        params = null
        mainHandler.post {
            runCatching { if (view != null) wm.removeView(view) }
                .onFailure { Logger.w(TAG, it) { "remove glass ball failed" } }
        }
        Logger.i(TAG) { "glass ball hidden" }
    }

    /** GuardService 销毁时调用（仅做 hide：解绑/退出管控即收起玻璃球）。 */
    fun dispose() {
        hide()
    }

    // ==================== 位置与拖拽 ====================

    /**
     * 构建窗口参数：位置从持久化百分比恢复（无记录时默认右侧垂直居中）。
     * 百分比而非像素：屏幕旋转、分辨率变化、跨设备恢复都不会把球丢到屏幕外。
     */
    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val sizePx = (GlassBallView.BALL_SIZE_DP * metrics.density).toInt()
        val fx = prefs.getFloat(KEY_POS_FX, 1f).coerceIn(0f, 1f)
        val fy = prefs.getFloat(KEY_POS_FY, 0.5f).coerceIn(0f, 1f)
        val x = ((metrics.widthPixels - sizePx) * fx).roundToInt().coerceIn(0, metrics.widthPixels - sizePx)
        val y = ((metrics.heightPixels - sizePx) * fy).roundToInt().coerceIn(0, metrics.heightPixels - sizePx)
        return WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // 不抢焦点、不拦触摸 modal：球自身可点，周围事件照常透传给下层应用
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    /**
     * 拖拽中：按视图换算好的增量平移窗口（主线程）。
     * 基准换算在视图侧（相对按下点），控制器不维护 lastRaw ——
     * 杜绝"首次移动以 0 为基准导致球瞬移"（2026-10-21 真机实测 bug）。
     */
    private fun onDragDelta(dx: Float, dy: Float) {
        val p = params ?: return
        val metrics = context.resources.displayMetrics
        val sizePx = p.width
        p.x = (p.x + dx).roundToInt().coerceIn(0, (metrics.widthPixels - sizePx).coerceAtLeast(0))
        p.y = (p.y + dy).roundToInt().coerceIn(0, (metrics.heightPixels - sizePx).coerceAtLeast(0))
        mainHandler.post {
            runCatching { ball?.let { wm.updateViewLayout(it, p) } }
                .onFailure { Logger.w(TAG, it) { "drag updateViewLayout failed" } }
        }
    }

    /** 拖拽结束：吸附最近屏幕边缘（左右），并持久化百分比位置 */
    private fun onDragEnd() {
        val p = params ?: return
        val metrics = context.resources.displayMetrics
        val sizePx = p.width
        val centerX = p.x + sizePx / 2f
        val targetX = if (centerX < metrics.widthPixels / 2f) 0 else metrics.widthPixels - sizePx
        ball?.animateSnapX(p.x, targetX) { value ->
            p.x = value
            runCatching { ball?.let { wm.updateViewLayout(it, p) } }
        }
        // 持久化百分比位置（先提交再吸附动画结束也无妨：位置语义连续）
        prefs.edit()
            .putFloat(KEY_POS_FX, targetX.toFloat() / (metrics.widthPixels - sizePx).coerceAtLeast(1))
            .putFloat(KEY_POS_FY, p.y.toFloat() / (metrics.heightPixels - sizePx).coerceAtLeast(1))
            .apply()
    }

    // ==================== 数据渲染 ====================

    /**
     * 每秒渲染一次。口径与 [UsageStatusProvider] 同源：
     * 会话激活 → 服务端基线插值；未激活 → 策略额度回退。
     */
    private fun renderNow() {
        val view = ball ?: return
        val session = usageSessionManager.state.value
        val now = timeProvider.now()

        val usedMinutes: Int
        val remainingLabel: String
        val ratio: Float
        if (session.active) {
            val remainingMs = usageSessionManager.liveRemainingMs(now)
            val quotaMinutes = session.quotaMinutes.coerceAtLeast(0)
            usedMinutes = (quotaMinutes - remainingMs / 60_000.0).roundToInt().coerceAtLeast(0)
            remainingLabel = formatRemaining(remainingMs)
            ratio = if (quotaMinutes > 0) {
                (remainingMs / (quotaMinutes * 60_000.0)).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
        } else {
            val fallback = fallbackStatus
            usedMinutes = fallback?.usedMinutes ?: 0
            remainingLabel = fallback?.let { formatRemaining(it.remainingMinutes * 60_000L) } ?: "--:--"
            ratio = if (fallback != null && fallback.quotaMinutes > 0) {
                (fallback.remainingMinutes.toFloat() / fallback.quotaMinutes).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
        view.render(
            usedMinutes = usedMinutes,
            remainingLabel = remainingLabel,
            remainingRatio = ratio,
            pendingDelayMinutes = session.pendingDelayMinutes
        )
    }

    /** 剩余时长格式：≥60 分钟显示「N 分」，否则 mm:ss */
    private fun formatRemaining(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        return if (totalSec >= 3600L) {
            "${(totalSec / 60L).toInt()}分"
        } else {
            val m = (totalSec / 60L).toInt()
            val s = (totalSec % 60L).toInt()
            "%02d:%02d".format(m, s)
        }
    }

    // ==================== 点击动作 ====================

    /** 单击：唤起家长验证入口（原悬浮卡的既有能力，隐藏形态下家长唯一触点） */
    private fun openParentGate() {
        val intent = Intent(context, FloatingActionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Logger.e(TAG, it) { "open FloatingActionActivity failed" } }
    }

    /**
     * 双击：弹出申请延时对话框。
     * 本应用持有 SYSTEM_ALERT_WINDOW（玻璃球能展示的前提），
     * Android 10+ 对持该权限的应用豁免后台启动 Activity 限制。
     */
    private fun openDelayDialog() {
        val intent = Intent(context, GlassBallDialogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Logger.e(TAG, it) { "open GlassBallDialogActivity failed" } }
    }

    companion object {
        private const val TAG = "GlassBallCtrl"
        private const val PREFS_NAME = "glassball_pos"
        private const val KEY_POS_FX = "fx"
        private const val KEY_POS_FY = "fy"

        /** 刷新节奏：1s。与 UsageSessionHost 内的倒计时浮层同口径 */
        private const val TICK_INTERVAL_MS = 1_000L
    }
}
