package com.padguard.child.monitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.padguard.child.AppForeground
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.LocationInfo
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.http.ApiResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 定位失败的具体原因，用于回执与日志（避免各处散写字符串文案、将来对不齐）。 */
enum class LocationFailure(val hint: String) {
    /** 未授予 ACCESS_FINE/COARSE_LOCATION */
    NO_PERMISSION("孩子端未授予定位权限"),
    /** App 在后台，系统禁止读取位置 */
    BACKGROUND_RESTRICTED("孩子此刻未使用平板，系统禁止后台读取位置；孩子打开平板后会自动补传"),
    /** 主动请求了新位置，但超时仍未拿到，且没有任何缓存 */
    NO_FIX("设备当前无法定位（室内无 GPS 且无网络定位时可稍后再试）"),
    /** 尚未绑定设备，无上报目标 */
    NOT_BOUND("设备尚未绑定家长端，无法上报位置"),
    /** 位置取到了，但上传失败 */
    UPLOAD_FAILED("位置已获取但上报失败，请稍后重试")
}

sealed class LocationOutcome {
    data object Success : LocationOutcome()
    /** 位置与上一点基本没动，按节流策略跳过本次上报（不是失败） */
    data object Skipped : LocationOutcome()
    data class Failure(val kind: LocationFailure) : LocationOutcome()
}

/**
 * 定位采集（家长端"立即定位"指令 + 周期性轨迹上报共用）。
 *
 * ## 为什么用平台 LocationManager 而不是 GMS FusedLocationProvider
 * 本机（联想平板）与多数国内设备**不带 Google Play Services**，
 * FusedLocationProviderClient 在这类设备上会直接抛/返回空，导致"定位永远失败"。
 * 平台 [LocationManager] 是 AOSP 自带能力，零额外依赖、任何 ROM 都有。
 *
 * ## "准确"是怎么做到的（本次补齐的关键）
 * 旧实现只取 `getLastKnownLocation`，而这可能是**几十分钟前甚至几天前**的缓存点：
 * 家长端看到的位置与实际相差甚远，却带着一个"刚刚上报"的时间戳，比失败更误导。
 * 现在分三步：
 * 1. **取最新的已知点**：不再按 GPS→网络→被动的固定顺序取第一个非空值，
 *    而是把所有 provider 的缓存都拿出来**比时间戳取最新**；
 * 2. **不够新鲜就主动要一个**：缓存点旧于 [freshnessMs]（或压根没有）时，
 *    向 GPS 与网络**同时**注册一次更新，谁先回用谁（室内通常网络定位更快），
 *    限时 [requestTimeoutMs]；
 * 3. **超时降级**：限时内拿不到新的，就退回第 1 步那个缓存点并如实带上它的时间戳，
 *    家长端据此知道"这个点可能是旧的"。
 *
 * ## ⚠️ 后台一律不读位置（本类的核心约束）
 * Android 10（API 29）起，没有 `ACCESS_BACKGROUND_LOCATION` 的 App **在后台读取位置会被系统直接拒绝**
 * （`getLastKnownLocation` 抛 SecurityException 或返回 null）。
 * 而本 App 是被管控端，**Google Play 家庭政策禁止面向儿童的 App 申请后台位置权限**，
 * 这条路合规上不可行，所以不做"申请后台位置 + location 类型前台服务"的补救。
 *
 * 因此这里的做法是：
 * - 后台收到定位指令 → **不**调用 LocationManager（此时调用必定失败，还可能直接抛异常），
 *   改为登记待办并返回 [LocationFailure.BACKGROUND_RESTRICTED]，由上层给出准确回执；
 * - 界面回到前台 0→1 的边缘 → 自动补做这次待办（[retryPendingOnForeground]）。
 * 家长实际感受到的是：孩子没在用平板时下发定位，平板会显示"等孩子使用时补传"，
 * 孩子一打开 App 就自动把位置上去了，而不是永远失败。
 */
