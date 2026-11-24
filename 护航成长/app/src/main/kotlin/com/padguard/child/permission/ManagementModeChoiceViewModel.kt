package com.padguard.child.permission

import androidx.lifecycle.ViewModel
import com.padguard.core.data.model.policy.ManagementMode
import com.padguard.core.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 装机首启「硬管控 / 软管控」二选一的数据源。
 *
 * 只做一件事：把家长的选择落盘。落库后 [AuthRepository.managementState] 会发出新值，
 * [com.padguard.child.MainActivity] 的入口分流自动重组进入下一步，页面本身不自行导航。
 *
 * 选择硬管控时会顺带撤销旧的 `DEVICE_OWNER_PROMPT_ACK`：那个标记表达的是
 * "这次不预置 DO 了"，与"明确选择硬管控"冲突。不清掉的话，曾降级到软管控的设备
 * 在切回硬管控时会直接跳过 DO 预置页，硬管控能力全部落空。
 */
@HiltViewModel
class ManagementModeChoiceViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    /** 记录选择；「我的」页切换入口必须做同样的清 ack 处理，见 ProfileViewModel。 */
    suspend fun choose(mode: ManagementMode) {
        if (mode == ManagementMode.HARD) authRepository.clearDeviceOwnerPromptAck()
        authRepository.setManagementMode(mode)
    }
}
