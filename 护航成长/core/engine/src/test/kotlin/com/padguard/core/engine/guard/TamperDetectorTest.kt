package com.padguard.core.engine.guard

import android.content.Context
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.data.model.policy.ControlMode
import com.padguard.core.data.model.policy.SecurityPolicy
import com.padguard.core.data.model.policy.SystemLockPolicy
import com.padguard.core.engine.admin.DeviceAdminBridge
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * [TamperDetector] 守护基建检测（S12）的 JVM 行为测试。
 *
 * 重点验证 [TamperDetector.detectGuardInfra] 的**闩锁语义**：
 * - 丢失那一刻报一次（无障碍 = HIGH，Usage Access = NORMAL）；
 * - 之后每轮扫描保持静默（防刷屏）；
 * - 权限恢复后闩锁复位，再次丢失会重新报告。
 *
 * JVM rig 说明：
 * - Context / DeviceAdminBridge 是 final 类，Mockito 5 inline mockmaker 直接 mock；
 *   未 stub 的行为全部落在 runCatching 兜底或默认返回值上，不干扰被测路径；
 * - 关闭 Logger（android.util.Log 在 JVM 是 stub）；
 * - SecurityPolicy 显式关掉 antiClockTamper/blockRoot，把被测面隔离到守护基建检测。
 */
class TamperDetectorTest {

    private var originalLoggerEnabled = true

