package com.padguard.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.DistributableApp
import com.padguard.domain.model.InstalledApp
import com.padguard.domain.repository.PolicyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用管理（应用监控 + 远程安装维护）UI 状态
 */
data class AppManageUiState(
    val apps: List<InstalledApp> = emptyList(),
    val isLoading: Boolean = false,
    val showSystemApps: Boolean = false,
    val query: String = "",
    /** 当前处于"批量选择"模式；退出时应清空 [selected] */
    val selectionMode: Boolean = false,
    val selected: Set<String> = emptySet(),
    val distributable: List<DistributableApp> = emptyList(),
    /** 正在上传并安装本地 APK：界面需要这段时间给出明确反馈，否则家长会以为没点上 */
    val installing: Boolean = false,
    val toast: String? = null,
    val error: String? = null
) {
    /** 按"是否显示系统应用 + 搜索关键字"过滤后的列表 */
    val visibleApps: List<InstalledApp>
        get() = apps
            .filter { showSystemApps || !it.isSystem }
            .let { list ->
                val q = query.trim()
                if (q.isEmpty()) list else list.filter {
                    it.appName.contains(q, ignoreCase = true) ||
                        it.packageName.contains(q, ignoreCase = true)
                }
            }
}

@HiltViewModel
class AppManageViewModel @Inject constructor(
    private val policyRepository: PolicyRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val deviceId: String = savedStateHandle.get<String>("deviceId").orEmpty()

    private val _uiState = MutableStateFlow(AppManageUiState())
    val uiState: StateFlow<AppManageUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            policyRepository.getAppInventory(deviceId)
                .onSuccess { list ->
                    _uiState.value = _uiState.value.copy(apps = list, isLoading = false)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "读取应用列表失败：${e.message}"
                    )
                }
        }
    }

    // ==================== 安装 / 卸载 ====================

    /**
     * 远程安装。
     *
     * 只更新提示不刷新列表：安装由被管控端异步执行（下载 + PackageInstaller），
     * 通常需要十几秒到几分钟。立刻刷新也拿不到新应用，
     * 反而会让家长以为"指令失败了"，所以这里如实提示"已下发，稍后自动出现"。
     */
    fun install(app: DistributableApp) {
        viewModelScope.launch {
            policyRepository.installRemoteApp(deviceId, app)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(toast = "已下发安装指令：${app.appName}，稍后自动出现在列表中")
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(toast = "安装指令下发失败：${e.message}")
                }
        }
    }

    /**
     * 从家长手机本地选一个 APK，上传后远程安装到孩子端。
     *
     * @param packageName 从 APK 里解出的包名。服务端要靠它预置台账、
     *                    孩子端要靠它确认安装目标，不能拿文件名凑。
     *
     * 整个过程要十几秒到几分钟（上传 + 孩子端下载 + 静默安装），
     * 所以这里如实提示"已下发"，而不是假装成功或一直转圈。
     */
    fun installLocalApk(bytes: ByteArray, fileName: String, packageName: String, appName: String?) {
        if (packageName.isBlank()) {
            _uiState.value = _uiState.value.copy(toast = "无法识别这个安装包的包名，请确认是有效的 APK")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(installing = true)
            policyRepository.installLocalApk(
                deviceId = deviceId,
                fileName = fileName,
                contentType = "application/vnd.android.package-archive",
                bytes = bytes,
                packageName = packageName,
                appName = appName
            ).onSuccess {
                _uiState.value = _uiState.value.copy(
                    installing = false,
                    toast = "已下发安装指令：${appName ?: packageName}，稍后自动出现在列表中"
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    installing = false,
                    toast = "安装失败：${e.message}"
                )
            }
        }
    }

    /**
     * 使用权限开关。
     *
     * 关闭 = 在孩子端隐藏（桌面不再显示）；开启 = 恢复显示。
     * 刻意不做"卸载"：开关是要能反复拨的简单操作，
     * 而卸载不可逆，一旦孩子数据丢了就没法挽回。
     */
    fun setAllowed(app: InstalledApp, allowed: Boolean) {
        viewModelScope.launch {
            policyRepository.setAppHidden(deviceId, app.packageName, hidden = !allowed)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        apps = _uiState.value.apps.map {
                            if (it.packageName == app.packageName) it.copy(hidden = !allowed) else it
                        },
                        toast = if (allowed) "已开启权限：${app.appName}" else "已关闭权限：${app.appName}（孩子端不再显示）"
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(toast = "操作失败：${e.message}")
                }
        }
    }

    fun uninstall(app: InstalledApp) {
        viewModelScope.launch {
            policyRepository.uninstallApp(deviceId, app.packageName)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        apps = _uiState.value.apps.map {
                            if (it.packageName == app.packageName) it.copy(installed = false) else it
                        },
                        toast = "已下发卸载指令：${app.appName}"
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(toast = "卸载指令下发失败：${e.message}")
                }
        }
    }

    /**
     * 一键授权全部 / 一键取消全部。
     *
     * 授权（allowed=true）= 全部显示、可用；取消（allowed=false）= 全部隐藏、不可用。
     * 用 `all=true` 交给服务端一次性下发，避免逐条循环打接口。
     * 本地先乐观更新 `hidden`，与服务端返回数量无关，保证界面即时反馈。
     */
    fun setAllAllowed(allowed: Boolean) {
        viewModelScope.launch {
            policyRepository.setAppsHiddenBatch(deviceId, emptyList(), hidden = !allowed, all = true)
                .onSuccess { sent ->
                    _uiState.value = _uiState.value.copy(
                        apps = _uiState.value.apps.map { it.copy(hidden = !allowed) },
                        toast = "已${if (allowed) "授权全部" else "取消全部授权"} ${sent} 个应用"
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(toast = "批量操作失败：${e.message}")
                }
        }
    }

    /**
     * 设置单个应用的每日使用时长上限（分钟）。
     * 0 / 负数视为不限制（清空时长）。步长是界面 ± 按钮的事，这里只负责落库。
     */
    fun setAppLimit(app: InstalledApp, minutes: Int) {
        val clamped = minutes.coerceAtLeast(0)
        viewModelScope.launch {
            policyRepository.setAppLimit(deviceId, app.packageName, clamped)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        apps = _uiState.value.apps.map {
                            if (it.packageName == app.packageName) {
                                it.copy(dailyLimitMinutes = if (clamped == 0) null else clamped)
                            } else it
                        },
                        toast = if (clamped == 0) {
                            "已取消「${app.appName}」的时长限制"
                        } else {
                            "已为「${app.appName}」设置 $clamped 分钟 / 天"
                        }
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(toast = "设置失败：${e.message}")
                }
        }
    }

    // ==================== 挂起 / 恢复 ====================

    fun setSuspended(app: InstalledApp, suspended: Boolean) {
        viewModelScope.launch {
            policyRepository.setAppSuspended(deviceId, app.packageName, suspended)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        apps = _uiState.value.apps.map {
                            if (it.packageName == app.packageName) it.copy(suspended = suspended) else it
                        },
                        toast = if (suspended) "已挂起：${app.appName}" else "已恢复：${app.appName}"
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(toast = "操作失败：${e.message}")
                }
        }
    }

    fun suspendSelected(suspended: Boolean) {
        val packages = _uiState.value.selected.toList()
        if (packages.isEmpty()) return
        viewModelScope.launch {
            policyRepository.setAppsSuspended(deviceId, packages, suspended)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        apps = _uiState.value.apps.map {
                            if (it.packageName in _uiState.value.selected) it.copy(suspended = suspended) else it
                        },
                        selectionMode = false,
                        selected = emptySet(),
                        toast = "已${if (suspended) "挂起" else "恢复"} ${packages.size} 个应用"
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(toast = "批量操作失败：${e.message}")
                }
        }
    }

    // ==================== 界面状态 ====================

    fun toggleSelectionMode() {
        _uiState.value = _uiState.value.copy(
            selectionMode = !_uiState.value.selectionMode,
            selected = emptySet()
        )
    }

    fun toggleSelected(packageName: String) {
        val current = _uiState.value.selected
        _uiState.value = _uiState.value.copy(
            selected = if (packageName in current) current - packageName else current + packageName
        )
    }

    fun setShowSystemApps(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSystemApps = show)
    }

    fun setQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }
}
