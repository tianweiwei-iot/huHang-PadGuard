package com.padguard.parent.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.parent.domain.model.GeofenceConfig
import com.padguard.parent.domain.model.LocationInfo
import com.padguard.parent.domain.model.MapProvider
import com.padguard.parent.domain.model.MapViewMode
import com.padguard.parent.domain.repository.LocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import javax.inject.Inject

/**
 * 定位 ViewModel（实时管控 > 定位）
 *
 * 职责：实时位置、地图服务与视图模式切换、电子围栏（多围栏）配置、越界判定与移动轨迹。
 * 地图渲染为占位实现（Mock 实现），接入百度 / 高德 SDK 时只需替换绘制层，状态层无需改动。
 */
data class LocationUiState(
    val provider: MapProvider = MapProvider.AMAP,
    val viewMode: MapViewMode = MapViewMode.MODE_2D,
    val location: LocationInfo? = null,
    /** 电子围栏列表（一对多，一台设备可有多条安全区域）。 */
    val geofences: List<GeofenceConfig> = emptyList(),
    val geofenceDirty: Boolean = false,
    val track: List<LocationInfo> = emptyList(),
    /** 设备当前电量（0-100，null 表示未知）。与位置并列展示，解释"为何位置有滞后"。 */
    val batteryLevel: Int? = null,
    /** 当前位置是否位于「任一启用围栏」内；无启用围栏时恒为 true（不告警）。 */
    val insideGeofence: Boolean = true,
    /** 当前位置到「最近启用围栏中心」的直线距离（米）。 */
    val distanceMeters: Int = 0,
    /** 最近启用围栏的半径（米），用于"超出 X 米"展示；无启用围栏时为 0。 */
    val nearestRadiusMeters: Int = 0,
    val isLoading: Boolean = false,
    /** 是否已下发「立即定位」指令、正在等待孩子端回传新坐标。 */
    val isLocating: Boolean = false,
    val toast: String? = null,
    /**
     * 最近一次成功读到位置/围栏的时刻（墙钟毫秒）。
     *
     * 位置页的失败此前**完全不显示**（`error` 字段无人消费）：地图继续画着上一个点，
     * 家长看不出这是刚刚的位置还是一小时前的。必须把"这份坐标是哪一刻的"钉在界面上，
     * 否则"孩子现在在学校"这个结论可能建立在过期坐标上。
     */
    val lastSuccessAt: Long? = null,
    val error: String? = null
)

/**
 * 围栏提交前校验的单一真相源（纯 JVM，无 Android 依赖）。
 *
 * 与服务端 `GeofenceService.validateZone` 同口径：UI slider 只是第一层，
 * 旧版本/深链/被篡改的入参必须在发请求前拦住，避免越界值写进台账后
 * 越界告警永久失真。抽成顶层对象让契约测试能直接验证边界，而不是靠源码扫描。
 */
object GeofenceInputValidator {
    const val RADIUS_MIN = 100
    const val RADIUS_MAX = 5000

    fun isValid(zone: GeofenceConfig): Boolean =
        zone.radiusMeters in RADIUS_MIN..RADIUS_MAX &&
            zone.centerLatitude.isFinite() && zone.centerLatitude in -90.0..90.0 &&
            zone.centerLongitude.isFinite() && zone.centerLongitude in -180.0..180.0
}

