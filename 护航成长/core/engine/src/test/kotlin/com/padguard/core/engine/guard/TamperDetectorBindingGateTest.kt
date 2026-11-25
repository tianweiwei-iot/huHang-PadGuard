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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * 检测门控回归：**未绑定设备不得产生"默认桌面被改/网络代理"告警**。
 *
 * 背景：`HomeRoleDetector` / `EgressExposureDetector` 此前在 `detect()` 里无条件执行。
 * 未绑定设备没有 deviceId，事件先落本地队列；家长首次绑定后，这批历史事件会带着
 * 新 deviceId 一起上传 —— 家长刚绑完就看到「默认桌面已被改为第三方」的假告警。
 * 这两类检测只在"已绑定受管态"才有意义，必须由 `isDeviceBound` 门控。
 *
 * 用真实 [TamperDetector] + 假检测器验证**门控位置**，而不是扫源码文本：
 * 这样将来有人把调用移出 `if (isDeviceBound)` 会直接红。
 */
class TamperDetectorBindingGateTest {

    private var originalLoggerEnabled = true

    private class FakeProbe : GuardInfraProbe {
        override fun isAccessibilityServiceEnabled(): Boolean = true
        override fun isAccessibilityServiceAlive(): Boolean = true
        override fun isUsageAccessGranted(): Boolean = true
    }

    private class FakeHome(ctx: Context, admin: DeviceAdminBridge) : HomeRoleDetector(ctx, admin) {
        override fun detect(): List<TamperFinding> = listOf(
            TamperFinding(RiskType.HOME_CHANGED, RiskLevel.HIGH, mapOf("homePackage" to "com.other.launcher"))
        )
    }

    private class FakeEgress(ctx: Context) : EgressExposureDetector(ctx) {
        override fun detect(): List<TamperFinding> = listOf(
            TamperFinding(RiskType.PROXY_DETECTED, RiskLevel.HIGH, mapOf("proxy" to "127.0.0.1:8888"))
        )
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

    private fun detector(): TamperDetector {
        val context = Mockito.mock(Context::class.java)
        val admin = Mockito.mock(DeviceAdminBridge::class.java)
        Mockito.`when`(admin.refreshControlMode()).thenReturn(ControlMode.LEGACY)
        Mockito.`when`(admin.controlMode).thenReturn(MutableStateFlow(ControlMode.LEGACY))
        return TamperDetector(
            context = context,
            admin = admin,
            probe = FakeProbe(),
            timeProvider = TimeProvider(),
            bindIntegrity = BindIntegrityStore(),
            egress = FakeEgress(context),
            homeRole = FakeHome(context, admin)
        )
    }

    /** 关掉无关检测面，把断言隔离到绑定门控 */
    private fun policy() = SecurityPolicy(antiClockTamper = false, blockRoot = false)

    @Test
    fun `unbound device does not report home or proxy findings`() {
        val findings = detector().detect(policy(), SystemLockPolicy(), isDeviceBound = false)
        assertFalse(findings.any { it.type == RiskType.HOME_CHANGED })
        assertFalse(findings.any { it.type == RiskType.PROXY_DETECTED })
    }

    @Test
    fun `bound device reports home and proxy findings`() {
        val findings = detector().detect(policy(), SystemLockPolicy(), isDeviceBound = true)
        assertTrue(findings.any { it.type == RiskType.HOME_CHANGED })
        assertTrue(findings.any { it.type == RiskType.PROXY_DETECTED })
    }
}
