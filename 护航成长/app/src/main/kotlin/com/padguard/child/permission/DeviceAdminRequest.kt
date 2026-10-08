package com.padguard.child.permission

import android.content.Context
import android.content.Intent
import com.padguard.child.receiver.PadGuardDeviceAdminReceiver
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 构造「激活设备管理员」的系统意图。
 *
 * 收口到这里是因为装机引导页与 App 内的补开引导页都需要它，且两处对组件名的
 * 推导必须一致：历史上曾经一处用 Receiver 的 `componentName()`、另一处靠 manifest 反查，
 * 反查在某些 ROM 上为空，导致 `putExtra(null)` —— 表现就是"点了去开启，毫无反应"。
 *
 * 另外这里**不能加 `FLAG_ACTIVITY_NEW_TASK`**：该 flag 会把系统确认页开到另一个任务栈，
 * 部分 ROM 上同样表现为点了没反应。页面统一用 ActivityResultLauncher 拉起。
 */
@Singleton
class DeviceAdminRequest @Inject constructor(
    @ApplicationContext private val app: Context
) {
    fun intent(admin: DeviceAdminBridge): Intent =
        Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            val comp = runCatching { PadGuardDeviceAdminReceiver.componentName(app) }
                .getOrNull() ?: admin.adminComponent
            putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "激活后本应用才能执行锁屏、应用管控、防止孩子卸载等家长守护能力"
            )
        }
}
