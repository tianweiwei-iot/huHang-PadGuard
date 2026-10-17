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
 * 早期实现只记一个"已申请过"的布尔量，于是：
 * 装机时点过一次同意 → 某次重启 → `requested=true` 让引导页再也不出现 →
 * 令牌却早没了 → 截屏/录屏从此**永久失效**，家长端只看到"指令发送失败"却查不到原因。
 *
 * 因此区分两个状态：
 * - [granted]：用户**曾经同意过**。同意过就等于授权了本产品做屏幕采集，
 *   进程重启后再次拉起授权页拿回令牌是合规且符合预期的；
 * - [requested] 且未 [granted]：用户明确拒绝过。**不再纠缠**，只在家长真正下发
 *   采集指令时由通知引导一次（那时上下文明确，孩子更容易理解）。
 *
 * ## 设备绑定镜像（暖令牌的前置门）
 * MediaProjection 的用户确认是"向孩子弹一个系统框"的高侵扰动作，
 * 因此它只允许发生在"确实有家长在等这个画面"的前提下 —— 即设备已绑定。
 * 未绑定（装机阶段：同意协议 → 登录 → 绑定）时没有任何人会下发采集指令，
 * 此刻每次打开 App 都顶一次"是否允许录制屏幕"纯属骚扰，
 * 这正是用户反馈"授权乱七糟、每次打开都弹"的直接成因。
 */
object CaptureConsent {

    private const val PREFS = "padguard_capture"
    private const val KEY_REQUESTED = "consent_requested"
    private const val KEY_GRANTED = "consent_granted"
    /**
     * 本轮解锁/开机内的"拒绝"标记。
     *
     * 与历史实现的关键区别：历史版本把孩子点一次"取消"写成 [KEY_GRANTED]=false 的**永久禁用**，
     * 结果就是"孩子拒绝一次 → 家长端实时看屏从此再也无法恢复"。
     * 这里只记"本轮拒绝"，由 [clearDenyCooldown] 在下次设备解锁/开机时清除，
     * 重新给一次补授权的机会——监护看屏是家长装机时确立的持久能力，不能被孩子一次点击永久剥夺。
     */
    private const val KEY_DENIED_SESSION = "consent_denied_session"

    /**
     * 设备绑定镜像。
     *
     * 为什么在这里维护一份镜像而不是直接读 DataStore：本对象的调用方
     * （开机广播、前后台观察者）都是**同步上下文**，而绑定态存在 DataStore 里
     * 是挂起 API。镜像只在权威时刻写入：绑定成功（[com.padguard.child.bind.BindViewModel]）
     * 与服务端确认解绑后的清理（[com.padguard.child.service.GuardService]），
     * 另由 GuardService 装载完凭据后自愈补写 —— 覆盖"旧版本升级上来、
     * 已绑定但没写过镜像"的设备。缺省 false（未绑定）：宁可不暖令牌
     * （家长真要下发指令时还有通知引导兜底），也不能在装机阶段反复骚扰孩子。
     */
    private const val KEY_DEVICE_BOUND = "device_bound"

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
        // 仅在本轮解锁/开机后"刚被孩子在系统框里拒绝"时短暂不打扰，避免循环弹窗；
        // 绝不再把一次拒绝写成"永久禁用"——否则孩子点一次拒授权，监护看屏就再也无法恢复。
        // 下一轮设备解锁（USER_PRESENT）/ 开机（BOOT_COMPLETED）会由 [clearDenyCooldown]
        // 清空该标记并再次尝试，由孩子在下次解锁时的系统确认来恢复监护能力。
        if (deniedThisSession(context)) return false
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

    /**
     * 本轮回绝冷却是否生效。
     *
     * 这里刻意**不**做"永久封禁"：一次拒绝只是"这次不想授权"，
     * 由 [clearDenyCooldown] 在下次设备解锁/开机时清除，重新给一次补授权的机会。
     * 监护看屏是家长在装机时确立的持久能力，不能因为孩子临时点了"取消"就永久失效。
     */
    fun deniedThisSession(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DENIED_SESSION, false)

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

    /**
     * 用户（孩子）在系统授权框点了"取消"：仅记一笔"本轮拒绝"，
     * 不顺手把 [KEY_GRANTED] 写成 false —— 那样会变成永久禁用看屏。
     * 下一轮解锁/开机 [clearDenyCooldown] 会清掉它，重新尝试补授权。
     */
    fun markDeniedTemporarily(context: Context) {
        prefs(context).edit().putBoolean(KEY_DENIED_SESSION, true).apply()
        Logger.w(TAG) { "capture consent denied this session, will retry on next unlock" }
    }

    /** 新一轮设备解锁/开机：清除上一轮的拒绝冷却，重新给一次补授权机会 */
    fun clearDenyCooldown(context: Context) {
        if (prefs(context).getBoolean(KEY_DENIED_SESSION, false)) {
            prefs(context).edit().putBoolean(KEY_DENIED_SESSION, false).apply()
            Logger.i(TAG) { "capture consent deny cooldown cleared on device interactive" }
        }
    }

    /** 设备是否已绑定家长账号（决定暖令牌是否允许打扰孩子）。 */
    fun isDeviceBound(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEVICE_BOUND, false)

    /** 写入绑定镜像。绑定成功 / 解绑清理时调用；值未变化时跳过写盘。 */
    fun markDeviceBound(context: Context, bound: Boolean) {
        val p = prefs(context)
        if (p.getBoolean(KEY_DEVICE_BOUND, false) == bound) return
        p.edit().putBoolean(KEY_DEVICE_BOUND, bound).apply()
        Logger.i(TAG) { "device bound mirror -> $bound" }
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
