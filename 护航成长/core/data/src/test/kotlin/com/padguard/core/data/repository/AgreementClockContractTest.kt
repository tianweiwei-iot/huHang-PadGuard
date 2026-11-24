package com.padguard.core.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R38：协议签署时刻的时间源契约（纯 JVM 源码扫描）。
 *
 * 原实现 `signedAt = System.currentTimeMillis()` 与日志 payload 里
 * `"signedAt" to System.currentTimeMillis().toString()` 是**两次独立取值**：
 * 一是孩子改系统时间即可伪造"何时同意授权"这一合规凭证；
 * 二是两次调用跨毫秒时 DB 与上报日志的签署时刻对不上。
 * 修复后一次 `timeProvider.now()` 同时供 DB、payload 与日志 timestamp 使用。
 */
class AgreementClockContractTest {

    @Test
    fun `协议签署时刻必须单次取自受信钟`() {
        val src = stripComments(
            readText(
                "src/main/kotlin/com/padguard/core/data/repository/AgreementRepository.kt",
                "core/data/src/main/kotlin/com/padguard/core/data/repository/AgreementRepository.kt"
            )
        )
        assertFalse("合规凭证禁止裸墙钟", src.contains("System.currentTimeMillis()"))
        assertTrue("必须注入 TimeProvider", src.contains("private val timeProvider: TimeProvider"))
        assertTrue("signedAt 必须取受信钟", src.contains("val signedAt = timeProvider.now()"))
        assertTrue("日志 payload 必须复用同一次取值", src.contains("\"signedAt\" to signedAt.toString()"))
        assertTrue("日志 timestamp 必须与 DB signedAt 同源", src.contains("timestamp = signedAt"))
    }

    /** 注释里会引用旧实现作反例，扫描代码前先剥离，避免"注释命中"的假红。 */
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
