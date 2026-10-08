package com.padguard.child.monitor

import com.padguard.child.AppForeground
import com.padguard.core.common.Logger
import com.padguard.core.data.repository.PolicyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 周期性位置上报（策略字段 `monitoring.locationIntervalSec` 的**执行者**）。
 *
 * ## 为什么必须补这一类
 * `locationIntervalSec` 在策略模型里定义多年，但此前只被"校验区间"（`MonitoringEnforcer`），
 * **从来没有任何代码按它去调度采集** —— 于是服务端 `location_tracks` 里几乎没有数据，
 * 家长端的"最新位置"永远是空的、轨迹画不出来、电子围栏也无从判定
 * （服务端的越界检测是在入库时触发的，没有上报点就等于围栏不存在）。
 * 定位"不准"的根子就在这里：不是定位源不准，而是压根没在采。
 *
 * ## 为什么只在 App 前台跑
 * Android 10+ 禁止无后台位置权限的 App 在后台读位置，而申请该权限对儿童向 App 不合规
 * （详见 [LocationCollector]）。因此这里**只在前台周期采集**：
 * 孩子正在用平板 → 持续产出轨迹点；孩子锁屏放下 → 停止采集，回到前台再继续。
 * 这是合规前提下唯一能持续产出位置的路径，也正是主流家长管控产品的通行做法。
 *
 * ## 为什么用"短周期轮询"而不是"delay(interval)"
 * 单一 `delay(interval)` 无法同时响应两件事：孩子中途离开/回到前台、家长改了上报间隔。
 * 用固定 10 秒的 tick 检查"距上次采集是否已过 interval"，两个变化都能在 10 秒内生效，
 * 且 tick 里只比较时间戳，前台判定才是真正的采集动作，开销可以忽略。
 */
@Singleton
class LocationAutoReporter @Inject constructor(
    private val policyRepository: PolicyRepository,
    private val collector: LocationCollector
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    private var policyJob: Job? = null
    private var loopJob: Job? = null

    /** 当前生效的上报间隔（秒）；0 = 关闭定时上报 */
    @Volatile
    private var intervalMs: Long = 0L

    @Volatile
    private var lastAttemptAtMs: Long = 0L

    fun start() {
        if (started) return
        started = true
        Logger.i(TAG) { "starting periodic location reporter" }
        policyJob = scope.launch {
            policyRepository.observePolicy()
                .map { it.monitoring.locationIntervalSec }
                .distinctUntilChanged()
                .collect { seconds -> applyInterval(seconds) }
        }
        // 孩子刚打开 App 的那一刻先补一次：此时位置最新鲜，
        // 家长端"最新位置"能立刻刷新，不必等下一个周期。
        AppForeground.addForegroundListener {
            if (intervalMs > 0) scope.launch { runCatching { reportOnce() } }
        }
    }

    fun stop() {
        started = false
        loopJob?.cancel()
        policyJob?.cancel()
        loopJob = null
        policyJob = null
        Logger.i(TAG) { "stopped periodic location reporter" }
    }

    private fun applyInterval(seconds: Int) {
        loopJob?.cancel()
        if (seconds <= 0) {
            intervalMs = 0L
            Logger.i(TAG) { "periodic location disabled (interval=0)" }
            return
        }
        intervalMs = seconds * 1_000L
        loopJob = scope.launch {
            Logger.i(TAG) { "periodic location every ${seconds}s" }
            while (isActive) {
                if (AppForeground.isForeground) reportOnce()
                delay(TICK_MS)
            }
        }
    }

    private suspend fun reportOnce() {
        val now = System.currentTimeMillis()
        if (now - lastAttemptAtMs < intervalMs) return
        lastAttemptAtMs = now
        when (val outcome = collector.collectAndUpload(
            // 周期上报要求的"新鲜度"：最长按 60s 算，间隔更短就按间隔来
            freshnessMs = intervalMs.coerceAtMost(LocationCollector.DEFAULT_FRESHNESS_MS),
            minDistanceMeters = MIN_MOVE_METERS
        )) {
            LocationOutcome.Success -> Logger.d(TAG) { "periodic location reported" }
            LocationOutcome.Skipped -> Unit // 静止不动，不上报（省流量且不刷屏轨迹）
            is LocationOutcome.Failure -> Logger.w(TAG) { "periodic location failed: ${outcome.kind}" }
        }
    }

    companion object {
        private const val TAG = "LocationAutoReporter"

        /** 状态检查的轮询周期 */
        private const val TICK_MS = 10_000L

        /** 与上一点的最小位移：不足视为没动，跳过上报（米） */
        private const val MIN_MOVE_METERS = 25f
    }
}
