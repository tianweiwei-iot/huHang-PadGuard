package com.padguard.parent.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.data.importer.DeviceImportFileParser
import com.padguard.parent.domain.model.Device
import com.padguard.parent.domain.model.DeviceImportRow
import com.padguard.parent.domain.model.DeviceImportResult
import com.padguard.parent.domain.model.Enrollment
import com.padguard.parent.domain.model.EnrollmentState
import com.padguard.parent.domain.model.LanDevice
import com.padguard.parent.domain.repository.DeviceRepository
import com.padguard.parent.domain.repository.PolicyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 接入会话的界面状态。
 *
 * 单一真相源：二维码 Tab 与配对码 Tab 读的是**同一个** [Active.enrollment]，
 * 因此两者永远同源 —— 这是"二维码里的码和显示的配对码不是一个"在端上的根治点
 * （服务端侧由接入会话实体保证，见 EnrollmentService）。
 *
 * 状态显式可枚举是为了消灭"静默失败"：加载中、失败、进行中、已过期都有对应的界面，
 * 任何错误都必须落到 [Failed] 并给出重试入口，不允许停留在转圈里。
 */
sealed interface EnrollmentUi {
    /** 尚未发起（接入页未打开） */
    data object Idle : EnrollmentUi

    data object Loading : EnrollmentUi

    /** 失败：必须显示原因 + 重试，不允许静默 */
    data class Failed(val message: String) : EnrollmentUi

    /**
     * 已拿到连接信息，等待孩子端接入。
     * [expired] 由剩余时长推导，界面据此提示"换一个接入码"。
     */
    data class Active(val enrollment: Enrollment, val expired: Boolean = false) : EnrollmentUi
}

data class DeviceManageUiState(
    val toast: String? = null,
    val accessSheetVisible: Boolean = false,
    /** 接入会话：二维码与配对码的唯一来源 */
    val enrollment: EnrollmentUi = EnrollmentUi.Idle,
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

    /** 接入监听（推送 + 查询兜底）的生命周期跟随接入页，关闭即取消 */
    private var enrollmentJob: Job? = null

    // ==================== 接入页 ====================

    fun openAccessSheet() {
        _uiState.value = DeviceManageUiState(
            accessSheetVisible = true,
            enrollment = EnrollmentUi.Loading
        )
        reloadEnrollment()
        watchEnrollment()
    }

    fun closeAccessSheet() {
        enrollmentJob?.cancel()
        enrollmentJob = null
        _uiState.value = DeviceManageUiState()
    }

    /** 重新获取接入信息（失败重试 / 过期后换码都走这里） */
    fun reloadEnrollment() {
        _uiState.value = _uiState.value.copy(enrollment = EnrollmentUi.Loading)
        viewModelScope.launch {
            deviceRepository.openEnrollment()
                .onSuccess { e -> applyEnrollment(e) }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        enrollment = EnrollmentUi.Failed(e.message ?: "无法获取接入信息，请检查网络后重试")
                    )
                }
        }
    }

    /** 主动换一个接入码（原码立即作废）：怀疑码已泄露、或孩子端一直绑不上时用 */
    fun refreshEnrollmentCode() {
        viewModelScope.launch {
            deviceRepository.refreshEnrollment()
                .onSuccess { e -> applyEnrollment(e); showToast("已更换接入码，请让孩子端使用新码") }
                .onFailure { e -> showToast(e.message ?: "换码失败，请稍后重试") }
        }
    }

    private fun applyEnrollment(e: Enrollment) {
        _uiState.value = _uiState.value.copy(enrollment = EnrollmentUi.Active(e))
    }

    /**
     * 监听接入进展：推送为快路径，查询为兜底。
     *
     * 为什么还需要轮询：WS 可能因断线/后台被杀而丢事件，而"孩子到底绑上没"这件事
     * 必须有确定答案。查询间隔取 3 秒——足够快（家长几乎无感），又不至于明显耗电。
     */
    private fun watchEnrollment() {
        enrollmentJob?.cancel()
        enrollmentJob = viewModelScope.launch {
            launch {
                deviceRepository.observeEnrollmentCompleted()
                    .collect { event -> onEnrolled(event.deviceId) }
            }
            while (isActive) {
                delay(ENROLLMENT_POLL_INTERVAL_MS)
                val current = _uiState.value.enrollment as? EnrollmentUi.Active ?: continue
                val state = deviceRepository.currentEnrollment().getOrNull() ?: continue
                when (state.state) {
                    EnrollmentState.BOUND -> state.deviceId?.let { onEnrolled(it) }
                    EnrollmentState.PENDING -> {
                        // 会话被换过码（或刚从过期恢复）→ 以服务端为准刷新展示
                        if (state.sessionId != current.enrollment.sessionId) applyEnrollment(state)
                    }
                    EnrollmentState.EXPIRED, EnrollmentState.CANCELLED -> {
                        if (!current.expired) {
                            _uiState.value = _uiState.value.copy(
                                enrollment = EnrollmentUi.Active(state, expired = true)
                            )
                        }
                    }
                }
            }
        }
    }

    /** 孩子端完成接入：关页、提示、刷新台账、按需引导设置操作密码 */
    private suspend fun onEnrolled(deviceId: String) {
        if (_uiState.value.enrollment !is EnrollmentUi.Active) return
        deviceRepository.getDeviceList()
            .onSuccess { list ->
                val device = list.firstOrNull { it.id == deviceId }
                enrollmentJob?.cancel()
                enrollmentJob = null
                _uiState.value = _uiState.value.copy(
                    accessSheetVisible = false,
                    enrollment = EnrollmentUi.Idle,
                    toast = device?.let { "已接入：${it.name}" } ?: "设备已接入"
                )
                if (device != null) maybePromptPostBindPassword(device)
            }
            .onFailure { e -> showToast(e.message ?: "设备已接入，但刷新设备列表失败") }
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

    override fun onCleared() {
        enrollmentJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** 接入进展的查询兜底间隔（推送正常时它只是空转一次查询，代价极低） */
        private const val ENROLLMENT_POLL_INTERVAL_MS = 3_000L
    }
}
