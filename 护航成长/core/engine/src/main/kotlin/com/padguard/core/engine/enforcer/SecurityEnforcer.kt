package com.padguard.core.engine.enforcer

import android.content.Context
import android.os.UserManager
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.SecurityPolicy
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自我保护策略。
 *
 * ## 四类绕过手段与对应封堵
 * | 绕过手段 | 封堵方式 | 能否封死 |
 * |---|---|---|
 * | 设置里卸载本应用 | `setUninstallBlocked` + `DISALLOW_UNINSTALL_APPS` + DO 身份 | ✅ 能（DO 下系统层禁止） |
 * | 设置里"强行停止" / "清除数据" | `DISALLOW_APPS_CONTROL` | ✅ 能（该限制会灰掉这两个按钮） |
 * | 改系统时间跳过时段/限额 | 强制自动对时 + `DISALLOW_CONFIG_DATE_TIME` | ✅ 能（DO 下） |
 * | root / 刷机 / 恢复出厂 | `DISALLOW_FACTORY_RESET` + 检测告警 | ⚠️ 只能封住系统内入口 |
 * | 关机、拔 SIM、断网 | —— | ❌ **封不住**，只能靠离线策略继续生效 + 心跳超时告警 |
 * | Recovery 刷机 / 拆机重装系统 | —— | ❌ 封不住，属于物理层，需 MDM 部署时的资产管理配合 |
 *
 * 最后两行必须如实写进部署文档。管控类产品最容易犯的错是"宣称全封"，
 * 而家长一旦发现关机就能绕过、却从未被告知，信任会一次性崩塌。
 * 正确做法是：**明确告知边界 + 用离线策略与告警把代价提高**
 * （关机期间设备不可用，开机即恢复管控，且服务端能看到"离线时长"）。
 *
 * ## `DISALLOW_APPS_CONTROL` 的副作用
 * 这条限制会一并禁止用户在设置里管理**所有**应用（禁用系统应用、清缓存等）。
 * 对家庭场景可能偏重，但换来的是"强行停止"按钮变灰 —— 这是保住常驻服务的唯一公开手段，
 * 权衡后选择开启，并在报告中标注影响范围，让管控端可以解释给家长。
 */
