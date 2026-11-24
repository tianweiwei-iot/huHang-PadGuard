package com.padguard.child.notify

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知代收开关（本地）。
 *
 * ## 默认关闭（fail-closed）
 * 说明书 §6.9 把 `notif.collect` 列为默认关闭的可选项。监听服务即便已获得系统「通知使用权」，
 * 也只在[isEnabled]为真时才采集；否则监听器静默放行，绝不记录任何通知。
 *
 * 为什么是本地开关而非策略下发：通知正文涉及隐私，默认关、让家长显式开启更符合合规预期；
 * 后续若要由家长端远程开启，只需把该值改为由策略同步即可，采集逻辑不变。
 */
@Singleton
class NotificationCollectionPrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "padguard_notify_collect"
        private const val KEY_ENABLED = "enabled"
    }
}
