package com.padguard.server

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 管理端写操作不得"断网假成功"的源码契约（纯 JVM 扫描 JS 源码）。
 *
 * 只读看板断网降级为空数据 + 明确 toast 是可接受的；写操作的 mockFn 一律返回
 * { ok: true }，一旦复用同一个降级出口，断网/超时会被上报成"操作成功"。
 */
class AdminWriteNoFallbackContractTest {

    @Test
    fun `所有管理端写操作都走不降级的liveWrite出口`() {
        val src = apiSource()
        assertTrue("async function liveWrite(" in src, "缺少写操作专用出口 liveWrite")
        val liveWriteBody = src.substringAfter("async function liveWrite(")
            .substringBefore("async function http(")
        assertTrue("if (config.useMock)" in liveWriteBody, "演示档仍需 mock 行为")
        assertFalse("catch" in liveWriteBody, "liveWrite 不得吞异常降级成假成功")
        listOf("POST", "PUT", "DELETE").forEach { method ->
            assertFalse(
                "liveOrMock(() => http(\"$method\"" in src,
                "写操作 $method 仍在走可降级的 liveOrMock，断网会误报成功"
            )
        }
        val sweep = src.substringAfter("async sweepOrphans(").substringBefore("// 服务器基本信息")
        assertTrue("liveWrite(" in sweep, "孤儿清理（含真删）必须在网络失败时如实上抛")
    }

    private fun apiSource(): String {
        val rel = "padguard-admin/assets/js/api.js"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (dir == null) return@repeat
            val candidate = File(dir!!, rel)
            if (candidate.isFile) return candidate.readText()
            dir = dir!!.parentFile
        }
        throw AssertionError("找不到管理端 API 源码 $rel；cwd=${System.getProperty("user.dir")}")
    }
}
