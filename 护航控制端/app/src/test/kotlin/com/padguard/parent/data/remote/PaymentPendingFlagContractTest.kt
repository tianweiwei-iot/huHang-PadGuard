package com.padguard.parent.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R41：「支付结果待确认」是状态转移标记，不是 UI 偏好。
 *
 * WXPayEntryActivity 冷启动时 markResultPending() 之后立刻 finish()，
 * 进程随时可能再次被回收。apply() 只写内存、异步刷盘，标记一旦丢失，
 * 订阅页 consumeResultPending() 返回 false，不会主动查单 ——
 * 用户看到"扣了钱却没开通"，且没有任何自助恢复入口。
 */
class PaymentPendingFlagContractTest {

    @Test
    fun `支付结果待确认标记的置位与消费都必须同步落盘`() {
        val src = stripComments(
            readText(
                "src/main/java/com/padguard/parent/data/remote/WeChatPayLauncher.kt",
                "app/src/main/java/com/padguard/parent/data/remote/WeChatPayLauncher.kt"
            )
        )
        assertTrue(
            "置位必须 commit()",
            src.contains("putBoolean(KEY_RESULT_PENDING, true).commit()")
        )
        assertTrue(
            "消费（清除）必须 commit()",
            src.contains("putBoolean(KEY_RESULT_PENDING, false).commit()")
        )
        assertFalse(
            "该标记禁止 apply()，进程在异步落盘前被杀会永久丢单",
            Regex("KEY_RESULT_PENDING[\\s\\S]{0,80}?\\.apply\\(\\)").containsMatchIn(src)
        )
    }

    /** 注释里会引用 apply() 作反例，扫描代码前先剥离，避免"注释命中"的假红。 */
    private fun stripComments(text: String): String = text
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
        .lines().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
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
