package com.padguard.core.transport.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R43b：证书固定 TOFU 落盘两处修复的契约回归（纯 JVM 源码扫描）。
 *
 * [CertPinTrustManager] 构造依赖系统 TrustManagerFactory、校验证书链依赖真实 X509 链，
 * JVM 单测无法低成本直构；这里以源码扫描钉住两个 fail-open 点，防止悄悄回退：
 *
 * 1. **TOFU 文件损坏必须 fail-closed**：旧 `getOrDefault(emptyMap())` 把"文件存在但解析失败"
 *    与"首次安装"混为一谈 → 落到"首次信任"分支，任意经系统 CA 的证书（含用户导入的 MITM
 *    根 CA）都被无条件记录为可信，TOFU 形同虚设。
 * 2. **TOFU 落盘必须原子写**：旧 `writeText` 直接覆写，证书轮换 re-pin 写到一半进程被杀 →
 *    半截文件 → 触发 1 的损坏分支。改为同目录 tmp + rename（与 JsonLinesFile 纪律一致）。
 */
class CertPinContractTest {

    private fun source(): String = readText(
        "src/main/kotlin/com/padguard/core/transport/security/CertPin.kt",
        "core/transport/src/main/kotlin/com/padguard/core/transport/security/CertPin.kt"
    )

    /** 去掉行注释与块注释，避免断言命中注释文字本身。 */
    private fun String.withoutComments(): String =
        replace(Regex("/\\*([\\s\\S]*?)\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `TOFU 损坏必须置位 fail-closed 而非静默空 map`() {
        val src = source().withoutComments()
        assertTrue(
            "loadTofu 读取失败必须置 tofuCorrupt 标志（fail-closed），" +
                "否则文件损坏会被当作首次信任，TOFU 被绕过（回退即事故）",
            src.contains("tofuCorrupt = true")
        )
        assertFalse(
            "禁止 getOrDefault(emptyMap()/mutableMapOf()) 静默回落空 map —— 那与首次安装不可区分",
            Regex("getOrDefault\\((emptyMap|mutableMapOf)\\(\\)\\)").containsMatchIn(src)
        )
        assertTrue(
            "checkServerTrusted 必须在 TOFU 分支开头因 tofuCorrupt 抛 CertificateException",
            src.contains("if (tofuCorrupt)") &&
                Regex("if \\(tofuCorrupt\\)[\\s\\S]{0,120}throw CertificateException").containsMatchIn(src)
        )
    }

    @Test
    fun `TOFU 落盘必须 tmp 加 rename 原子写`() {
        val src = source().withoutComments()
        assertTrue(
            "saveTofu 必须写临时文件后 rename 覆盖，避免写一半被杀留下半截文件",
            src.contains("+ \".tmp\"") && src.contains("renameTo(tofuFile)")
        )
        assertFalse(
            "禁止对 tofuFile 直接 writeText 覆写（非原子，崩溃即半截文件）",
            src.contains("tofuFile.writeText")
        )
    }

    /** 与 app/transport 其它契约测试同款定位：先按相对路径，再向上回溯。 */
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
        throw AssertionError(
            "找不到源码文件：${candidates.joinToString()}；cwd=$cwd。若源码被移动，请同步修正本契约测试路径。"
        )
    }
}
