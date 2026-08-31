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
        if (enabled) log(Log.VERBOSE, tag, msg())
    }

    fun d(tag: String = TAG, msg: () -> String) {
        if (enabled) log(Log.DEBUG, tag, msg())
    }

    fun i(tag: String = TAG, msg: () -> String) {
        if (enabled) log(Log.INFO, tag, msg())
    }

    fun w(tag: String = TAG, msg: () -> String) {
        if (enabled) log(Log.WARN, tag, msg())
    }

    fun w(tag: String = TAG, tr: Throwable? = null, msg: () -> String) {
        if (enabled) Log.w(tag, msg(), tr)
    }

    fun e(tag: String = TAG, tr: Throwable? = null, msg: () -> String) {
        if (enabled) Log.e(tag, msg(), tr)
    }

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
