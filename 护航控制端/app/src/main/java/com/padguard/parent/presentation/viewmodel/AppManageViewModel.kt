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
