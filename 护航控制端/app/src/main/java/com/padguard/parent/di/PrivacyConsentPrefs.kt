package com.padguard.parent.di

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.padguard.parent.compliance.AmapPrivacyCompliance
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
 * - 两份文本各自版本：政策改版递增 [CURRENT_POLICY_VERSION]、协议改版递增 [CURRENT_TERMS_VERSION]，
 *   任一不匹配即视为未同意，强制重新勾选。
 */
@Singleton
class PrivacyConsentPrefs @Inject constructor(
    private val application: Application
) {
    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 是否已对**当前两份文本**作出显式同意。
     *
     * 政策与协议独立版本：只用一个版本号时，协议不变、政策小改也会逼用户重签，
     * 或反过来政策变了但常量没动导致老同意继续放行。两份必须分别比对。
     */
    fun isAgreed(): Boolean =
        prefs.getString(KEY_POLICY_VERSION, null) == CURRENT_POLICY_VERSION &&
            prefs.getString(KEY_TERMS_VERSION, null) == CURRENT_TERMS_VERSION &&
            prefs.getLong(KEY_AGREED_AT, 0L) > 0L

    /** 用户勾选同意后调用，记录两份文本的版本与时间。 */
    fun agree() {
        prefs.edit()
            .putString(KEY_POLICY_VERSION, CURRENT_POLICY_VERSION)
            .putString(KEY_TERMS_VERSION, CURRENT_TERMS_VERSION)
            .putLong(KEY_AGREED_AT, System.currentTimeMillis())
            .apply()
        // 同意留痕与 SDK 侧状态必须同拍落定：只写本地 prefs 而 SDK 仍是 false，
        // 地图会因"未同意"拒绝工作；反之（旧实现）则是未同意先宣告已同意。
        AmapPrivacyCompliance.sync(application, agreed = true)
    }

    /**
     * 用户取消勾选或点击「不同意」时调用：真实清除同意留痕。
     *
     * 不能只改 UI 状态而保留落盘的同意记录——那样构成"假撤回"（PIPL 第 15 条）：
     * 界面显示未勾选，下次启动却仍按已同意处理。
     */
    fun revoke() {
        prefs.edit()
            .remove(KEY_POLICY_VERSION)
            .remove(KEY_TERMS_VERSION)
            .remove(KEY_AGREED_AT)
            .apply()
        // 真实撤回必须同时把 SDK 侧状态置回 false，否则下次地图仍按"已同意"工作。
        AmapPrivacyCompliance.sync(application, agreed = false)
    }

    /** 已同意的版本描述（供「关于」页展示），未同意返回 null。 */
    fun agreedVersion(): String? =
        if (isAgreed()) "《隐私政策》v$CURRENT_POLICY_VERSION /《用户协议》v$CURRENT_TERMS_VERSION" else null

    companion object {
        private const val PREF_NAME = "padguard_parent_privacy_consent"
        private const val KEY_POLICY_VERSION = "policy_version"
        private const val KEY_TERMS_VERSION = "terms_version"
        private const val KEY_AGREED_AT = "agreed_at"

        /**
         * 两份文本各自独立版本，与随包全文头部逐字一致：
         * 《隐私政策》v2.1 /《用户协议》v1.1（均 2026-11-25 生效）。
         *
         * 正文实质变更时必须同步递增对应常量、更新 md 头部版本/日期；
         * 随包 assets/legal 由 Gradle `syncLegalAssets` 在 preBuild 自动同步，不再依赖人工复制。
         * 任一份版本不匹配都视为未同意（政策和协议是两份独立法律文件，不能共用一个标量）。
         */
        const val CURRENT_POLICY_VERSION = "2.1.0"
        const val CURRENT_TERMS_VERSION = "1.1.0"

        /**
         * 供**非 DI 场景**（导航起始页判断是否已登录可直接进主页）读取同一份留痕。
         *
         * 不能改成"内存里缓存一个已同意标志"：进程重启后首帧必须立刻判定，
         * 缓存未初始化会被当成未同意，已登录用户被强制回登录页。
         */
        fun isAgreed(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_POLICY_VERSION, null) == CURRENT_POLICY_VERSION &&
                prefs.getString(KEY_TERMS_VERSION, null) == CURRENT_TERMS_VERSION &&
                prefs.getLong(KEY_AGREED_AT, 0L) > 0L
        }
    }
}
