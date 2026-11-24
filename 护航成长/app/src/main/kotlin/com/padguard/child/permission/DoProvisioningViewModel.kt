package com.padguard.child.permission

import android.app.Application
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.model.policy.ManagementMode
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * 发布包「设备所有者(DO)预置」引导页数据源（#7 DO 部署闭环·强制首步）。
 *
 * 仅服务于发布包装机第一道门：向家长展示成为 DO 的合法路径
 * （adb 命令 / 扫码预置——App 自身**无法**静默自提，这是 Android 系统级安全边界），
 * 并提供「重新检测」与「已知风险仍要继续」两个动作。
 *
 * debug 构建不挂载本页（见 [com.padguard.child.MainActivity]）：开发者常在多账号 /
 * 已 DO 设备上联调，强制会卡死开发流程，故保留软降级。
 */
@HiltViewModel
class DoProvisioningViewModel @Inject constructor(
    app: Application,
    private val admin: DeviceAdminBridge,
    private val authRepository: AuthRepository
) : androidx.lifecycle.ViewModel() {

    init {
        // 进入引导页先刷新一次身份，确保读到最新的 DO 状态（家长可能刚执行完 adb）。
        admin.refreshControlMode()
    }

    /** 当前管控身份；变为 [ControlMode.DEVICE_OWNER] 时引导页由父级自动放行。 */
    val controlMode: StateFlow<ControlMode> = admin.controlMode

    /** 成为设备所有者的预置指引（adb 命令 / DPC 二维码 / 前置条件 / 失败对照）。 */
    val provisioning = ProvisioningGuide(admin.adminComponent, app.packageName)

    /** 家长执行完 adb / 扫码后点「重新检测」，重读管控身份。 */
    fun refresh() = admin.refreshControlMode()

    /**
     * 家长在硬管控引导页选择「改用软管控」。
     *
     * 两个标记都要写：ack 保留给旧分流 / 验收脚本兼容，management_mode 是新分流的事实源。
     * **不清除**已授予的三件套权限与已下发的策略配置——两档各自保留，日后切回硬管控无需重配。
     */
    suspend fun fallbackToSoft() {
        authRepository.setDeviceOwnerPromptAck()
        authRepository.setManagementMode(ManagementMode.SOFT)
    }
}
