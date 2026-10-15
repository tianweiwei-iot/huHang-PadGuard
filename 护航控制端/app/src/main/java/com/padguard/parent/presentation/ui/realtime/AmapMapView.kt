package com.padguard.parent.presentation.ui.realtime

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    modifier: Modifier = Modifier,
    /** 地图加载失败时的回调，回传真实诊断数据（包名 / 签名 SHA1 / Key 是否注入 / 网络可达性），供调用方回退占位底图并给出可执行的排查提示。 */
    onMapFailed: (AmapDiag) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    var destroyed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

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
        // 关键修复：本 Composable 挂载时宿主（Activity）往往早已 RESUMED，
        // LifecycleEventObserver 只回调"后续"事件，收不到 ON_RESUME，导致 onResume() 永不调用、
        // MapView 只 onCreate 不 onResume → 高德底图永远灰屏、加载不出瓦片。
        // 因此挂载时若当前状态已达 RESUMED，必须手动补齐 onResume。
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }
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
            mapView.apply {
                onCreate(Bundle())
                // 底图真正就绪的回调：用于判定"加载成功"；看门狗据此区分成功与失败。
                map.setOnMapLoadedListener { loaded = true }
            }
        }
    )

    // 看门狗：若 8 秒内底图始终未就绪，做一次真实诊断再回退占位图。
    // 关键修正：旧实现只是超时，却在文案里断言"Key 无效 / SHA1 不符 / 网络受限"——
    // 它根本无法区分这三者，属于误导性诊断，会让运维对着错误的方向排查。
    // 改为超时后回传真实可观测数据（包名、签名 SHA1、Key 是否注入、网络是否可达），
    // 由调用方据此给出有差异的、可执行的提示，并在 Logcat 打印，便于 `adb logcat` 快速核对控制台绑定。
    LaunchedEffect(mapView) {
        delay(8_000)
        if (!loaded) onMapFailed(diagnoseAmapFailure(context))
    }

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
 * 底图加载失败时的真实诊断数据。
 *
 * 看门狗超时后回传这些**可观测**字段，让上层给出有差异的提示，而不是像旧实现那样
 * 凭空断言"Key 无效 / SHA1 不符 / 网络受限"（它其实分辨不了）。
 *
 * @param keyPresent 高德 Key 是否已注入（非空）。false 时上层根本不该走真实底图，属构建/注入问题。
 * @param packageName 当前 APK 的包名——高德控制台 Key 必须绑定**完全一致**的包名。
 * @param sha1 当前 APK 签名证书的 SHA-1（冒号分隔）——高德控制台 Key 必须绑定此值；
 *   装到设备上的包是什么签名，这里就显示什么，debug 与 release 自动区分，无需人工算。
 * @param networkReachable 设备能否连上高德服务器（尽力探测）。false 强烈指向"网络受限/被拦截"；
 *   true 但底图仍失败，则几乎可以确定是 Key 类型错误或包名/SHA1 绑定不符。
 */
internal data class AmapDiag(
    val keyPresent: Boolean,
    val packageName: String,
    val sha1: String,
    val networkReachable: Boolean
)

private const val TAG_DIAG = "PadGuard/AmapDiag"
// 用于"能否连上高德服务器"的探测目标（Web API 网关，网络正常即可连通，不代表地图 Key 合法）。
private const val AMAP_REACH_HOST = "https://restapi.amap.com"

/**
 * 底图超时后的真实诊断：收集包名、签名 SHA1、Key 注入状态，并尽力探测网络可达性，
 * 全部打印到 Logcat（便于 `adb logcat -s PadGuard/AmapDiag`），再回传结构化结果。
 *
 * 注意：本函数**不猜测**失败原因，只提供事实；真正的"该改控制台还是该查网络"由上层据字段判定。
 */
internal suspend fun diagnoseAmapFailure(context: Context): AmapDiag {
    val keyPresent = readAmapKey(context).isNotBlank()
    val pkg = context.packageName
    val sha1 = getSigningSha1(context)
    val net = isAmapReachable()
    Log.w(TAG_DIAG, "map not loaded within timeout | keyPresent=$keyPresent package=$pkg sha1=$sha1 networkReachable=$net")
    return AmapDiag(keyPresent = keyPresent, packageName = pkg, sha1 = sha1, networkReachable = net)
}

/**
 * 取当前 APK 签名证书的 SHA-1（冒号分隔大写十六进制）。
 *
 * 高德 Android Key 的绑定约束就是"包名 + 签名 SHA1"，此值必须与控制台一致。
 * 用 [PackageManager] 直接读运行中的自身签名，debug / release 各自正确，家长无需手动 keytool。
 * API 28+ 用 [PackageManager.GET_SIGNING_CERTIFICATES]，低版本回退到已废弃的 [PackageManager.GET_SIGNATURES]。
 */
internal fun getSigningSha1(context: Context): String {
    return try {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val pi = pm.getPackageInfo(context.packageName, flags)
        val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION", "UNCHECKED_CAST")
            pi.signatures as? Array<Signature> ?: emptyArray()
        }
        val sig = sigs.firstOrNull() ?: return ""
        val md = MessageDigest.getInstance("SHA-1")
        md.update(sig.toByteArray())
        md.digest().joinToString(":") { "%02X".format(it) }
    } catch (e: Exception) {
        Log.e(TAG_DIAG, "failed to read signing SHA1", e)
        ""
    }
}

/**
 * 尽力探测设备能否连上高德服务器（仅作"网络受限"的判定信号，不验证 Key 合法性）。
 * 连不通（超时/异常/无响应）返回 false；拿到任意 HTTP 响应码（含 401/403）都视为"网络通"。
 */
private suspend fun isAmapReachable(): Boolean = withContext(Dispatchers.IO) {
    var conn: HttpsURLConnection? = null
    try {
        val url = URL(AMAP_REACH_HOST)
        conn = (url.openConnection() as HttpsURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
            requestMethod = "HEAD"
            instanceFollowRedirects = false
        }
        conn.connect()
        conn.responseCode // 任意码（含 4xx）都说明网络可达
        true
    } catch (e: Exception) {
        Log.w(TAG_DIAG, "amap host unreachable: ${e.message}")
        false
    } finally {
        runCatching { conn?.disconnect() }
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
