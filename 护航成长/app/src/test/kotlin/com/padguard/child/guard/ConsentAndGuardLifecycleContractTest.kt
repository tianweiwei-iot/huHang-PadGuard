package com.padguard.child.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-23「授权不掉线 + 撤回同意真停采」的源码契约。
 *
 * 这些分支无法在纯 JVM 单测里跑起 ForegroundService / NotificationManager，
 * 但它们是历史上反复回退的命脉（注释写对了、实现却没做），因此用源码扫描钉死。
 */
class ConsentAndGuardLifecycleContractTest {

    @Test
    fun `full screen intent 权限缺失时必须降级为普通高优通知而不是静默放弃`() {
        val src = read("app/src/main/kotlin/com/padguard/child/guard/A11yRebindNotifier.kt")
        assertFalse(
            "旧实现在无 USE_FULL_SCREEN_INTENT 时直接 return false：设备端零提示、节流也不落，每轮自检静默重试",
            src.contains("a11y rebind guide cannot be pulled by system")
        )
        assertTrue(src.contains("fullScreenIntentAllowed"))
        assertTrue(src.contains(".setContentIntent(pi)"))
        assertTrue(src.contains("if (fullScreenIntentAllowed) setFullScreenIntent(pi, true)"))
    }

    @Test
    fun `被清理判定必须先于心跳落盘 且前台通知先于一切耗时启动`() {
        val src = read("app/src/main/kotlin/com/padguard/child/service/GuardService.kt")
        val onCreate = src.substringAfter("override fun onCreate()")
            .substringBefore("override fun onStartCommand")
        val foreground = onCreate.indexOf("enterForeground()")
        val judge = onCreate.indexOf("cleanKillDetector.judgeOnProcessStart")
        val alive = onCreate.indexOf("GuardHeartbeat.writeGuardAlive")
        assertTrue("onCreate 必须进入前台以保住 5 秒宽限期", foreground >= 0)
        assertTrue("必须有被清理判定", judge >= 0)
        assertTrue("必须写首条心跳", alive >= 0)
        assertTrue("judgeOnProcessStart 必须在 writeGuardAlive 之前，顺序反了永远判不出被清理", judge < alive)
        assertTrue("enterForeground 必须早于被清理判定等耗时启动", foreground < judge)
    }

    @Test
    fun `撤回同意时所有即时采集指令都在执行前被闸门拦下`() {
        val src = read("app/src/main/kotlin/com/padguard/child/service/GuardService.kt")
        val start = src.indexOf("private suspend fun handleEffect")
        assertTrue("找不到 handleEffect", start >= 0)
        val body = src.substring(start)
        val end = body.indexOf("\n    private suspend fun consentRevoked")
        val effectBody = if (end > 0) body.substring(0, end) else body
        val actions = listOf(
            "CaptureScreenshot", "CapturePhoto", "RequestLocation",
            "StartScreenRecord", "StartLiveView", "StartAudioRecord"
        )
        actions.forEach { name ->
            val branch = effectBody.indexOf("is EngineEffect.$name ->")
            assertTrue("找不到 $name 分支", branch >= 0)
            val gate = effectBody.indexOf("consentRevoked(", branch)
            assertTrue("$name 分支必须先判 consentRevoked 再采集", gate > branch && gate - branch < 240)
        }
    }

    private fun read(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(8) { depth ->
            val candidate = dir?.let { File(it, relative) }
            if (candidate?.isFile == true) return candidate.readText()
            dir = dir?.parentFile ?: return@repeat
            if (depth == 7) Unit
        }
        throw AssertionError("找不到源码 $relative，cwd=${System.getProperty("user.dir")}")
    }
}
