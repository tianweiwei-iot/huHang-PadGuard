package com.padguard.parent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R44：账号切换（登出 / 换账号登录）时，Activity 作用域的三个消息中心 VM 与单例仓储
 * 必须清空上一账号的会话态。
 *
 * 这三个 VM 由导航根 Composable 的 hiltViewModel() 创建，作用域 = Activity；账号切换
 * 只导航、不重建 Activity，若不显式清空，B 在首次轮询回来前（10 秒，弱网更久）会看到
 * A 的工单/告警记录、铃铛红点，甚至弹出 A 的申请 —— 跨账号隐私残留。
 *
 * 都是 UI 生命周期时序，单测难稳定复现；用源码契约锁定结构，防后续重构回退。
 */
class SessionSwitchResetR44Test {

    @Test
    fun `三个消息中心VM都提供会话切换重置并清空去重集合`() {
        val cases = listOf(
            "UnlockRequestCenterViewModel.kt" to listOf("handledInSession", "failedPopupCooldown", "notifiedTicketIds", "UnlockCenterUiState()"),
            "UsageDelayCenterViewModel.kt" to listOf("handledInSession", "notifiedTicketIds", "UsageDelayCenterUiState()"),
            "RiskAlertCenterViewModel.kt" to listOf("notifiedAlertIds", "RiskAlertUiState()")
        )
        cases.forEach { (file, tokens) ->
            val src = read("src/main/java/com/padguard/parent/presentation/viewmodel/$file")
            val start = src.indexOf("fun resetForSessionChange()")
            assertTrue("$file 缺少会话切换重置方法", start >= 0)
            val body = src.substring(start, src.indexOf("\n    }", start))
            tokens.forEach { token ->
                assertTrue("$file 的 resetForSessionChange 必须清理 $token", token in body)
            }
            assertTrue("$file 重置必须复位刷新闸门", "refreshGate.reset()" in body)
        }
    }

    // 「刷新闸门提供 reset 出口」的旧守卫（扫 "pending.set(false)"/"inFlight.set(false)" 源码文本）
    // 已移除：RefreshGate 是纯 JVM 类，其行为契约（单飞/补一次/reset 清两标志）现由
    // RefreshGateBehaviorTest 用真实函数调用断言。文本扫描无法发现 compareAndSet 逻辑被改坏。

    @Test
    fun `会话门控在用户id变化时调用三个VM重置`() {
        val src = read("src/main/java/com/padguard/parent/presentation/ui/PadGuardNavigation.kt")
        val start = src.indexOf("LaunchedEffect(user?.id)")
        assertTrue("导航根缺少以 user id 为键的会话切换 effect", start >= 0)
        val body = src.substring(start, src.indexOf("\n    }", start))
        listOf("unlockVm.resetForSessionChange()", "delayVm.resetForSessionChange()", "riskVm.resetForSessionChange()")
            .forEach { assertTrue("会话切换必须调用 $it", it in body) }
    }

    @Test
    fun `换账号登录必须清空整机管控开关单例缓存`() {
        val src = read("src/main/java/com/padguard/parent/data/remote/ApiDataSource.kt")
        val start = src.indexOf("private fun resetSessionCaches()")
        assertTrue("缺少会话缓存重置", start >= 0)
        val body = src.substring(start, src.indexOf("\n    }", start))
        assertTrue("必须清空设备列表", "_deviceListFlow.value = emptyList()" in body)
        assertTrue("必须清空以 deviceId 为键的整机管控开关缓存", "_managementActive.value = emptyMap()" in body)
    }

    private fun read(relative: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir: File? = cwd
        repeat(6) {
            if (dir == null) return@repeat
            val candidate = File(dir!!, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir!!.parentFile
        }
        throw AssertionError("找不到源码文件：$relative；cwd=$cwd")
    }
}
