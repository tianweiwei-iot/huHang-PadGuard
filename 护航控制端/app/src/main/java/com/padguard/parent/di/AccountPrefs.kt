package com.padguard.parent.di

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 登录账号历史与「记住密码」的本地存储。
 *
 * ## 设计取舍
 * - **历史账号**：只存手机号明文，最多 [MAX_HISTORY] 条，按最近使用倒序。
 * - **记住密码**：勾选后才存，且按账号分开存；取消勾选立即清除该账号的密码，
 *   不留"以为关掉了其实还在"的残留。
 * - **不加密**：密码落在 app 私有目录的 SharedPreferences（MODE_PRIVATE），
 *   与主流 App 的"记住密码"实现一致；设备已被 root 或被人物理接管时本就不安全，
 *   这里不引入 EncryptedSharedPreferences 以免在低端机上因 Keystore 异常导致登录页直接不可用。
 *
 * 存储格式：一份 JSON 数组，元素 {phone, pwd?, ts}，改动即整体写回 —— 条数极小，无需增量。
 */
@Singleton
class AccountPrefs @Inject constructor(
    private val application: Application
) {
    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 一条历史账号。password 为 null 表示该账号未勾选记住密码。 */
    data class SavedAccount(
        val phone: String,
        val password: String? = null,
        val lastUsedAt: Long = 0L
    )

    /** 历史账号列表（最近使用的排在最前）。 */
    fun history(): List<SavedAccount> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val phone = o.optString("phone")
            if (phone.isBlank()) null
            else SavedAccount(
                phone = phone,
                password = o.optString("pwd").takeIf { it.isNotBlank() },
                lastUsedAt = o.optLong("ts", 0L)
            )
        }.sortedByDescending { it.lastUsedAt }
    }.getOrDefault(emptyList())

    /** 最近一次使用的手机号，用于登录页自动回填。 */
    fun lastPhone(): String? = history().firstOrNull()?.phone

    fun passwordOf(phone: String): String? =
        history().firstOrNull { it.phone == phone }?.password

    /**
     * 登录成功后调用：把账号提到历史最前，并按 [remember] 决定保存 / 清除密码。
     */
    fun save(phone: String, password: String?, remember: Boolean) {
        if (phone.isBlank()) return
        val now = System.currentTimeMillis()
        val merged = history()
            .filterNot { it.phone == phone }
            .toMutableList()
        merged.add(
            0,
            SavedAccount(
                phone = phone,
                password = if (remember) password?.takeIf { it.isNotBlank() } else null,
                lastUsedAt = now
            )
        )
        write(merged.take(MAX_HISTORY))
    }

    /** 从历史中删除某个账号（连同其保存的密码）。 */
    fun remove(phone: String) {
        write(history().filterNot { it.phone == phone })
    }

    private fun write(list: List<SavedAccount>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("phone", a.phone)
                    .put("pwd", a.password.orEmpty())
                    .put("ts", a.lastUsedAt)
            )
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    private companion object {
        const val PREF_NAME = "padguard_parent_accounts"
        const val KEY_ACCOUNTS = "accounts"
        const val MAX_HISTORY = 5
    }
}
