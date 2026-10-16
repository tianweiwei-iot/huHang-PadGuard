package com.padguard.child.guard

/**
 * 连续点击（连点）计数器。
 *
 * ## 用途
 * 受控态（锁定 / 时间到）期间，孩子连续快速尝试打开被管控应用时，
 * 不应每次都只是"顶回桌面（闪退）"——那样既闪烁又给不出明确反馈。
 * 本计数器把"窗口内连续被拦截达到阈值"识别为"连点"，交由调用方升级为
 * 一次性弹窗提示（"您当前无权限操作"）。
 *
 * ## 设计要点（与 HOME 去抖完全解耦）
 * - **每包独立计数**：不同应用互不影响；
 * - **滑动窗口**：窗口内累计被拦截次数达到 [threshold] 即视为连点；
 * - **过期懒重置**：窗口外无任何新命中时，下次命中自动把计数归零，避免"早上的三次点击"
 *   到下午还被算作连点；
 * - **触发后由调用方显式 reset**：弹窗期间不再重复触发。
 *
 * 纯逻辑、无 Android 依赖，可直接在 JVM 单测。
 */
class BlockCounter(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val threshold: Int = DEFAULT_THRESHOLD
) {

    private data class Entry(var count: Int, var firstAt: Long)

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * 记录一次拦截。
     *
     * @param pkg 被拦截的包名
     * @param now 当前时间戳（注入以便单测，默认取系统时钟）
     * @return 若已达连点阈值（应弹窗）返回 true
     */
    fun record(pkg: String, now: Long = System.currentTimeMillis()): Boolean {
        val entry = entries[pkg] ?: Entry(count = 0, firstAt = now).also { entries[pkg] = it }
        // 窗口滑动过期：距首次命中已超过窗口，视为新一轮，计数从头开始。
        if (now - entry.firstAt > windowMs) {
            entry.count = 0
            entry.firstAt = now
        }
        entry.count += 1
        return entry.count >= threshold
    }

    /**
     * 重置某包的计数（弹窗后调用，避免弹窗期间反复触发）。
     * 同时清理其它已自然过期的窗口，防止 [entries] 无限增长。
     */
    fun reset(pkg: String, now: Long = System.currentTimeMillis()) {
        entries.remove(pkg)
        pruneExpired(now)
    }

    /** 清理所有已超过窗口、不会再被命中的计数，避免长期驻留内存。 */
    private fun pruneExpired(now: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val (key, value) = it.next()
            if (now - value.firstAt > windowMs) it.remove()
        }
    }

    companion object {
        /** 滑动窗口长度：默认 3 秒 */
        const val DEFAULT_WINDOW_MS = 3000L

        /** 连点阈值：默认连续 3 次 */
        const val DEFAULT_THRESHOLD = 3
    }
}
