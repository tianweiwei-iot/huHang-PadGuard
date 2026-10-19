package com.padguard.child.ui.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import com.padguard.core.common.Logger
import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.admin.OpResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统级锁屏（keyguard）的生命周期管理。
 *
 * ## 为什么需要这一层（两个线上缺陷的共同根因）
 * 孩子端锁屏在「无 Device Owner、仅有设备管理员」的 C 端平板上，靠
 * [com.padguard.core.engine.admin.DeviceAdminBridge.lockNow] 压一次**系统 keyguard**
 * 来封堵手势逃逸（HOME/最近任务/上滑回桌面）。这正是用户认可的「锁屏并熄屏」效果，
 * 必须保留。但系统 keyguard 一旦压下，会连带出两个此前没有对称处理的问题：
 *
 * 1. **孩子无法输入解锁申请（没有软键盘）**：Android 平台限制——设备被 keyguard 锁定时，
 *    即使是 `setShowWhenLocked` 显示在 keyguard 之上的窗口，**输入法（IME）也无法显示**
 *    （IME 窗口被 keyguard 窗口压制，键盘"弹不出来"）。这是 kiosk/锁屏类应用的已知
 *    平台行为，官方解法是在需要输入时先 `requestDismissKeyguard` 撤掉系统锁，用完再压回。
 *    此前代码压了系统锁却没有任何撤锁路径，于是「申请解锁」对话框里的输入框永远点不出键盘。
 *
 * 2. **家长解锁后孩子端毫无反应**：家长端 UNLOCK 指令只清掉了 App 内部的锁定态
 *    （[com.padguard.core.engine.lock.LockController] + 收起锁屏页），**系统 keyguard
 *    仍被 lockNow 锁着、屏幕仍熄着**——孩子端看起来就是"解锁了但什么都没发生"。
 *    系统锁是我们压下去的，解开时也必须由我们撤掉，否则这一侧永远不对称。
 *
 * ## 记账为什么必须持久化
 * 「系统锁是不是我们压的」不能只存内存：锁屏可能持续数小时，期间应用进程随时可能被
 * 回收再拉活（看门狗）。进程一重启，内存态清零，解锁时就没人知道该撤 keyguard 了。
 * 因此压锁时写入 SharedPreferences，解锁撤锁后清除，跨进程重启保持同一口径。
 *
 * ## 「待撤锁」意图同样必须持久化（解锁死锁的根治）
 * 家长解锁是一条**跨组件的意图**：指令到达时锁屏页可能已被回收、进程可能刚重启、
 * FSI 与后台启动可能都被 ROM 静默吞掉。若这条意图只活在内存里，任何一次意外
 * （撤锁回调不来 / 页面被回收 / 进程死亡）都会让它永久丢失 —— 表现为
 * 「家长反复点解锁，孩子端始终黑屏无反应」。
 * 因此解锁意图以 [KEY_PENDING_UNLOCK_AT] 落盘，由多个自愈触发点（锁屏页 onResume、
 * 无障碍看门狗、守护服务周期自检、进程启动）共同追逐，直到 keyguard 确认不在为止。
 * 只有两种结局会清掉它：撤锁成功、或发现系统锁本就不在。
 */
