package com.padguard.child.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 操作闸门豁免窗口的「持有者」契约（R34）。
 *
 * 背景：家长在「授权总览 / 权限引导」补开页点「一键补齐」→ 置 reauthRequired →
 * MainActivity 入口分流同一帧切到 OneTimeConsentScreen。旧页 `DisposableEffect.onDispose`
 * 无条件 `GuardGateSuspension.clear()`，而流水线刚为第一项设置页调用 `suspendFor()`；
 * 两者无先后顺序保证，clear 后执行就会抹掉刚开的窗口，家长被带去无障碍设置页时
 * 立刻被闸门顶回（「设置页闪一下就回来、开关拨不动」）。
 *
 * 这里既测纯逻辑（非持有者不得清别人的窗口），也用源码扫描钉死三处接线：
 * 补开页两处 suspendFor 必须带 owner、endSettingsSession 必须用带 owner 的 clear、
 * 流水线 finish 必须用 clearAll 无条件收口。
 */
class GuardGateOwnershipContractTest {

    @Test
    fun `补开页离场不得清掉流水线刚开的窗口`() {
        val gate = GuardGateSuspension()
        var clock = 1_000L
        gate.elapsedNow = { clock }

        gate.suspendFor(60_000L, GuardGateSuspension.OWNER_PERMISSION_GUIDE)
        // 流水线接手并打开系统设置页，窗口登记到流水线名下
        gate.suspendFor(60_000L, GuardGateSuspension.OWNER_CONSENT)
        // 旧页 onDispose 后执行
        gate.clear(GuardGateSuspension.OWNER_PERMISSION_GUIDE)

        assertTrue(
            "非持有者的 clear 抹掉了流水线刚开的豁免窗口，家长会被闸门从设置页顶回",
            gate.isActive()
        )
        clock += 59_999L
        assertTrue("窗口仍应按单调钟自愈", gate.isActive())
        clock += 1L
        assertFalse("到点必须自动恢复拦截", gate.isActive())
    }

    @Test
    fun `持有者可以清掉自己开的窗口`() {
        val gate = GuardGateSuspension()
        gate.elapsedNow = { 5_000L }
        gate.suspendFor(60_000L, GuardGateSuspension.OWNER_PERMISSION_GUIDE)
        assertTrue(gate.isActive())
        gate.clear(GuardGateSuspension.OWNER_PERMISSION_GUIDE)
        assertFalse(gate.isActive())
    }

    @Test
    fun `流水线 finish 的 clearAll 无条件收口`() {
        val gate = GuardGateSuspension()
        gate.elapsedNow = { 5_000L }
        // 窗口登记在补开页名下（例如家长从补开页直接开设置页后流水线结束）
        gate.suspendFor(60_000L, GuardGateSuspension.OWNER_PERMISSION_GUIDE)
        gate.clearAll()
        assertFalse("授权收尾必须无条件恢复拦截，不能留下最长 10 分钟的设置页豁免", gate.isActive())
    }

    @Test
    fun `补开页先清再让流水线开窗不互相踩踏`() {
        val gate = GuardGateSuspension()
        gate.elapsedNow = { 5_000L }
        gate.suspendFor(60_000L, GuardGateSuspension.OWNER_PERMISSION_GUIDE)
        gate.clear(GuardGateSuspension.OWNER_PERMISSION_GUIDE) // 旧页 onDispose 先执行
        assertFalse(gate.isActive())
        gate.suspendFor(60_000L) // 流水线随后开窗口（默认 owner）
        assertTrue("流水线新开的窗口必须生效", gate.isActive())
    }

    @Test
    fun `默认 owner 行为与既有调用保持一致`() {
        val gate = GuardGateSuspension()
        gate.elapsedNow = { 5_000L }
        gate.suspendFor(60_000L)
        gate.clear()
        assertFalse("流水线用无参 suspendFor/clear 的既有语义不得被破坏", gate.isActive())
    }

    // ==================== 源码接线契约 ====================

    @Test
    fun `补开页两处 suspendFor 与 endSettingsSession 必须带持有者`() {
        val guide = readSource("permission/PermissionGuideViewModel.kt")
        val ownerCalls = Regex(
            """suspendFor\(\s*SETTINGS_GATE_SUSPEND_MS,\s*GuardGateSuspension\.OWNER_PERMISSION_GUIDE"""
        ).findAll(guide).count()
        assertEquals(
            "openAppDetails / openSettingsFor 两处跳转都必须登记补开页持有者，" +
                "漏一处就会在切流水线时把别人的豁免窗口清掉",
            2,
            ownerCalls
        )
        assertTrue(
            "endSettingsSession 必须用带 owner 的 clear",
            guide.contains("gateSuspension.clear(GuardGateSuspension.OWNER_PERMISSION_GUIDE)")
        )
        assertFalse(
            "补开页不得出现无参 clear()",
            guide.contains("gateSuspension.clear()")
        )
    }

    @Test
    fun `流水线收尾必须用 clearAll 且不再出现带 owner 的 clear`() {
        val consent = readSource("consent/OneTimeConsentViewModel.kt")
        assertTrue(
            "OneTimeConsentViewModel.finish() 必须无条件收口",
            consent.contains("gateSuspension.clearAll()")
        )
        assertFalse(
            "流水线 finish 用带 owner 的 clear 会认不出当前持有者，残留 10 分钟豁免",
            consent.contains("gateSuspension.clear()")
        )
    }

    private fun readSource(relativeUnderChild: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            "src/main/kotlin/com/padguard/child/$relativeUnderChild",
            "护航成长/app/src/main/kotlin/com/padguard/child/$relativeUnderChild"
        )
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isFile) return direct.readText()
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir!!, relative)
                if (candidate.isFile) return candidate.readText()
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到源码文件：$relativeUnderChild；cwd=$cwd")
    }
}
