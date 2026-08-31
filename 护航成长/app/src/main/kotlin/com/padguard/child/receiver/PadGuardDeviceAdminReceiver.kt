package com.padguard.child.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.padguard.child.di.ApplicationScope
import com.padguard.child.service.GuardService
import com.padguard.core.common.Logger
import com.padguard.core.data.model.LogType
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设备管理器回调入口。
 *
 * 这个类同时承担三个角色，容易被混淆，先说清：
 * 1. **DEVICE_ADMIN 的能力载体** —— 只有激活它，`lockNow` / `setCameraDisabled` 才可用；
 * 2. **Device Owner 的锚点** —— `adb shell dpm set-device-owner` 指向的就是这个组件；
 * 3. **管控失效的第一现场** —— 用户取消激活时，这里是唯一能感知到的地方。
 *
 * ## 关于"防取消激活"的真实能力边界
 * - 有 DO 身份：设置里根本没有"停用设备管理器"入口，[onDisableRequested] 不会被调用；
 * - 只有 DEVICE_ADMIN：用户**可以**停用，我们拦不住。
 *   能做的只有两件事：给一段有威慢力的提示文案（[onDisableRequested] 的返回值），
 *   以及在真的被停用时立刻上报高危告警（[onDisabled]）。
 *
 * 不要在这里写"阻止停用"的逻辑 —— Android 没有这个 API。
 * 声称能阻止会让管控端把 DEVICE_ADMIN 模式当成 DO 模式来承诺，那是更大的问题。
 */
@AndroidEntryPoint
class PadGuardDeviceAdminReceiver : DeviceAdminReceiver() {

    @Inject
    lateinit var admin: DeviceAdminBridge

    @Inject
    lateinit var logRepository: LogRepository

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        val mode = admin.refreshControlMode()
        Logger.i(TAG) { "device admin enabled, controlMode=$mode" }
        // 权限刚拿到，立刻拉起守护服务并重新下发一次策略
        GuardService.start(context, reason = "adminEnabled")
    }

    /**
     * 用户在设置里点了"停用设备管理器"，系统弹确认框前会问我们要一段说明。
     *
     * 返回的文案不是装饰：这是唯一一次能让家长/学生看到后果的机会。
     * 写清"会做什么"而不是空喊警告，实际劝退效果更好。
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Logger.w(TAG) { "device admin disable requested" }
        recordAsync {
            logRepository.append(
                type = LogType.SETTING_CHANGE_ATTEMPT,
                payload = mapOf("action" to "disableDeviceAdminRequested")
            )
        }
        return context.getString(com.padguard.child.R.string.admin_disable_warning)
    }

    /**
     * 已经被停用。
     *
     * 此时绝大部分管控 API 已失效，我们只剩"如实上报"这一件事可做。
     * 用 HIGH 级别 —— 这等同于设备脱管，必须让家长立刻看到。
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        val mode = admin.refreshControlMode()
        Logger.w(TAG) { "device admin DISABLED, controlMode=$mode" }
        recordAsync {
            logRepository.raise(
                type = RiskType.PERMISSION_REVOKED,
                level = RiskLevel.HIGH,
                detail = mapOf(
                    "component" to "DeviceAdminReceiver",
                    "controlMode" to mode.name,
                    "note" to "设备管理器已被停用，管控能力大幅降级"
                ),
                deviceId = authRepository.getDeviceId()
            )
        }
    }

    /**
     * DO 预置流程（QR 码 / NFC 配置）完成。
     *
     * 走到这里说明已经是 Device Owner，是能力最完整的状态。
     * 必须主动拉起服务：预置流程结束后系统不会自动启动我们的任何组件。
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        val mode = admin.refreshControlMode()
        Logger.i(TAG) { "provisioning complete, controlMode=$mode" }
        GuardService.start(context, reason = "provisioningComplete")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Logger.i(TAG) { "lock task entering: $pkg" }
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Logger.i(TAG) { "lock task exiting" }
        // Kiosk 被意外退出（如厂商 ROM 的特殊按键组合），重新压一次策略
        GuardService.start(context, reason = "lockTaskExited")
    }

    /**
     * 异步落库的统一入口。
     *
     * 必须配合 `goAsync()`：Receiver 回调返回后进程随时可能被回收，
     * 直接 `scope.launch` 会让告警在"停用管理器 → 进程被杀"这条最关键的路径上丢失，
     * 而这恰恰是最需要留下证据的时刻。
     */
    private fun recordAsync(block: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                Logger.w(TAG, t) { "failed to record admin event" }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AdminReceiver"

        /** DO 部署与 API 调用都需要这个组件名，集中在这里避免各处硬编码 */
        fun componentName(context: Context): ComponentName =
            ComponentName(context, PadGuardDeviceAdminReceiver::class.java)
    }
}
