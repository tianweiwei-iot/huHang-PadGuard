package com.padguard.child.guard

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import com.padguard.core.engine.guard.GuardInfraProbe
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [GuardInfraProbe] 的 app 层实现（S12）。
 *
 * 两项探测的口径来源：
 * - 无障碍：复用 [GuardA11y.isEnabled]（匹配本包名 + OperationGuardAccessibilityService 类名），
 *   与 GuardService.ensureGuardInfra、「我的」页状态横幅共用同一判定，避免多处口径漂移；
 * - Usage Access：AppOpsManager.checkOpNoThrow(OPSTR_GET_USAGE_STATS) == MODE_ALLOWED，
 *   与 ProfileViewModel.isUsageStatsGranted 同一口径 —— 普通 checkSelfPermission 拿不到
 *   这个权限的真实状态，必须走 AppOps。
 */
@Singleton
class GuardInfraProbeImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : GuardInfraProbe {

    override fun isAccessibilityServiceEnabled(): Boolean =
        GuardA11y.isEnabled(context)

    override fun isAccessibilityServiceAlive(): Boolean =
        GuardA11y.isAlive(context)

    override fun isUsageAccessGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        return runCatching {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}
