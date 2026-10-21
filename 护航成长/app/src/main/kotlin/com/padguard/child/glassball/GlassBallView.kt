package com.padguard.child.glassball

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.padguard.core.common.Logger
import kotlin.math.hypot

/**
 * 玻璃球悬浮视图（孩子端前台运行的可视载体，2026-10-20 重设计）。
 *
 * ## 它替代了什么
 * 原「悬浮状态卡」（LinearLayout + 两行文字）：无进度可视化、不能拖拽、
 * 延时申请入口只存在于孩子端 App 内 Compose 浮层 —— 而孩子端绑定后图标已隐藏，
 * 孩子平时根本触达不到那个入口。玻璃球把「已用/剩余 + 延时申请」带到系统最顶层。
 *
 * ## 绘制（纯 Canvas，不引入 ComposeView）
 * - 玻璃体：[RadialGradient] 中心高光 → 浅青 → 边缘半透明，左上补椭圆高光、底部补反光；
 * - 进度环：[SweepGradient] 扫过的弧长 = 剩余比例，颜色随余量 青→琥珀→红；
 * - 文字三行：上「已用 X 分」、中「剩余大字（mm:ss / 分钟）」、下「剩余 X 分」；
 * - 待审态：进度环琥珀色呼吸脉冲，中行改显「待审」。
 *
 * ## 交互（点击/双击/拖拽，全部手写判定）
 * 单击 = 家长验证入口（隐藏形态下家长唯一触点，保留原能力）；双击 = 申请延时对话框；
 * 位移超过 [ViewConfiguration] 触摸容差后转为窗口拖拽（按增量回调控制器更新
 * LayoutParams），抬手吸附最近边缘。三者互斥：一旦判为拖拽，抬手不再派发点击，
 * 避免"拖完弹一下对话框"。
 */
class GlassBallView(context: Context) : View(context) {

    /** 数据快照（由控制器每秒刷新） */
    var usedMinutes: Int = 0
        private set
    var remainingLabel: String = "--:--"
        private set
    var remainingRatio: Float = 1f
        private set
    var pendingDelayMinutes: Int = 0
        private set

    /** 单击：唤起家长验证入口（隐藏形态下家长唯一触点，保留原能力） */
    var onSingleTap: (() -> Unit)? = null

    /** 双击：弹出申请延时对话框 */
    var onDoubleTap: (() -> Unit)? = null

    /** 拖拽中：本帧位移增量（dx/dy，像素），控制器据此平移窗口 */
    var onDragDelta: ((dx: Float, dy: Float) -> Unit)? = null

