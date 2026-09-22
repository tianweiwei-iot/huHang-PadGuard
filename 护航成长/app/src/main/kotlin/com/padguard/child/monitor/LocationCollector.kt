package com.padguard.child.monitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.LocationInfo
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.http.ApiResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单次定位采集（家长端"立即定位"指令用）。
 *
 * ## 为什么用平台 LocationManager 而不是 GMS FusedLocationProvider
 * 本机（联想平板）与多数国内设备**不带 Google Play Services**，
 * FusedLocationProviderClient 在这类设备上会直接抛/返回空，导致"定位永远失败"。
 * 平台 [LocationManager] 是 AOSP 自带能力，零额外依赖、任何 ROM 都有，
 * 在这里是唯一稳妥的选择。
 *
 * ## 只取"最近一次已知位置"而不发起新的定位请求
 * 远程定位的诉求是"现在大概在哪"，而 [LocationManager] 冷启动一次 GPS 可能要几十秒甚至失败（室内）。
 * 取 lastKnown 是毫秒级的，且系统本来就会因其他应用持续刷新该值；
 * 代价是位置可能略旧 —— 我们把 [LocationInfo.ts] 如实带上，家长端能看到时间戳，
 * 这比"卡 30 秒然后失败"体验好得多。
 */
@Singleton
class LocationCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remote: RemoteDataSource,
    private val authRepository: AuthRepository,
    private val timeProvider: TimeProvider
) {

    /** 采集并上报一次位置。@return 是否成功上报 */
    suspend fun collectAndUpload(): Boolean {
        if (!hasLocationPermission()) {
            Logger.w(TAG) { "location permission not granted" }
            return false
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            Logger.w(TAG) { "LocationManager unavailable" }
            return false
        }

        val deviceId = authRepository.getDeviceId()
        if (deviceId.isBlank()) {
            Logger.w(TAG) { "device not bound, skip location report" }
            return false
        }

        val fix = lastKnown(manager)
        if (fix == null) {
            Logger.w(TAG) { "no last known location available" }
            return false
        }

        val point = LocationInfo(
            lat = fix.latitude,
            lng = fix.longitude,
            accuracy = if (fix.hasAccuracy()) fix.accuracy else 0f,
            ts = timeProvider.now(),
            provider = fix.provider.orEmpty()
        )

        return when (val result = remote.uploadLocations(deviceId, listOf(point))) {
            is ApiResult.Success -> {
                Logger.i(TAG) { "location reported: ${point.lat},${point.lng} acc=${point.accuracy}" }
                true
            }
            else -> {
                Logger.w(TAG) { "location upload failed: $result" }
                false
            }
        }
    }

    /** 按 GPS -> 网络 -> 被动 的顺序取最近一次已知位置 */
    private fun lastKnown(manager: LocationManager): android.location.Location? {
        for (provider in PROVIDER_PRIORITY) {
            val fix = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            if (fix != null) return fix
        }
        return null
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "LocationCollector"
        private val PROVIDER_PRIORITY = arrayOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
    }
}
