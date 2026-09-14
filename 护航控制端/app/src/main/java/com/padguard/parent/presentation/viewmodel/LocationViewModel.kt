package com.padguard.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.domain.model.GeofenceConfig
import com.padguard.domain.model.LocationInfo
import com.padguard.domain.model.MapProvider
import com.padguard.domain.model.MapViewMode
import com.padguard.domain.repository.LocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * 职责：实时位置、地图服务与视图模式切换、电子围栏配置、越界判定与移动轨迹。
 * 地图渲染为占位实现（Mock 阶段），接入百度 / 高德 SDK 时只需替换绘制层，状态层无需改动。
 */
data class LocationUiState(
    val provider: MapProvider = MapProvider.BAIDU,
    val viewMode: MapViewMode = MapViewMode.MODE_2D,
    val location: LocationInfo? = null,
    val geofence: GeofenceConfig = GeofenceConfig(deviceId = ""),
    val geofenceDirty: Boolean = false,
    val track: List<LocationInfo> = emptyList(),
    /** 当前位置是否位于围栏内；围栏未启用时恒为 true（不告警）。 */
    val insideGeofence: Boolean = true,
    /** 当前位置到围栏中心的直线距离（米）。 */
    val distanceMeters: Int = 0,
    val isLoading: Boolean = false,
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
            locationRepository.getGeofence(deviceId)
                .onSuccess { fence ->
                    _uiState.value = _uiState.value.copy(geofence = fence, geofenceDirty = false)
                }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
            locationRepository.getTrackHistory(deviceId)
                .onSuccess { _uiState.value = _uiState.value.copy(track = it) }
            refreshLocation()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /** 刷新设备实时位置，并重算越界状态。 */
    fun refreshLocation() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            locationRepository.getDeviceLocation(deviceId)
                .onSuccess { location ->
                    _uiState.value = applyGeofenceStatus(_uiState.value.copy(location = location))
                }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun selectProvider(provider: MapProvider) {
        _uiState.value = _uiState.value.copy(provider = provider)
    }

    fun selectViewMode(mode: MapViewMode) {
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }

    /** 修改围栏草稿（仅改表单，不立即下发）。围栏参数变化会实时重算越界状态。 */
    fun editGeofence(transform: (GeofenceConfig) -> GeofenceConfig) {
        val next = _uiState.value.copy(
            geofence = transform(_uiState.value.geofence),
            geofenceDirty = true
        )
        _uiState.value = applyGeofenceStatus(next)
    }

    /** 以设备当前位置作为围栏圆心。 */
    fun setGeofenceCenterToDevice() {
        val location = _uiState.value.location ?: run {
            _uiState.value = _uiState.value.copy(toast = "尚未获取到设备位置")
            return
        }
        editGeofence {
            it.copy(
                centerLatitude = location.latitude,
                centerLongitude = location.longitude,
                enabled = true
            )
        }
    }

    /** 保存围栏配置并下发至被管控平板。 */
    fun saveGeofence() {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            locationRepository.updateGeofence(_uiState.value.geofence)
                .onSuccess { saved ->
                    _uiState.value = applyGeofenceStatus(
                        _uiState.value.copy(geofence = saved, geofenceDirty = false, toast = "围栏配置已下发")
                    )
                }
                .onFailure { _uiState.value = _uiState.value.copy(toast = "围栏下发失败：${it.message}") }
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    private fun applyGeofenceStatus(state: LocationUiState): LocationUiState {
        val location = state.location ?: return state.copy(insideGeofence = true, distanceMeters = 0)
        val geofence = state.geofence
        if (!geofence.enabled) return state.copy(insideGeofence = true, distanceMeters = 0)
        val distance = distanceMeters(
            geofence.centerLatitude, geofence.centerLongitude,
            location.latitude, location.longitude
        )
        return state.copy(insideGeofence = distance <= geofence.radiusMeters, distanceMeters = distance)
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
    }
}
