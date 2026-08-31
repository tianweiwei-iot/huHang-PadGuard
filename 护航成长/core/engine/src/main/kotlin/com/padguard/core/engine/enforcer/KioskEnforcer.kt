package com.padguard.core.engine.enforcer

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.KioskMode
import com.padguard.core.data.model.policy.KioskPolicy
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kiosk 纯净桌面（学校场景核心）。
 *
 * ## 三层锁定，缺一层就能被绕过
 * 1. **LockTask 白名单**（`setLockTaskPackages`）：只有名单内的应用能进入锁定任务模式，
 *    名单外的应用连启动都会被系统拒绝 —— 这是系统级拦截，比无障碍事后关闭可靠得多；
 * 2. **常驻默认桌面**（`addPersistentPreferredActivity`）：按 HOME 键回到本应用，
 *    且用户无法在设置里改回原桌面；
 * 3. **LockTask 特性裁剪**（`setLockTaskFeatures`）：关掉通知栏、状态栏信息、最近任务，
 *    避免从通知/多任务界面跳出白名单。
 *
 * ## 故意保留 HOME 键的原因
 * 很多 Kiosk 实现会把 HOME 一起锁掉（`LOCK_TASK_FEATURE_NONE`），结果是：
 * 一旦被锁定的那个应用崩溃或白屏，设备就彻底不可操作，**现场只能刷机**。
 * 本实现保留 HOME + 系统信息：HOME 会回到本应用自己的桌面（第 2 层保证），
 * 既不会跳出管控，又给了异常情况下的退路。
 *
 * ## 单应用模式（SINGLE_APP）的额外风险
 * 单应用模式下只放一个业务应用，若该应用未安装/被卸载，设备会陷入"无处可去"的状态。
 * 因此本类**始终把自身包名加入白名单**，保证任何情况下都能回到管控端界面。
 */