@HiltViewModel
class LocationViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val deviceId: String = savedStateHandle.get<String>("deviceId").orEmpty()

    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            locationRepository.getGeofences(deviceId)
                .onSuccess { zones -> _uiState.value = _uiState.value.copy(geofences = zones) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
            locationRepository.getTrackHistory(deviceId, 30, TRACK_HISTORY_LIMIT)
                .onSuccess { _uiState.value = _uiState.value.copy(track = it) }
            refreshLocationInternal()
            refreshBatteryInternal()
            _uiState.value = applyGeofenceStatus(_uiState.value.copy(isLoading = false))
        }
    }

    /** 刷新设备实时位置，并重算越界状态。 */
    fun refreshLocation() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            // 新一轮尝试开始即清错误：成功路径不再各自清 error（否则"围栏读取失败"会被
            // 紧随其后的位置成功抹掉），由"新一次尝试"统一复位，语义才是"最近一次刷新的结果"。
            _uiState.value = _uiState.value.copy(error = null)
            refreshLocationInternal()
            refreshBatteryInternal()
            _uiState.value = applyGeofenceStatus(_uiState.value)
        }
    }

    /**
     * 立即定位：向被管控平板下发 LOCATE 指令，由孩子端当场采集一次新坐标并上报。
     */
    fun locateNow() {
        if (deviceId.isBlank()) return
        if (_uiState.value.isLocating) return
        // 记住请求前的位置时间戳：只有回拉到"更新的坐标"才算定位成功。
        // 原来无论回拉成功与否都固定提示"位置已更新"——平板离线时家长等 16 秒，
        // 收到一句"已更新"，地图上却还是一个小时前的点，据此判断孩子在哪儿就是错的。
        val beforeTs = _uiState.value.location?.timestamp ?: 0L
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLocating = true, error = null)
            locationRepository.requestLocate(deviceId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(toast = "定位指令已下发，正在获取最新位置")
                    delay(LOCATE_FIRST_PULL_DELAY_MS)
                    refreshLocationInternal()
                    delay(LOCATE_SECOND_PULL_DELAY_MS)
                    refreshLocationInternal()
                    val after = _uiState.value.location
                    // fresh=false 表示孩子端没有新坐标、降级上传了缓存点，这种也不算"已更新"
                    val gotNew = after != null && (after.timestamp > beforeTs || after.fresh == true)
                    _uiState.value = _uiState.value.copy(
                        isLocating = false,
                        toast = if (gotNew) {
                            "位置已更新"
                        } else {
                            "平板未上报新位置（可能离线或定位权限被关闭），当前显示的是上次记录"
                        }
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLocating = false,
                        toast = "定位指令下发失败：${it.message}"
                    )
                }
        }
    }

    private suspend fun refreshLocationInternal() {
        locationRepository.getDeviceLocation(deviceId)
            .onSuccess { location ->
                _uiState.value = applyGeofenceStatus(
                    _uiState.value.copy(
                        location = location,
                        // 成功即刷新时刻：
                        // 角标要说的是"这份坐标是哪一刻的"，而位置成功本身就是最有力的时间证据。
                        lastSuccessAt = System.currentTimeMillis()
                    )
                )
            }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message ?: "位置读取失败") }
    }

    /** 刷新设备电量（与位置并列，反映设备当前是否在身边 / 是否低电）。 */
    private suspend fun refreshBatteryInternal() {
        locationRepository.getDeviceBattery(deviceId)
            .onSuccess { battery -> _uiState.value = _uiState.value.copy(batteryLevel = battery) }
            .onFailure { /* 电量非关键，失败仅留 null，不影响定位主流程 */ }
    }

    fun selectProvider(provider: MapProvider) {
        _uiState.value = _uiState.value.copy(provider = provider)
    }

    fun selectViewMode(mode: MapViewMode) {
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }

    /** 新增一条围栏草稿：以设备当前位置为圆心，便于家长快速圈定安全区域。 */
    fun addGeofence() {
        if (deviceId.isBlank()) return
        val location = _uiState.value.location ?: run {
            // 尚未获取到设备位置时禁止建围栏：否则圆心会落在 (0,0)（非洲外海），导致越界判定失真。
            _uiState.value = _uiState.value.copy(toast = "尚未获取到设备位置")
            return
        }
        val seq = _uiState.value.geofences.size + 1
        val draft = GeofenceConfig(
            id = "", deviceId = deviceId, enabled = true,
            name = "安全区域 $seq",
            centerLatitude = location.latitude,
            centerLongitude = location.longitude,
            radiusMeters = 500, alertOnExit = true, alertOnEnter = false
        )
        viewModelScope.launch {
            locationRepository.createGeofence(draft)
                .onSuccess { saved -> _uiState.value = _uiState.value.copy(geofences = _uiState.value.geofences + saved, toast = "已新增围栏") }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "新增围栏失败：${it.message}") }
        }
    }

    /** 编辑某条围栏草稿（仅改列表内存，不立即下发；保存时调用 [saveZone]）。 */
    fun updateZoneDraft(id: String, transform: (GeofenceConfig) -> GeofenceConfig) {
        _uiState.value = applyGeofenceStatus(
            _uiState.value.copy(
                geofences = _uiState.value.geofences.map { if (it.id == id) transform(it) else it },
                geofenceDirty = true
            )
        )
    }

    /** 以设备当前位置作为某条围栏的圆心。 */
    fun setZoneCenterToDevice(id: String) {
        val location = _uiState.value.location ?: run {
            _uiState.value = _uiState.value.copy(toast = "尚未获取到设备位置")
            return
        }
        updateZoneDraft(id) {
            it.copy(centerLatitude = location.latitude, centerLongitude = location.longitude, enabled = true)
        }
    }

    /** 保存某条围栏（id 为空按新建，否则按更新）。 */
    fun saveZone(zone: GeofenceConfig) {
        if (deviceId.isBlank()) return
        if (!GeofenceInputValidator.isValid(zone)) {
            _uiState.value = _uiState.value.copy(
                toast = "围栏参数不合法：半径需在 ${GeofenceInputValidator.RADIUS_MIN}~${GeofenceInputValidator.RADIUS_MAX} 米，坐标需在有效范围内"
            )
            return
        }
        viewModelScope.launch {
            val task = if (zone.id.isBlank()) locationRepository.createGeofence(zone)
            else locationRepository.updateGeofence(zone)
            task
                .onSuccess { saved ->
                    _uiState.value = _uiState.value.copy(
                        geofences = _uiState.value.geofences.replaceOrAppend(saved) { it.id == zone.id },
                        geofenceDirty = false, toast = "围栏已保存"
                    )
                }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "围栏保存失败：${it.message}") }
        }
    }

    /** 删除某条围栏。 */
    fun deleteZone(id: String) {
        if (deviceId.isBlank() || id.isBlank()) return
        viewModelScope.launch {
            locationRepository.deleteGeofence(deviceId, id)
                .onSuccess {
                    _uiState.value = applyGeofenceStatus(
                        _uiState.value.copy(geofences = _uiState.value.geofences.filter { it.id != id }, toast = "围栏已删除")
                    )
                }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "删除围栏失败：${it.message}") }
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    private fun applyGeofenceStatus(state: LocationUiState): LocationUiState {
        val location = state.location ?: return state.copy(insideGeofence = true, distanceMeters = 0, nearestRadiusMeters = 0)
        val enabled = state.geofences.filter { it.enabled }
        if (enabled.isEmpty()) return state.copy(insideGeofence = true, distanceMeters = 0, nearestRadiusMeters = 0)
        var nearest = Int.MAX_VALUE
        var nearestRadius = 0
        var insideAny = false
        for (g in enabled) {
            val d = distanceMeters(g.centerLatitude, g.centerLongitude, location.latitude, location.longitude)
            if (d <= g.radiusMeters) insideAny = true
            if (d < nearest) { nearest = d; nearestRadius = g.radiusMeters }
        }
        return state.copy(
            insideGeofence = insideAny,
            distanceMeters = if (nearest == Int.MAX_VALUE) 0 else nearest,
            nearestRadiusMeters = nearestRadius
        )
    }

    /** 球面距离（Haversine），返回米。 */
    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (EARTH_RADIUS_METERS * c).roundToInt()
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        /**
         * 历史轨迹拉取上限（30 天窗口内最多取多少点）。
         *
         * 早期取 9000（按"覆盖 30 天 @5 分钟间隔"倒推），但家长端对这些点的**唯一用途**
         * 是下方「历史轨迹时间线」的翻看列表 —— 地图并不消费它（地图走自身数据源）。
         * 9000 个点意味着：单次响应体积巨大（网络 + 解析耗时）、ViewModel 里常驻一个大列表、
         * 以及时间线一次性组合数千行触发 ANR（该列表已改为懒加载，但数据量本身仍需收敛）。
         *
         * 600 个点按 5 分钟间隔约合 2 天、按 15 分钟间隔约合 6 天，
         * 对"看看孩子这两天去过哪"这一真实诉求完全够用，且把三项成本都降了一个数量级。
         */
        const val TRACK_HISTORY_LIMIT = 600
        /** 下发定位指令后首次回拉位置的延时（毫秒）。 */
        const val LOCATE_FIRST_PULL_DELAY_MS = 3_000L
        /** 首次回拉后再次补拉一次的延时（毫秒）。
         *  孩子端"立即定位"主动要新坐标的最长等待为 12s（室内 GPS 冷启动常更久），
         *  此处延到 13s 以覆盖该窗口，确保慢速修复点已入库再回拉，避免家长看到旧位置。 */
        const val LOCATE_SECOND_PULL_DELAY_MS = 13_000L
    }
}

/** 以 [matcher] 命中则替换，否则追加到列表末尾。 */
private fun List<GeofenceConfig>.replaceOrAppend(zone: GeofenceConfig, matcher: (GeofenceConfig) -> Boolean): List<GeofenceConfig> {
    val idx = indexOfFirst(matcher)
    return if (idx >= 0) mapIndexed { i, it -> if (i == idx) zone else it }
    else this + zone
}
