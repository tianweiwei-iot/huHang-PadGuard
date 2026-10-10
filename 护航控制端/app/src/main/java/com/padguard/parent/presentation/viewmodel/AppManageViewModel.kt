package com.padguard.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.DistributableApp
import com.padguard.domain.model.InstalledApp
import com.padguard.domain.repository.DeviceRepository
import com.padguard.domain.repository.PolicyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
    /**
     * 应用名单模式：BLACKLIST（只管控关掉的）/ WHITELIST（只放行打开的）。
     * 决定下方每个应用开关的判定口径，缺省 BLACKLIST 与服务端一致。
     */
    val listMode: String = "BLACKLIST",
    /**
     * 无 Device Owner 时，打开受管控应用是否要求输入管理密码。
     * 仅在设备拿不到 DO 时由无障碍服务兜底拦截，有 DO 时不生效。
     */
    val requirePasswordOnLaunch: Boolean = false,
    /**
     * 无 DO 降级：安装确认页是否要求输入管理密码（默认 true = 必须）。
     * 仅设备没有 DO、且已设置「设备操作密码」时由无障碍服务兜底拦截。
     */
    val requirePasswordOnInstall: Boolean = true,
    /**
     * 无 DO 降级：卸载确认页是否要求输入管理密码（默认 true = 必须）。
     */
    val requirePasswordOnUninstall: Boolean = true,
    /**
     * 设备操作密码是否已设置（服务端派生下发）。
     * 未设时「打开应用也要密码」开关置灰，因为该开关依赖操作密码才生效。
     */
    val guardPasswordSet: Boolean = false,
    val toast: String? = null,
    val error: String? = null
) {
    /** 按"是否显示系统应用 + 搜索关键字"过滤后的列表 */
    val visibleApps: List<InstalledApp>
        get() = apps
            // 已卸载的应用只剩历史台账，不应再出现在可管控列表（避免"看到未安装应用"的幽灵项）
            .filter { (showSystemApps || !it.isSystem) && it.installed }
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
    private val deviceRepository: DeviceRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val deviceId: String = savedStateHandle.get<String>("deviceId").orEmpty()

    private val _uiState = MutableStateFlow(AppManageUiState())
    val uiState: StateFlow<AppManageUiState> = _uiState.asStateFlow()

    init {
        load()
        // 轻量自动刷新：孩子端装卸后会在数秒内把台账同步给服务端，
        // 这里让已打开的管控界面也跟着刷新，避免停留在"看到未安装应用"的陈旧视图。
        // 仅在非加载 / 非批量选择 / 非安装进行中时刷新，不干扰用户操作与乐观更新。
        viewModelScope.launch {
            while (isActive) {
                delay(15_000L)
                refreshInventory()
            }
        }
    }

    /**
     * 静默刷新应用台账（不触发 loading，不打断用户）。
     * 失败仅记日志，下次再试；绝不覆盖 [AppManageUiState.toast] / [AppManageUiState.error] / 选择态。
     */
    private suspend fun refreshInventory() {
        val cur = _uiState.value
        if (cur.isLoading || cur.selectionMode || cur.installing) return
        policyRepository.getAppInventory(deviceId)
            .onSuccess { list -> _uiState.value = _uiState.value.copy(apps = list) }
            .onFailure { Log.w("AppManageVM", "refreshInventory failed: ${it.message}") }
    }

    fun load() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            // 管控模式与列表分开加载：模式读失败不该让整个列表打不开
            policyRepository.getAppListPolicy(deviceId).onSuccess { p ->
                _uiState.value = _uiState.value.copy(
                    listMode = p.mode,
                    requirePasswordOnLaunch = p.requirePasswordOnLaunch,
                    requirePasswordOnInstall = p.requirePasswordOnInstall,
                    requirePasswordOnUninstall = p.requirePasswordOnUninstall
                )
            }
            // 设备操作密码是否已设：应用管理页据此置灰"打开应用也要密码"开关。
            // 从设备详情派生（服务端 guardPasswordSet），与设备详情页共用同一事实来源，
            // 无论从设备详情、首页还是 Tab 进入都能拿到真实状态。
            deviceRepository.getDeviceDetail(deviceId)
                .onSuccess { dev -> _uiState.value = _uiState.value.copy(guardPasswordSet = dev.guardPasswordSet) }
                .onFailure { /* 拿不到密码状态不阻塞应用列表，开关按"未设"处理 */ }
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

    // ==================== 管控模式（白名单 / 黑名单）====================

    /**
     * 切换名单模式。
     *
     * 切到白名单前必须提醒一次：白名单下**没打开的应用一律打不开**，
     * 家长如果只想"挡住游戏"，切错了会变成"除了勾选的都打不开"。
     * 这里在 UI 层做二次确认，ViewModel 只负责下发。
     */
    fun setListMode(mode: String) {
        if (mode == _uiState.value.listMode) return
        val normalized = if (mode == "WHITELIST") "WHITELIST" else "BLACKLIST"
        viewModelScope.launch {
            policyRepository.setAppListPolicy(
                deviceId,
                normalized,
                _uiState.value.requirePasswordOnLaunch,
                _uiState.value.requirePasswordOnInstall,
                _uiState.value.requirePasswordOnUninstall
            )
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        listMode = normalized,
                        toast = if (normalized == "WHITELIST") {
                            "已切换为白名单：只有打开的应用可以使用"
                        } else {
                            "已切换为黑名单：只有关闭的应用会被管控"
                        }
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(toast = "切换失败：${e.message}")
                }
        }
    }

    /**
     * 「打开应用也要密码」开关。
     *
     * 只在设备**没有** Device Owner 时起作用：有 DO 时管控由系统隐藏/挂起完成，
     * 根本走不到"打开"这一步。UI 上要如实标注这一点，
     * 否则家长会以为"开了就一定生效"，在 DO 设备上验证后得出"功能是坏的"结论。
     */
    fun setRequirePasswordOnLaunch(enabled: Boolean) {
        viewModelScope.launch {
            policyRepository.setAppListPolicy(
                deviceId,
                _uiState.value.listMode,
                enabled,
                _uiState.value.requirePasswordOnInstall,
                _uiState.value.requirePasswordOnUninstall
            )
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        requirePasswordOnLaunch = enabled,
                        toast = if (enabled) "打开任意应用都将要求输入管理密码" else "已关闭启动密码校验"
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(toast = "设置失败：${e.message}")
                }
        }
    }

    /**
     * 「安装应用需要密码」开关。
     *
     * 无 DO 降级：孩子每次安装新应用都要在确认页输入管理密码。
     * 默认开启（核心安全能力）；家长若想允许免密安装可关闭。
     * 同样依赖「设备操作密码」已设置，否则无障碍软拦截不生效。
     */
    fun setRequirePasswordOnInstall(enabled: Boolean) {
        viewModelScope.launch {
            policyRepository.setAppListPolicy(
                deviceId,
                _uiState.value.listMode,
                _uiState.value.requirePasswordOnLaunch,
                enabled,
                _uiState.value.requirePasswordOnUninstall
            )
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        requirePasswordOnInstall = enabled,
                        toast = if (enabled) "安装应用将要求输入管理密码" else "已关闭安装密码校验"
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(toast = "设置失败：${e.message}")
                }
        }
    }

    /**
     * 「卸载应用需要密码」开关。
     *
     * 无 DO 降级：孩子每次卸载应用都要在确认页输入管理密码。
     * 默认开启；家长若想允许免密卸载可关闭。
     */
    fun setRequirePasswordOnUninstall(enabled: Boolean) {
        viewModelScope.launch {
            policyRepository.setAppListPolicy(
                deviceId,
                _uiState.value.listMode,
                _uiState.value.requirePasswordOnLaunch,
                _uiState.value.requirePasswordOnInstall,
                enabled
            )
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        requirePasswordOnUninstall = enabled,
                        toast = if (enabled) "卸载应用将要求输入管理密码" else "已关闭卸载密码校验"
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(toast = "设置失败：${e.message}")
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
        // 已卸载的应用只剩历史台账，不存在"隐藏 / 显示"的意义；
        // 直接提示而非打服务端接口，否则会收到"设备上没有这个应用"的报错，表现为"无法授权"。
        if (!app.installed) {
            _uiState.value = _uiState.value.copy(
                toast = "「${app.appName}」已不在设备上，无需设置使用权限"
            )
            return
        }
        viewModelScope.launch {
            policyRepository.setAppHidden(deviceId, app.packageName, hidden = !allowed)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        apps = _uiState.value.apps.map {
                            if (it.packageName == app.packageName) it.copy(hidden = !allowed) else it
                        },
                        toast = if (allowed) "已开启权限：${app.appName}" else "已关闭权限：${app.appName}（打开需管理密码）"
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
     * 一键授权全部 / 一键取消全部（使用权限：显示 / 隐藏）。
     *
     * 「系统」勾选框（[AppManageUiState.showSystemApps]）决定批量操作的作用范围：
     * - 勾选系统（true）：作用于全部已安装应用，含系统应用。以 `all=true` 交给服务端一次性下发，避免逐条循环打接口。
     * - 不勾选系统（false）：仅作用于非系统应用，系统应用保留原有授权状态。
     *   此时改用显式包名列表 + `all=false`，由服务端只下发给非系统应用。
     *
     * 本地先乐观更新 `hidden`，与服务端返回数量无关，保证界面即时反馈。
     * 乐观更新只触及「已安装」应用，与服务端 `all=true` 仅遍历已安装台账的行为一致
     * （已卸载的应用只有历史记录，无需改动使用权限）。
     */
    fun setAllAllowed(allowed: Boolean) {
        val includeSystem = _uiState.value.showSystemApps
        // 显式构造「作用范围内」的完整包名列表，并以 all=false 下发：
        // 不依赖服务端 all=true 的隐式语义，确保「一次点击」即覆盖范围内全部应用，
        // 避免「只关了系统应用、要点第二次才全关」的异常。
        // 范围由「系统」勾选框决定：勾选=全部（含系统），不勾选=仅非系统（系统保留）。
        val targetPackages = _uiState.value.apps
            .filter { it.installed && (includeSystem || !it.isSystem) }
            .map { it.packageName }
        val scopeLabel = if (includeSystem) "全部" else "非系统"
        viewModelScope.launch {
            policyRepository.setAppsHiddenBatch(
                deviceId = deviceId,
                packages = targetPackages,
                hidden = !allowed,
                all = false
            ).onSuccess { sent ->
                _uiState.value = _uiState.value.copy(
                    apps = _uiState.value.apps.map { app ->
                        val inScope = app.installed && (includeSystem || !app.isSystem)
                        if (inScope) app.copy(hidden = !allowed) else app
                    },
                    toast = "已${if (allowed) "授权" else "取消授权"}$scopeLabel $sent 个应用"
                )
            }.onFailure { e ->
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
