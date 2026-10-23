package com.padguard.child.hidden

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.padguard.core.common.Logger
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 家长密码验证通过后的「退出并卸载」。
 *
 * ## 设计：家长密码门 + 设备管理员 deterrent
 * 1. 先尝试撤销设备管理员（[DeviceAdminBridge.isAdminActive] 为真时 [DevicePolicyManager.removeActiveAdmin]）。
 *    设备管理员激活期间，系统设置里的"卸载"入口被禁用——这是阻止孩子随意卸载的第一道物理屏障。
 * 2. 再拉起系统 [Intent.ACTION_UNINSTALL_PACKAGE] 卸载页，由家长在系统界面确认卸载。
 *
 * ## 已知边界（如实标注，不谎报"已锁死"）
 * - 非 Device Owner 下，设备管理员**不能**阻止强制停止（Settings → 强制停止依然可用），
 *   也无法阻止在设置里"停用/删除设备管理员"后卸载。本能力是 deterrent，不是绝对锁。
 * - 强制停止会一并取消 JobScheduler 看门狗，守护在下次开机/解锁前失效；这是非 DO 设备的固有限制，
 *   真正防脱管需 Device Owner 出厂预装（见三件套重构计划 §7）。
 * - 撤销/卸载意图均包 [android.content.Intent.FLAG_ACTIVITY_NEW_TASK]，因其由后台/Activity 触发。
 */
@Singleton
class ExitController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge
) {
    fun requestUninstall(activity: Activity) {
        // DO/PO 形态下自身卸载被系统级封死（setUninstallBlocked(true)），
        // 必须先解封，否则即便家长密码门已通过，系统仍会驳回卸载，卸载流程卡死。
        // 非 DO 设备 can(BLOCK_UNINSTALL)=false，此处为 no-op，幂等安全。
        runCatching {
            if (admin.can(Capability.BLOCK_UNINSTALL)) {
                admin.setUninstallBlocked(admin.selfPackageName, false)
                Logger.i(TAG) { "uninstall block released before uninstall" }
            }
        }.onFailure { Logger.e(TAG, it) { "release uninstall block failed" } }

        runCatching {
            admin.adminComponent?.let { comp ->
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (dpm.isAdminActive(comp)) {
                    dpm.removeActiveAdmin(comp)
                    Logger.i(TAG) { "device admin removed before uninstall" }
                }
            }
        }.onFailure { Logger.e(TAG, it) { "remove device admin failed" } }

        val uri = Uri.parse("package:${context.packageName}")
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }
            .onFailure { Logger.e(TAG, it) { "uninstall intent failed" } }
    }

    companion object {
        private const val TAG = "ExitController"
    }
}
