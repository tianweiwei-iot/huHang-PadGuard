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
    val error: String? = null
)

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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLocating = true, error = null)
            locationRepository.requestLocate(deviceId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(toast = "定位指令已下发，正在获取最新位置")
                    delay(LOCATE_FIRST_PULL_DELAY_MS)
                    refreshLocationInternal()
                    delay(LOCATE_SECOND_PULL_DELAY_MS)
                    refreshLocationInternal()
                    _uiState.value = _uiState.value.copy(isLocating = false, toast = "位置已更新")
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
                _uiState.value = applyGeofenceStatus(_uiState.value.copy(location = location))
            }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
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
        /** 历史轨迹拉取上限：覆盖 30 天 @5 分钟间隔（约 8640 点），避免服务端截断。 */
        const val TRACK_HISTORY_LIMIT = 9000
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
