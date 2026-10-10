package com.padguard.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.data.importer.DeviceImportFileParser
import com.padguard.domain.model.Device
import com.padguard.domain.model.DeviceImportRow
import com.padguard.domain.model.DeviceImportResult
import com.padguard.domain.model.LanDevice
import com.padguard.domain.repository.DeviceRepository
import com.padguard.domain.repository.PolicyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设备管理 ViewModel
 * 承载：设备台账列表（实时流）、四种接入方式（扫码/配对码/局域网发现/Excel 导入）、
 * 重命名与解绑（增删改查）。
 */
data class DeviceManageUiState(
    val toast: String? = null,
    val accessSheetVisible: Boolean = false,
    // 扫码接入
    val qrPayload: String? = null,
    // 配对码接入
    val pairingCode: String? = null,
    // 局域网发现
    val lanDevices: List<LanDevice> = emptyList(),
    val lanScanning: Boolean = false,
    // Excel 导入
    val importFileName: String? = null,
    val importPreview: List<DeviceImportRow> = emptyList(),
    val importError: String? = null,
    // 编辑 / 删除
    val editingDevice: Device? = null,
    val deletingDevice: Device? = null,
    // S11：绑定成功后弹出「设置操作密码」步骤（设备尚未设置操作密码时）
    val postBindDevice: Device? = null,
    val postBindBusy: Boolean = false
)

