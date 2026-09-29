package com.padguard.child.capture

import android.content.Context
import android.os.SystemClock
import com.padguard.core.common.Logger

/**
 * 屏幕采集授权的"预置 / 复权"标记。
 *
 * ## 为什么要预置
 * MediaProjection 必须由用户在系统弹窗里确认一次（Android 的隐私设计，Device Owner 也没有静默通道）。
 * 若不在装机引导阶段就把这个确认拿掉，那么家长第一次点"实时看屏"时，
 * 孩子屏幕上会先弹出一个系统授权框 —— 家长看到的是"点了没反应"，
 * 孩子则会被这个突兀的弹窗提醒"你正在被监控"，场景直接失真。
 *
 * 因此：装机完成（权限引导 + 登录 + 绑定全部就绪）后主动申请一次并记账，
 * 之后真正下发截屏/录屏/实时看屏指令时令牌已就位，全程无感。
 *
 * ## 为什么用 SharedPreferences 而不是进程内变量
 * 进程重启后变量会丢失，那样每次开机都会再弹一次授权框。
 * 这里只是几个布尔标记，用不到 DataStore 的 Flow/事务能力，直接 SharedPreferences 最轻。
 *
 * ## 重启后必须"复权"（关键，历史 bug）
 * MediaProjection 令牌是进程内的 IBinder，**进程一死就废**，设备重启更是必然丢失。
 * 旧实现只记一个"已申请过"的布尔量，于是：
 * 装机时点过一次同意 → 某次重启 → `requested=true` 让引导页再也不出现 →
 * 令牌却早没了 → 截屏/录屏从此**永久失效**，家长端只看到"指令发送失败"却查不到原因。
 *
 * 因此区分两个状态：
 * - [granted]：用户**曾经同意过**。同意过就等于授权了本产品做屏幕采集，
 *   进程重启后再次拉起授权页拿回令牌是合规且符合预期的；
 * - [requested] 且未 [granted]：用户明确拒绝过。**不再纠缠**，只在家长真正下发
 *   采集指令时由通知引导一次（那时上下文明确，孩子更容易理解）。
 */
object CaptureConsent {

    private const val PREFS = "padguard_capture"
    private const val KEY_REQUESTED = "consent_requested"
    private const val KEY_GRANTED = "consent_granted"

    /** 同一进程内两次拉起授权页的最小间隔，避免授权页把自己反复拉起 */
    private const val PROMPT_THROTTLE_MS = 60_000L

    @Volatile private var lastPromptMs = 0L

    /**
     * 是否应当现在去申请采集授权。
     *
     * - 已有令牌：不需要；
     * - 从未申请过（装机首次）：需要；
     * - 曾同意过但令牌已失效（进程 / 设备重启）：需要 —— 这是唯一能恢复采集的办法；
     * - 曾明确拒绝：不需要，不再打扰孩子。
     */
    fun shouldRequest(context: Context): Boolean {
        if (ScreenCaptureSession.hasToken()) return false
        // 只有"明确拒绝过"才不再打扰；其余情况（含历史版本遗留的"只记了已申请"）
        // 一律允许复权 —— 否则设备一重启采集就永久失效。
        if (denied(context)) return false
        return !throttled()
    }

    fun requested(context: Context): Boolean = prefs(context).getBoolean(KEY_REQUESTED, false)

    /**
     * 是否曾经同意过。
     *
     * 缺省值取 true 是**升级兼容**的刻意选择：旧版本只写了 `requested`，没有 `granted`，
     * 若缺省按 false 处理，老用户升级后会被判定成"拒绝过授权"从而永远不再申请，
     * 采集功能静默失效且无任何提示。按"曾同意"处理最多是重启后多弹一次授权框，
     * 代价远小于功能永久失效。
     */
    fun granted(context: Context): Boolean = prefs(context).getBoolean(KEY_GRANTED, true)

    /** 是否被明确拒绝过（仅 [markDenied] 会写入 false，据此区分"拒绝"与"未知"） */
    fun denied(context: Context): Boolean =
        prefs(context).contains(KEY_GRANTED) && !prefs(context).getBoolean(KEY_GRANTED, true)

    fun markRequested(context: Context) {
        lastPromptMs = SystemClock.elapsedRealtime()
        prefs(context).edit().putBoolean(KEY_REQUESTED, true).apply()
        Logger.i(TAG) { "capture consent marked as requested" }
    }

    /** 用户同意：此后进程重启也可以合法地重新拉起授权页拿回令牌 */
    fun markGranted(context: Context) {
        prefs(context).edit().putBoolean(KEY_GRANTED, true).apply()
        Logger.i(TAG) { "capture consent granted" }
    }

    /** 用户拒绝：停止主动引导，只在家长真正下发采集指令时再提示一次 */
    fun markDenied(context: Context) {
        prefs(context).edit().putBoolean(KEY_GRANTED, false).apply()
        Logger.w(TAG) { "capture consent denied, stop auto prompting" }
    }

    private fun throttled(): Boolean {
        val last = lastPromptMs
        if (last == 0L) return false
        return SystemClock.elapsedRealtime() - last < PROMPT_THROTTLE_MS
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "CaptureConsent"
}