@Singleton
class LocationCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remote: RemoteDataSource,
    private val authRepository: AuthRepository,
    private val timeProvider: TimeProvider
) {

    /** 进程级作用域：补传是"孤儿"任务，不挂在任何一个界面或服务的生命周期上。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 是否有一条因后台受限而搁置的定位请求 */
    private val pending = AtomicBoolean(false)

    /** 上一次成功上报的点，用于节流（静止不动时不必反复上报同一个点） */
    @Volatile
    private var lastUploaded: Location? = null

    init {
        AppForeground.addForegroundListener(::retryPendingOnForeground)
    }

    /**
     * 采集并上报一次位置。
     *
     * @param freshnessMs 缓存点被视为"足够新鲜"的时限；超过则主动向系统请求一次新位置。
     * @param requestTimeoutMs 主动请求新位置的等待上限，超时降级为缓存点。
     * @param minDistanceMeters 与上一点的最小距离阈值；不足则 [LocationOutcome.Skipped]。
     *                          立即定位传 0（每次都要结果），周期上报传几十米以免刷屏。
     */
    suspend fun collectAndUpload(
        freshnessMs: Long = DEFAULT_FRESHNESS_MS,
        requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        minDistanceMeters: Float = 0f
    ): LocationOutcome {
        if (!AppForeground.isForeground) {
            // 不是"做不到"，而是"此刻系统不允许"。搁置一条待办，前台回来补做。
            pending.set(true)
            Logger.i(TAG) { "location requested while backgrounded: deferred to foreground" }
            return LocationOutcome.Failure(LocationFailure.BACKGROUND_RESTRICTED)
        }

        if (!hasLocationPermission()) {
            Logger.w(TAG) { "location permission not granted" }
            return LocationOutcome.Failure(LocationFailure.NO_PERMISSION)
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            Logger.w(TAG) { "LocationManager unavailable" }
            return LocationOutcome.Failure(LocationFailure.NO_FIX)
        }

        val deviceId = authRepository.getDeviceId()
        if (deviceId.isBlank()) {
            Logger.w(TAG) { "device not bound, skip location report" }
            return LocationOutcome.Failure(LocationFailure.NOT_BOUND)
        }

        val fix = resolveLocation(manager, freshnessMs, requestTimeoutMs)
        if (fix == null) {
            Logger.w(TAG) { "no location available (neither cached nor fresh)" }
            return LocationOutcome.Failure(LocationFailure.NO_FIX)
        }

        if (minDistanceMeters > 0f) {
            val moved = lastUploaded?.let { distanceMeters(it, fix) } ?: Float.MAX_VALUE
            if (moved < minDistanceMeters) {
                Logger.d(TAG) { "location skipped: moved only ${moved.toInt()}m" }
                return LocationOutcome.Skipped
            }
        }

        pending.set(false)
        val point = LocationInfo(
            lat = fix.latitude,
            lng = fix.longitude,
            accuracy = if (fix.hasAccuracy()) fix.accuracy else 0f,
            ts = timeProvider.now(),
            provider = fix.provider.orEmpty()
        )

        return when (val result = remote.uploadLocations(deviceId, listOf(point))) {
            is ApiResult.Success -> {
                lastUploaded = fix
                Logger.i(TAG) {
                    "location reported: ${point.lat},${point.lng} acc=${point.accuracy} " +
                        "provider=${point.provider} fixAge=${(point.ts - fix.time) / 1000}s"
                }
                LocationOutcome.Success
            }
            else -> {
                Logger.w(TAG) { "location upload failed: $result" }
                LocationOutcome.Failure(LocationFailure.UPLOAD_FAILED)
            }
        }
    }

    /**
     * 界面回到前台时补传。
     *
     * 由 [AppForeground] 在 resumed 计数 0→1 的边沿调用，因此 App 内部页面互跳不会重复触发。
     */
    private fun retryPendingOnForeground() {
        if (!pending.getAndSet(false)) return
        Logger.i(TAG) { "retrying deferred location now that app is foreground" }
        scope.launch {
            runCatching { collectAndUpload() }
                .onFailure { Logger.w(TAG) { "deferred location failed: ${it.message}" } }
        }
    }

    /** 先取最新缓存，不够新鲜就主动要一个新的，都拿不到才返回 null。 */
    private suspend fun resolveLocation(
        manager: LocationManager,
        freshnessMs: Long,
        timeoutMs: Long
    ): Location? {
        val cached = latestCached(manager)
        val age = cached?.let { timeProvider.now() - it.time } ?: Long.MAX_VALUE
        if (cached != null && age <= freshnessMs) {
            Logger.d(TAG) { "using cached fix from ${cached.provider}, age=${age / 1000}s" }
            return cached
        }
        Logger.d(TAG) { "cached fix stale (age=${age / 1000}s), requesting fresh update" }
        return requestFreshFix(manager, timeoutMs) ?: cached
    }

    /** 所有 provider 的缓存点里**时间戳最新**的那个（旧实现按固定顺序取第一个非空，可能拿回一个很旧的点）。 */
    private fun latestCached(manager: LocationManager): Location? =
        PROVIDER_PRIORITY.mapNotNull { provider ->
            // 逐个 runCatching：ROM 差异很大，有的 provider 未启用时会直接抛，
            // 一次异常不该让整条取数链路断掉，后面还有降级源可用。
            runCatching { manager.getLastKnownLocation(provider) }
                .onFailure { Logger.d(TAG) { "getLastKnownLocation($provider) failed: ${it.message}" } }
                .getOrNull()
        }.maxByOrNull { it.time }

    /**
     * 向所有可用 provider 同时注册一次更新，**谁先回用谁**。
     *
     * 只注册 GPS 会室内长时间无解；只注册网络又拿不到高精度 —— 并发取先到的那个，
     * 兼顾"快"与"准"。超时返回 null，由调用方降级到缓存点。
     */
    private suspend fun requestFreshFix(manager: LocationManager, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs) {
            val fixes = Channel<Location>(Channel.CONFLATED)
            val listeners = mutableListOf<LocationListener>()
            for (provider in REQUEST_PROVIDERS) {
                if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) continue
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        fixes.trySend(location)
                    }

                    override fun onProviderDisabled(provider: String) = Unit
                    override fun onProviderEnabled(provider: String) = Unit

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                if (runCatching {
                        manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                    }.isSuccess
                ) {
                    listeners += listener
                }
            }
            if (listeners.isEmpty()) {
                Logger.w(TAG) { "no location provider available for fresh request" }
                return@withTimeoutOrNull null
            }
            try {
                fixes.receive()
            } finally {
                // 无论成功还是超时都必须注销，否则 GPS 会被一直占着耗电
                for (l in listeners) runCatching { manager.removeUpdates(l) }
            }
        }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /** 球面距离（Haversine），单位米。仅用于节流，精度要求不高。 */
    private fun distanceMeters(a: Location, b: Location): Float {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLng / 2).pow(2)
        return (2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))).toFloat()
    }

    companion object {
        private const val TAG = "LocationCollector"
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        /** 取缓存点时按此顺序探查（取的是其中**最新**的那个，顺序只影响遍历顺序） */
        private val PROVIDER_PRIORITY = arrayOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        /** 主动请求新位置时并发注册的 provider（PASSIVE 无法主动请求，故不在此列） */
        private val REQUEST_PROVIDERS = arrayOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )

        /** 缓存点被视为"新鲜"的默认时限：超过就主动要一个新的 */
        const val DEFAULT_FRESHNESS_MS = 60_000L

        /** 主动等待新位置的默认上限：超时即降级为缓存点，不让家长等太久 */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L
    }
}
