package com.padguard.child.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.padguard.child.AppForeground
import com.padguard.core.common.Logger
import com.padguard.core.data.model.LocationInfo
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.transport.JsonLinesFile
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.http.ApiResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
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
 * 早期实现只取 `getLastKnownLocation`，而这可能是**几十分钟前甚至几天前**的缓存点：
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
 * ## 缓存新鲜度一律用单调钟判定（MON-09）
 * 判定"缓存点够不够新"**不能**用 `TimeProvider.now() - Location.time`：前者是受信单调护栏时间
 * （墙钟跳变时忽略墙钟、对时成功还会被服务端时间重新锚定），后者是系统墙钟 UTC 毫秒，
 * 两者基准不同源，相减结果会随校时/篡改整体平移——墙钟一回拨 age 就变负，
 * 几小时前的旧点会被当成刚采集的 `fresh=true` 实时位置上报，家长看到的是假实时。
 * 这里改用 `Location.elapsedRealtimeNanos` 与 `SystemClock.elapsedRealtimeNanos` 求差，
 * 单调、同源、改系统时间不可伪造；排序与新鲜度判定共用同一基准，详见 [fixAgeMs]。
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
    private val authRepository: AuthRepository
) {

    /** 进程级作用域：补传是"孤儿"任务，不挂在任何一个界面或服务的生命周期上。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 上报临界区锁。「立即定位」指令、周期上报、回前台补传可能并发进入 [collectAndUpload]，
     * 若同时读到同一份 [lastUploaded] 节流基准，会判定「均已移动超阈值」而重复上传同一位置点。
     * 串行化"解析 + 上传 + 更新 lastUploaded"，保证后到的调用看到前者已更新的节流基准。
     */
    private val collectMutex = Mutex()

    /** 是否有一条因后台受限而搁置的定位请求 */
    private val pending = AtomicBoolean(false)

    /** 上一次成功上报的点，用于节流（静止不动时不必反复上报同一个点） */
    @Volatile
    private var lastUploaded: Location? = null

    /** 离线轨迹暂存：上报失败的点先落盘，联网后补传（见 [flushOutbox]） */
    private val outbox = LocationOutbox(File(context.filesDir, "location-outbox.jsonl"))

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
        minDistanceMeters: Float = 0f,
        /**
         * 允许在后台采集。仅当调用方正处于"定位类型前台服务"上下文时（如收到家长端
         * "立即定位"指令、且守护服务已声明 foregroundServiceType=location）才传 true。
         * 此时 Android 把位置访问视为"使用中"，无需申请 ACCESS_BACKGROUND_LOCATION（合规）。
         * 周期上报 [LocationAutoReporter] 始终传 false（仅前台采集，省电且不刷屏）。
         */
        allowBackground: Boolean = false
    ): LocationOutcome {
        if (!AppForeground.isForeground && !allowBackground) {
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

        return collectMutex.withLock {
            pending.set(false)
            val (fix, isFresh) = resolveLocation(manager, freshnessMs, requestTimeoutMs) ?: run {
                Logger.w(TAG) { "no location available (neither cached nor fresh)" }
                return@withLock LocationOutcome.Failure(LocationFailure.NO_FIX)
            }

            if (minDistanceMeters > 0f) {
                val moved = lastUploaded?.let { distanceMeters(it, fix) } ?: Float.MAX_VALUE
                if (moved < minDistanceMeters) {
                    Logger.d(TAG) { "location skipped: moved only ${moved.toInt()}m" }
                    return@withLock LocationOutcome.Skipped
                }
            }

            // 关键修复：ts 必须写"坐标真实采集时间"fix.time，而不是上报时刻。
            // 旧实现写上报时刻（当时的 timeProvider.now()），会把缓存旧点的时间抹成"刚刚"，
            // 服务端据此误判为实时，家长端于是看到历史位置。isFresh=false 时家长端明确提示"缓存位置"。
            // 注意：ts 是墙钟基准的上报数据，与上面【新鲜度判定】用的单调钟基准是两件事，不要混用。
            val point = LocationInfo(
                lat = fix.latitude,
                lng = fix.longitude,
                accuracy = if (fix.hasAccuracy()) fix.accuracy else 0f,
                ts = fix.time,
                provider = fix.provider.orEmpty(),
                fresh = isFresh
            )

            when (val result = remote.uploadLocations(deviceId, listOf(point))) {
                is ApiResult.Success -> {
                    lastUploaded = fix
                    Logger.i(TAG) {
                        "location reported: ${point.lat},${point.lng} acc=${point.accuracy} " +
                            "provider=${point.provider} fixAge=${(point.ts - fix.time) / 1000}s"
                    }
                    // 本次成功说明网络已恢复：顺手把离线期间攒下的点补传上去。
                    flushOutbox(deviceId)
                    LocationOutcome.Success
                }
                else -> {
                    Logger.w(TAG) { "location upload failed: $result" }
                    // 失败不再丢弃：断网期间的轨迹点先落盘，联网（或下次成功上报）后补传。
                    // 早期实现直接丢弃，家长端在离线时段看到的是"轨迹断档"，且无法事后追回。
                    withContext(Dispatchers.IO) { outbox.append(point) }
                    LocationOutcome.Failure(LocationFailure.UPLOAD_FAILED)
                }
            }
        }
    }

    /**
     * 把离线期间落盘的轨迹点补传出去。
     *
     * 语义上归属"尽力而为"：失败什么都不做，等下一次成功上报时再试；
     * 成功则按时间戳精确删除已上传的行，未成功的继续留着。
     * 单批上限 [LocationOutbox.MAX_POINTS]，一次请求不会因积压过大而超时。
     */
    private suspend fun flushOutbox(deviceId: String) {
        val queued = withContext(Dispatchers.IO) { outbox.snapshot() }
        if (queued.isEmpty()) return
        when (val r = remote.uploadLocations(deviceId, queued)) {
            is ApiResult.Success -> {
                // 按**内容标识**（ts+坐标）精确删除已上传的点，而不是只按 ts（见 [outboxKey]）。
                withContext(Dispatchers.IO) { outbox.remove(queued) }
                Logger.i(TAG) { "location outbox flushed: ${queued.size} points" }
            }
            else -> Logger.w(TAG) { "location outbox flush failed (${queued.size} points kept): $r" }
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

    /**
     * 先取最新缓存，不够新鲜就主动要一个新的；都拿不到才返回 null。
     * 返回 [Pair]：坐标 + 是否"实时采集"（true=拿到新坐标；false=降级使用了缓存旧点）。
     */
    private suspend fun resolveLocation(
        manager: LocationManager,
        freshnessMs: Long,
        timeoutMs: Long
    ): Pair<Location, Boolean>? {
        // latestCached 一并返回按单调钟算出的年龄，避免两处各自读一次时钟产生基准漂移。
        val cached = latestCached(manager)
        val fix = cached?.first
        val age = cached?.second ?: Long.MAX_VALUE
        if (fix != null && isCacheFresh(age, freshnessMs)) {
            Logger.d(TAG) { "using cached fix from ${fix.provider}, age=${age / 1000}s" }
            return fix to true
        }
        val ageText = if (age == Long.MAX_VALUE) "untrusted/clock-rolled-back" else "${age / 1000}s"
        Logger.d(TAG) { "cached fix stale (age=$ageText), requesting fresh update" }
        val freshFix = requestFreshFix(manager, timeoutMs)
        if (freshFix != null) return freshFix to true
        // 超时且无新坐标：降级为缓存点，但如实标记为"非实时"，让家长端知道这可能是旧位置。
        return fix?.let { it to false }
    }

    /**
     * 所有 provider 的缓存点里**单调年龄最小**的那个。
     *
     * 早期按 `maxByOrNull { it.time }` 取"墙钟时间戳最大"：一旦系统时间被回拨，
     * 一个带着未来时间戳的陈旧点会被挑成"最新"，进而绕过新鲜度判定。
     * 改为统一按 [fixAgeMs] 求年龄再取最小值，排序与新鲜度判定共用同一单调基准。
     *
     * @return 位置点 + 其单调年龄；无可用缓存返回 null
     */
    // 权限门在 collect() 入口（hasLocationPermission()），此处每个 provider 另有 runCatching
    // 兜住 SecurityException（权限被撤销的瞬间部分 ROM 仍会抛）。lint 无法跨函数推断，显式声明。
    @SuppressLint("MissingPermission")
    private fun latestCached(manager: LocationManager): Pair<Location, Long>? {
        val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val nowWallMs = System.currentTimeMillis()
        return PROVIDER_PRIORITY.mapNotNull { provider ->
            // 逐个 runCatching：ROM 差异很大，有的 provider 未启用时会直接抛，
            // 一次异常不该让整条取数链路断掉，后面还有降级源可用。
            runCatching { manager.getLastKnownLocation(provider) }
                .onFailure { Logger.d(TAG) { "getLastKnownLocation($provider) failed: ${it.message}" } }
                .getOrNull()
                ?.let { cached ->
                    val age = fixAgeMs(
                        fixElapsedNanos = runCatching { cached.elapsedRealtimeNanos }.getOrDefault(0L),
                        nowElapsedNanos = nowElapsedNanos,
                        fixWallMs = cached.time,
                        nowWallMs = nowWallMs
                    )
                    cached to age
                }
        }.minByOrNull { it.second }
    }

    /**
     * 向所有可用 provider 同时注册一次更新，**谁先回用谁**。
     *
     * 只注册 GPS 会室内长时间无解；只注册网络又拿不到高精度 —— 并发取先到的那个，
     * 兼顾"快"与"准"。超时返回 null，由调用方降级到缓存点。
     */
    // 同上：权限由 collect() 前置把关，requestLocationUpdates 已在 runCatching 内，
    // 权限缺失只会让本 provider 注册失败并降级到缓存点，不会崩进程。
    @SuppressLint("MissingPermission")
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

        private const val NANOS_PER_MS = 1_000_000L

        /**
         * 缓存定位点的"年龄"（毫秒）。抽成只接收基本类型的纯函数，便于在纯 JVM 单测里
         * 覆盖时间篡改分支（[Location] / [SystemClock] 在 android.jar 里都是 stub，单测碰不得）。
         *
         * ## 为什么不能再用 `TimeProvider.now() - Location.time`（MON-09）
         * [com.padguard.core.common.TimeProvider.now] 是**受信单调护栏时间**：墙钟被大幅
         * 回拨/前拨时它会忽略墙钟、按单调钟继续推进，对时成功还会被服务端时间重新锚定；
         * 而 [Location.time] 是**系统墙钟** UTC 毫秒。两者不同源，相减得到的年龄会随
         * 校时/篡改整体平移：
         * - 墙钟被回拨 → `Location.time` 落在 `now()` 之后，age 变负 → 命中 `age <= freshnessMs`
         *   → 一个几小时前的陈旧点被当成"刚刚采集"，以 `fresh=true` 上报假实时位置；
         * - 对时重新锚定后 `now()` 领先墙钟 → 刚拿到的点反被判陈旧，无谓地反复唤醒 GPS 耗电。
         *
         * ## 正确基准
         * [Location.elapsedRealtimeNanos] 与 [SystemClock.elapsedRealtimeNanos] 同源，
         * 都是"自开机起的单调纳秒"，改系统时间、NTP 校时、服务端对时都不会影响它。
         *
         * 单调标记缺失（部分 ROM 的跨重启持久点、测试桩）时退回墙钟差值 —— 此时两个操作数
         * **同为墙钟基准**，至少自洽；且墙钟回拨产生的负差值一律判为不可信
         * （`Long.MAX_VALUE`），强制走主动请求新位置，绝不把"未来时间戳"的点当新鲜点。
         *
         * @return 年龄毫秒；`Long.MAX_VALUE` 表示该点时间基准不可信、必须视为陈旧
         */
        @JvmStatic
        internal fun fixAgeMs(
            fixElapsedNanos: Long,
            nowElapsedNanos: Long,
            fixWallMs: Long,
            nowWallMs: Long
        ): Long {
            if (fixElapsedNanos > 0L && nowElapsedNanos >= fixElapsedNanos) {
                return (nowElapsedNanos - fixElapsedNanos) / NANOS_PER_MS
            }
            val wallAge = nowWallMs - fixWallMs
            return if (wallAge >= 0L) wallAge else Long.MAX_VALUE
        }

        /** 缓存点是否足够新鲜；`Long.MAX_VALUE`（不可信基准）与负年龄一律判陈旧。 */
        @JvmStatic
        internal fun isCacheFresh(ageMs: Long, freshnessMs: Long): Boolean =
            ageMs in 0L..freshnessMs
    }
}

/**
 * 离线轨迹暂存的**内容标识**：`时间戳 + 坐标`（R-P3-12）。
 *
 * ## 为什么不能只用时间戳
 * 旧实现用 `ts` 当唯一键做两件事：追加时去重、补传成功后按 `ts` 删除。两种都错在同一处：
 * **同一毫秒完全可能有两个不同的点**——
 * - 家长"立即定位"与周期上报撞在同一刻，两者拿到的可能是不同 provider 的 fix；
 * - `LocationManager` 并发注册 GPS + 网络，回调落在同一毫秒；
 * - 设备快速移动时，高频率 provider 的相邻两点时间戳被截断到同一毫秒。
 * 后果是：后到的点被判为"重复"而**直接丢弃**（轨迹断档），
 * 且补传成功后按 ts 删除会把**没传出去的那条也删掉**（数据静默丢失）。
 *
 * 加上坐标之后，只有"同一时刻同一位置"才算重复 —— 那本来就是同一个点，去重才是对的。
 * 抽成不依赖 org.json 的纯函数，便于在 JVM 单测里直接锁定这条语义。
 */
internal fun outboxKey(ts: Long, lat: Double, lng: Double): String = "$ts|$lat|$lng"

/**
 * 离线轨迹暂存（store-and-forward 的小型落盘队列）。
 *
 * ## 为什么不用 Room
 * 这里只需要「有界追加 + 按时间戳删除」两件事。新开一张表意味着数据库版本升级与迁移脚本，
 * 一旦迁移写错就是"升级即丢数据"的事故，风险远大于收益。
 * JSON-Lines 文件每行一个点：追加/删除都是整文件重写（≤ 300 行，代价可忽略）。
 * 原子提交统一走 [JsonLinesFile]（tmp 写完整 → rename 覆盖 → 失败保留 tmp 供恢复），
 * 正常路径进程被杀最多丢最后一次写；仅在 rename 失败的降级覆写路径才可能出现半行，
 * 由坏行剔除逻辑清掉。详见新增缺陷 J。
 *
 * ## 上限与淘汰
 * 超过 [MAX_POINTS] 丢**最旧**的点：轨迹的价值随时间衰减，
 * 保住最近的轨迹比留住三天前的点更有意义，也避免文件无限增长。
 */
private class LocationOutbox(file: File) {

    private val lock = Any()

    /**
     * 原子落盘统一委托 [JsonLinesFile]（新增缺陷 J）。
     *
     * 旧实现在这里先 `file.delete()` 再 `tmp.renameTo(file)`：POSIX rename 本就能原子覆盖
     * 已存在的目标，这一次 delete 把「原子替换」拆成「删除 + 创建」两步，进程若在两步之间
     * 被系统回收，`file` 不存在而读路径只看 `file` → 离线期间攒下的整队轨迹一次性全丢。
     * 现在由共用底座保证「绝不预先 delete、tmp 残留可恢复」，最坏只丢最后一次写。
     */
    private val lines = JsonLinesFile(file)

    fun append(point: LocationInfo) = synchronized(lock) {
        try {
            val raw = lines.readOrNull()
            if (raw == null) {
                // 读失败必须跳过写盘：否则会用"只含新点"的文件覆盖掉盘上整队离线轨迹。
                Logger.w(TAG_OUTBOX) { "append skipped: outbox unreadable, kept on-disk data" }
            } else {
                val all = dropCorrupt(raw)
                val key = outboxKey(point.ts, point.lat, point.lng)
                if (all.none { keyOfLine(it) == key }) {
                    val next = all + toJson(point)
                    lines.write(if (next.size > MAX_POINTS) next.takeLast(MAX_POINTS) else next)
                }
            }
        } catch (t: Throwable) {
            Logger.w(TAG_OUTBOX, t) { "append failed" }
        }
    }

    fun snapshot(): List<LocationInfo> = synchronized(lock) {
        dropCorrupt(lines.read()).mapNotNull(::fromJson).sortedBy { it.ts }
    }

    /**
     * 删除"已成功上传"的那些行。
     *
     * 入参是**点本身**而不是 `Set<Long>`（R-P3-12）：旧实现只按 `ts` 删，
     * 一旦两个点时间戳相同（同一枚 fix 被重复上报、或同一毫秒内的多个 provider 回包），
     * 就会连带删掉那条根本没传出去的点 —— 轨迹出现无法解释的断点。
     */
    fun remove(points: List<LocationInfo>) = synchronized(lock) {
        try {
            val raw = lines.readOrNull()
            if (raw == null) {
                // 读失败 != 队列为空：跳过写盘，保住盘上原数据等下轮重试。
                Logger.w(TAG_OUTBOX) { "remove skipped: outbox unreadable, kept on-disk data" }
            } else {
                val keys = points.mapTo(HashSet()) { outboxKey(it.ts, it.lat, it.lng) }
                val kept = dropCorrupt(raw).filter { line ->
                    val key = keyOfLine(line)
                    key != null && key !in keys
                }
                lines.write(kept)
            }
        } catch (t: Throwable) {
            Logger.w(TAG_OUTBOX, t) { "remove failed" }
        }
    }

    /**
     * 剔除无法解析的坏行并写回（新增缺陷 J）。
     *
     * 坏行永远传不出去，旧实现里 `keyOfLine` 返回 null 会被 `filterNot` 一直保留，
     * 占着 [MAX_POINTS] 名额把后到的有效轨迹挤掉。写回失败也不影响本轮返回，下轮再试。
     */
    private fun dropCorrupt(raw: List<String>): List<String> {
        val valid = raw.filter { fromJson(it) != null }
        if (valid.size != raw.size) lines.write(valid)
        return valid
    }

    private fun toJson(p: LocationInfo): String = JSONObject()
        .put("lat", p.lat)
        .put("lng", p.lng)
        .put("acc", p.accuracy.toDouble())
        .put("ts", p.ts)
        .put("prov", p.provider)
        .put("fresh", p.fresh)
        .toString()

    private fun fromJson(line: String): LocationInfo? = try {
        val o = JSONObject(line)
        LocationInfo(
            lat = o.getDouble("lat"),
            lng = o.getDouble("lng"),
            accuracy = o.optDouble("acc", 0.0).toFloat(),
            ts = o.getLong("ts"),
            provider = o.optString("prov", ""),
            fresh = o.optBoolean("fresh", true)
        )
    } catch (t: Throwable) {
        null
    }

    /** 一行 JSON → 去重键；解析失败返回 null（坏行在 [dropCorrupt] 阶段已被剔除） */
    private fun keyOfLine(line: String): String? = try {
        val o = JSONObject(line)
        outboxKey(o.optLong("ts", 0L), o.getDouble("lat"), o.getDouble("lng"))
    } catch (t: Throwable) {
        null
    }

    private companion object {
        const val TAG_OUTBOX = "LocationOutbox"

        /** 队列上限：按 5 分钟一个点算，300 点≈24 小时，足够覆盖常见断网时长 */
        const val MAX_POINTS = 300
    }
}
