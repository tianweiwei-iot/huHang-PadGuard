package com.padguard.child.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 解绑撤权闭环的接线契约（纯 JVM 源码扫描，不触碰 Android 运行时）。
 *
 * `handleRemoteUnbind()` 的职责是把设备上所有管控痕迹归还：投影令牌、采集同意、
 * VPN、无障碍启用名单。其中 [AccessibilityCapabilityHealer.disable] 是 suspend，
 * 旧实现用 `scope.launch { ... }` fire-and-forget，紧接着 `stopSelf()` 触发
 * `onDestroy { scope.cancel() }`；若 disable 正卡在互斥锁上，写 secure settings
 * 之前就被取消，系统无障碍开关残留启用——设备转手后仍保留本应用的采集/拦截能力。
 *
 * 本测试锁定「撤权必须在 stopSelf 之前同步完成」，防止回退成 fire-and-forget。
 */
class UnbindRevocationContractTest {

    @Test
    fun `解绑必须同步等待无障碍撤权完成后再 stopSelf`() {
        val src = guardService()
        val unbind = src.substringAfter("private suspend fun handleRemoteUnbind()")
            .substringBefore("// ==================== 工具 ====================")

        assertFalse(
            "禁止 fire-and-forget：scope.launch 起的撤权协程会被紧随的 stopSelf()->scope.cancel() 中断",
            unbind.contains("scope.launch { runCatching { a11yCapabilityHealer.disable() }")
        )
        assertTrue(
            "必须在解绑函数内直接 runCatching { a11yCapabilityHealer.disable() } 等待落地",
            Regex("runCatching \\{ a11yCapabilityHealer\\.disable\\(\\) \\}").containsMatchIn(unbind)
        )
        assertTrue(
            "撤权必须发生在 GuardLink.releaseAll/stopSelf 之前",
            unbind.indexOf("a11yCapabilityHealer.disable()") < unbind.indexOf("GuardLink.releaseAll")
        )
        val onDestroy = src.substringAfter("override fun onDestroy()")
            .substringBefore("// ==================== 启动流程 ====================")
        assertTrue(
            "onDestroy 仍会 cancel scope：这是 fire-and-forget 撤权被吞的根因，测试前提不能漂移",
            onDestroy.contains("scope.cancel()")
        )
    }

    private fun guardService() = readText(
        "src/main/kotlin/com/padguard/child/service/GuardService.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardService.kt"
    )

    /** Gradle cwd 可能是模块目录或仓库根，逐级向上找，避免 IDE / 命令行差异造成假红。 */
    private fun readText(vararg candidates: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isFile) return direct.readText()
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir, relative)
                if (candidate.isFile) return candidate.readText()
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到源码文件：${candidates.joinToString()}；cwd=$cwd")
    }
}
