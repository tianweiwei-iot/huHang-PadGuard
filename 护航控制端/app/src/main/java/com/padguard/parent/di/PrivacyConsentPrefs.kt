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

    /** 已同意的版本号（供「关于」页展示），未同意返回 null。 */
    fun agreedVersion(): String? =
        prefs.getString(KEY_VERSION, null)?.takeIf { isAgreed() }

    private companion object {
        const val PREF_NAME = "padguard_parent_privacy_consent"
        const val KEY_VERSION = "policy_version"
        const val KEY_AGREED_AT = "agreed_at"

        /**
         * 协议版本。正文（登录页《用户协议》《隐私政策》要点）实质变更时递增，
         * 递增后所有用户需重新勾选——这是「变更告知并重新取得同意」的合规要求。
         */
        const val CURRENT_POLICY_VERSION = "1.0"
    }
}
