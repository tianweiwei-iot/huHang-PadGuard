package com.padguard.child.permission

import android.content.Context
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「曾经授予过」账本：把授权回退自愈从「没开就拉回」修正为「开过又丢才拉回」。
 *
 * ## 为什么需要它（授权专项「逻辑合理」）
 * 旧 [AuthorizationKeeper] 只要看到某个特殊权限当前是缺失态，就置 `reauthRequired`
 * 把家长拉回授权页。这在两类完全正常的场景里会变成骚扰：
 * - 家长在装机时**明确跳过**了某项（清单里有「暂时跳过」出口）；
 * - 该机型压根没有这个开关（ROM 阉割），走完一趟也开不了。
 * 两者都不是"授权掉了"，却每 15 分钟被拉回一次，家长看到的就是"怎么授权都授权不完"。
 *
 * 反过来，真正危险的是"曾开过又被关掉"（孩子在系统设置里关掉无障碍 / 悬浮窗、
 * 或 ROM 切换权限时强制重启 App 清空授权）——这才是必须把家长叫回来的场景。
 *
 * 因此判据与 [isDeviceAdminMissing] 的既有纪律一致：**区分「从未有过」与「曾有过又丢」**。
 * 账本跨进程 / 重启持久化（SharedPreferences），只在确实观察到「已授予」时单向写入，
 * 没有「删除」入口：一次都不会因为状态抖动把历史抹掉。
 *
 * ## 不可检测项不进账本
 * [SpecialPermission.OEM_BACKGROUND] 这类 OEM 私有开关没有公开查询接口，
 * `isGranted` 恒 false；把它记进账本只会得到一条永远无法闭合的假告警。
 * [needsReauth] 用 `detectable && requiredForReauth` 两道闸把它们挡在外面。
 */
@Singleton
class AuthorizationLedger @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 观察到某项确实处于授予态时调用；重复调用是幂等的。 */
    fun observe(key: String, granted: Boolean) {
        if (!granted) return
        // 白名单收口在数据层，不靠每个调用方自觉：
        // ① 不在目录里的 key（拼写错误 / 未来误传 / 外部注入）不该把账本撑大，
        //    更不该在 needsReauth 里被当成"曾授予又丢"而误拉回授权页；
        // ② 不可检测 / 明确不参与自愈的项（厂商私有开关）记进去只会得到一条
        //    永远无法闭合的假告警 —— 与 needsReauth 的两道闸保持同一口径。
        if (!isTrackable(key)) {
            Logger.w(TAG) { "ignore untrackable authorization key: $key" }
            return
        }
        if (prefs.getBoolean(prefixed(key), false)) return
        // 单向、无删除入口：丢一次就永远补不回来（日后权限被撤销再也不会拉回授权页）。
        // 只在首次观察到授予时写一次，用 commit 同步落盘，不产生持续 I/O。
        prefs.edit().putBoolean(prefixed(key), true).commit()
        Logger.i(TAG) { "recorded ever-granted: $key" }
    }

    fun everGranted(key: String): Boolean = prefs.getBoolean(prefixed(key), false)

    private fun prefixed(key: String) = "$KEY_PREFIX$key"

    companion object {
        private const val TAG = "AuthorizationLedger"
        private const val PREFS_NAME = "padguard_auth_ledger"
        private const val KEY_PREFIX = "ever_"

        /**
         * 该 key 是否值得进账本（**纯函数**，便于 JVM 单测锁定）。
         *
         * 判据与 [needsReauth] 的两道闸严格一致：只有「有公开状态查询接口」且
         * 「参与掉失自愈」的目录项，才可能出现"曾授予又丢"这一真实信号。
         */
        fun isTrackable(key: String): Boolean =
            SpecialPermission.fromKey(key)?.let { it.detectable && it.requiredForReauth } == true

        /**
         * 是否应把家长拉回授权页（**纯函数**，便于单测锁定四条分支）。
         *
         * - 当前已授予 → 不拉回；
         * - 不可检测 / 明确不参与自愈的项 → 不拉回（否则每次自检都误报）；
         * - 从未授予过 → 不拉回（尊重家长的主动跳过，也避免 ROM 无此开关时死循环）；
         * - 曾授予、现在丢了 → **拉回**，这是唯一的真实脱管信号。
         */
        fun needsReauth(
            granted: Boolean,
            everGranted: Boolean,
            detectable: Boolean,
            requiredForReauth: Boolean
        ): Boolean = !granted && everGranted && detectable && requiredForReauth
    }
}
