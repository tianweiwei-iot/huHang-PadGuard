package com.padguard.child.guard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * 操作闸门无障碍服务的状态探测。
 *
 * 该服务（[OperationGuardAccessibilityService]）是操作密码闸门的系统级拦截硬依赖：
 * 未启用时，孩子可从设置 / 通知栏绕过家长设置的密码，闸门形同虚设。
 *
 * 判定口径集中在此，[com.padguard.child.service.GuardService.ensureGuardInfra]
 * 与「我的」页的状态横幅共用同一逻辑，避免多处实现导致判定不一致。
 */
object GuardA11y {

    /**
     * 本应用的「操作闸门无障碍服务」是否已在系统设置中启用。
     *
     * @return true = 已启用（闸门系统级拦截可用）；false = 未启用或探测失败。
     */
    fun isEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        return runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any {
                    it.resolveInfo?.serviceInfo?.packageName == context.packageName &&
                        it.resolveInfo?.serviceInfo?.name
                            ?.endsWith("OperationGuardAccessibilityService") == true
                }
        }.getOrDefault(false)
    }
}
