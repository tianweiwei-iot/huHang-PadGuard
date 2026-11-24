package com.padguard.core.engine

import com.padguard.core.data.model.RiskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态型篡改告警「边沿上报」契约（R31）。
 *
 * `PolicyEngine.scanTamper()` 是 TamperFinding → RiskEvent 的唯一出口。
 * 旧实现对每个 finding 无条件 `raise`，而服务端 `createFromEvent` 按 eventId 去重、
 * 不按类别去重：开发者选项开着 / Wi-Fi 挂代理 / debug 包装到已绑定设备 /
 * 凭据被清（CONTROL_LOST）都会**每轮自检新建一条 ACTIVE 告警**，
 * 家长端红点只增不减，正是「反复提示 / 一直提示」的直接来源。
 *
 * 这里锁死边沿语义：状态「从无到有」报一次、持续为真不再报、消失后再出现仍会报，
 * 且事件型行为记录绝不进白名单（家长要看次数）。
 */
class PolicyEngineTamperEdgeTest {

    @Test
    fun `状态型信号首次出现才上报`() {
        val fresh = PolicyEngine.freshStatefulTamper(
            prev = emptySet(),
            current = setOf(RiskType.DEVELOPER_OPTIONS_ENABLED.name)
        )
        assertEquals(setOf("DEVELOPER_OPTIONS_ENABLED"), fresh)
    }

    @Test
    fun `状态持续为真时不重复上报`() {
        val fresh = PolicyEngine.freshStatefulTamper(
            prev = setOf(RiskType.USB_DEBUG_ENABLED.name),
            current = setOf(RiskType.USB_DEBUG_ENABLED.name)
        )
        assertTrue("同一状态每轮自检都上报会把家长端告警刷满", fresh.isEmpty())
    }

    @Test
    fun `状态消失后再次出现仍会重新上报`() {
        val fresh = PolicyEngine.freshStatefulTamper(
            prev = emptySet(), // 上一轮已恢复，基线被清空
            current = setOf(RiskType.PROXY_DETECTED.name)
        )
        assertEquals(setOf("PROXY_DETECTED"), fresh)
    }

    @Test
    fun `多个状态型信号各自独立判定`() {
        val fresh = PolicyEngine.freshStatefulTamper(
            prev = setOf(RiskType.DEBUG_BUILD_ACTIVE.name),
            current = setOf(
                RiskType.DEBUG_BUILD_ACTIVE.name,
                RiskType.CONTROL_LOST.name
            )
        )
        assertEquals("只该上报新出现的那一项", setOf("CONTROL_LOST"), fresh)
    }

    @Test
    fun `事件型行为记录不进边沿白名单`() {
        val eventTypes = setOf(
            RiskType.UNINSTALL_ATTEMPT.name,
            RiskType.BLACKLIST_APP_LAUNCH.name,
            RiskType.BLOCKED_URL_ACCESS.name,
            RiskType.CLOCK_TAMPERING.name,
            RiskType.INVALID_SIGNATURE.name,
            RiskType.APP_INSTALLED.name,
            RiskType.APP_UNINSTALLED.name,
            RiskType.TIME_LIMIT_EXCEEDED.name
        )
        assertTrue(
            "事件型告警是行为记录，去重会让家长看不到次数：${eventTypes.intersect(PolicyEngine.STATEFUL_TAMPER_TYPES)}",
            eventTypes.intersect(PolicyEngine.STATEFUL_TAMPER_TYPES).isEmpty()
        )
        // 即使事件型出现在 current 里，也绝不产出边沿类别
        assertTrue(PolicyEngine.freshStatefulTamper(emptySet(), eventTypes).isEmpty())
    }

    @Test
    fun `已有自身闩锁的状态型不重复纳入边沿白名单`() {
        // 这些在 TamperDetector / HomeRoleDetector / GuardInfra 里已有各自的迟滞或边沿闩锁，
        // 再套一层会吞掉「恢复后再次掉失」的真实第二报。
        val selfLatched = setOf(
            RiskType.ROOT_DETECTED.name,
            RiskType.HOME_CHANGED.name,
            RiskType.ACCESSIBILITY_DISABLED.name,
            RiskType.USAGE_ACCESS_LOST.name
        )
        assertFalse(
            "检测层已有闩锁的项不应重复纳入上报层去重",
            selfLatched.intersect(PolicyEngine.STATEFUL_TAMPER_TYPES).isNotEmpty()
        )
    }

    @Test
    fun `边沿白名单五项全为合法 RiskType 且恰好覆盖无闩锁的持续型信号`() {
        val expected = setOf(
            "DEVELOPER_OPTIONS_ENABLED", "USB_DEBUG_ENABLED", "DEBUG_BUILD_ACTIVE",
            "PROXY_DETECTED", "CONTROL_LOST"
        )
        assertEquals(expected, PolicyEngine.STATEFUL_TAMPER_TYPES)
        val allNames = RiskType.values().mapTo(HashSet()) { it.name }
        assertTrue(
            "白名单出现不存在的 RiskType：${PolicyEngine.STATEFUL_TAMPER_TYPES - allNames}",
            PolicyEngine.STATEFUL_TAMPER_TYPES.all { it in allNames }
        )
    }
}
