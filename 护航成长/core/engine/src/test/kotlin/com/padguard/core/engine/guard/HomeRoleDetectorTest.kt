package com.padguard.core.engine.guard

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito
import java.io.File

/**
 * [HomeRoleDetector]（P1-2 非 DO 第三方桌面检测+夺回）的 JVM 行为测试。
 *
 * 覆盖：
 * - 默认桌面是自身 → 不报、不抢；
 * - 默认桌面被换第三方 → 报 HOME_CHANGED(HIGH)，且按降级态决定是否夺回；
 * - 无默认桌面（resolveActivity 返回 null）→ 静默；
 * - 迟滞：同一第三方桌面只报一次，再次扫描保持静默。
 *
 * JVM rig：Context/PackageManager/DeviceAdminBridge 均为 final，Mockito 5 inline mockmaker 直接 mock；
 * JVM 上 Build.VERSION.SDK_INT = 0，走 deprecated 的 resolveActivity(intent, int) 分支；Logger 关闭。
 */
class HomeRoleDetectorTest {

    private var originalLoggerEnabled = true
    private lateinit var context: Context
    private lateinit var pm: PackageManager
    private lateinit var admin: DeviceAdminBridge

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
        context = Mockito.mock(Context::class.java)
        pm = Mockito.mock(PackageManager::class.java)
        admin = Mockito.mock(DeviceAdminBridge::class.java)
        Mockito.`when`(context.packageName).thenReturn("com.padguard.child")
        Mockito.`when`(context.packageManager).thenReturn(pm)
        // 默认 reclaim 路径的 queryIntentActivities 返回空：夺回分支会提前 return，
        // 既验证"尝试夺回"又不依赖 startActivity 的真实行为。
        Mockito.`when`(pm.queryIntentActivities(any(Intent::class.java), anyInt()))
            .thenReturn(emptyList())
    }

    @After
    fun tearDown() {
        Logger.enabled = originalLoggerEnabled
    }

    private fun detector(): HomeRoleDetector = TestableHomeRoleDetector(context, admin)

    /**
     * 测试子类：覆盖两处 Intent 构造为 mock，绕开 android.jar 在 JVM 单测下的 "not mocked" 限制，
     * 让检测逻辑（含迟滞、DO/非DO 分支）可在纯 JVM 验证，无需 Robolectric。
     */
    private class TestableHomeRoleDetector(
        context: Context,
        admin: DeviceAdminBridge
    ) : HomeRoleDetector(context, admin) {
        override fun homeIntent(): Intent {
            val m = Mockito.mock(Intent::class.java)
            Mockito.`when`(m.addCategory(Mockito.anyString())).thenReturn(m)
            return m
        }

        override fun reclaimIntent(own: String): Intent {
            val m = Mockito.mock(Intent::class.java)
            Mockito.`when`(m.addCategory(Mockito.anyString())).thenReturn(m)
            Mockito.`when`(m.setPackage(Mockito.anyString())).thenReturn(m)
            Mockito.`when`(m.setComponent(Mockito.any())).thenReturn(m)
            Mockito.`when`(m.addFlags(Mockito.anyInt())).thenReturn(m)
            return m
        }

        // JVM 上 android.jar 的 SystemClock 是 not-mocked 桩，改用等价的 JVM 单调钟。
        override fun monotonicNow(): Long = System.nanoTime() / 1_000_000L
    }

    private fun resolveInfo(pkg: String): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.MainActivity"
        }
    }

    @Test
    fun `default home is self - no finding, no reclaim`() {
        Mockito.`when`(pm.resolveActivity(any(Intent::class.java), anyInt()))
            .thenReturn(resolveInfo("com.padguard.child"))
        Mockito.`when`(admin.can(Capability.LOCK_TASK)).thenReturn(false)

        val findings = detector().detect()

        assertTrue(findings.isEmpty())
        Mockito.verify(pm, Mockito.never()).queryIntentActivities(any(Intent::class.java), anyInt())
    }

    @Test
    fun `default home hijacked by third-party, non-DO - reports HOME_CHANGED and attempts reclaim`() {
        Mockito.`when`(pm.resolveActivity(any(Intent::class.java), anyInt()))
            .thenReturn(resolveInfo("com.other.launcher"))
        Mockito.`when`(admin.can(Capability.LOCK_TASK)).thenReturn(false) // 非 DO / 降级态

        val findings = detector().detect()

        assertEquals(1, findings.size)
        assertEquals(RiskType.HOME_CHANGED, findings[0].type)
        assertEquals(RiskLevel.HIGH, findings[0].level)
        // 降级态下应尝试夺回：queryIntentActivities 被调用（内部再决定 startActivity）
        Mockito.verify(pm).queryIntentActivities(any(Intent::class.java), anyInt())
    }

    @Test
    fun `default home hijacked, DO mode - reports but never reclaims`() {
        Mockito.`when`(pm.resolveActivity(any(Intent::class.java), anyInt()))
            .thenReturn(resolveInfo("com.other.launcher"))
        Mockito.`when`(admin.can(Capability.LOCK_TASK)).thenReturn(true) // DO 态

        val findings = detector().detect()

        assertEquals(1, findings.size)
        assertEquals(RiskType.HOME_CHANGED, findings[0].type)
        // DO 态系统已强制常驻，不应主动夺回焦点
        Mockito.verify(pm, Mockito.never()).queryIntentActivities(any(Intent::class.java), anyInt())
    }

    @Test
    fun `no default home resolver - silent`() {
        Mockito.`when`(pm.resolveActivity(any(Intent::class.java), anyInt())).thenReturn(null)
        Mockito.`when`(admin.can(Capability.LOCK_TASK)).thenReturn(false)

        val findings = detector().detect()

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `same hijacker only reported once - hysteresis`() {
        Mockito.`when`(pm.resolveActivity(any(Intent::class.java), anyInt()))
            .thenReturn(resolveInfo("com.other.launcher"))
        Mockito.`when`(admin.can(Capability.LOCK_TASK)).thenReturn(false)

        val d = detector()
        val first = d.detect()
        assertEquals(1, first.size)
        assertEquals(RiskType.HOME_CHANGED, first[0].type)

        // 第二次扫描仍是同一个第三方桌面 → 迟滞，不再报
        val second = d.detect()
        assertTrue(second.isEmpty())
    }

    @Test
    fun `夺回去抖必须使用单调钟而非裸墙钟`() {
        val src = File("src/main/kotlin/com/padguard/core/engine/guard/HomeRoleDetector.kt").readText()
        assertFalse(
            "回拨系统时间会让夺回去抖窗口永不达标，非 DO 态换桌面逃逸只能告警不能拉回",
            src.contains("System.currentTimeMillis()")
        )
        assertTrue(
            "去抖时钟必须是单调钟（SystemClock.elapsedRealtime）",
            src.contains("SystemClock.elapsedRealtime()")
        )
    }

    @Test
    fun `switching to a different hijacker re-reports`() {
        Mockito.`when`(admin.can(Capability.LOCK_TASK)).thenReturn(false)
        Mockito.`when`(pm.resolveActivity(any(Intent::class.java), anyInt()))
            .thenReturn(resolveInfo("com.other.launcher"))

        val d = detector()
        val first = d.detect()
        assertEquals(1, first.size)

        // 换成另一个第三方桌面 → 应再次上报
        Mockito.`when`(pm.resolveActivity(any(Intent::class.java), anyInt()))
            .thenReturn(resolveInfo("com.another.launcher"))
        val second = d.detect()
        assertEquals(1, second.size)
        assertEquals(RiskType.HOME_CHANGED, second[0].type)
        @Suppress("UNCHECKED_CAST")
        val detail = second[0].detail as? Map<String, String>
        assertFalse(detail?.get("homePackage")?.contains("com.other.launcher") ?: true)
    }
}
