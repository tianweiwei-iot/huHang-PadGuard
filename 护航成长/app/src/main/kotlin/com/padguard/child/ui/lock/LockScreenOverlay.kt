package com.padguard.child.ui.lock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
    private val unlockRequestRepository: UnlockRequestRepository
) {

    private val wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var root: View? = null

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
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
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
            hint = "申请解锁的理由（可不填）"
            setHintTextColor("#FF9E9E9E".toColorInt())
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        container.addView(input)

        container.addView(Button(context).apply {
            text = "申请解锁"
            setPadding(0, (16 * density).toInt(), 0, 0)
            setOnClickListener {
                submitUnlock(input.text?.toString().orEmpty().trim())
            }
        })
        return container
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

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private companion object {
        const val TAG = "LockScreenOverlay"
        val BG_COLOR = "#F21B1B1B".toColorInt()
        const val ID_DETAIL = 0x00D0_0001
        /** 与 LockScreenActivity 的默认申请时长保持一致 */
        const val DEFAULT_REQUEST_MINUTES = 30
    }
}
