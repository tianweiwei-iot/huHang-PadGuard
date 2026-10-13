package com.padguard.child.guard

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.padguard.core.common.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 操作密码闸门（C 端软拦截方案的核心存储）。
 *
 * ## 为什么是「软拦截」
 * 不依赖设备所有者(DO)：Android 不允许普通应用对系统 PackageInstaller / 设置 app
 * 的确认流程插入自定义密码框。本类只负责「密码是否启用 / 是否处于解锁窗口」，
 * 真正的拦截由 [OperationGuardAccessibilityService]（无障碍服务）在前台包切换时把家长/孩子
 * 顶出设置/安装界面，以及 App 内部发起下载/安装/卸载/设置前调用 [launchUnlock] 校验。
 *
 * ## 密码的存储与校验口径（与服务端一致）
 * - 协议哈希 = SHA-256(明文)，服务端算好下发，本端只存哈希，明文永不出本机；
 * - 本端校验时把家长输入的明文再算一次 SHA-256 比对；
 * - 不引入盐：保证服务端下发的哈希与本地校验口径完全一致（盐会破坏跨设备一致性）。
 *
 * ## 解锁窗口（W34 修复）
 * 旧实现只用 `System.currentTimeMillis()`（墙钟）记录解锁到期时间，孩子**回拨系统时间**
 * 即可让 `now < unlockedUntil` 永远成立 → 永久解锁。现改为**双时钟**：
 * - 单调时钟（`SystemClock.elapsedRealtime()`）为主，不受手动改时间影响；
 * - 墙钟（`System.currentTimeMillis()`）为辅，防重启后单调时钟归零导致的"永久解锁"。
 * 两者任一判定过期即视为过期（见 [isUnlocked] / [Snapshot.isUnlocked]），宁可提前恢复管控也不留真空。
 *
 * ## 主线程读取（W34 修复）
 * 旧实现 `isUnlockedNow/isEnabledNow/isLockedOutNow` 用 `runBlocking(Dispatchers.IO)` 在主线程读 DataStore，
 * 无障碍服务高频回调下易触发 ANR。现改为：初始化时订阅 DataStore Flow，把最新值写入 [@Volatile] 快照，
 * 主线程方法只读快照，零挂起。
 */
private val Context.guardDataStore: DataStore<Preferences> by preferencesDataStore(name = "padguard_guard")

