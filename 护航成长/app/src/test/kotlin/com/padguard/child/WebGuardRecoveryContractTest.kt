package com.padguard.child

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Web 过滤隧道「恢复判据」与「告警上报」的源码契约（R32）。
 *
 * 已实际发生的缺陷：`watchdogWebGuard()` 用
 * `if (state != START_BLOCKED) { 关掉 WEB_GUARD_DOWN 告警; return }`
 * 反推恢复。`SyncState` 有四个值，其中 `NEED_CONSENT`（VPN 授权被撤 / 从未授予，
 * 隧道压根没起、域名过滤完全失效）**同样是失败态**，却被当成"隧道已就位"：
 * 既不上报，还会把家长端已有的「网页防护掉了」告警关掉 ——
 * 防护失效的同时告诉家长已修好，属于最坏的一类 fail-open 静默。
 *
 * 纯 JVM 单测拿不到 VpnService / system_server 授权状态，源码契约比行为测试更抗重构：
 * - 恢复只认 `RUNNING`（或家长主动关过滤的 `OFF`）；
 * - 枚举里除这两个之外的**每一个**失败态都必须在看门狗里被显式处理，
 *   以后新增 SyncState 值却忘了接线会当场红。
 */
class WebGuardRecoveryContractTest {

    private val guardService = readText(
        "app/src/main/kotlin/com/padguard/child/service/GuardService.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardService.kt"
    )
    private val controller = readText(
        "app/src/main/kotlin/com/padguard/child/guard/WebGuardVpnController.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/guard/WebGuardVpnController.kt"
    )
    private val watchdog: String = guardService
        .substringAfter("private suspend fun watchdogWebGuard()", "")
        .substringBefore("private fun clearWebGuardDownAlert")

    @Test
    fun `恢复只认 RUNNING 或 OFF 不得用反向判据糊弄`() {
        assertTrue("没有显式判定 SyncState.RUNNING", watchdog.contains("SyncState.RUNNING"))
        assertTrue("家长主动关闭过滤的 OFF 也该算恢复", watchdog.contains("SyncState.OFF"))
        assertFalse(
            "仍有 `state != START_BLOCKED` 这种反向判据，会把 NEED_CONSENT 等失败态误当恢复",
            watchdog.contains("state != com.padguard.child.guard.WebGuardVpnController.SyncState.START_BLOCKED")
        )
    }

    @Test
    fun `所有失败态都必须显式处理 新增 SyncState 值不得漏接`() {
        val states = syncStates()
        assertTrue("SyncState 解析失败，源码结构已变", states.isNotEmpty())
        val failing = states - setOf("OFF", "RUNNING")
        assertTrue("SyncState 至少有两类失败态", failing.size >= 2)
        for (state in failing) {
            assertTrue(
                "失败态 $state 没有在 watchdogWebGuard 里被显式提及 —— " +
                    "隧道没跑却可能被当成恢复并关掉告警",
                watchdog.contains(".$state")
            )
        }
    }

    @Test
    fun `授权缺失与启动被拒都要上报 且原因可区分`() {
        assertTrue("没有处理 NEED_CONSENT", watchdog.contains("SyncState.NEED_CONSENT"))
        assertTrue("没有处理 START_BLOCKED", watchdog.contains("START_BLOCKED"))
        assertTrue("缺少 VPN 授权缺失的 reason", watchdog.contains("\"vpn_consent_missing\""))
        assertTrue("缺少启动被拒的 reason", watchdog.contains("\"restart_blocked\""))
        assertTrue(
            "上报没有把 reason 带进事件详情，家长端无法区分两类故障",
            watchdog.contains("mapOf(\"reason\" to reason)")
        )
    }

    @Test
    fun `关闭告警只在恢复分支 且必须先于失败态上报`() {
        val clearAt = watchdog.indexOf("clearWebGuardDownAlert()")
        val reasonAt = watchdog.indexOf("val reason =")
        assertTrue("恢复分支没有关闭 WEB_GUARD_DOWN 告警", clearAt >= 0)
        assertTrue("失败态没有上报分支", reasonAt >= 0)
        assertTrue(
            "关告警的代码出现在失败态上报之后，说明恢复/失败分支顺序颠倒",
            clearAt < reasonAt
        )
    }

    @Test
    fun `上报仍受十分钟节流与已上报边沿保护`() {
        assertTrue(
            "丢了 10 分钟节流窗，两种失败态会在 30 秒看门狗里刷屏",
            watchdog.contains("WEB_GUARD_DOWN_ALARM_THROTTLE_MS")
        )
        assertTrue("丢了 webGuardDownRaised 边沿标记", watchdog.contains("webGuardDownRaised = true"))
    }

    @Test
    fun `always-on 系统锁记账必须同步落盘 否则关不掉 VPN`() {
        // KEY_ALWAYS_ON 是"本端确实给系统挂上 always-on 锁"的记账，决定将来
        // disableAlwaysOn 是否去清除它。用 apply() 时若设锁后进程立即被杀、落盘丢失，
        // 守卫会误判"没设过"直接 return —— 孩子端永久留一个关不掉的系统级 VPN。
        assertTrue(
            "enableAlwaysOn 的置位丢了 commit()",
            controller.contains("putBoolean(KEY_ALWAYS_ON, true).commit()")
        )
        assertTrue(
            "disableAlwaysOn 的清除丢了 commit()（与置位侧不对称）",
            controller.contains("putBoolean(KEY_ALWAYS_ON, false).commit()")
        )
        assertFalse(
            "KEY_ALWAYS_ON 是状态转移记账，禁止用 apply() 异步落盘",
            Regex("putBoolean\\(KEY_ALWAYS_ON,\\s*(true|false)\\)\\.apply\\(\\)")
                .containsMatchIn(controller)
        )
    }

    @Test
    fun `锁定 VPN 设置失败必须可见 不得静默吞掉返回值`() {
        // DISALLOW_CONFIG_VPN 没设上时，VPN 虽成了 always-on，孩子仍能在系统设置里关掉它，
        // 上网管控静默失效。返回值被丢弃会让日志里看不到这次降级。
        assertTrue(
            "DISALLOW_CONFIG_VPN 的返回值未被检查，设锁失败会静默降级",
            Regex("val restriction = admin\\.setUserRestriction\\(\\s*UserManager\\.DISALLOW_CONFIG_VPN,\\s*true\\s*\\)")
                .containsMatchIn(controller)
        )
        assertTrue(
            "锁定失败没有 warn 日志，家长/日志看不见这次降级",
            controller.contains("DISALLOW_CONFIG_VPN failed") && controller.contains("restriction.isOk")
        )
    }

    // ==================== 源码解析 ====================

    /** 从 `enum class SyncState` 块内提取枚举名（跳过 KDoc 行）。 */
    private fun syncStates(): Set<String> = controller
        .substringAfter("enum class SyncState {", "")
        .substringBefore("}")
        .lines()
        .map { it.trim().trimEnd(',') }
        .filter { it.matches(Regex("[A-Z_]+")) }
        .toSet()

    private fun readText(vararg candidates: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
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
        throw AssertionError("找不到源码文件：${candidates.joinToString()}；cwd=$cwd")
    }
}
