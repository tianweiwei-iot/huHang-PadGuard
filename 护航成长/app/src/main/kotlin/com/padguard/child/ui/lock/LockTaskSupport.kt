package com.padguard.child.ui.lock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.padguard.child.receiver.PadGuardDeviceAdminReceiver
import com.padguard.core.common.Logger

/**
 * 锁定任务（屏幕固定）支持。
 *
 * ## 为什么必须在这里"补一次白名单"
 * `startLockTask()` 只对 `DevicePolicyManager.setLockTaskPackages` 白名单内的包生效，
 * 名单外的调用会被系统直接拒绝（不抛异常，静默无效 —— 这正是"锁屏能滑走"的根因之一）。
 *
 * 本项目里白名单原本只在 Kiosk 开启时由 [com.padguard.core.engine.enforcer.KioskEnforcer] 设置，
 * Kiosk 关闭时还会被清空。也就是说：**不开 Kiosk 的普通管控场景，锁屏页根本进不了锁定任务模式**。
 *
 * 因此锁屏页/霸屏页在进入锁定任务前，先确保自身包名在白名单里。
 * 这里是"追加"而不是"覆盖"：直接 setLockTaskPackages(只有自己) 会抹掉 Kiosk 已配置的允许应用，
 * 学校场景下等于把学习应用从纯净桌面里踢出去。
 */
object LockTaskSupport {

    private const val TAG = "LockTaskSupport"

    /**
     * 确保本应用已进入锁定任务白名单。
     * @return true 表示具备 Device Owner 资格且白名单已包含本应用
     */
    fun ensureWhitelisted(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = ComponentName(context, PadGuardDeviceAdminReceiver::class.java)
        return try {
            if (!dpm.isDeviceOwnerApp(context.packageName)) return false
            val current = dpm.getLockTaskPackages(admin).toMutableList()
            if (current.contains(context.packageName)) return true
            current.add(context.packageName)
            dpm.setLockTaskPackages(admin, current.toTypedArray())
            Logger.i(TAG) { "added self to lock task whitelist: ${current.joinToString()}" }
            true
        } catch (t: Throwable) {
            Logger.w(TAG) { "ensure lock task whitelist failed: ${t.message}" }
            false
        }
    }

    /**
     * 进入锁定任务模式。
     *
     * 即便白名单设置失败也要尝试一次：部分 ROM 在"用户手动固定过一次"后也允许锁定，
     * 失败与否不影响页面本身显示，只是少了系统级的逃逸拦截。
     */
    fun start(activity: android.app.Activity): Boolean {
        ensureWhitelisted(activity)
        return try {
            activity.startLockTask()
            true
        } catch (t: Throwable) {
            Logger.w(TAG) { "startLockTask failed: ${t.message}" }
            false
        }
    }

    fun stop(activity: android.app.Activity) {
        try {
            activity.stopLockTask()
        } catch (t: Throwable) {
            Logger.w(TAG) { "stopLockTask failed: ${t.message}" }
        }
    }
}
