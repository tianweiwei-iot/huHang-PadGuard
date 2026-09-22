package com.padguard.child.permission

import android.content.ComponentName

/**
 * 设备所有者（最高权限）预置指引。
 *
 * ## 为什么需要它
 * Android 不允许普通已安装应用"自己把自己变成设备所有者"——这是系统级安全边界。
 * 必须由外部预置流程完成，之后 [PermissionGranter.grantAllWithProgress] 才能在后台静默授予全部权限。
 * 因此"一键获取最高权限"的真实含义是：先让设备成为 DO，再静默拿全权限。
 *
 * ## 两条预置路径
 * 1. **调试 / 部署（最常用，无需恢复出厂）**：`adb shell dpm set-device-owner <组件>`；
 * 2. **量产 / 首次开机（扫码）**：在设置向导阶段扫描 DPC 二维码完成。
 *
 * 两条路径都把 [PadGuardDeviceAdminReceiver] 设为设备所有者，效果等价。
 */
data class ProvisioningGuide(
    private val adminComponent: ComponentName?,
    private val packageName: String
) {
    /** `包名/类名` 扁平形式，用于 `dpm set-device-owner` 与二维码。 */
    val componentFlat: String =
        adminComponent?.let { "${it.packageName}/${it.className}" }.orEmpty()

    /** 设备所有者激活命令（无需恢复出厂，调试/部署首选）。 */
    val adbCommand: String
        get() = if (componentFlat.isNotEmpty())
            "adb shell dpm set-device-owner $componentFlat"
        else "（未找到设备管理员组件，请检查 Manifest 声明）"

    /** 标准 DPC 预置二维码 JSON（设置向导扫码用）。 */
    val dpcQrJson: String
        get() = buildString {
            append("{\"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME\":\"")
            append(componentFlat)
            append("\",\"android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE\":\"")
            append(packageName)
            append("\",\"android.app.extra.PROVISIONING_SKIP_ENCRYPTION\":true")
            append(",\"android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED\":true")
            append("}")
        }
}
