package com.padguard.parent.di

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 登录账号历史与「记住账号」的本地存储。
 *
 * ## 设计取舍
 * - **历史账号**：只存手机号明文，最多 [MAX_HISTORY] 条，按最近使用倒序。
 * - **密码不再持久化**：出于安全，家长密码**绝不落库**。设备被 root / 备份提取即可能泄露，
 *   等同于把家长门户钥匙交给孩子 —— 这比"记住密码"的便利更不可接受。
 *   该决策与孩子端一致（孩子端早已移除家长密码明文落库）。
 *
 * ## 为什么彻底移除 password 字段而不只是"写 null"
 *
 * 早期版本保留了 `SavedAccount.password` 与 `save(phone, password, remember)` 签名，
 * 但 `save` 内部恒写 null —— 于是登录页的「记住账号和密码」复选框**点了永远不会生效**，
 * 历史列表里那句「已记住密码」也永远不出现。这是一处"UI 承诺了功能、实现从未兑现"的死功能，
 * 比没有该选项更糟（家长会反复勾选、怀疑 App 坏了）。
 * 现在字段与参数一并删除，界面只承诺「记住账号」，行为与文案彻底一致。
 *
 * 存储格式：一份 JSON 数组，元素 {phone, ts}，改动即整体写回 —— 条数极小，无需增量。
 * 旧版本若已写入过 `pwd`，本次整体重写会自然把它丢弃（顺带清掉历史遗留的明文密码）。
 */
@Singleton
class AccountPrefs @Inject constructor(
    private val application: Application
) {
    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 一条历史账号（**只有手机号**，不含密码 —— 见类注释）。 */
    data class SavedAccount(
        val phone: String,
        val lastUsedAt: Long = 0L
    )

    /** 历史账号列表（最近使用的排在最前）。 */
    fun history(): List<SavedAccount> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val phone = o.optString("phone")
            if (phone.isBlank()) null
            else SavedAccount(phone = phone, lastUsedAt = o.optLong("ts", 0L))
        }.sortedByDescending { it.lastUsedAt }
    }.getOrDefault(emptyList())

    /** 最近一次使用的手机号，用于登录页自动回填。 */
    fun lastPhone(): String? = history().firstOrNull()?.phone

    /** 登录成功后调用：把账号提到历史最前。只记手机号，不记任何凭据。 */
    fun save(phone: String) {
        if (phone.isBlank()) return
        val now = System.currentTimeMillis()
        val merged = history()
            .filterNot { it.phone == phone }
            .toMutableList()
        merged.add(0, SavedAccount(phone = phone, lastUsedAt = now))
        write(merged.take(MAX_HISTORY))
    }

    /** 从历史中删除某个账号。 */
    fun remove(phone: String) {
        write(history().filterNot { it.phone == phone })
    }

    private fun write(list: List<SavedAccount>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("phone", a.phone)
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