@Singleton
class SystemKeyguardBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val keyguardManager: KeyguardManager? =
        context.getSystemService(KeyguardManager::class.java)

    // ==================== 状态探测 ====================

    /** 系统 keyguard 当前是否处于锁定态（息屏时同样为 true：唤醒后就会显示） */
    fun isKeyguardLocked(): Boolean =
        runCatching { keyguardManager?.isKeyguardLocked == true }.getOrDefault(false)

    /** 系统 keyguard 是否为安全锁（PIN/图案/密码）。撤锁时系统会弹出凭证界面 */
    fun isKeyguardSecure(): Boolean =
        runCatching { keyguardManager?.isKeyguardSecure == true }.getOrDefault(false)

    /** 当前压着的系统锁是否由我们发起（决定解锁时该不该由我们撤） */
    fun wasPushedByUs(): Boolean = prefs.getLong(KEY_PUSHED_AT, 0L) > 0L

    /**
     * 是否存在「家长已解锁、但系统 keyguard 还没撤成」的待办。
     *
     * 这是解锁链路唯一允许跨进程 / 跨组件存活的状态：见类注释「待撤锁意图」。
     * 典型置位时机：锁定态翻转到未锁定（家长解锁 / 审批通过）而我们仍压着系统锁。
     */
    fun isUnlockPending(): Boolean = prefs.getLong(KEY_PENDING_UNLOCK_AT, 0L) > 0L

    // ==================== 压锁 / 撤锁 ====================

    /**
     * 压一次系统级锁屏并记账。
     *
     * 与直接调 `lockNow()` 的区别只在于**记账**：没有这一步，解锁时就无法判断
     * 该不该撤 keyguard，家长端解锁也就永远不完整（见类注释缺陷 2）。
     *
     * @return true 表示确实压了锁（设备管理员可用）；false 表示无能力，由调用方降级
     */
    fun pushSystemLock(): Boolean {
        if (!admin.can(com.padguard.core.engine.admin.Capability.LOCK_NOW)) {
            Logger.w(TAG) { "pushSystemLock skipped: no device admin (LOCK_NOW unavailable)" }
            return false
        }
        return when (val r = admin.lockNow()) {
            is OpResult.Ok -> {
                markPushed()
                // 新锁会话使一切"待撤锁"意图失效：设备又被锁上了，追逐解锁已无意义
                clearUnlockPending()
                Logger.i(TAG) { "system lockNow() pushed and recorded" }
                true
            }
            else -> {
                Logger.w(TAG) { "pushSystemLock failed: $r" }
                false
            }
        }
    }

    /** 清除「系统锁由我们压着」的记账。撤锁完成后调用 */
    fun clearPushed() {
        if (prefs.getLong(KEY_PUSHED_AT, 0L) == 0L) return
        prefs.edit().remove(KEY_PUSHED_AT).apply()
        // 系统锁已不在，"待撤锁"自然也无从谈起 —— 必须同步清，否则残留标记
        // 会让自愈触发点反复追逐一个不存在的锁
        clearUnlockPending()
        Logger.i(TAG) { "system lock record cleared" }
    }

    /**
     * 标记「待撤锁」：家长已解锁，但我们压的系统 keyguard 还在。
     *
     * 幂等：重复调用只刷新时间戳。任何解锁路径（指令直达 / 撤锁失败重试）都应在
     * 无法立即确认撤锁成功时调用它，把意图交给自愈触发点。
     */
    fun markUnlockPending() {
        prefs.edit().putLong(KEY_PENDING_UNLOCK_AT, System.currentTimeMillis()).apply()
        Logger.i(TAG) { "unlock pending recorded (system keyguard still held by us)" }
    }

    /** 撤锁成功 / 系统锁确认不在时清除待办 */
    fun clearUnlockPending() {
        if (prefs.getLong(KEY_PENDING_UNLOCK_AT, 0L) == 0L) return
        prefs.edit().remove(KEY_PENDING_UNLOCK_AT).apply()
        Logger.i(TAG) { "unlock pending cleared" }
    }

    private fun markPushed() {
        prefs.edit().putLong(KEY_PUSHED_AT, System.currentTimeMillis()).apply()
    }

    /**
     * 请求撤销系统 keyguard。
     *
     * - keyguard 未锁：直接回调成功（无需撤）；
     * - 非安全锁（滑动/无）：系统立即撤销，无任何界面交互；
     * - 安全锁（PIN/图案/密码）：系统弹出凭证界面，用户通过后回调成功，
     *   取消/失败回调 false。这是平台标准行为，无法绕过也不应绕过。
     *
     * 调用方必须是已 resumed 的 Activity（凭证界面需要挂在调用者之上）。
     * 注意：带说明文字的 `requestDismissKeyguard(activity, title, executor, callback)`
     * 重载是 @SystemApi，第三方应用不可用，这里只能用公开的双参版本。
     *
     * @param onResult 撤锁结果；true = keyguard 已不在锁定态
     */
    fun requestDismiss(activity: Activity, onResult: (Boolean) -> Unit) {
        val km = keyguardManager
        if (km == null) {
            Logger.w(TAG) { "requestDismiss: no KeyguardManager" }
            onResult(false)
            return
        }
        if (!isKeyguardLocked()) {
            // 没有系统锁：无需撤，直接放行
            onResult(true)
            return
        }
        val callback = object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                Logger.i(TAG) { "keyguard dismissed" }
                onResult(true)
            }

            override fun onDismissCancelled() {
                Logger.i(TAG) { "keyguard dismiss cancelled by user" }
                onResult(false)
            }

            override fun onDismissError() {
                Logger.w(TAG) { "keyguard dismiss not feasible (activity not showing behind keyguard?)" }
                onResult(false)
            }
        }
        runCatching { km.requestDismissKeyguard(activity, callback) }
            .onFailure {
                Logger.w(TAG) { "requestDismissKeyguard threw: ${it.message}" }
                onResult(false)
            }
    }

    // ==================== 解锁自愈（幂等入口） ====================

    /**
     * 尝试完成一次解锁，幂等，可被任意触发点反复调用。
     *
     * 这是解锁链的**唯一收口**：所有"家长已解锁"的后续动作都经这里收敛，
     * 而不是各自实现一套撤锁逻辑。三种结局：
     * 1. 系统锁不是我们压的 → 清掉残留待办，回调 true（无需任何操作）；
     * 2. 系统锁已不在（孩子自己滑开了 / 已被撤过）→ 清记账，回调 true；
     * 3. 系统锁还在 → 先点屏（保证凭证界面/撤锁结果可见），有可用 activity 就
     *    [requestDismiss]：成功 → 清记账回调 true；失败 → **保留记账与待办**，
     *    回调 false，由调用方稍后重试（看门狗/周期自检会持续追）。
     *
     * 为什么失败时绝不清记账：系统锁仍压着，一旦清了，"下次解锁"就没人知道
     * 该撤它 —— 那正是「解锁一次失败后永远解不开」的死锁成因。
     *
     * @param activity 已 resumed 的 Activity；无可用页面时只点屏并回调 false
     */
    fun ensureUnlocked(activity: Activity?, onResult: (Boolean) -> Unit) {
        if (!wasPushedByUs()) {
            clearUnlockPending()
            onResult(true)
            return
        }
        if (!isKeyguardLocked()) {
            Logger.i(TAG) { "ensureUnlocked: keyguard already gone, clearing record" }
            clearPushed()
            onResult(true)
            return
        }
        // 系统锁还在：先把"待撤锁"意图落盘，再点屏 + 撤锁。
        // 顺序不能反 —— 先标记，任何时刻进程死掉都有触发点接着追。
        markUnlockPending()
        wakeScreen()
        if (activity == null || activity.isFinishing) {
            Logger.w(TAG) { "ensureUnlocked: no live activity to dismiss keyguard, will retry" }
            onResult(false)
            return
        }
        requestDismiss(activity) { ok ->
            if (ok) {
                clearPushed() // 内部同步清待办
                Logger.i(TAG) { "ensureUnlocked: keyguard dismissed, unlock complete" }
            } else {
                Logger.w(TAG) { "ensureUnlocked: dismiss failed, keeping pending for retry" }
            }
            onResult(ok)
        }
    }

    // ==================== 兜底点屏 ====================

    /**
     * 解锁兜底：把屏幕点亮。
     *
     * 解锁 UX 的正规路径是拉起一次性的 [LockScreenActivity]（MODE_UNLOCKING）——
     * 它自带 `FLAG_TURN_SCREEN_ON`，到前台即点亮屏幕并撤掉 keyguard 后自行退出。
     * 但后台启动 Activity 在个别 ROM 上会被**静默丢弃**（不抛异常、无回调），
     * 此时若不再兜底点屏，家长解锁后平板仍旧黑屏——正是本次要消灭的"无反应"。
     *
     * `PowerManager.wakeUp()` 是 @SystemApi，第三方应用不可用；退而求其次用
     * `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` 点亮屏幕（API 17 起标记废弃，
     * 但仍是应用层唯一可用的点屏手段，且无需任何权限）。带超时自动释放，无需手动 release。
     */
    fun wakeScreen() {
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        @Suppress("DEPRECATION")
        val wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            WAKE_LOCK_TAG
        )
        runCatching { wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS) }
            .onSuccess { Logger.i(TAG) { "screen woken for unlock fallback (auto-release in ${WAKE_LOCK_TIMEOUT_MS}ms)" } }
            .onFailure { Logger.w(TAG) { "wake lock acquire failed: ${it.message}" } }
    }

    companion object {
        private const val TAG = "SystemKeyguardBridge"
        private const val PREFS_NAME = "padguard_system_lock"
        private const val KEY_PUSHED_AT = "pushed_at"

        /**
         * 「待撤锁」时间戳：家长已解锁、系统 keyguard 仍被我们压着。
         *
         * 与 [KEY_PUSHED_AT] 同文件不同键：压锁记账管"该不该由我们撤"，
         * 待办标记管"还没撤成、继续追"。两者组合出解锁链的完整持久状态。
         */
        private const val KEY_PENDING_UNLOCK_AT = "pending_unlock_at"

        /** 兜底点屏 WakeLock 的持有上限：过渡页/凭证界面会各自保住屏幕，超时即释放防泄漏 */
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L
        private const val WAKE_LOCK_TAG = "PadGuard:UnlockWake"

        /**
         * 免注入的静态探测：供 [LockScreenActivity] 的伴生对象（无 Hilt 注入点）使用。
         * 与实例方法同源同口径，只是读取路径不同。
         */
        fun wasPushedByUs(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_PUSHED_AT, 0L) > 0L

        fun isKeyguardLocked(context: Context): Boolean =
            runCatching {
                (context.getSystemService(KeyguardManager::class.java))?.isKeyguardLocked == true
            }.getOrDefault(false)

        fun wakeScreen(context: Context) {
            val pm = context.getSystemService(PowerManager::class.java) ?: return
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_LOCK_TAG
            )
            runCatching { wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS) }
        }

        // ---- 待撤锁意图的静态三件套：供无障碍服务 / 守护服务（无 activity 场景）使用 ----

        fun isUnlockPending(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_PENDING_UNLOCK_AT, 0L) > 0L

        fun markUnlockPending(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_PENDING_UNLOCK_AT, System.currentTimeMillis()).apply()
        }

        fun clearUnlockPending(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING_UNLOCK_AT).apply()
        }

        /**
         * 静态自愈：keyguard 已被孩子自己滑开（USER_PRESENT 等场景）时，
         * 清掉"待撤锁"标记与压锁记账 —— 系统锁既已不在，就没有任何可撤的东西。
         * keyguard 仍锁着时不动（留给带 activity 的触发点去撤）。
         */
        fun clearIfKeyguardGone(context: Context) {
            if (!isUnlockPending(context)) return
            if (isKeyguardLocked(context)) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_PENDING_UNLOCK_AT).remove(KEY_PUSHED_AT).apply()
            Logger.i(TAG) { "keyguard gone by user, unlock pending + record cleared" }
        }
    }
}
