package com.padguard.core.common

import android.util.Log

/**
 * 统一日志门面。
 *
 * release 构建中被 proguard 剥离 d/v 级别（见 app/proguard-rules.pro）：
 * `-assumenosideeffects class android.util.Log { d(...); v(...); }`
 */
object Logger {

    private const val TAG = "PadGuard"
    private const val MAX_LINE = 3500

    var enabled: Boolean = true

    fun v(tag: String = TAG, msg: () -> String) {
        if (enabled) log(Log.VERBOSE, tag, safeMsg(msg))
    }

    fun d(tag: String = TAG, msg: () -> String) {
        if (enabled) log(Log.DEBUG, tag, safeMsg(msg))
    }

    fun i(tag: String = TAG, msg: () -> String) {
        if (enabled) log(Log.INFO, tag, safeMsg(msg))
    }

    fun w(tag: String = TAG, msg: () -> String) {
        if (enabled) log(Log.WARN, tag, safeMsg(msg))
    }

    fun w(tag: String = TAG, tr: Throwable? = null, msg: () -> String) {
        if (enabled) Log.w(tag, safeMsg(msg), tr)
    }

    fun e(tag: String = TAG, tr: Throwable? = null, msg: () -> String) {
        if (enabled) Log.e(tag, safeMsg(msg), tr)
    }

    /**
     * 日志门面必须是副作用无关的：msg lambda 内部抛异常（如字符串模板里空指针 /
     * 意外的 toString）绝不能逃逸到调用方——否则在一个周期循环或 catch 块里抛日志
     * 会杀死循环或掩盖真实错误（fail-open）。这里把求值包进 runCatching。
     */
    private fun safeMsg(msg: () -> String): String =
        runCatching { msg() }.getOrElse { "<log msg eval failed: ${it.message}>" }

    private fun log(priority: Int, tag: String, message: String) {
        // Logcat 单行上限约 4k 字符，超长日志分片输出，避免被截断
        if (message.length <= MAX_LINE) {
            Log.println(priority, tag, message)
            return
        }
        var start = 0
        while (start < message.length) {
            val end = (start + MAX_LINE).coerceAtMost(message.length)
            Log.println(priority, tag, message.substring(start, end))
            start = end
        }
    }
}
