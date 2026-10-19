package com.padguard.child.ui.lock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.padguard.core.common.Logger
import com.padguard.core.data.repository.UnlockRequestRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 后台可用的锁定层（悬浮窗）。
 *
 * ## 为什么必须有这一层
 * 锁屏原本只靠 [LockScreenActivity]：守护服务收到锁屏命令后 `startActivity` 拉起锁定页。
 * 但 **Android 10（API 29）起系统禁止后台启动 Activity**，而孩子端最常见的状态恰恰是"不在前台"——
 * 退出家庭账号后停在登录页、按 Home 回到桌面、正在用别的应用。
 * 此时 `startActivity` 会被系统**静默丢弃**（不抛异常，日志里什么都看不到），
 * 表现就是"家长端点了锁屏、回执还是成功，平板上却毫无变化"。
 *
 * 悬浮窗不受这条限制：只要持有 SYSTEM_ALERT_WINDOW（一次性授权已收走），
 * 后台也能立刻铺满屏幕，是本机唯一可靠的应用内锁定手段。
 *
 * ## 与 Activity 的分工
 * - 应用在前台 → 仍走 Activity（体验一致、能用 Lock Task 固定）；
 * - 应用在后台 → 本层接管；
 * - 两者互斥显示，由 [LockScreenCoordinator] 统一调度，不会出现两层叠着。
 *
 * ## 为什么不复用 Compose
 * 在 WindowManager 里渲染 Compose 需要手工装配 LifecycleOwner / SavedStateRegistryOwner，
 * 一旦装配遗漏就是崩溃或空白。这一层只承担"挡住屏幕 + 提供申请解锁入口"，
 * 用原生 View 更稳；完整的申请交互仍由 Activity 承担。
 */
