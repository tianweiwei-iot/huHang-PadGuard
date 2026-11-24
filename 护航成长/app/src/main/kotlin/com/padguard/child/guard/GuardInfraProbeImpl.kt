package com.padguard.child.guard

import android.content.Context
import com.padguard.child.permission.SpecialPermission
import com.padguard.core.engine.guard.GuardInfraProbe
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [GuardInfraProbe] 的 app 层实现（S12）。
 *
 * 两项探测一律复用 app 层的**单一事实源**，不在本类另写一套系统 API 查询：
 * - 无障碍：[GuardA11y.isEnabled] / [GuardA11y.isAlive]（匹配本包名 + 服务类名，
 *   带存活心跳），与 GuardService.ensureGuardInfra、「我的」页状态横幅同源；
 * - Usage Access：[SpecialPermission.USAGE_STATS] 的 `isGranted()`。
 *
 * ## 为什么不能再手写 AppOps
 * 本类旧实现用已废弃的 `AppOpsManager.checkOpNoThrow` + `Process.myUid()`，
 * 而单一事实源在 Q+ 用 `unsafeCheckOpNoThrow` + `applicationInfo.uid`。
 * 本类是 [com.padguard.core.engine.guard.TamperDetector] 的输入，`PolicyEngine`
 * 每轮自检都会读它：一旦两套口径在某台 ROM 上分歧，「使用情况访问」明明开着，
 * 也会被判定为 `USAGE_ACCESS_LOST` 上报家长端，且该告警没有恢复闭环，会一直挂着。
 * 口径不一致比误判更危险——它让界面与告警互相打脸、无法自证。
 */
@Singleton
class GuardInfraProbeImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : GuardInfraProbe {

    override fun isAccessibilityServiceEnabled(): Boolean =
        GuardA11y.isEnabled(context)

    override fun isAccessibilityServiceAlive(): Boolean =
        GuardA11y.isAlive(context)

    override fun isUsageAccessGranted(): Boolean =
        SpecialPermission.USAGE_STATS.isGranted(context)
}
