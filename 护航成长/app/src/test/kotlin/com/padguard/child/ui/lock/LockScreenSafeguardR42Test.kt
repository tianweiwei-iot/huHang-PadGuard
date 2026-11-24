package com.padguard.child.ui.lock

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R42 锁屏保底链四缺口的源码契约（纯 JVM 扫描，Android 运行时依赖拿不到）。
 *
 * 这些路径真机难复现、回退却极易发生（少发一条 FSI / 少挂一个兜底定时器 / 一处早返回），
 * 因此把「结构不变量」本身锁死在测试里。
 */
class LockScreenSafeguardR42Test {

    @Test
    fun `非硬锁首次锁屏必须同时发FSI`() {
        val source = readText(
            "app/src/main/kotlin/com/padguard/child/ui/lock/LockScreenPresenter.kt",
            "src/main/kotlin/com/padguard/child/ui/lock/LockScreenPresenter.kt"
        )
        val nonHardLockBranch = source.substringAfter("if (!hardLockAvailable())")
            .substringBefore("// 有硬锁能力")
        assertTrue(
            "非硬锁分支必须走 attemptOnce（内含 FSI 通知），不能只 startActivity",
            "attemptOnce(detail)" in nonHardLockBranch
        )
    }

    @Test
    fun `申请框两条撤锁入口都必须武装回调丢失兜底`() {
        val source = readText(
            "app/src/main/kotlin/com/padguard/child/ui/lock/LockScreenActivity.kt",
            "src/main/kotlin/com/padguard/child/ui/lock/LockScreenActivity.kt"
        )
        val fallbackArm = "handler.postDelayed(requestDismissFallback, KEYGUARD_DISMISS_FALLBACK_MS)"
        assertTrue(
            "撤锁兜底定时器必须在两处入口各武装一次（onResume 抢先场景不能漏）",
            source.windowed(fallbackArm.length).count { it == fallbackArm } >= 2
        )
        val onResume = source.substringAfter("override fun onResume()")
            .substringBefore("override fun onPause()")
        assertTrue(
            "onResume 撤锁回调到达后必须撤掉兜底定时器",
            "handler.removeCallbacks(requestDismissFallback)" in onResume
        )
    }

    @Test
    fun `分屏自救预算用尽必须冷却重来而非彻底放弃`() {
        val source = readText(
            "app/src/main/kotlin/com/padguard/child/ui/lock/LockScreenActivity.kt",
            "src/main/kotlin/com/padguard/child/ui/lock/LockScreenActivity.kt"
        )
        val exhausted = source.substringAfter("if (multiWindowFixAttempts >= MAX_MULTI_WINDOW_EVICTIONS)")
            .substringBefore("multiWindowFixAttempts++")
        assertTrue(
            "预算用尽后必须挂冷却重来，singleTask 下看门狗反复 show() 并不能退出分屏",
            "handler.postDelayed(multiWindowRetryRunnable, MULTI_WINDOW_RETRY_MS)" in exhausted
        )
        assertTrue(
            "冷却 Runnable 必须重置预算再自救",
            "multiWindowFixAttempts = 0" in source.substringAfter("private val multiWindowRetryRunnable")
                .substringBefore("override fun onCreate")
        )
    }

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
