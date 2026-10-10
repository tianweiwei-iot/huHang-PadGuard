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
        override fun isAccessibilityServiceEnabled(): Boolean = a11y
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
}