@Singleton
class KioskEnforcer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge
) {

    @Volatile
    private var activeConfig: KioskRuntime = KioskRuntime()

    /** 当前生效的 Kiosk 运行态，供 app 层 UI（桌面、水印）读取 */
    fun runtime(): KioskRuntime = activeConfig

    fun apply(policy: KioskPolicy): EnforceReport {
        val report = EnforceReport()

        if (!policy.enabled) {
            disable(report)
            activeConfig = KioskRuntime(watermark = WatermarkRuntime(enabled = false))
            return report
        }

        if (!admin.can(Capability.LOCK_TASK)) {
            // 降级：无 DO 时 LockTask 完全不可用，只能靠"本应用作为桌面 + 无障碍回弹"近似模拟
            report.markUnsupported("kiosk", "需要 Device Owner；将退化为普通桌面替换 + 事后拦截")
            activeConfig = KioskRuntime(
                enabled = true,
                degraded = true,
                mode = policy.mode,
                allowedPackages = withEssentials(policy),
                watermark = policy.watermark.toRuntime()
            )
            return report
        }

        val allowed = withEssentials(policy)
        report.record("kiosk.lockTaskPackages=${allowed.size}", admin.setLockTaskPackages(allowed.toList()))

        // 特性裁剪：保留 HOME 与系统信息（见类注释的取舍说明）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val features = DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
            report.record("kiosk.lockTaskFeatures", admin.setLockTaskFeatures(features))
        } else {
            report.markUnsupported("kiosk.lockTaskFeatures", "需要 Android 9+，低版本沿用系统默认（全部关闭）")
        }

        // 常驻桌面
        val launcher = resolveOwnLauncher()
        if (launcher == null) {
            report.markFailed("kiosk.persistentLauncher", "本应用未声明 CATEGORY_HOME Activity")
        } else {
            report.record("kiosk.persistentLauncher", admin.setPersistentPreferredLauncher(launcher))
        }

        activeConfig = KioskRuntime(
            enabled = true,
            degraded = false,
            mode = policy.mode,
            allowedPackages = allowed,
            singleAppPackage = policy.allowedPackages.firstOrNull().orEmpty()
                .takeIf { policy.mode == KioskMode.SINGLE_APP }.orEmpty(),
            watermark = policy.watermark.toRuntime()
        )

        Logger.i(TAG) { "kiosk applied: mode=${policy.mode}, allowed=${allowed.size}" }
        return report
    }

    /**
     * 解除 Kiosk。
     *
     * 顺序很重要：**先清常驻桌面偏好，再清 LockTask 白名单**。
     * 反过来的话，中间那一瞬间设备既没有 LockTask 保护、又仍强制回本应用，
     * 若此时进程被杀就会留下"回不到系统桌面"的残留状态。
     */
    fun disable(report: EnforceReport = EnforceReport()): EnforceReport {
        if (!admin.can(Capability.LOCK_TASK)) {
            report.note("kiosk=OFF")
            return report
        }
        report.record("kiosk.clearPersistentLauncher", admin.clearPersistentPreferredLauncher(context.packageName))
        report.record("kiosk.clearLockTaskPackages", admin.setLockTaskPackages(emptyList()))
        report.note("kiosk=OFF")
        return report
    }

    /** 某个应用当前是否允许在 Kiosk 下运行（无障碍拦截与桌面 UI 共用同一判定） */
    fun isAllowed(packageName: String): Boolean {
        val config = activeConfig
        if (!config.enabled) return true
        return packageName in config.allowedPackages
    }

    /**
     * 白名单必须补齐的"生存必需品"。
     *
     * 少了这些的后果都很具体：漏掉自身 → 回不到管控端；漏掉输入法 → 打不了字；
     * 漏掉系统 UI → 弹不出系统对话框（含权限授权框）。
     */
    private fun withEssentials(policy: KioskPolicy): Set<String> {
        val set = policy.allowedPackages.toMutableSet()
        set += context.packageName
        set += policy.homePackage.takeIf { it.isNotBlank() } ?: context.packageName
        set += ESSENTIAL_PACKAGES

        runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
            )?.substringBefore('/')?.takeIf { it.isNotBlank() }?.let { set += it }
        }
        return set
    }

    /** 反查本应用声明的 HOME Activity，避免 engine 模块硬编码 app 模块的类名 */
    private fun resolveOwnLauncher(): ComponentName? {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(context.packageName)
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrNull().orEmpty()
        return resolved.firstOrNull()?.activityInfo?.let { ComponentName(it.packageName, it.name) }
    }

    private fun com.padguard.core.data.model.policy.WatermarkPolicy.toRuntime() = WatermarkRuntime(
        enabled = enabled,
        content = content,
        position = position.name,
        opacity = opacity.coerceIn(0.05f, 0.8f)
    )

    companion object {
        private const val TAG = "KioskEnforcer"

        /** Kiosk 白名单里必须存在的系统包，否则设备会失去基本可用性 */
        private val ESSENTIAL_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.emergency"
        )
    }
}

/** Kiosk 运行态，供 app 层桌面与水印渲染使用 */
data class KioskRuntime(
    val enabled: Boolean = false,
    /** 无 Device Owner 时的降级态：只能近似模拟，需在 UI 上如实提示 */
    val degraded: Boolean = false,
    val mode: KioskMode = KioskMode.MULTI_APP,
    val allowedPackages: Set<String> = emptySet(),
    val singleAppPackage: String = "",
    val watermark: WatermarkRuntime = WatermarkRuntime()
)

data class WatermarkRuntime(
    val enabled: Boolean = false,
    val content: String = "",
    val position: String = "TOP",
    val opacity: Float = 0.35f
) {
    /** 渲染前替换占位符，占位符定义见 [com.padguard.core.data.model.policy.WatermarkPolicy] */
    fun render(deviceSn: String, studentName: String, className: String, deviceId: String): String =
        content
            .replace("{deviceSn}", deviceSn)
            .replace("{studentName}", studentName)
            .replace("{className}", className)
            .replace("{deviceId}", deviceId)
}
