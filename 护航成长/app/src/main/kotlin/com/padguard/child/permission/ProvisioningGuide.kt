package com.padguard.child.permission

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import java.security.MessageDigest

/**
 * 设备所有者（最高权限）预置指引。
 *
 * ## 为什么需要它
 * Android 不允许普通已安装应用"自己把自己变成设备所有者"——这是系统级安全边界。
 * 必须由外部预置流程完成，之后 [PermissionGranter.grantAllWithProgress] 才能在后台静默授予全部权限。
 * 因此"一键获取最高权限"的真实含义是：先让设备成为 DO，再静默拿全权限。
 *
 * ## 两条预置路径
 * 1. **调试 / 部署**：`adb shell dpm set-device-owner <组件>`；
 * 2. **量产 / 首次开机（扫码）**：在设置向导阶段扫描 DPC 二维码完成。
 *
 * 两条路径都把 [PadGuardDeviceAdminReceiver] 设为设备所有者，效果等价。
 *
 * ## 这里的每个字段都踩过坑，别再"简化"掉
 * - [adbCommand] 的 `--user current`：多用户设备上不加会静默失败
 *   （报错只有一句 "not allowed"，看不出是用户参数的问题）；
 * - [dpcQrJson] 的 `PACKAGE_DOWNLOAD_LOCATION` 与 `SIGNATURE_CHECKSUM`：
 *   扫码部署要求系统现下载 APK，缺校验值时 Android 会**直接拒绝安装**并回到向导首页，
 *   表现为"扫了码没反应"，极难定位。
 */
data class ProvisioningGuide(
    private val adminComponent: ComponentName?,
    private val packageName: String
) {
    /** `包名/类名` 扁平形式，用于 `dpm set-device-owner` 与二维码。 */
    val componentFlat: String =
        adminComponent?.let { "${it.packageName}/${it.className}" }.orEmpty()

    /**
     * 设备所有者激活命令。
     *
     * `--user current` 不可省略：平板出厂往往带有多个用户profile（如"儿童空间"、
     * 厂商的多用户模式），`dpm` 默认作用于 user 0，而我们在 user 0 之外的场景部署时，
     * 命令会返回 "Operation not allowed"，且这条信息不会提示是用户参数导致的。
     */
    val adbCommand: String
        get() = if (componentFlat.isNotEmpty())
            "adb shell dpm set-device-owner --user current $componentFlat"
        else "（未找到设备管理员组件，请检查 Manifest 声明）"

    /**
     * 激活前必须满足的前置条件。
     *
     * 这是部署失败**最常见**的原因，且报错信息完全不指向真正的问题：
     * `dpm set-device-owner` 只要设备上存在**任何一个已添加的账号**（Google 账号、
     * 厂商账号、甚至只是一个同步账号），就会失败并报
     * "Cannot set the device owner because the device has accounts"。
     * 而平板到手后第一件事往往就是登录账号，于是这条几乎必踩。
     */
    val adbPreconditions: String
        get() = buildString {
            appendLine("1. 【最常见失败原因】设备上不能有任何已登录的账号（Google / 厂商 / 微信 / 邮箱）。")
            appendLine("   已登录过账号的平板：设置 → 账号，全部移除后重试；仍失败则只能恢复出厂设置，")
            appendLine("   开机向导里【不要登录任何账号】，再执行该命令。")
            appendLine("2. 本应用必须已安装（无需先激活设备管理员，dpm 命令会直接完成）。")
            appendLine("3. 设备上不能已有其它 Device Owner（已有则无法覆盖，只能恢复出厂设置）。")
            appendLine("4. 开发者选项与 USB 调试已开启，电脑端已授权（adb devices 显示 device）。")
        }.trimEnd()

    /** 常见失败原因对照表，用于在界面上直接给出"下一步做什么" */
    val failureHints: List<Pair<String, String>>
        get() = listOf(
            "device has accounts" to "设置 → 账号，移除全部账号后重试",
            "already has a device owner" to "需恢复出厂设置后重新部署（已有 DO 无法覆盖）",
            "not allowed" to "命令缺少 --user current，或当前不是 shell 用户",
            "Unknown admin" to "本应用未授予设备管理员，先点「激活设备管理员」",
            "adb: no devices" to "USB 调试未开启或数据线仅充电，换线/换口后重试"
        )

    /**
     * 标准 DPC 预置二维码 JSON（设置向导扫码用）。
     *
     * 两个字段是**硬性要求**，省略会导致扫码后直接失败：
     * - `PACKAGE_DOWNLOAD_LOCATION`：向导阶段设备上还没有本应用，系统要从这里下载；
     * - `SIGNATURE_CHECKSUM`：下载包的 SHA-256（Base64/URL-safe）校验值，
     *   系统用它防止被投放到恶意 DPC。
     *
     * [apkUrl] / [sha256Base64] 由部署方注入（本机调试可以为空串，
     * 此时生成的是"仅设备管理员"的精简 JSON，适用于已装好 APK 的 adb 路径）。
     *
     * 校验值算法（安卓官方口径）：对 APK 签名证书做 SHA-256，
     * 可用 `apksigner verify --print-certs app-debug.apk` 取 cert SHA-256。
     */
    fun dpcQrJson(apkUrl: String = "", sha256Base64: String = ""): String = buildString {
        append("{")
        append("\"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME\":\"")
        append(componentFlat)
        append("\",\"android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE\":\"")
        append(packageName)
        append("\",\"android.app.extra.PROVISIONING_SKIP_ENCRYPTION\":true")
        append(",\"android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED\":true")
        if (apkUrl.isNotBlank()) {
            append(",\"android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION\":\"")
            append(apkUrl)
            append("\"")
        }
        if (sha256Base64.isNotBlank()) {
            append(",\"android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM\":\"")
            append(sha256Base64)
            append("\"")
        }
        append("}")
    }

    /** 兼容旧调用：不带下载信息的精简二维码（仅适用于 APK 已预装的场景） */
    val dpcQrJson: String get() = dpcQrJson()
}

/**
 * 计算本应用签名证书的 SHA-256 校验值（URL-safe Base64，无 padding、无换行）。
 *
 * 这是扫码部署（方式二）**必填**的
 * `android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM`：
 * 缺少它，Android 会直接拒绝安装并静默回到设置向导首页，表现为"扫了码没反应"。
 * 之前界面里生成的二维码没有这个字段，等于方式二必然失败 —— 这里补上可复制的取值，
 * 方便把 APK 放到可访问的 HTTPS 地址后生成一张真正可用的二维码。
 */
fun computeSignatureChecksum(context: Context): String = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    }
    // 必须先取到局部 val：signingInfo 是 Java 的可变属性(var)，
    // 直接用 `info.signingInfo != null` 无法智能转换为非空类型。
    @Suppress("DEPRECATION")
    val signing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo else null
    @Suppress("DEPRECATION")
    val certs = if (signing != null) signing.apkContentsSigners else info.signatures
    val cert = certs?.firstOrNull() ?: return ""
    val digest = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
    Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}.getOrDefault("")