    /** 拖拽结束：供控制器做边缘吸附 */
    var onDragEnd: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val ballSizePx = BALL_SIZE_DP * density
    private val ringWidthPx = RING_WIDTH_DP * density

    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x1F000000
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFF374151.toInt()
        typeface = android.graphics.Typeface.DEFAULT
    }
    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFF111827.toInt()
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val bottomTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFF4B5563.toInt()
    }

    private val ringRect = RectF()

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * 手写点击/双击判定（替代 GestureDetector）。
     *
     * **为什么不用 GestureDetector**（2026-10-21 真机实测）：
     * SimpleOnGestureListener 同时覆写 onDoubleTap 时，GestureDetector 内部把监听器
     * 注册为 double-tap listener，onSingleTapConfirmed 的派发被转入双击状态机，
     * 实测单击/双击**都不回调**；且双击判定依赖两次抬脚的进程间隔，adb/自动化
     * 场景与慢速点击都不可靠。改为手写：
     * - 抬手时距上次有效点击 < [DOUBLE_TAP_TIMEOUT_MS] 且位移未超容差 → 双击，立即回调；
     * - 否则延迟 [DOUBLE_TAP_TIMEOUT_MS] 回调单击（期间第二次点击可将其取消）。
     */
    private var lastTapAtMs = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val singleTapRunnable = Runnable { onSingleTap?.invoke() }

    /** 待审呼吸脉冲相位（0..1），由属性动画平滑推进 */
    private var pulsePhase = 0f
    private var pulseAnimator: ValueAnimator? = null

    init {
        // 让 View 自身拿到触摸事件（悬浮窗口默认不拦截）
        isClickable = true
        isFocusable = false
    }

    /** 控制器每秒调用：刷新展示数据（主线程） */
    fun render(usedMinutes: Int, remainingLabel: String, remainingRatio: Float, pendingDelayMinutes: Int) {
        var changed = false
        if (this.usedMinutes != usedMinutes) { this.usedMinutes = usedMinutes; changed = true }
        if (this.remainingLabel != remainingLabel) { this.remainingLabel = remainingLabel; changed = true }
        if (this.remainingRatio != remainingRatio) { this.remainingRatio = remainingRatio; changed = true }
        if (this.pendingDelayMinutes != pendingDelayMinutes) {
            this.pendingDelayMinutes = pendingDelayMinutes; changed = true
        }
        // 待审态呼吸动画：进入时启动、退出时停止（约 1.2s 一个呼吸周期）
        if (pendingDelayMinutes > 0) startPulse() else stopPulse()
        if (changed || pendingDelayMinutes > 0) invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ballSizePx.toInt(), ballSizePx.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders(w, h)
    }

    private fun rebuildShaders(w: Int, h: Int) {
        val cx = w * 0.32f
        val cy = h * 0.26f
        val radius = hypot(w.toFloat(), h.toFloat()) * 0.72f
        ballPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                0xF2FFFFFF.toInt(), // 中心：近白高光
                0xE6EAF8FE.toInt(), // 浅青白
                0xCCBEE6F8.toInt(), // 浅蓝
                0x998FC3E8.toInt()  // 边缘半透明
            ),
            floatArrayOf(0f, 0.45f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        // 进度环扫光着色器：以圆心为中心的环向渐变（青 → 紫蓝）
        ringPaint.shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(0xFF2DD4BF.toInt(), 0xFF38BDF8.toInt(), 0xFF818CF8.toInt(), 0xFF2DD4BF.toInt()),
            floatArrayOf(0f, 0.33f, 0.66f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val ballRadius = (ballSizePx / 2f) - ringWidthPx / 2f

        // ---- 玻璃体 ----
        canvas.drawCircle(cx, cy, ballRadius, ballPaint)

        // ---- 左上主高光（椭圆，旋转 -30°）----
        canvas.save()
        canvas.rotate(-32f, cx, cy)
        highlightPaint.color = 0x66FFFFFF
        canvas.drawOval(
            cx - ballRadius * 0.62f, cy - ballRadius * 0.86f,
            cx + ballRadius * 0.10f, cy - ballRadius * 0.30f,
            highlightPaint
        )
        canvas.restore()

        // ---- 底部反光（窄椭圆，低透明）----
        highlightPaint.color = 0x33FFFFFF
        canvas.drawOval(
            cx - ballRadius * 0.45f, cy + ballRadius * 0.55f,
            cx + ballRadius * 0.45f, cy + ballRadius * 0.92f,
            highlightPaint
        )

        // ---- 进度环 ----
        ringTrackPaint.strokeWidth = ringWidthPx
        ringPaint.strokeWidth = ringWidthPx
        ringRect.set(
            cx - ballRadius, cy - ballRadius,
            cx + ballRadius, cy + ballRadius
        )
        canvas.drawArc(ringRect, 0f, 360f, false, ringTrackPaint)

        val pending = pendingDelayMinutes > 0
        val ratio = remainingRatio.coerceIn(0f, 1f)
        val sweep = 360f * ratio
        val ringColor = when {
            pending -> 0xFFF59E0B.toInt() // 待审：琥珀
            ratio > 0.5f -> 0xFF2DD4BF.toInt() // 余量充足：青
            ratio > 0.2f -> 0xFFFBBF24.toInt() // 余量偏紧：琥珀
            else -> 0xFFF87171.toInt() // 即将耗尽：红
        }
        ringPaint.color = ringColor
        // 待审态呼吸脉冲：透明度随时间波动，远看即知"有申请在处理中"
        ringPaint.alpha = if (pending) {
            (160 + 80 * pulseAlpha()).toInt().coerceIn(0, 255)
        } else {
            235
        }
        // 从顶部（-90°）开始顺时针扫过剩余比例
        canvas.drawArc(ringRect, -90f, sweep, false, ringPaint)

        // ---- 文字三行 ----
        topTextPaint.textSize = 8.5f * density * resources.configuration.fontScale.coerceAtMost(1.1f)
        centerTextPaint.textSize = 15f * density
        bottomTextPaint.textSize = 8.5f * density * resources.configuration.fontScale.coerceAtMost(1.1f)

        val topText = if (pending) {
            "已申请 +${pendingDelayMinutes}分"
        } else {
            "已用 ${usedMinutes}分"
        }
        val centerText = if (pending) "待审" else remainingLabel
        val bottomText = if (pending) {
            "家长审核中"
        } else {
            "剩余 ${remainingLabel}"
        }

        val topY = cy - ballRadius * 0.34f
        val centerY = cy + centerTextPaint.textSize * 0.36f
        val bottomY = cy + ballRadius * 0.52f
        canvas.drawText(topText, cx, topY, topTextPaint)
        canvas.drawText(centerText, cx, centerY, centerTextPaint)
        canvas.drawText(bottomText, cx, bottomY, bottomTextPaint)
    }

    /** 呼吸脉冲的当前透明度系数（0..1） */
    private fun pulseAlpha(): Float {
        return (1f - kotlin.math.abs(pulsePhase - 0.5f) * 2f).coerceIn(0f, 1f)
    }

    /** 启动待审呼吸动画（幂等；REVERSE 往复 ≈ 一个完整呼吸周期 1.2s） */
    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_CYCLE_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulsePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 停止呼吸动画并复位相位，避免下次进入时从中间跳变 */
    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulsePhase = 0f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.rawX - downRawX
                val totalDy = event.rawY - downRawY
                if (!dragging && hypot(totalDx, totalDy) > touchSlop) {
                    dragging = true
                    // 进入拖拽即取消悬而未决的单击判定，避免"拖一下又弹对话框"
                    removeCallbacks(singleTapRunnable)
                    Logger.v(TAG) { "glass ball drag start" }
                }
                if (dragging) {
                    // 传**增量**给控制器：控制器不再维护 lastRaw 基准，
                    // 杜绝"首次移动以 0 为基准导致球瞬移"（2026-10-21 真机实测 bug）。
                    onDragDelta?.invoke(totalDx, totalDy)
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    onDragEnd?.invoke()
                    return true
                }
                // 未拖拽 → 点击/双击判定（手写，见 lastTapAtMs 注释）
                val now = android.os.SystemClock.uptimeMillis()
                val dx = event.rawX - lastTapX
                val dy = event.rawY - lastTapY
                val isDoubleTap = lastTapAtMs != 0L &&
                    now - lastTapAtMs <= DOUBLE_TAP_TIMEOUT_MS &&
                    hypot(dx, dy) <= touchSlop
                removeCallbacks(singleTapRunnable)
                if (isDoubleTap) {
                    lastTapAtMs = 0L
                    onDoubleTap?.invoke()
                } else {
                    lastTapAtMs = now
                    lastTapX = event.rawX
                    lastTapY = event.rawY
                    // 延迟到双击窗口结束才回调单击：期间第二次点击会取消它
                    postDelayed(singleTapRunnable, DOUBLE_TAP_TIMEOUT_MS)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    onDragEnd?.invoke()
                }
                removeCallbacks(singleTapRunnable)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(singleTapRunnable)
        stopPulse()
        super.onDetachedFromWindow()
    }

    /** 供控制器做吸附动画：直接设置自身 translation 并刷新（窗口位置由控制器算） */
    fun animateSnapX(from: Int, to: Int, onUpdate: (Int) -> Unit) {
        ValueAnimator.ofInt(from, to).apply {
            duration = SNAP_ANIM_MS
            addUpdateListener { onUpdate(it.animatedValue as Int) }
            start()
        }
    }

    companion object {
        private const val TAG = "GlassBallView"

        /** 球体直径：56dp，悬浮于应用最顶层不遮挡又能看清三行文字 */
        const val BALL_SIZE_DP = 56f

        /** 进度环宽度 */
        const val RING_WIDTH_DP = 3f

        /** 边缘吸附动画时长 */
        const val SNAP_ANIM_MS = 180L

        /** 单击/双击判定窗口：与系统 ViewConfiguration.getDoubleTapTimeout() 同量级 */
        const val DOUBLE_TAP_TIMEOUT_MS = 300L

        /** 待审呼吸动画单程时长（REVERSE 往复 ≈ 一个完整呼吸周期） */
        const val PULSE_CYCLE_MS = 600L
    }
}