    /** 无障碍 / Usage Access 状态可随手翻转的假探针 */
    private class FakeProbe(
        var a11y: Boolean = true,
        var usage: Boolean = true
    ) : GuardInfraProbe {
        // 默认「存活」与「开关打开」一致；测试「开关开着但进程被杀」的瞬时死亡场景时，
        // 用 [aliveOverride] 单独打破，模拟看门狗拉起前的空窗。
        var aliveOverride: Boolean? = null
        override fun isAccessibilityServiceEnabled(): Boolean = a11y
        override fun isAccessibilityServiceAlive(): Boolean = aliveOverride ?: a11y
        override fun isUsageAccessGranted(): Boolean = usage
    }

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
    }

    @After
    fun tearDown() {
        Logger.enabled = originalLoggerEnabled
    }

    private fun detector(probe: GuardInfraProbe): TamperDetector {
        val context = Mockito.mock(Context::class.java)
        val admin = Mockito.mock(DeviceAdminBridge::class.java)
        Mockito.`when`(admin.refreshControlMode()).thenReturn(ControlMode.LEGACY)
        Mockito.`when`(admin.controlMode).thenReturn(MutableStateFlow(ControlMode.LEGACY))
        return TamperDetector(context, admin, probe, TimeProvider())
    }

    /** 关掉与本测试无关的检测面，隔离守护基建断言 */
    private fun policy() = SecurityPolicy(antiClockTamper = false, blockRoot = false)

    @Test
    fun `accessibility loss reports once at HIGH then stays silent until recovery`() {
        val probe = FakeProbe(a11y = false, usage = true)
        val det = detector(probe)

        // 第 1 轮：报告 HIGH
        val first = det.detect(policy(), SystemLockPolicy())
        val finding = first.firstOrNull { it.type == RiskType.ACCESSIBILITY_DISABLED }
        assertTrue("丢失时应报告 ACCESSIBILITY_DISABLED", finding != null)
        assertEquals(RiskLevel.HIGH, finding!!.level)

        // 第 2 轮：闩锁生效，不再重复报告
        assertTrue(
            "闩锁期内不应重复报告",
            det.detect(policy(), SystemLockPolicy()).none { it.type == RiskType.ACCESSIBILITY_DISABLED }
        )

        // 恢复 → 复位
        probe.a11y = true
        assertTrue(
            "恢复后不应残留告警",
            det.detect(policy(), SystemLockPolicy()).none { it.type == RiskType.ACCESSIBILITY_DISABLED }
        )

        // 再次丢失 → 重新报告（复位语义）
        probe.a11y = false
        assertTrue(
            "复位后再次丢失应重新报告",
            det.detect(policy(), SystemLockPolicy()).any { it.type == RiskType.ACCESSIBILITY_DISABLED }
        )
    }

    @Test
    fun `usage access loss reports once at NORMAL then stays silent until recovery`() {
        val probe = FakeProbe(a11y = true, usage = false)
        val det = detector(probe)

        val finding = det.detect(policy(), SystemLockPolicy())
            .firstOrNull { it.type == RiskType.USAGE_ACCESS_LOST }
        assertTrue("丢失时应报告 USAGE_ACCESS_LOST", finding != null)
        assertEquals("Usage Access 丢失为 NORMAL（采集失明但闸门未失守）", RiskLevel.NORMAL, finding!!.level)

        assertTrue(
            det.detect(policy(), SystemLockPolicy()).none { it.type == RiskType.USAGE_ACCESS_LOST }
        )

        probe.usage = true
        assertTrue(
            det.detect(policy(), SystemLockPolicy()).none { it.type == RiskType.USAGE_ACCESS_LOST }
        )

        probe.usage = false
        assertTrue(
            det.detect(policy(), SystemLockPolicy()).any { it.type == RiskType.USAGE_ACCESS_LOST }
        )
    }

    @Test
    fun `healthy infra produces no findings at all`() {
        val det = detector(FakeProbe(a11y = true, usage = true))
        val findings = det.detect(policy(), SystemLockPolicy())
        assertTrue("基建健康时不应有任何发现（含其它检测面的 mock 噪音）: ${findings.map { it.type }}", findings.isEmpty())
    }

    /**
     * 开关开着但进程被系统杀掉（看门狗拉起前的瞬时空窗）不应报「人为关闭」，也不应刷屏。
     * 这正是 Bug ⑤ 误报「可能被人为关闭」的根因：把一次可自愈的进程死亡当成了用户手动关闭。
     */
    @Test
    fun `switch ON but process transiently dead must NOT report as human-closed`() {
        val probe = FakeProbe(a11y = true, usage = true).apply { aliveOverride = false }
        val det = detector(probe)

        repeat(3) {
            assertTrue(
                "开关开着、进程被杀属于可自愈空窗，不应误报 ACCESSIBILITY_DISABLED（第 ${it + 1} 轮）",
                det.detect(policy(), SystemLockPolicy()).none { it.type == RiskType.ACCESSIBILITY_DISABLED }
            )
        }

        // 进程自愈（看门狗拉起，心跳恢复）→ 更应无告警
        probe.aliveOverride = true
        assertTrue(
            "进程恢复后不应残留告警",
            det.detect(policy(), SystemLockPolicy()).none { it.type == RiskType.ACCESSIBILITY_DISABLED }
        )
    }

    /**
     * 开关确实被关闭（用户手动关闭 / 系统重置权限）才报 ACCESSIBILITY_DISABLED，
     * 且只在「由好变坏」那一刻报一次（闩锁语义），避免冤枉未手动关闭的用户。
     */
    @Test
    fun `genuinely disabled switch reports once with latch`() {
        val probe = FakeProbe(a11y = false, usage = true).apply { aliveOverride = false }
        val det = detector(probe)

        assertTrue(
            "开关已关闭应报一次 ACCESSIBILITY_DISABLED",
            det.detect(policy(), SystemLockPolicy()).any { it.type == RiskType.ACCESSIBILITY_DISABLED }
        )
        assertTrue(
            "闩锁期内不应重复报告",
            det.detect(policy(), SystemLockPolicy()).none { it.type == RiskType.ACCESSIBILITY_DISABLED }
        )
        // 恢复 → 复位，再关 → 重新报
        probe.a11y = true; probe.aliveOverride = true
        assertTrue(
            "恢复后不应残留告警",
            det.detect(policy(), SystemLockPolicy()).none { it.type == RiskType.ACCESSIBILITY_DISABLED }
        )
        probe.a11y = false; probe.aliveOverride = false
        assertTrue(
            "复位后再次关闭应重新报告",
            det.detect(policy(), SystemLockPolicy()).any { it.type == RiskType.ACCESSIBILITY_DISABLED }
        )
    }

    /**
     * 控制权态"变弱"需跨多次扫描确认，单次瞬时抖动（下一轮即恢复）**不应**误报 PERMISSION_REVOKED。
     * 这正是家长端"权限被撤销"误报的根因：refreshControlMode 在接收器未就绪时会偶发回落到 LEGACY。
     */
    @Test
    fun `transient control-mode flicker must NOT report PERMISSION_REVOKED`() {
        val context = Mockito.mock(Context::class.java)
        val admin = Mockito.mock(DeviceAdminBridge::class.java)
        // 序列：DEVICE_ADMIN → LEGACY(抖动) → DEVICE_ADMIN(恢复)
        Mockito.`when`(admin.refreshControlMode())
            .thenReturn(ControlMode.DEVICE_ADMIN, ControlMode.LEGACY, ControlMode.DEVICE_ADMIN)
        Mockito.`when`(admin.controlMode).thenReturn(MutableStateFlow(ControlMode.DEVICE_ADMIN))
        val det = TamperDetector(context, admin, FakeProbe(a11y = true, usage = true), TimeProvider())

        repeat(3) { i ->
            assertTrue(
                "瞬时抖动（第 ${i + 1} 轮）不应误报 PERMISSION_REVOKED",
                det.detect(policy(), SystemLockPolicy()).none { it.type == RiskType.PERMISSION_REVOKED }
            )
        }
    }

    /**
     * 控制权态"持续变弱"（用户确实关闭了设备管理员）跨过确认阈值后必须报一次 PERMISSION_REVOKED。
     */
    @Test
    fun `persistent control-mode downgrade reports PERMISSION_REVOKED after confirm scans`() {
        val context = Mockito.mock(Context::class.java)
        val admin = Mockito.mock(DeviceAdminBridge::class.java)
        // 序列：DEVICE_ADMIN → LEGACY → LEGACY（持续）
        Mockito.`when`(admin.refreshControlMode())
            .thenReturn(ControlMode.DEVICE_ADMIN, ControlMode.LEGACY, ControlMode.LEGACY)
        Mockito.`when`(admin.controlMode).thenReturn(MutableStateFlow(ControlMode.DEVICE_ADMIN))
        val det = TamperDetector(context, admin, FakeProbe(a11y = true, usage = true), TimeProvider())

        assertTrue("第 1 轮仅建立基线，不应报告",
            det.detect(policy(), SystemLockPolicy()).none { it.type == RiskType.PERMISSION_REVOKED })
        assertTrue("第 2 轮抖动未确认，不应报告",
            det.detect(policy(), SystemLockPolicy()).none { it.type == RiskType.PERMISSION_REVOKED })
        assertTrue("第 3 轮持续降级应报告 PERMISSION_REVOKED",
            det.detect(policy(), SystemLockPolicy()).any { it.type == RiskType.PERMISSION_REVOKED })
    }
}
