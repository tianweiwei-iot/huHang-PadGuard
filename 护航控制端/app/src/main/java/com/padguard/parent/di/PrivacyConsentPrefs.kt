package com.padguard.parent.di

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 隐私政策 / 用户协议的**显式同意**状态（应用市场审核硬指标）。
 *
 * ## 为什么必须显式勾选
 * 国内应用市场（华为/小米/OPPO/vivo/应用宝）对未成年人相关产品审核尤其严格：
 * 「登录即表示同意」属**默示同意**，会被判定为未取得有效同意而拒绝上架。
 * 必须在首次使用前有用户主动勾选动作，且记录所同意的协议版本与时间。
 *
 * ## 存什么 / 不存什么
 * - 只存协议版本号 + 同意时间戳（合规留痕，非个人信息）。
 * - **不存**任何账号、手机号、密码或设备标识 —— 凭据一律由 [AccountPrefs] / TokenManager 管理。
 * - 协议正文改版时递增 [CURRENT_POLICY_VERSION]，旧版本号视为未同意，强制重新勾选。
 */
@Singleton
class PrivacyConsentPrefs @Inject constructor(
    application: Application
) {
    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 是否已对当前版本协议作出显式同意。 */
    fun isAgreed(): Boolean =
        prefs.getString(KEY_VERSION, null) == CURRENT_POLICY_VERSION &&
            prefs.getLong(KEY_AGREED_AT, 0L) > 0L

    /** 用户勾选同意后调用，记录版本与时间。 */
    fun agree() {
        prefs.edit()
            .putString(KEY_VERSION, CURRENT_POLICY_VERSION)
            .putLong(KEY_AGREED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * 用户取消勾选或点击「不同意」时调用：真实清除同意留痕。
     *
     * 不能只改 UI 状态而保留落盘的同意记录——那样构成"假撤回"（PIPL 第 15 条）：
     * 界面显示未勾选，下次启动却仍按已同意处理。
     */
    fun revoke() {
        prefs.edit()
            .remove(KEY_VERSION)
            .remove(KEY_AGREED_AT)
            .apply()
    }

    /** 已同意的版本号（供「关于」页展示），未同意返回 null。 */
    fun agreedVersion(): String? =
        prefs.getString(KEY_VERSION, null)?.takeIf { isAgreed() }

    private companion object {
        const val PREF_NAME = "padguard_parent_privacy_consent"
        const val KEY_VERSION = "policy_version"
        const val KEY_AGREED_AT = "agreed_at"

        /**
         * 协议版本，与随包全文头部一致：《用户协议》v1.1 /《隐私政策》v2.0（均 2026-11-25 生效）。
         *
         * 旧值 "1.0" 无法区分政策 v1.0 与 v2.0，导致政策升版后老用户不被强制重新同意。
         * 正文实质变更时必须同步递增本常量、更新两份 md 头部版本/日期、重新复制 assets/legal/，
         * 否则同意留痕与展示版本脱节（契约测试会拦截）。
         */
        const val CURRENT_POLICY_VERSION = "2.0.0"
    }
}