@Singleton
class SecurityEnforcer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge
) {

    fun apply(policy: SecurityPolicy): EnforceReport {
        val report = EnforceReport()

        applyAntiUninstall(policy, report)
        applyAntiForceStop(policy, report)
        applyAntiClockTamper(policy, report)
        applyRootHardening(policy, report)
        applyAccessibilityHardening(policy, report)

        Logger.i(TAG) { "security applied: $report" }
        return report
    }

    /**
     * 防卸载。
     *
     * 注意：即使 [SecurityPolicy.antiUninstall] 为 false，也**不解除**自身卸载保护。
     * 解绑必须走服务端授权的解绑流程（清除 DO → 卸载），
     * 否则一次策略误配就会让全校设备变成可随手卸载的状态。
     */
    private fun applyAntiUninstall(policy: SecurityPolicy, report: EnforceReport) {
        if (!admin.can(Capability.BLOCK_UNINSTALL)) {
            report.markUnsupported("security.antiUninstall", "需要 Device Owner / Profile Owner")
            return
        }
        report.record("security.antiUninstall(self)", admin.setUninstallBlocked(context.packageName, true))
        if (policy.antiUninstall && admin.can(Capability.USER_RESTRICTIONS)) {
            report.record(
                "security.disallowUninstallApps",
                admin.setUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS, true)
            )
        }
    }

    /**
     * 防强行停止 / 清除数据。
     *
     * `DISALLOW_APPS_CONTROL` 是唯一能让设置页里"强行停止""清除数据"变灰的公开 API。
     * 除此之外的常见做法（监听 onTaskRemoved 自拉起、双进程守护）在 Android 8+ 已基本失效，
     * 且属于对抗系统的黑科技，上架与稳定性风险都高，本项目不采用。
     */
    private fun applyAntiForceStop(policy: SecurityPolicy, report: EnforceReport) {
        if (!policy.antiForceStop) {
            if (admin.can(Capability.USER_RESTRICTIONS)) {
                report.record(
                    "security.antiForceStop=off",
                    admin.setUserRestriction(UserManager.DISALLOW_APPS_CONTROL, false)
                )
            }
            return
        }
        if (!admin.can(Capability.USER_RESTRICTIONS)) {
            report.markUnsupported(
                "security.antiForceStop",
                "需要 Device Owner / Profile Owner；降级后进程可被用户强行停止，仅能靠开机自启与心跳超时告警兜底"
            )
            return
        }
        report.record(
            "security.antiForceStop(副作用:设置内应用管理整体受限)",
            admin.setUserRestriction(UserManager.DISALLOW_APPS_CONTROL, true)
        )
    }

    /** 防改时间：真正的封堵在 SystemLockEnforcer，这里做安全策略侧的兜底与一致性保证 */
    private fun applyAntiClockTamper(policy: SecurityPolicy, report: EnforceReport) {
        if (!policy.antiClockTamper) return
        if (admin.can(Capability.GLOBAL_SETTINGS)) {
            report.record("security.autoTimeEnforced", admin.setAutoTimeEnforced(true))
        } else if (admin.can(Capability.USER_RESTRICTIONS)) {
            report.record(
                "security.disallowConfigDateTime",
                admin.setUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, true)
            )
            report.markUnsupported("security.autoTimeEnforced", "需要 Device Owner，已退化为禁止手改时间")
        } else {
            report.markUnsupported(
                "security.antiClockTamper",
                "无管控权限；时长统计仍基于单调时钟不受影响，但日切与时段判定可能被改时间影响"
            )
        }
    }

    /**
     * root / 刷机相关加固。
     *
     * 无法阻止已 root 的设备，只能：① 封住系统内的重置与调试入口；② 检测并告警。
     * 检测逻辑放在 [com.padguard.core.engine.guard.TamperDetector]，本类只负责下发限制。
     */
    private fun applyRootHardening(policy: SecurityPolicy, report: EnforceReport) {
        if (!policy.blockRoot) return
        if (!admin.can(Capability.USER_RESTRICTIONS)) {
            report.markUnsupported("security.blockRoot", "需要 Device Owner / Profile Owner")
            return
        }
        report.record(
            "security.disallowFactoryReset",
            admin.setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, true)
        )
        report.record(
            "security.disallowDebugging",
            admin.setUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, true)
        )
        report.record(
            "security.disallowSafeBoot",
            admin.setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, true)
        )
        report.note("security.rootDetection=检测告警（无法阻止已 root 设备）")
    }

    /**
     * 无障碍 / 输入法白名单加固。
     *
     * 自动点击器类应用能自动点掉拦截弹窗，第三方输入法内置的浏览器能绕过上网管控 ——
     * 这两个通道不封，前面的管控就是纸糊的。
     *
     * 白名单里必须保留系统读屏服务（视障学生的可用性底线）与当前默认输入法
     * （否则设备立刻无法输入，属于变砖级故障）。
     */
    private fun applyAccessibilityHardening(policy: SecurityPolicy, report: EnforceReport) {
        if (!policy.antiForceStop) return // 与防绕过同一开关，避免再引入一个协议字段
        if (!admin.can(Capability.USER_RESTRICTIONS)) {
            report.markUnsupported("security.accessibilityWhitelist", "需要 Device Owner / Profile Owner")
            return
        }

        val allowedAccessibility = buildList {
            add(context.packageName)
            addAll(SYSTEM_SCREEN_READERS)
        }
        report.record(
            "security.accessibilityWhitelist",
            admin.setPermittedAccessibilityServices(allowedAccessibility)
        )

        val currentIme = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
            )?.substringBefore('/')
        }.getOrNull()

        if (currentIme.isNullOrBlank()) {
            // 读不到默认输入法时**不下发白名单**：下发空列表会把当前输入法一起禁掉
            report.markUnsupported("security.imeWhitelist", "无法解析默认输入法，跳过以避免设备无法输入")
        } else {
            val enabledImes = runCatching {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
                imm?.enabledInputMethodList?.map { it.packageName }.orEmpty()
            }.getOrDefault(emptyList())
            val allowedImes = (listOf(currentIme) + enabledImes).distinct()
            report.record("security.imeWhitelist(${allowedImes.size})", admin.setPermittedInputMethods(allowedImes))
        }
    }

    /** 日志保留天数的安全下限：太短会导致告警在上报前就被清掉 */
    fun effectiveRetentionDays(policy: SecurityPolicy): Int =
        policy.logRetentionDays.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)

    companion object {
        private const val TAG = "SecurityEnforcer"
        const val MIN_RETENTION_DAYS = 3
        const val MAX_RETENTION_DAYS = 180

        /** 系统读屏服务：必须始终放行，否则视障用户无法使用设备 */
        private val SYSTEM_SCREEN_READERS = setOf(
            "com.google.android.marvin.talkback",
            "com.android.talkback",
            "com.samsung.android.accessibility.talkback",
            "com.huawei.screenreader",
            "com.miui.accessibility"
        )
    }
}
