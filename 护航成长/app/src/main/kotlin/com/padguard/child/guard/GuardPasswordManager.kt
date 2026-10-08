package com.padguard.child.guard

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
 * ## 解锁窗口
 * 家长在 [GuardUnlockActivity] 输对密码后调用 [unlock]，开启 10 分钟的允许窗口，
 * 期间安装/卸载/改设置被放行；超时自动重新锁定。这是「要改设备必须输入密码」的可操作形态。
 */
private val Context.guardDataStore: DataStore<Preferences> by preferencesDataStore(name = "padguard_guard")

@Singleton
class GuardPasswordManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store = context.guardDataStore

    private val KEY_HASH = stringPreferencesKey("guard_hash")
    private val KEY_ENABLED = booleanPreferencesKey("guard_enabled")
    private val KEY_UNLOCKED_UNTIL = longPreferencesKey("guard_unlocked_until")
    private val KEY_FAIL_COUNT = intPreferencesKey("guard_fail_count")
    private val KEY_LOCKED_UNTIL = longPreferencesKey("guard_locked_until")

    /** 解锁后允许的时长窗口（毫秒）。默认 10 分钟，足够家长完成一次改设置/装应用。 */
    var unlockWindowMs: Long = 10 * 60 * 1000L

    data class GuardState(val enabled: Boolean, val unlocked: Boolean)

    val state: Flow<GuardState> = store.data.map { prefs ->
        val enabled = prefs[KEY_ENABLED] ?: false
        val until = prefs[KEY_UNLOCKED_UNTIL] ?: 0L
        GuardState(enabled, enabled && System.currentTimeMillis() < until)
    }

    /** 是否启用了操作密码（家长端已下发或本地设置） */
    suspend fun isEnabled(): Boolean = (store.data.first()[KEY_ENABLED] ?: false)

    /** 当前是否处于「已解锁」窗口内（解锁后才允许安装/卸载/改设置） */
    suspend fun isUnlocked(): Boolean {
        val prefs = store.data.first()
        val enabled = prefs[KEY_ENABLED] ?: false
        val until = prefs[KEY_UNLOCKED_UNTIL] ?: 0L
        return enabled && System.currentTimeMillis() < until
    }

    /** 同步快捷读取：无障碍服务在主线程事件回调里用，避免挂起。 */
    fun isUnlockedNow(): Boolean = runCatching {
        val prefs = runBlocking(Dispatchers.IO) { store.data.first() }
        val enabled = prefs[KEY_ENABLED] ?: false
        val until = prefs[KEY_UNLOCKED_UNTIL] ?: 0L
        enabled && System.currentTimeMillis() < until
    }.getOrDefault(false)

    fun isEnabledNow(): Boolean = runCatching {
        runBlocking(Dispatchers.IO) { store.data.first() }[KEY_ENABLED] ?: false
    }.getOrDefault(false)

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
            it[KEY_FAIL_COUNT] = 0
            it[KEY_LOCKED_UNTIL] = 0L
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
     */
    suspend fun verifyWithLimit(plain: String): VerifyResult {
        val prefs = store.data.first()
        val enabled = prefs[KEY_ENABLED] ?: false
        if (!enabled) return VerifyResult(false, 0, 0L)

        val now = System.currentTimeMillis()
        val lockedUntil = prefs[KEY_LOCKED_UNTIL] ?: 0L
        if (now < lockedUntil) {
            return VerifyResult(false, 0, lockedUntil - now)
        }

        val stored = prefs[KEY_HASH].orEmpty()
        if (stored.isNotEmpty() && hash(plain) == stored) {
            store.edit {
                it[KEY_FAIL_COUNT] = 0
                it[KEY_LOCKED_UNTIL] = 0L
            }
            return VerifyResult(true, MAX_ATTEMPTS, 0L)
        }

        val fails = (prefs[KEY_FAIL_COUNT] ?: 0) + 1
        val remaining = (MAX_ATTEMPTS - fails).coerceAtLeast(0)
        store.edit {
            it[KEY_FAIL_COUNT] = fails
            if (remaining == 0) it[KEY_LOCKED_UNTIL] = now + LOCKOUT_MS
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

    /** 同步读：当前是否处于"失败过多锁定期"，无障碍回调用 */
    fun isLockedOutNow(): Boolean = runCatching {
        val prefs = runBlocking(Dispatchers.IO) { store.data.first() }
        System.currentTimeMillis() < (prefs[KEY_LOCKED_UNTIL] ?: 0L)
    }.getOrDefault(false)

    /** 解锁：开启一个时间窗口，期间允许安装/卸载/改设置。 */
    suspend fun unlock() {
        store.edit { it[KEY_UNLOCKED_UNTIL] = System.currentTimeMillis() + unlockWindowMs }
    }

    /** 立即重新锁定。 */
    suspend fun lockNow() {
        store.edit {
            it[KEY_UNLOCKED_UNTIL] = 0L
            it[KEY_FAIL_COUNT] = 0
            it[KEY_LOCKED_UNTIL] = 0L
        }
    }

    /** 跳到解锁界面（APP 内部发起下载/安装/卸载/设置前、或无障碍拦截时由家长在 App 内调起）。 */
    fun launchUnlock(target: Context) {
        target.startActivity(
            android.content.Intent(target, GuardUnlockActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

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
