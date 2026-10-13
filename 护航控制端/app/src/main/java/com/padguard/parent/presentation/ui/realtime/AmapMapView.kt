package com.padguard.parent.presentation.ui.realtime

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.padguard.parent.domain.model.GeofenceConfig
import com.padguard.parent.domain.model.LocationInfo
import com.padguard.parent.domain.model.MapViewMode

/**
 * 高德真实底图（定位页地图区域）。
 *
 * 仅在「已配置高德 Key 且用户选择高德」时由 [DeviceLocationCard] 调用；否则由调用方回退到
 * 等距投影占位图 [GeofenceMapCanvas]，保证「无 Key / 未选高德」时页面仍可正常显示。
 *
 * 合规要点：
 *  - 服务端落库的是 WGS-84（GPS 原始坐标），高德底图使用 GCJ-02，绘制前必须把坐标做
 *    WGS-84 → GCJ-02 转换（[wgs84ToGcj02]），否则设备会整体偏移几百米。
 *  - Key 经 AndroidManifest 的 `com.amap.api.v2.apikey` 元数据注入，运行时读取；代码仓库内
 *    只保留占位空串，绝不内嵌真实 Key。
 */
@Composable
internal fun AmapMapView(
    location: LocationInfo?,
    geofences: List<GeofenceConfig>,
    track: List<LocationInfo>,
    viewMode: MapViewMode,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    var destroyed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!destroyed) mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> if (!destroyed) mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> {
                    mapView.onDestroy()
                    destroyed = true
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!destroyed) {
                mapView.onDestroy()
                destroyed = true
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply { onCreate(Bundle()) }
        }
    )

    // 高德 MapView 通过 `map` 属性同步获取 AMap 实例（无 getMapAsync）；
    // 数据变化时重绘（先 clear 再叠加），保证设备标记 / 围栏 / 轨迹随最新状态刷新。
    LaunchedEffect(mapView, location, geofences, track, viewMode) {
        drawOnAmap(mapView.map, location, geofences, track, viewMode)
    }
}

private fun drawOnAmap(
    map: AMap,
    location: LocationInfo?,
    geofences: List<GeofenceConfig>,
    track: List<LocationInfo>,
    viewMode: MapViewMode
) {
    map.clear()

    // 电子围栏范围（每条启用围栏都绘制；圆心与半径做坐标转换后绘制）
    geofences.filter { it.enabled }.forEach { zone ->
        val c = wgs84ToGcj02(zone.centerLatitude, zone.centerLongitude)
        map.addCircle(
            CircleOptions()
                .center(LatLng(c.first, c.second))
                .radius(zone.radiusMeters.toDouble())
                .strokeColor(AColor.parseColor("#8000A37A"))
                .fillColor(AColor.parseColor("#1A00A37A"))
                .strokeWidth(2f)
        )
    }

    // 移动轨迹折线
    if (track.size >= 2) {
        val pts = track.map { wgs84ToGcj02(it.latitude, it.longitude) }
            .map { LatLng(it.first, it.second) }
        map.addPolyline(
            PolylineOptions()
                .addAll(pts)
                .color(AColor.parseColor("#FF00A37A"))
                .width(6f)
        )
    }

    // 设备当前位置标记（标题带文字地址，长按可见）
    location?.let {
        val g = wgs84ToGcj02(it.latitude, it.longitude)
        map.addMarker(
            MarkerOptions()
                .position(LatLng(g.first, g.second))
                .title(it.address ?: "设备位置")
        )
    }

    // 镜头：以设备位置优先，其次第一个启用围栏圆心；按最大半径选缩放级别
    val target = when {
        location != null -> {
            val g = wgs84ToGcj02(location.latitude, location.longitude)
            LatLng(g.first, g.second)
        }
        geofences.any { it.enabled } -> {
            val z = geofences.first { it.enabled }
            val g = wgs84ToGcj02(z.centerLatitude, z.centerLongitude)
            LatLng(g.first, g.second)
        }
        else -> LatLng(0.0, 0.0)
    }
    val maxRadius = geofences.filter { it.enabled }.maxOfOrNull { it.radiusMeters } ?: 0
    val zoom = if (maxRadius > 0) zoomForRadius(maxRadius) else 16f
    // 2D / 3D：3D 通过俯仰角（tilt）呈现立体视角，2D 拉平。
    val tilt = if (viewMode == MapViewMode.MODE_3D) 45f else 0f
    val cameraPosition = CameraPosition.Builder()
        .target(target)
        .zoom(zoom)
        .tilt(tilt)
        .build()
    map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
}

private fun zoomForRadius(radiusMeters: Int): Float = when {
    radiusMeters <= 200 -> 16f
    radiusMeters <= 500 -> 15f
    radiusMeters <= 1000 -> 14f
    radiusMeters <= 2000 -> 13f
    else -> 12f
}

/**
 * 读取 AndroidManifest 中配置的高德 Key。空串代表未配置，调用方应回退占位底图。
 */
internal fun readAmapKey(context: Context): String {
    return try {
        val ai = context.packageManager.getApplicationInfo(
            context.packageName, PackageManager.GET_META_DATA
        )
        ai.metaData?.getString("com.amap.api.v2.apikey")?.takeIf { it.isNotBlank() } ?: ""
    } catch (e: Exception) {
        ""
    }
}

/**
 * WGS-84（GPS 原始坐标）→ GCJ-02（高德/国测局加密坐标）转换。
 *
 * 中国法律规定公开地图必须使用 GCJ-02 偏移坐标系，高德底图即此坐标系；
 * 设备上报的 GPS 是 WGS-84，不经此转换直接绘到高德上会产生数百米偏移。
 * 境外坐标（超出中国陆域）不偏移，原样返回。
 */
internal fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
    if (outOfChina(lat, lng)) return lat to lng
    val dLat = transformLat(lng - 105.0, lat - 35.0)
    val dLng = transformLng(lng - 105.0, lat - 35.0)
    val radLat = Math.toRadians(lat)
    val magic = Math.sin(radLat)
    val sqrtMagic = Math.sqrt(1 - 0.00669342162296594323 * magic * magic)
    val dLatFinal = (dLat * 180.0) / ((6335552.717 / (sqrtMagic * sqrtMagic)) * Math.PI)
    val dLngFinal = (dLng * 180.0) / (6378245.0 / sqrtMagic * Math.cos(radLat) * Math.PI)
    return (lat + dLatFinal) to (lng + dLngFinal)
}

private fun transformLat(x: Double, y: Double): Double {
    var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
    ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
    ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
    return ret
}

private fun transformLng(x: Double, y: Double): Double {
    var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
    ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
    ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
    return ret
}

private fun outOfChina(lat: Double, lng: Double): Boolean {
    return !(lng > 73.66 && lng < 135.05 && lat > 3.86 && lat < 53.55)
}