@Singleton
class GuardPasswordManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store = context.guardDataStore

    private val KEY_HASH = stringPreferencesKey("guard_hash")
    private val KEY_ENABLED = booleanPreferencesKey("guard_enabled")
    /** 解锁窗口到期（墙钟）。W34：与 elapsed 双时钟并用。 */
    private val KEY_UNLOCKED_UNTIL = longPreferencesKey("guard_unlocked_until")
    /** 解锁窗口到期（单调时钟）。W34 新增：防回拨系统时间永久解锁。 */
    private val KEY_UNLOCKED_UNTIL_ELAPSED = longPreferencesKey("guard_unlocked_until_elapsed")
    private val KEY_FAIL_COUNT = intPreferencesKey("guard_fail_count")
    /** 试错锁定到期（墙钟）。W34：与 elapsed 双时钟并用。 */
    private val KEY_LOCKED_UNTIL = longPreferencesKey("guard_locked_until")
    /** 试错锁定到期（单调时钟）。W34 新增。 */
    private val KEY_LOCKED_UNTIL_ELAPSED = longPreferencesKey("guard_locked_until_elapsed")

    /** 解锁后允许的时长窗口（毫秒）。默认 10 分钟，足够家长完成一次改设置/装应用。 */
    var unlockWindowMs: Long = 10 * 60 * 1000L

    data class GuardState(val enabled: Boolean, val unlocked: Boolean)

    /**
     * 主线程安全的状态快照（W34）。由 init 中订阅 DataStore Flow 持续刷新，
     * 所有 `*Now()` 方法只读它，绝不挂起。
     */
    @Volatile
    private var snapshot = Snapshot()

    private data class Snapshot(
        val enabled: Boolean = false,
        val unlockedUntilElapsed: Long = 0L,
        val unlockedUntilWall: Long = 0L,
        val lockedUntilElapsed: Long = 0L,
        val lockedUntilWall: Long = 0L,
        val failCount: Int = 0
    ) {
        fun isUnlocked(): Boolean {
            if (!enabled) return false
            // 双时钟：单调时钟为主（防改时间），墙钟为辅（防重启归零）。任一过期即失效。
            return unlockedUntilElapsed > 0
                && SystemClock.elapsedRealtime() < unlockedUntilElapsed
                && System.currentTimeMillis() < unlockedUntilWall
        }

        fun isLockedOut(): Boolean {
            if (lockedUntilElapsed <= 0) return false
            return SystemClock.elapsedRealtime() < lockedUntilElapsed
                && System.currentTimeMillis() < lockedUntilWall
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 持续把 DataStore 最新值镜像到 @Volatile 快照，避免主线程 runBlocking 读（W34 ANR）。
        scope.launch {
            store.data.collect { prefs -> snapshot = snapshotFrom(prefs) }
        }
    }

    val state: Flow<GuardState> = store.data.map { prefs ->
        val enabled = prefs[KEY_ENABLED] ?: false
        GuardState(enabled, isUnlocked(prefs))
    }

    /** 是否启用了操作密码（家长端已下发或本地设置） */
    suspend fun isEnabled(): Boolean = (store.data.first()[KEY_ENABLED] ?: false)

    /** 当前是否处于「已解锁」窗口内（解锁后才允许安装/卸载/改设置） */
    suspend fun isUnlocked(): Boolean = isUnlocked(store.data.first())

    /** 同步快捷读取：无障碍服务在主线程事件回调里用，避免挂起（W34：只读 @Volatile 快照）。 */
    fun isUnlockedNow(): Boolean = snapshot.isUnlocked()

    fun isEnabledNow(): Boolean = snapshot.enabled

    /** 服务端下发的密码哈希（SHA-256(明文)），直接落库，明文永不出网。 */
    suspend fun setPasswordHash(protocolHash: String) {
        store.edit {
            it[KEY_HASH] = protocolHash
            it[KEY_ENABLED] = true
        }
    }

    /** 本端直接按明文设置（本地/调试/家长端同设备回落用）。 */
    suspend fun setPasswordPlain(plain: String) {
        store.edit {
            it[KEY_HASH] = hash(plain)
            it[KEY_ENABLED] = true
        }
    }

    /** 关闭密码（设备解绑 / 家长清除时调用）。 */
    suspend fun clear() {
        store.edit {
            it[KEY_ENABLED] = false
            it[KEY_HASH] = ""
            it[KEY_UNLOCKED_UNTIL] = 0L
            it[KEY_UNLOCKED_UNTIL_ELAPSED] = 0L
            it[KEY_FAIL_COUNT] = 0
            it[KEY_LOCKED_UNTIL] = 0L
            it[KEY_LOCKED_UNTIL_ELAPSED] = 0L
        }
    }

    /** 校验家长输入的明文：本地重算 SHA-256 与存储哈希比对。 */
    suspend fun verify(plain: String): Boolean = verifyWithLimit(plain).ok

    /**
     * 带失败次数限制的校验。
     *
     * ## 为什么必须限次
     * 密码框是**孩子可见、可反复尝试**的：不限次等于把 4~6 位数字密码的搜索空间
     * 完全暴露给孩子，爆破成本几乎为零（4 位数字约 1 万次，自动化脚本几秒就试完）。
     * 所以连续错 [MAX_ATTEMPTS] 次后锁定 [LOCKOUT_MS]，让暴力破解在经济上不可行。
     *
     * 锁定期间即使密码正确也返回 false —— 否则可以"故意错 5 次锁住，
     * 等冷却后用同一个已知密码立刻通过"，限制形同虚设。
     *
     * ## 锁定计时（W34 修复）
     * 锁定到期同样用双时钟（elapsed + wall），回拨系统时间无法提前解除锁定。
     */
    suspend fun verifyWithLimit(plain: String): VerifyResult {
        val prefs = store.data.first()
        val enabled = prefs[KEY_ENABLED] ?: false
        if (!enabled) return VerifyResult(false, 0, 0L)

        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val lockedUntilE = prefs[KEY_LOCKED_UNTIL_ELAPSED] ?: 0L
        val lockedUntilW = prefs[KEY_LOCKED_UNTIL] ?: 0L
        if (lockedUntilE > 0 && elapsed < lockedUntilE && now < lockedUntilW) {
            return VerifyResult(false, 0, (lockedUntilW - now).coerceAtLeast(0L))
        }

        val stored = prefs[KEY_HASH].orEmpty()
        if (stored.isNotEmpty() && hash(plain) == stored) {
            store.edit {
                it[KEY_FAIL_COUNT] = 0
                it[KEY_LOCKED_UNTIL_ELAPSED] = 0L
                it[KEY_LOCKED_UNTIL] = 0L
            }
            return VerifyResult(true, MAX_ATTEMPTS, 0L)
        }

        val fails = (prefs[KEY_FAIL_COUNT] ?: 0) + 1
        val remaining = (MAX_ATTEMPTS - fails).coerceAtLeast(0)
        store.edit {
            it[KEY_FAIL_COUNT] = fails
            if (remaining == 0) {
                it[KEY_LOCKED_UNTIL_ELAPSED] = SystemClock.elapsedRealtime() + LOCKOUT_MS
                it[KEY_LOCKED_UNTIL] = System.currentTimeMillis() + LOCKOUT_MS
            }
        }
        Logger.w(TAG) { "guard password wrong: fail=$fails remaining=$remaining locked=${remaining == 0}" }
        return VerifyResult(false, remaining, if (remaining == 0) LOCKOUT_MS else 0L)
    }

    data class VerifyResult(
        val ok: Boolean,
        /** 剩余可试次数；0 表示已耗尽或被锁定 */
        val attemptsLeft: Int,
        /** 被锁定时距解锁的剩余毫秒；0 表示未锁定 */
        val lockedForMs: Long
    )

    /** 同步读：当前是否处于"失败过多锁定期"，无障碍回调用（W34：只读 @Volatile 快照）。 */
    fun isLockedOutNow(): Boolean = snapshot.isLockedOut()

    /** 解锁：开启一个时间窗口，期间允许安装/卸载/改设置（W34：双时钟写入）。 */
    suspend fun unlock() {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        store.edit {
            it[KEY_UNLOCKED_UNTIL_ELAPSED] = elapsed + unlockWindowMs
            it[KEY_UNLOCKED_UNTIL] = now + unlockWindowMs
        }
    }

    /** 立即重新锁定。 */
    suspend fun lockNow() {
        store.edit {
            it[KEY_UNLOCKED_UNTIL] = 0L
            it[KEY_UNLOCKED_UNTIL_ELAPSED] = 0L
            it[KEY_FAIL_COUNT] = 0
            it[KEY_LOCKED_UNTIL] = 0L
            it[KEY_LOCKED_UNTIL_ELAPSED] = 0L
        }
    }

    /** 跳到解锁界面（APP 内部发起下载/安装/卸载/设置前、或无障碍拦截时由家长在 App 内调起）。 */
    fun launchUnlock(target: Context) {
        target.startActivity(
            android.content.Intent(target, GuardUnlockActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun isUnlocked(prefs: Preferences): Boolean {
        val enabled = prefs[KEY_ENABLED] ?: false
        if (!enabled) return false
        val untilElapsed = prefs[KEY_UNLOCKED_UNTIL_ELAPSED] ?: 0L
        val untilWall = prefs[KEY_UNLOCKED_UNTIL] ?: 0L
        return untilElapsed > 0
            && SystemClock.elapsedRealtime() < untilElapsed
            && System.currentTimeMillis() < untilWall
    }

    /**
     * 把 DataStore 首选项映射为 [Snapshot]（W34）。
     *
     * 必须是类内成员：[Snapshot] 是私有嵌套类，顶层函数看不见它；
     * 同时直接用 [KEY_] 常量而不是重复键名字符串——两处字符串漂移过一回（W23 同款事故）。
     */
    private fun snapshotFrom(prefs: Preferences): Snapshot = Snapshot(
        enabled = prefs[KEY_ENABLED] ?: false,
        unlockedUntilElapsed = prefs[KEY_UNLOCKED_UNTIL_ELAPSED] ?: 0L,
        unlockedUntilWall = prefs[KEY_UNLOCKED_UNTIL] ?: 0L,
        lockedUntilElapsed = prefs[KEY_LOCKED_UNTIL_ELAPSED] ?: 0L,
        lockedUntilWall = prefs[KEY_LOCKED_UNTIL] ?: 0L,
        failCount = prefs[KEY_FAIL_COUNT] ?: 0
    )

    private fun hash(plain: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return Base64.getEncoder().encodeToString(md.digest(plain.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        private const val TAG = "GuardPasswordManager"

        /** 连续输错多少次后锁定。4~6 位数字密码不限次等同于敞开 */
        const val MAX_ATTEMPTS = 5

        /** 锁定时长：足够让"守着平板试密码"这种手速失效 */
        const val LOCKOUT_MS = 5 * 60 * 1000L

        /** 协议哈希算法，与服务端保持一致：SHA-256(明文) 的 Base64。 */
        fun protocolHash(plain: String): String =
            Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(plain.toByteArray(Charsets.UTF_8))
            )
    }
}
