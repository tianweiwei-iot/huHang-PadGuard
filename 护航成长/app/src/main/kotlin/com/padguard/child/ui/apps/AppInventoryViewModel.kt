package com.padguard.child.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.child.monitor.AppInventoryCollector
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.AppListMode
import com.padguard.core.data.model.policy.PolicyPackage
import com.padguard.core.data.repository.PolicyRepository
import com.padguard.core.transport.http.AppInventoryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "AppInventoryViewModel"

/**
 * 「我的 → 应用管理」视图模型。
 *
 * 只读台账：把本机**可启动的**已安装应用与家长下发的策略并到一起，
 * 回答孩子最关心的两个问题：这个应用能不能打开、今天能用多久。
 *
 * ## 数据来源为什么是两条独立的流
 * 已安装应用属于设备现状（PackageManager），策略属于服务端下发（Room）。
 * 两者变化频率差几个数量级：策略随家长操作分钟级变化，应用列表只有装卸载才变。
 * 合到一条流里会让每次策略刷新都重扫一遍 PackageManager（实测 250 个包耗时数百毫秒），
 * 所以应用列表只采集一次后以 [StateFlow] 常驻，策略走 [PolicyRepository.observePolicy] 独立驱动。
 */
@HiltViewModel
class AppInventoryViewModel @Inject constructor(
    private val inventoryCollector: AppInventoryCollector,
    policyRepository: PolicyRepository
) : ViewModel() {

    /** 本机可启动应用台账（含家长后续隐藏补回来的包），生命周期内采集一次。 */
    private val apps: StateFlow<List<AppInventoryItem>> = flow {
        emit(inventoryCollector.collect())
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val state: StateFlow<AppInventoryUiState> = combine(
        apps,
        policyRepository.observePolicy()
    ) { items, policy ->
        AppInventoryUiState(
            items = items.map { it.toRow(policy) },
            loaded = true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppInventoryUiState())

    init {
        // 采集失败必须暴露：静默吞掉会让页面显示"一个应用都没有"，
        // 孩子据此判断"家长没管控我"，而真实情况是包可见性受限导致查不到。
        viewModelScope.launch {
            apps.collect {
                if (it.isEmpty()) {
                    Logger.w(TAG) { "app inventory empty: package visibility restricted?" }
                    _error.value = "未能读取本机应用列表。若设备限制了应用可见性，请到「隐私与安全」检查使用情况访问权限。"
                } else {
                    _error.value = null
                }
            }
        }
    }

    private fun AppInventoryItem.toRow(policy: PolicyPackage): AppInventoryRow {
        val restricted = policy.app.isRestricted(packageName)
        val limit = policy.appLimit.rules.firstOrNull { it.packageName == packageName }
        return AppInventoryRow(
            packageName = packageName,
            appName = appName?.takeIf { it.isNotBlank() } ?: packageName,
            isSystem = isSystem == true,
            restricted = restricted,
            dailyMinutes = limit?.dailyMinutes ?: 0,
            singleMinutes = limit?.singleMinutes ?: 0
        )
    }

    /**
     * 包名是否处于受限名单。
     *
     * 名单语义必须与引擎执行口径一致（黑名单=命中即禁，白名单=未命中即禁），
     * 否则页面显示"不受限"而打开即被拦截，孩子会认为是 App 坏了。
     */
    private fun com.padguard.core.data.model.policy.AppPolicy.isRestricted(packageName: String): Boolean =
        when (mode) {
            AppListMode.BLACKLIST -> blacklist.contains(packageName)
            AppListMode.WHITELIST -> !whitelist.contains(packageName)
        }
}

/** 应用管理页 UI 状态。 */
data class AppInventoryUiState(
    val items: List<AppInventoryRow> = emptyList(),
    /** 首次采集是否完成；未完成前展示加载态而不是空列表，避免误报"没有受管控的应用" */
    val loaded: Boolean = false
)

data class AppInventoryRow(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean,
    val restricted: Boolean,
    /** 每日可用分钟数；0 表示未单独设限 */
    val dailyMinutes: Int,
    /** 单次连续可用分钟数；0 表示未单独设限 */
    val singleMinutes: Int
)
