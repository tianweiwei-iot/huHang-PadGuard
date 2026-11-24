package com.padguard.core.engine.enforcer

import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.MonitoringPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PIPL 撤回同意的端上闸门：策略包里的 consentRevoked 必须让所有采集项停摆，
 * 但**心跳不能停**——守卫掉线就再也收不到"恢复同意"的新策略包了。
 */
class MonitoringEnforcerConsentTest {

    private val enforcer = MonitoringEnforcer()

    init {
        // 纯 JVM 单测没有 android.util.Log 的 mock 实现，门面上关日志即可（只影响本测试进程）。
        Logger.enabled = false
    }

    @Test
    fun `consent revoked stops every collector but keeps heartbeat`() {
        enforcer.apply(
            MonitoringPolicy(
                heartbeatIntervalSec = 30,
                logUploadIntervalSec = 300,
                locationIntervalSec = 300,
                screenshotIntervalSec = 600,
                collectAppUsage = true,
                collectUrlHistory = true,
                collectHardware = true,
                consentRevoked = true
            )
        )

        val runtime = enforcer.current()
        assertTrue(runtime.consentRevoked)
        assertEquals(0, runtime.locationIntervalSec)
        assertEquals(0, runtime.screenshotIntervalSec)
        assertFalse(runtime.collectAppUsage)
        assertFalse(runtime.collectUrlHistory)
        assertFalse(runtime.collectHardware)
        assertEquals(30, runtime.heartbeatIntervalSec)
    }

    @Test
    fun `old policy packages without the field keep collecting`() {
        enforcer.apply(
            MonitoringPolicy(
                heartbeatIntervalSec = 30,
                locationIntervalSec = 300,
                collectAppUsage = true
            )
        )

        val runtime = enforcer.current()
        assertFalse(runtime.consentRevoked)
        assertTrue(runtime.collectAppUsage)
        assertEquals(300, runtime.locationIntervalSec)
    }
}