@HiltViewModel
class DeviceManageViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val policyRepository: PolicyRepository,
    private val importFileParser: DeviceImportFileParser,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /** 设备台账实时流：接入 / 解绑 / 重命名后自动刷新 */
    val devices: StateFlow<List<Device>> = deviceRepository.observeDeviceList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(DeviceManageUiState())
    val uiState: StateFlow<DeviceManageUiState> = _uiState.asStateFlow()

    // ==================== 接入弹窗 ====================

    fun openAccessSheet() {
        _uiState.value = DeviceManageUiState(accessSheetVisible = true)
        loadQrPayload()
        loadPairingCode()
    }

    fun closeAccessSheet() {
        _uiState.value = DeviceManageUiState()
    }

    private fun loadQrPayload() {
        viewModelScope.launch {
            deviceRepository.generateBindQrPayload()
                .onSuccess { _uiState.value = _uiState.value.copy(qrPayload = it) }
        }
    }

    private fun loadPairingCode() {
        viewModelScope.launch {
            deviceRepository.generatePairingCode()
                .onSuccess { _uiState.value = _uiState.value.copy(pairingCode = it) }
        }
    }

    /** 家长端扫描被控平板二维码后回调（Mock 阶段支持手动输入载荷模拟扫码） */
    fun bindByQrPayload(payload: String) {
        viewModelScope.launch {
            deviceRepository.bindByQrPayload(payload.trim(), alias = null)
                .onSuccess {
                    showToast("已接入：${it.name}")
                    closeAccessSheet()
                    maybePromptPostBindPassword(it)
                }
                .onFailure { showToast(it.message ?: "扫码接入失败") }
        }
    }

    fun bindByPairingCode(code: String) {
        viewModelScope.launch {
            deviceRepository.bindByPairingCode(code.trim(), alias = null)
                .onSuccess {
                    showToast("已接入：${it.name}")
                    closeAccessSheet()
                    maybePromptPostBindPassword(it)
                }
                .onFailure { showToast(it.message ?: "配对码接入失败") }
        }
    }

    fun scanLanDevices() {
        _uiState.value = _uiState.value.copy(lanScanning = true)
        viewModelScope.launch {
            deviceRepository.discoverLanDevices()
                .onSuccess { _uiState.value = _uiState.value.copy(lanDevices = it, lanScanning = false) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(lanDevices = emptyList(), lanScanning = false)
                    showToast(it.message ?: "局域网扫描失败")
                }
        }
    }

    fun bindLanDevice(lanDevice: LanDevice) {
        viewModelScope.launch {
            deviceRepository.bindLanDevice(lanDevice, alias = null)
                .onSuccess {
                    showToast("已接入：${it.name}")
                    closeAccessSheet()
                    _uiState.value = _uiState.value.copy(
                        lanDevices = _uiState.value.lanDevices.filterNot { d -> d.deviceId == lanDevice.deviceId }
                    )
                    maybePromptPostBindPassword(it)
                }
                .onFailure { showToast(it.message ?: "接入失败") }
        }
    }

    // ==================== Excel 导入 ====================

    /** SAF 选定文件后解析预览 */
    fun onImportFilePicked(uri: Uri, fileName: String) {
        viewModelScope.launch {
            val rows = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    importFileParser.parse(fileName, stream)
                } ?: throw IllegalStateException("无法读取所选文件")
            }.getOrElse { showToast(it.message ?: "文件读取失败"); return@launch }

            rows.fold(
                onSuccess = { parsed ->
                    if (parsed.isEmpty()) {
                        showToast("表格中未解析到设备数据")
                    } else {
                        _uiState.value = _uiState.value.copy(
                            importFileName = fileName,
                            importPreview = parsed,
                            importError = null
                        )
                    }
                },
                onFailure = { _uiState.value = _uiState.value.copy(importError = it.message) }
            )
        }
    }

    fun confirmImport() {
        val rows = _uiState.value.importPreview
        if (rows.isEmpty()) return
        viewModelScope.launch {
            deviceRepository.importDevices(rows)
                .onSuccess { result: DeviceImportResult ->
                    val failedNote = if (result.failedRows.isEmpty()) "" else "，失败 ${result.failedRows.size} 行"
                    showToast(
                        "导入完成：成功 ${result.successCount} 台，" +
                            "跳过已存在 ${result.skippedCount} 台$failedNote"
                    )
                    closeAccessSheet()
                }
                .onFailure { showToast(it.message ?: "导入失败") }
        }
    }

    // ==================== 编辑 / 删除 ====================

    fun openEdit(device: Device) {
        _uiState.value = _uiState.value.copy(editingDevice = device)
    }

    fun dismissEdit() {
        _uiState.value = _uiState.value.copy(editingDevice = null)
    }

    fun renameDevice(deviceId: String, newName: String) {
        viewModelScope.launch {
            deviceRepository.renameDevice(deviceId, newName.trim())
                .onSuccess { showToast("已重命名"); dismissEdit() }
                .onFailure { showToast(it.message ?: "重命名失败") }
        }
    }

    fun openDelete(device: Device) {
        _uiState.value = _uiState.value.copy(deletingDevice = device)
    }

    fun dismissDelete() {
        _uiState.value = _uiState.value.copy(deletingDevice = null)
    }

    fun deleteDevice(deviceId: String) {
        viewModelScope.launch {
            deviceRepository.unbindDevice(deviceId)
                .onSuccess { showToast("设备已解绑"); dismissDelete() }
                .onFailure { showToast(it.message ?: "解绑失败") }
        }
    }

    // ==================== S11：绑定后强制设密码步骤 ====================

    /**
     * 绑定成功后的「操作密码」补设引导（S11 家长侧）。
     *
     * 为什么是"引导"而不是硬闸：密码设置走服务端下发，若设备离线/接口失败，
     * 硬闸会把家长卡死在流程里。这里的"强制"体现在：绑定成功即打断当前操作弹出对话框，
     * 明确给出「跳过」按钮但文案说明后果 —— 既有仪式感，又不制造死路。
     * 已设密码的设备（重新接入/换绑）不重复打扰。
     */
    private fun maybePromptPostBindPassword(device: Device) {
        if (device.guardPasswordSet) return
        _uiState.value = _uiState.value.copy(postBindDevice = device)
    }

    /** 在绑定后弹窗里确认设置操作密码（口径与设备详情页 GuardPasswordCard 一致：4~32 位） */
    fun confirmPostBindPassword(device: Device, password: String) {
        if (password.length !in 4..32) return
        _uiState.value = _uiState.value.copy(postBindBusy = true)
        viewModelScope.launch {
            policyRepository.setGuardPassword(device.id, password, clear = false)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        postBindBusy = false,
                        postBindDevice = null,
                        toast = "操作密码已设置，已同步到孩子端"
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        postBindBusy = false,
                        toast = it.message ?: "设置失败，请稍后在设备详情页重试"
                    )
                }
        }
    }

    /** 跳过补设：只关闭弹窗并说明后果，设备保持「未设密码」状态（设备详情页可随时补） */
    fun dismissPostBindPassword() {
        _uiState.value = _uiState.value.copy(
            postBindDevice = null,
            toast = "已跳过。未设操作密码前，孩子端设置页仅能顶回、无法验证家长身份"
        )
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    private fun showToast(message: String) {
        _uiState.value = _uiState.value.copy(toast = message)
    }
}