@Singleton
class LockScreenOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val unlockRequestRepository: UnlockRequestRepository,
    private val keyguardBridge: SystemKeyguardBridge
) {

    private val wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null

    /**
     * 「拉起申请页被 ROM 静默丢弃」的兜底提交。
     *
     * 后台启动 Activity 在部分 ROM 上不抛异常、直接被吃掉，孩子点了「申请解锁」
     * 却毫无反应。这里给 1.5s 宽限：锁屏页仍未到前台就退回悬浮窗内直接提交，
     * 与历史行为保持一致（理由可为空）。
     */
    private var pendingFallbackSubmit: Runnable? = null

    /** 是否具备悬浮窗权限。缺失时必须由调用方明确降级/上报，不能静默当作已锁。 */
    fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * 显示锁定层。
     *
     * 幂等：已显示时只更新文案，不会叠第二层（那会让屏幕越来越暗，且返回键要多按几次）。
     */
    @SuppressLint("RtlHardcoded")
    fun show(detail: String) {
        if (!canDrawOverlay()) {
            Logger.w(TAG) { "cannot draw overlay: SYSTEM_ALERT_WINDOW not granted" }
            return
        }
        val existing = root
        if (existing != null) {
            existing.findViewById<TextView>(ID_DETAIL)?.text = detail
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            windowAnimations = 0
        }

        val view = buildView(detail)
        runCatching {
            wm.addView(view, params)
            root = view
            Logger.i(TAG) { "lock overlay shown" }
            // WindowManager 浮窗不会像 Activity 那样自动弹出软键盘；必须主动把焦点交给
            // 输入框并显式 showSoftInput，否则"申请解锁理由"输入框点不出键盘。
            view.post {
                val et = view.findViewById<EditText?>(ID_INPUT)
                et?.requestFocus()
                et?.let { showKeyboard(it) }
            }
        }.onFailure {
            // 部分 ROM 在后台任务栈里 addView 会抛 BadTokenException。
            // 这里吞掉是有意的：失败不影响其他管控能力，下次状态翻转会重试。
            Logger.w(TAG) { "failed to add lock overlay: ${it.message}" }
            root = null
        }
    }

    fun dismiss() {
        val view = root ?: return
        root = null
        pendingFallbackSubmit?.let { handler.removeCallbacks(it) }
        pendingFallbackSubmit = null
        runCatching { wm.removeView(view) }
            .onFailure { Logger.w(TAG) { "failed to remove lock overlay: ${it.message}" } }
    }

    fun isShowing(): Boolean = root != null

    @SuppressLint("SetTextI18n")
    private fun buildView(detail: String): View {
        val density = context.resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_COLOR)
            setPadding(pad, (48 * density).toInt(), pad, pad)
            // 关键：锁定层必须吃掉触摸，否则孩子能直接点穿到下面的应用
            isClickable = true
            // 必须可聚焦：不可聚焦的窗口拿不到按键派发，返回键会直接穿给下层应用，
            // 表现就是"铺了锁定层，按返回照样退出刚才的应用"。
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            // 吞掉返回 / 最近任务 / 菜单：锁定层是最后一道防线，不能留逃逸键。
            // HOME 由系统保留、无法拦截，靠 LockScreenPresenter 的持续重试把锁定页顶回来。
            setOnKeyListener { _, keyCode, _ -> keyCode in BLOCKED_KEYS }
            // 隐藏状态栏与导航栏：留着导航栏会给孩子"还能滑"的暗示（悬浮层无法真正禁手势，
            // 但隐藏视觉提示能显著降低尝试率；真正的手势封锁由 Activity 的 Lock Task 承担）。
            systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }

        container.addView(TextView(context).apply {
            text = "设备已被家长锁定"
            setTextColor(Color.WHITE)
            textSize = 22f
        })
        container.addView(TextView(context).apply {
            id = ID_DETAIL
            text = detail
            setTextColor("#FFE0E0E0".toColorInt())
            textSize = 15f
            setPadding(0, (8 * density).toInt(), 0, 0)
        })

        val input = EditText(context).apply {
            id = ID_INPUT
            hint = "申请解锁的理由（可不填）"
            setHintTextColor("#FF9E9E9E".toColorInt())
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(0, (16 * density).toInt(), 0, 0)
            // 浮窗 EditText 必须主动请求焦点并弹 IME：WindowManager 浮窗不像 Activity 那样
            // 自动弹出软键盘。若只 addView 不显式 showSoftInput，输入框获得焦点却无键盘，
            // 表现为"键盘用不了"。点击时再补一次，避免初次自动弹失败。
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) showKeyboard(this)
            }
        }
        container.addView(input)

        container.addView(Button(context).apply {
            text = "申请解锁"
            setPadding(0, (16 * density).toInt(), 0, 0)
            setOnClickListener {
                // 系统锁屏（keyguard）锁定态下，悬浮窗输入框同样弹不出输入法——
                // 平台限制：IME 窗口无法显示在 keyguard 之上，只有 Activity 能经
                // requestDismissKeyguard 撤锁后正常弹键盘。此时拉起锁屏页走正规申请流程
                // （LockScreenActivity.MODE_REQUEST：撤锁 → 对话框+键盘 → 提交后补压系统锁）。
                // 非硬锁设备（无 keyguard）保持旧行为：悬浮窗内直接提交，不跳页面。
                if (!keyguardBridge.isKeyguardLocked()) {
                    submitUnlock(input.text?.toString().orEmpty().trim())
                    return@setOnClickListener
                }
                val launched = runCatching {
                    context.startActivity(
                        LockScreenActivity.intent(
                            context,
                            detail,
                            LockScreenActivity.MODE_REQUEST
                        )
                    )
                }.isSuccess
                if (launched) {
                    scheduleFallbackSubmit(input)
                } else {
                    // 直接拉起即失败（罕见）：立刻退回悬浮窗内提交，不让孩子按了没反应
                    submitUnlock(input.text?.toString().orEmpty().trim())
                }
            }
        })
        return container
    }

    /**
     * 兜底提交：锁屏页 1.5s 内未到前台（ROM 静默丢弃后台启动），
     * 退回悬浮窗内直接提交。锁屏页到位后本兜底由 [dismiss] 撤掉。
     */
    private fun scheduleFallbackSubmit(input: EditText) {
        pendingFallbackSubmit?.let { handler.removeCallbacks(it) }
        val fallback = Runnable {
            pendingFallbackSubmit = null
            if (!LockScreenActivity.visible) {
                Logger.w(TAG) { "request activity never came up, falling back to in-overlay submit" }
                submitUnlock(input.text?.toString().orEmpty().trim())
            }
        }
        pendingFallbackSubmit = fallback
        handler.postDelayed(fallback, REQUEST_LAUNCH_FALLBACK_MS)
    }

    /**
     * 提交解锁申请。
     *
     * 与 Activity 走同一个 [UnlockRequestRepository]：业务口径只有一份，
     * 不会出现"悬浮层提交的申请家长端看不到"这类分叉。
     */
    private fun submitUnlock(reason: String) {
        scope.launch {
            runCatching {
                unlockRequestRepository.expireStale()
                unlockRequestRepository.submit(
                    packageName = "",
                    appLabel = "整台设备",
                    reasonText = reason,
                    durationMinutes = DEFAULT_REQUEST_MINUTES
                )
            }.onSuccess {
                Logger.i(TAG) { "unlock request submitted from overlay" }
                toast("已提交申请，等待家长同意")
            }.onFailure {
                Logger.w(TAG) { "unlock request failed: ${it.message}" }
                toast("申请失败：${it.message ?: "请稍后再试"}")
            }
        }
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 在 WindowManager 浮窗里显式弹起软键盘（浮窗不会自动弹 IME）。 */
    private fun showKeyboard(editText: EditText) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private companion object {
        const val TAG = "LockScreenOverlay"
        val BG_COLOR = "#F21B1B1B".toColorInt()
        const val ID_DETAIL = 0x00D0_0001
        const val ID_INPUT = 0x00D0_0002
        /** 与 LockScreenActivity 的默认申请时长保持一致 */
        const val DEFAULT_REQUEST_MINUTES = 30

        /**
         * 拉起申请页后的兜底等待：约 1.5s。
         * 覆盖"ROM 静默丢弃后台启动"——锁屏页到前台会撤掉本兜底（见 [dismiss]）。
         */
        const val REQUEST_LAUNCH_FALLBACK_MS = 1500L

        /** 锁定层吞掉的按键 */
        private val BLOCKED_KEYS = setOf(
            android.view.KeyEvent.KEYCODE_BACK,
            android.view.KeyEvent.KEYCODE_APP_SWITCH,
            android.view.KeyEvent.KEYCODE_MENU,
            android.view.KeyEvent.KEYCODE_RECENT_APPS
        )
    }
}
