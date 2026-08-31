package com.padguard.child.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import com.padguard.core.common.Logger
import com.padguard.core.data.model.BatteryInfo
import com.padguard.core.data.model.MemoryInfo
import com.padguard.core.data.model.NetworkInfo
import com.padguard.core.data.model.ScreenInfo
import com.padguard.core.data.model.StorageInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备状态快照采集，供心跳上报使用。
 *
 * ## 设计原则：任何一项采集失败都不能拖垮心跳
 * 心跳是"设备还活着"的唯一证据。如果因为某个字段（比如 SSID）取不到就抛异常，
 * 服务端会判定设备离线并告警，而设备其实好得很 —— 这是典型的自伤。
 * 所以每一项都用 `runCatching` 包住并给出中性的兜底值。
 *
 * ## 已知的取值受限项（不是 Bug，别去"修"）
 * | 字段 | 受限条件 | 兜底值 |
 * |---|---|---|
 * | `network.ssid` | Android 8.1+ 需定位权限且定位开关打开 | `""` |
 * | `network.ip` | 需遍历网络接口，部分 ROM 限制 | `""` |
 * | `battery.temperature` | 少数设备不上报 | `0f` |
 * | `screen.brightness` | 自动亮度下读到的是"上次手动值" | `-1` |
 */
@Singleton
class DeviceSnapshotCollector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 屏幕是否点亮。
     *
     * 用 `isInteractive` 而不是已废弃的 `isScreenOn`：
     * 后者在 Android 7+ 分屏 / 息屏显示场景下返回值不可靠，
     * 会导致"息屏不计时"这条防作弊规则被绕过。
     */
    fun isScreenOn(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive
    }.getOrDefault(true)

    fun network(): NetworkInfo = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val type = when {
            caps == null -> TYPE_NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TYPE_WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TYPE_CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TYPE_ETHERNET
            else -> TYPE_OTHER
        }
        NetworkInfo(
            type = type,
            ssid = if (type == TYPE_WIFI) currentSsid() else "",
            // getSignalStrength() 是 API 29 才公开的，低版本直接调用会 NoSuchMethodError
            signalLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                caps?.signalStrength ?: 0
            } else {
                0
            },
            ip = ""
        )
    }.onFailure { Logger.w(TAG, it) { "collect network failed" } }
        .getOrDefault(NetworkInfo(type = TYPE_UNKNOWN))

    /**
     * SSID 在 Android 8.1 之后需要定位权限且系统定位开关打开，否则返回 `<unknown ssid>`。
     * 这里把这个占位串归一化成空串，免得管控端把它当成真实网络名展示出去。
     */
    private fun currentSsid(): String = runCatching {
        @Suppress("DEPRECATION")
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val raw = wifi.connectionInfo?.ssid.orEmpty().trim('"')
        if (raw.isBlank() || raw.equals(UNKNOWN_SSID, ignoreCase = true)) "" else raw
    }.getOrDefault("")

    fun battery(): BatteryInfo = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenth = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        BatteryInfo(
            level = if (level < 0 || scale <= 0) -1 else level * 100 / scale,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            temperature = tempTenth / 10f
        )
    }.onFailure { Logger.w(TAG, it) { "collect battery failed" } }
        .getOrDefault(BatteryInfo(level = -1, charging = false))

    /**
     * 存储用 data 分区而不是外部存储。
     *
     * 管控关心的是"还能不能装应用、还能不能缓存日志"，那是 data 分区的事。
     * 报外部存储容量会得到一个漂亮但无用的数字。
     */
    fun storage(): StorageInfo = runCatching {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        StorageInfo(
            totalMb = stat.blockCountLong * stat.blockSizeLong / MB,
            availableMb = stat.availableBlocksLong * stat.blockSizeLong / MB
        )
    }.onFailure { Logger.w(TAG, it) { "collect storage failed" } }
        .getOrDefault(StorageInfo(totalMb = 0, availableMb = 0))

    fun memory(): MemoryInfo = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        MemoryInfo(totalMb = info.totalMem / MB, availableMb = info.availMem / MB)
    }.onFailure { Logger.w(TAG, it) { "collect memory failed" } }
        .getOrDefault(MemoryInfo(totalMb = 0, availableMb = 0))

    fun screen(): ScreenInfo = ScreenInfo(
        on = isScreenOn(),
        brightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
    )

    /** 系统版本与机型，绑定时上报一次即可，不进心跳 */
    fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun osVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    companion object {
        private const val TAG = "SnapshotCollector"
        private const val MB = 1024L * 1024L
        private const val UNKNOWN_SSID = "<unknown ssid>"

        const val TYPE_WIFI = "WIFI"
        const val TYPE_CELLULAR = "CELLULAR"
        const val TYPE_ETHERNET = "ETHERNET"
        const val TYPE_OTHER = "OTHER"
        const val TYPE_NONE = "NONE"
        const val TYPE_UNKNOWN = "UNKNOWN"
    }
}
