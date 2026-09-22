package com.padguard.child.capture

import android.content.Context
import com.padguard.core.common.Logger

/**
 * 屏幕采集授权的"预置"标记。
 *
 * ## 为什么要预置
 * MediaProjection 必须由用户在系统弹窗里确认一次（Android 的隐私设计，Device Owner 也没有静默通道）。
 * 若不在装机引导阶段就把这个确认拿掉，那么家长第一次点"实时看屏"时，
 * 孩子屏幕上会先弹出一个系统授权框 —— 家长看到的是"点了没反应"，
 * 孩子则会被这个突兀的弹窗提醒"你正在被监控"，场景直接失真。
 *
 * 因此：装机完成（权限引导 + 登录 + 绑定全部就绪）后主动申请一次并记住"已申请过"，
 * 之后真正下发截屏/录屏指令时令牌已就位，全程无感。
 *
 * ## 为什么用 SharedPreferences 而不是进程内变量
 * 进程重启后变量会丢失，那样每次开机都会再弹一次授权框。
 * 这里只是个"是否已尝试过"的布尔标记，用不到 DataStore 的 Flow/事务能力，
 * 直接 SharedPreferences 最轻。
 *
 * ## 用户拒绝了怎么办
 * 照样标记"已申请"，**不反复弹**。真到需要采集时 [ScreenCaptureService] 会再走一次授权流程，
 * 那时的弹窗是有明确上下文的（家长正在请求看屏），比开机就弹更容易被接受。
 */
object CaptureConsent {

    private const val PREFS = "padguard_capture"
    private const val KEY_REQUESTED = "consent_requested"

    /** 是否应当现在去申请采集授权：尚未持有令牌，且从未申请过 */
    fun shouldRequest(context: Context): Boolean =
        !ScreenCaptureSession.hasToken() && !requested(context)

    fun requested(context: Context): Boolean = prefs(context).getBoolean(KEY_REQUESTED, false)

    fun markRequested(context: Context) {
        prefs(context).edit().putBoolean(KEY_REQUESTED, true).apply()
        Logger.i(TAG) { "capture consent marked as requested" }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "CaptureConsent"
}
