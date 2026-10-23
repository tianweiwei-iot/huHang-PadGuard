package com.padguard.child.guard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.padguard.child.capture.AccessibilityScreenshot
import com.padguard.core.common.Logger
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 无障碍截屏能力自愈 —— 修复"服务启用早于 canTakeScreenshot 能力"的陈旧元数据。
 *
 * ## 解决什么问题
 * `canTakeScreenshot`（远程截屏/实时看屏无障碍通道的前提）来自无障碍服务元数据。
 * 系统只在服务被**启用**的那一刻读取一次元数据；孩子端从"无此能力的旧版本"覆盖升级后，
 * 服务仍是启用态但能力位不在 —— 截屏/实时看屏被迫退回 MediaProjection 投影链路：
 * 状态栏出现"正在共享屏幕"红标、必要时还要向孩子弹授权框。手动修复需要用户在
 * 系统设置里把服务关闭再打开，但无障碍入口藏得深，家长基本找不到。
 *
 * ## 自愈原理
 * Device Owner 有权写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`。
 * 把服务**先禁用再启用**一次，系统会以新版本元数据重新绑定 —— 能力位随重读生效。
 * 全程无界面、无弹窗，孩子无感知（约 3 秒内完成，期间拦截闸门短暂离线后自动恢复）。
 *
 * ## 触发纪律（防循环）
 * - 仅当：API 30+ 且服务能力缺失（[AccessibilityScreenshot.isAvailable] 为 false）；
 * - 每个进程生命周期至多尝试一次（内存标记）；成功后能力就位，天然不会再进本分支；
 * - 设备非 Device Owner 时无法静默写 secure 设置，只记日志给运维指引（手动开关或
 *   卸载重装——卸载会清掉服务启用态，重装后首次启用即读取新元数据，同样能治愈）。
 */
@Singleton
class AccessibilityCapabilityHealer @Inject constructor(
    private val admin: DeviceAdminBridge,
    private val accessibilityScreenshot: AccessibilityScreenshot,
    @ApplicationContext private val context: Context
) {

    /** 本进程是否已尝试过自愈（成功与失败都算：失败留待下次进程重启再试，防抖动循环） */
    @Volatile private var attemptedThisProcess = false

    private val component = ComponentName(context, OperationGuardAccessibilityService::class.java)
    private val flat = component.flattenToString()

    /**
     * 检测并在需要时执行自愈。非阻塞：内部切到 IO 线程执行禁用→延迟→启用序列。
     * 可安全重复调用（自带 once-per-process 守卫）。
     */
    suspend fun healIfNeeded() = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext
        if (attemptedThisProcess) return@withContext
        if (accessibilityScreenshot.isAvailable()) return@withContext
        attemptedThisProcess = true

        if (!admin.isDeviceOwner()) {
            Logger.w(TAG) {
                "a11y screenshot capability missing and no Device Owner: " +
                    "ask guardian to re-toggle the service in Settings > Accessibility (or reinstall)"
            }
            return@withContext
        }

        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val current = Settings.Secure.getString(context.contentResolver, key).orEmpty()
        val entries = current.split(':').filter { it.isNotBlank() }
        val ours = entries.filter {
            ComponentName.unflattenFromString(it) == component || it.equals(flat, ignoreCase = true)
        }

        when {
            // 场景 1：服务根本没启用 —— 直接启用，启用即读取新元数据，一步到位
            ours.isEmpty() -> {
                Logger.w(TAG) { "a11y guard service not enabled, enabling via Device Owner (fresh metadata)" }
                admin.setSecureSetting(key, (entries + flat).joinToString(":"))
                delay(ENABLE_DELAY_MS)
            }
            // 场景 2：已启用但能力缺失（陈旧元数据）—— 禁用→启用，迫使系统重读
            else -> {
                Logger.w(TAG) { "a11y service enabled but screenshot capability stale, silent re-toggle" }
                admin.setSecureSetting(key, (entries - ours.toSet()).joinToString(":"))
                delay(DISABLE_DELAY_MS)
                admin.setSecureSetting(key, current)
                delay(ENABLE_DELAY_MS)
            }
        }

        val healed = accessibilityScreenshot.isAvailable()
        if (healed) {
            Logger.i(TAG) { "a11y screenshot capability healed (metadata re-read ok)" }
        } else {
            Logger.w(TAG) {
                "a11y capability still missing after re-toggle; will retry on next process start"
            }
        }
    }

    /**
     * 确保无障碍服务处于启用态（仅 Device Owner 可静默写系统设置）。
     *
     * 与 [healIfNeeded] 的区别：无论截图能力是否就绪，只要服务没在启用名单里就启用它。
     * 用于权限掉失自检：国产 ROM 在系统设置里切换"后台弹出界面 / 悬浮窗"等权限时会强制重启
     * 本应用，重启后无障碍服务绑定被解除（设置里"无障碍"项被系统自动关闭），DO 设备上这里
     * 静默重启用即可治愈，非 DO 设备则由 [com.padguard.child.guard.PermissionHealthChecker]
     * 诚实告警并引导家长去设置重新启用。
     */
    suspend fun ensureEnabled() = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext
        if (!admin.isDeviceOwner()) {
            Logger.w(TAG) { "cannot auto-enable a11y without Device Owner; ask guardian to enable in Settings" }
            return@withContext
        }
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val current = Settings.Secure.getString(context.contentResolver, key).orEmpty()
        val entries = current.split(':').filter { it.isNotBlank() }
        val ours = entries.any { it.equals(flat, ignoreCase = true) }
        if (!ours) {
            admin.setSecureSetting(key, (entries + flat).joinToString(":"))
            Logger.i(TAG) { "a11y service force-enabled via Device Owner (permission self-heal)" }
        }
    }

    /**
     * 静默禁用无障碍服务（仅设备解绑时调用）。
     *
     * 与 [ensureEnabled] 相反：把本服务从 `ENABLED_ACCESSIBILITY_SERVICES` 中移除，
     * 使"授权被钉死"的状态彻底解除。仅 Device Owner 可静默写系统设置；非 DO 设备无操作
     * （解绑后本就由引导流程重新授权，不影响正确性）。
     */
    suspend fun disable() = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext
        if (!admin.isDeviceOwner()) {
            Logger.w(TAG) { "cannot auto-disable a11y without Device Owner; skip" }
            return@withContext
        }
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val current = Settings.Secure.getString(context.contentResolver, key).orEmpty()
        val entries = current.split(':').filter { it.isNotBlank() }
        val next = entries.filter { !it.equals(flat, ignoreCase = true) }
        admin.setSecureSetting(key, next.joinToString(":"))
        Logger.i(TAG) { "a11y service disabled via Device Owner (unbind)" }
    }

    companion object {
        private const val TAG = "A11yCapabilityHealer"

        /** 禁用后停留时长：给系统完成解绑的时间，避免"写回启用"被当作无效操作忽略 */
        private const val DISABLE_DELAY_MS = 1_500L

        /** 重新启用后等系统重绑、能力位生效的时长 */
        private const val ENABLE_DELAY_MS = 3_000L
    }
}
