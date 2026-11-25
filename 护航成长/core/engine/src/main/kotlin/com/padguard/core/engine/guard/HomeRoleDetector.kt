package com.padguard.core.engine.guard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 非 DO 第三方桌面检测与夺回（P1-2）。
 *
 * ## 背景
 * 设备不是 Device Owner 时，系统不会强制常驻桌面（`setPersistentPreferredLauncher` 不可用），
 * 孩子可以在系统设置里把默认桌面换回系统桌面、或装一个第三方 launcher 设为默认 ——
 * 此后按 HOME 键就跳出管控，[KioskEnforcer] 也无力阻止。
 *
 * ## 做法
 * 周期性 `resolveActivity(CATEGORY_HOME)` 看"默认桌面是不是自己"：
 * - 非己 → 上报 [RiskType.HOME_CHANGED](HIGH)，让家长端立刻可见并提示改回；
 * - 同时启动自家 Launcher（`FLAG_ACTIVITY_NEW_TASK`）把焦点抢回 —— 仅非 DO（降级）态下主动抢，
 *   DO 态系统已强制常驻，检测到异常也只告警不抢，避免无谓干扰。
 *
 * ## 不是治本
 * 真正治本是设备装机时 `dpm set-device-owner` 设为 DO。本项只是把"换桌面逃逸"从无声变成
 * 可见 + 可被即时拉回，抬高绕过门槛；治本仍需 DO 部署（见报告 P2）。
 */
@Singleton
open class HomeRoleDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge
) {
    @Volatile
    private var lastReportedHome: String? = null

    @Volatile
    private var lastReclaimAt: Long = 0L

    open fun detect(): List<TamperFinding> {
        val own = context.packageName
        // 仅在能拿到 PackageManager 时才构造查询 Intent：packageManager 为 null（受限/mock 环境）直接静默，
        // 同时避免 android.jar 在 JVM 单测下 Intent.addCategory 抛 "not mocked"。
        val defaultHomePkg = runCatching { context.packageManager }
            .getOrNull()
            ?.let { resolveDefaultHome(homeIntent()) }
            ?: return emptyList()

        // 默认桌面是自身 → 正常，清掉迟滞标记以便将来真被换时能再报
        if (defaultHomePkg == own) {
            lastReportedHome = null
            return emptyList()
        }

        val findings = mutableListOf<TamperFinding>()
        // 迟滞：同一第三方桌面只报一次，拉回后复位；换成了另一个桌面才再报
        if (lastReportedHome != defaultHomePkg) {
            lastReportedHome = defaultHomePkg
            findings += TamperFinding(
                type = RiskType.HOME_CHANGED,
                level = RiskLevel.HIGH,
                detail = mapOf("homePackage" to defaultHomePkg)
            )
        }

        // 仅非 DO（降级）态主动夺回焦点：DO 态系统已强制常驻，无需抢
        if (!admin.can(Capability.LOCK_TASK)) {
            // 去抖必须走单调钟：孩子回拨系统时间会让墙钟差值为负，夺回动作永久失效，
            // 降级态最后一道"换桌面逃逸"防线退化成只有告警。
            val now = monotonicNow()
            if (now - lastReclaimAt > RECLAIM_INTERVAL_MS) {
                lastReclaimAt = now
                reclaimOwnHome(own)
            }
        }
        return findings
    }

    internal fun resolveDefaultHome(intent: Intent): String? {
        // 只返回默认桌面的包名即可：检测逻辑只需包名比对；真正的 ComponentName 留给夺回路径按需构造。
        // 抽出为 internal 便于 JVM 单测注入 mock Intent（避免 android.jar 的 not-mocked）。
        // 容错：mock/受限环境下 packageManager 可能为 null，直接判空返回（与 EgressExposureDetector 一致）
        val pm = runCatching { context.packageManager }.getOrNull() ?: return null
        val resolveInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrNull() ?: return null
        return resolveInfo.activityInfo?.packageName
    }

    /** 构建「默认桌面」查询 Intent。抽出为 protected/open 以便 JVM 单测注入 mock（避免 android.jar 的 not-mocked）。 */
    protected open fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    private fun reclaimOwnHome(own: String) {
        val intent = reclaimIntent(own)
        val component = runCatching {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            list.firstOrNull()?.activityInfo?.let { ComponentName(it.packageName, it.name) }
        }.getOrNull() ?: return

        runCatching {
            context.startActivity(
                intent.setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Logger.i(TAG) { "reclaim home to $own (was hijacked by ${component.packageName})" }
        }.onFailure { Logger.w(TAG) { "reclaim home failed: ${it.message}" } }
    }

    /** 构建「夺回焦点」Intent。抽出为 protected/open 以便 JVM 单测注入 mock。 */
    protected open fun reclaimIntent(own: String): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(own)

    /** 去抖时钟：生产走单调钟，JVM 单测可覆盖（android.jar 的 SystemClock 在 JVM 上 not-mocked）。 */
    protected open fun monotonicNow(): Long = SystemClock.elapsedRealtime()

    companion object {
        private const val TAG = "HomeRoleDetector"
        /** 夺回焦点的最小间隔，避免每轮自检都 startActivity 刷屏 */
        private const val RECLAIM_INTERVAL_MS = 30_000L
    }
}
