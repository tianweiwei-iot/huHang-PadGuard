package com.padguard.core.engine.enforcer

import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.MonitoringPolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 采集与上报参数的落地。
 *
 * 本类不调用任何 DPM API —— 它做的是**把服务端下发的采集参数收敛到设备能承受的范围**，
 * 并给出可执行的运行时配置（[MonitoringRuntime]）。
 *
 * ## 为什么必须做区间收敛
 * 服务端配置页面上的输入框，运营手滑填个 `1` 就是"每秒心跳一次"。
 * 在真实平板上这会造成三个后果：
 * 1. 唤醒锁频繁获取，一晚上掉 30% 电，家长第一反应是"这个软件把电耗光了"；
 * 2. 网络请求密度触发运营商/服务端限流，反而导致真正的心跳丢失；
 * 3. 定时截屏若配到 10 秒一次，存储与流量都会在一天内被打满。
 *
 * 终端必须有自己的下限，并把"我实际用的是多少"如实上报，
 * 而不是默默按服务端的离谱值执行、或者默默改成别的值不告诉任何人。
 *
 * ## Doze 的现实约束
 * Android 6+ 的 Doze 模式下，AlarmManager 非精确闹钟最小唤醒间隔约 9 分钟，
 * `setExactAndAllowWhileIdle` 也被限制为每 9 分钟一次。所以"30 秒心跳"只在亮屏/充电时成立，
 * 息屏后实际间隔会被系统拉长 —— 这是系统行为，不是 bug。
 * 因此心跳超时判定必须由服务端按 **3 倍间隔 + Doze 宽限** 来做，
 * 否则每台设备一到晚上就报"离线告警"，告警会彻底失去意义。
 */
@Singleton
class MonitoringEnforcer @Inject constructor() {

    @Volatile
    private var runtime: MonitoringRuntime = MonitoringRuntime()

    fun current(): MonitoringRuntime = runtime

    fun apply(policy: MonitoringPolicy): EnforceReport {
        val report = EnforceReport()

        val heartbeat = policy.heartbeatIntervalSec.coerceIn(MIN_HEARTBEAT_SEC, MAX_HEARTBEAT_SEC)
        val logUpload = policy.logUploadIntervalSec.coerceIn(MIN_LOG_UPLOAD_SEC, MAX_LOG_UPLOAD_SEC)
        val screenshot = policy.screenshotIntervalSec.let {
            if (it <= 0) 0 else it.coerceIn(MIN_SCREENSHOT_SEC, MAX_SCREENSHOT_SEC)
        }
        val location = policy.locationIntervalSec.let {
            if (it <= 0) 0 else it.coerceIn(MIN_LOCATION_SEC, MAX_LOCATION_SEC)
        }

        // 被收敛的项要如实记录，管控端才能发现"配了 1 秒但实际按 15 秒执行"
        if (heartbeat != policy.heartbeatIntervalSec) {
            report.markUnsupported(
                "monitor.heartbeat",
                "请求 ${policy.heartbeatIntervalSec}s 超出允许区间，实际按 ${heartbeat}s 执行"
            )
        }
        if (logUpload != policy.logUploadIntervalSec) {
            report.markUnsupported(
                "monitor.logUpload",
                "请求 ${policy.logUploadIntervalSec}s 超出允许区间，实际按 ${logUpload}s 执行"
            )
        }
        if (screenshot != policy.screenshotIntervalSec) {
            report.markUnsupported(
                "monitor.screenshot",
                "请求 ${policy.screenshotIntervalSec}s 超出允许区间，实际按 ${screenshot}s 执行"
            )
        }
        if (location != policy.locationIntervalSec) {
            report.markUnsupported(
                "monitor.location",
                "请求 ${policy.locationIntervalSec}s 超出允许区间，实际按 ${location}s 执行"
            )
        }

        runtime = MonitoringRuntime(
            heartbeatIntervalSec = heartbeat,
            logUploadIntervalSec = logUpload,
            screenshotIntervalSec = screenshot,
            locationIntervalSec = location,
            collectAppUsage = policy.collectAppUsage,
            collectUrlHistory = policy.collectUrlHistory,
            collectHardware = policy.collectHardware
        )

        report.note("monitor.heartbeat=${heartbeat}s")
        report.note("monitor.logUpload=${logUpload}s")
        if (screenshot > 0) report.note("monitor.screenshot=${screenshot}s")
        if (location > 0) report.note("monitor.location=${location}s")

        Logger.i(TAG) { "monitoring runtime: $runtime" }
        return report
    }

    companion object {
        private const val TAG = "MonitoringEnforcer"

        /** 心跳下限 15 秒：再密只会耗电，且 Doze 下根本到不了 */
        const val MIN_HEARTBEAT_SEC = 15
        const val MAX_HEARTBEAT_SEC = 900

        const val MIN_LOG_UPLOAD_SEC = 60
        const val MAX_LOG_UPLOAD_SEC = 3600

        /** 截屏下限 60 秒：截屏涉及 MediaProjection，开销与隐私成本都高 */
        const val MIN_SCREENSHOT_SEC = 60
        const val MAX_SCREENSHOT_SEC = 7200

        /** 定位下限 60 秒：GPS 高频定位是最大的耗电项 */
        const val MIN_LOCATION_SEC = 60
        const val MAX_LOCATION_SEC = 7200
    }
}

/**
 * 实际生效的采集参数。
 *
 * app 层的前台服务与 WorkManager 只读这个对象，不读原始策略 ——
 * 保证"收敛后的值"是唯一事实来源，避免两处各自 clamp 出不同结果。
 */
data class MonitoringRuntime(
    val heartbeatIntervalSec: Int = 30,
    val logUploadIntervalSec: Int = 300,
    val screenshotIntervalSec: Int = 0,
    val locationIntervalSec: Int = 0,
    val collectAppUsage: Boolean = true,
    val collectUrlHistory: Boolean = true,
    val collectHardware: Boolean = true
) {
    val heartbeatIntervalMs: Long get() = heartbeatIntervalSec * 1000L
    val logUploadIntervalMs: Long get() = logUploadIntervalSec * 1000L
}
