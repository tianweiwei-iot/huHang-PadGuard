package com.padguard.child.usagesession

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.padguard.core.common.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *
 * ## 2026-11-09：双按钮 + 永不自动开始
 * - 「开始计时」→ [onConfirm]（在线经服务端 start()，离线本地起表）；
 * - 「暂不开始」→ 收起浮层并进入 [DECLINE_SUPPRESS_MS] 抑制窗口：窗口内不再自动弹出
 *   （否则 10s 心跳立刻把浮层顶回来，等于没有"暂不"）。窗口过后恢复自动提示
 *   （needsStartConfirm 仍成立时）。等待页（LockScreenActivity 的 AWAITING_START）上的
 *   「开始计时」按钮不受抑制影响，孩子随时可主动确认；
 * - 自动确认（原 3 分钟超时 confirmStart）已删除：未确认 = 不开始计时，没有绕过面。
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

    /**
     * 一次调用的**不可变意图快照**：是否展示 / 是否家长催办 / 点击回调。
     *
     * 每个 `mainHandler.post` 自带一份快照，由 [reconcile] 在主线程串行消费。
     * 这样既保证 add/remove 全部收敛到主线程（见 [show] 注释），又不会让后一次调用
     * 覆盖掉前一次尚未执行的意图 —— 特别是家长端「开始计时」催办（fromParent=true，
     * 必须绕过孩子的「暂不开始」抑制窗口）：若用共享可变字段，紧随其后的 10s 心跳
     * 普通 show 会把 fromParent 降级成 false，催办就被抑制窗口吞掉了。
     *
     * 同一时刻的多次调用按 post 入队顺序串行执行，「最后入队的意图」决定最终状态；
     * 每次收敛都自带完整快照，不存在跨调用撕裂（visible/fromParent/onConfirm 永远同源）。
     */
    private data class Intent(
        val visible: Boolean,
        val fromParent: Boolean,
        val onConfirm: (() -> Unit)?
    )

    /**
     * 浮层是否正在展示（view 已 add 到 WindowManager）。
     * 锁屏等待页（AWAITING_START）据此决定是否需要把"开始计时"按钮作为兜底——
     * 浮层在展示时它就是唯一的确认入口，锁屏页不再重复提供按钮，避免"开始计时"在两处同时出现。
     */
    val isShowing: Boolean
        get() = view != null

    /**
     * 展示态的可观察流（与 [isShowing] 同步），供锁屏等待页 Compose 实时响应浮层显隐，
     * 从而在浮层被"暂不开始"抑制 / 权限缺失 / 屏幕熄灭收起时，立即把兜底按钮交还给孩子。
     */
    private val _showing = MutableStateFlow(false)
    val showingFlow: StateFlow<Boolean> = _showing.asStateFlow()

    /**
     * 「暂不开始」的抑制截止（单调时钟，A7 短时窗规则：高水位 + elapsedRealtime，不受改时间影响）。
     * 0 表示不在抑制窗口内。
     */
    @Volatile private var declinedUntilElapsed: Long = 0L

    /**
     * 是否能绘制系统浮层（SYSTEM_ALERT_WINDOW）。
     * 锁屏等待页据此判断：无此权限时浮层永远无法展示，必须让锁屏页的"开始计时"按钮作为兜底。
     */
    val canDraw: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)

    /**
     * 展示计时开始确认浮层（幂等：已展示则直接返回）。[confirmAction] 为点「开始计时」后的回调。
     *
     * @param fromParent true = 家长端「开始计时」指令触发（force 弹出并换文案），
     *        即使孩子刚点过「暂不开始」也要弹出——家长催办优先于孩子的抑制窗口。
     *        false = 日切自动提示，受 [declinedUntilElapsed] 抑制。
     */
    fun show(confirmAction: () -> Unit, fromParent: Boolean = false) {
        // 只登记"最新意图"，不直接操作 WindowManager。
        //
        // 旧实现是「调用线程里判空 → 主线程 post 内才构造 View 并挂载」：
        // 检查与赋值隔着一次主线程排队，而 GuardService 有三条路径会在同一帧内
        // 连续/并发调用 —— 状态流 collect（needsStartConfirm 变化）、10s 心跳兜底、
        // 家长端 START_TIMER_CONFIRM 指令。两次调用都看到 view==null，于是排进两次
        // 挂载，屏幕上叠出两层浮层；view 只指向最后一层，hide() 只能移走一层，
        // 另一层变成点不掉的僵尸浮层（真机表现：计时浮层不对、确认后仍不开始）。
        // 同理，hide() 与一个尚未执行的 show post 交错时，浮层会在收起后又自己冒出来。
        //
        // 现在统一收敛到 [reconcile]：所有路径 post 一份自带快照的纯收敛，
        // 主线程串行执行，天然幂等，且后入队的意图胜出（见 [Intent] 注释）。
        val intent = Intent(visible = true, fromParent = fromParent, onConfirm = confirmAction)
        mainHandler.post { reconcile(intent) }
    }

    /** 移除计时开始确认浮层（幂等）：post 一份"不展示"快照并收敛。 */
    fun hide() {
        val intent = Intent(visible = false, fromParent = false, onConfirm = null)
        mainHandler.post { reconcile(intent) }
    }

    /**
     * 主线程串行收敛：把 [intent] 映射到 WindowManager 的真实状态。
     *
     * 只在主线程执行，因此 [view] / [_showing] / [onConfirm] / [declinedUntilElapsed]
     * 无需额外加锁；[intent] 是本次调用自带的不可变快照，全程口径一致。
     */
    private fun reconcile(intent: Intent) {
        if (!intent.visible) {
            removeViewIfPresent()
            return
        }
        if (!canDraw) {
            Logger.w(TAG) { "SYSTEM_ALERT_WINDOW not granted -> 无法弹出计时开始浮层" }
            // 权限可能在浮层已展示后被系统/家长撤销，此时也要如实收起，
            // 避免留一个点不动、又压着锁屏兜底按钮的孤儿浮层。
            removeViewIfPresent()
            return
        }
        if (view != null) return // 已展示，幂等
        if (!intent.fromParent && declinedUntilElapsed != 0L &&
            SystemClock.elapsedRealtime() < declinedUntilElapsed
        ) {
            return // 「暂不开始」抑制窗口内；家长催办（fromParent）不受此限
        }
        val action = intent.onConfirm
        if (action == null) {
            Logger.w(TAG) { "计时开始浮层缺少确认回调 -> 不展示（避免点了没反应的死浮层）" }
            return
        }
        onConfirm = action
        declinedUntilElapsed = 0L
        runCatching {
            val v = buildView(intent.fromParent)
            view = v
            _showing.value = true
            wm.addView(v, buildParams())
            Logger.i(TAG) { "计时开始浮层已展示 (fromParent=${intent.fromParent})" }
        }.onFailure {
            // 关键：挂载失败必须把状态一起回滚。旧实现只记日志，却已把 view 赋值，
            // 之后所有 show() 都被判空挡住 → 浮层永远不再出现。
            view = null
            _showing.value = false
            onConfirm = null
            Logger.e(TAG, it) { "add 计时开始浮层失败" }
        }
    }

    /** 真正从 WindowManager 摘除当前浮层（只在主线程调用）。 */
    private fun removeViewIfPresent() {
        val v = view ?: return
        view = null
        onConfirm = null
        _showing.value = false
        runCatching { wm.removeView(v) }
            .onFailure { Logger.w(TAG, it) { "remove 计时开始浮层失败" } }
    }

    /** 孩子点「暂不开始」：收起浮层并进入抑制窗口（期间不再自动弹出）。 */
    private fun decline() {
        declinedUntilElapsed = SystemClock.elapsedRealtime() + DECLINE_SUPPRESS_MS
        Logger.i(TAG) { "计时开始被暂缓 ${DECLINE_SUPPRESS_MS / 60_000L} 分钟" }
        hide()
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

    private fun buildView(fromParent: Boolean): View {
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
            text = if (fromParent) {
                "家长已为你开启今日平板使用计时。点「开始计时」后即开始记录今日使用时间，" +
                    "到达今日限额后平板会自动进入管控。（每天需确认一次）"
            } else {
                "每天使用前需确认一次：点「开始计时」后开始记录今日使用时间，" +
                    "到达今日限额后平板会自动进入管控。这是正常提醒，并非故障。"
            }
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

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val confirmBtn = Button(context).apply {
            text = "开始计时"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1976D2"))
                cornerRadius = 10 * dip
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                // 回调在点击线程（主线程）执行；confirmStart 是 suspend，
                // 由 GuardService 注入的 lambda 自行切协程。
                onConfirm?.invoke()
            }
        }
        val declineBtn = Button(context).apply {
            text = "暂不开始"
            setTextColor(Color.parseColor("#616161"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1EFE8"))
                cornerRadius = 10 * dip
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = (10 * dip).toInt()
            }
            setOnClickListener { decline() }
        }
        buttonRow.addView(confirmBtn)
        buttonRow.addView(declineBtn)

        root.addView(title)
        root.addView(body)
        root.addView(buttonRow)
        return root
    }

    companion object {
        private const val TAG = "UsageStartOverlay"

        /**
         * 点「暂不开始」后的自动提示抑制窗口（10 分钟）。
         * 单调时钟 + 高水位（A7）：不受改系统时间影响；浮层再次弹出前会被重置。
         * 等待页常驻按钮不受此窗口影响（那里是孩子主动确认的唯一稳定入口）。
         */
        private const val DECLINE_SUPPRESS_MS = 10 * 60_000L
    }
}
