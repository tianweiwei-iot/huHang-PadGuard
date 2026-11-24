package com.padguard.core.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R38：解锁申请仓储两处修复的契约回归（纯 JVM 源码扫描）。
 *
 * 1. **时间源纪律**：[UnlockRequestRepository] 的 submit 会把 `createdAt` 原样传给
 *    `LogRepository.append(timestamp = ...)`。任何一处用裸墙钟，孩子改系统时间即可
 *    伪造 UNLOCK_REQUEST 行为日志的"申请时刻"——绕过 R27 给日志注入 TimeProvider 的加固。
 *    本类所有时间戳（submit / approveLatest / reject / expireStale / purge）必须同源受信钟。
 *
 * 2. **状态转移终态不可回退**：`updateStatus` 原 SQL 只按 requestId 更新，`latestPending`
 *    查询与 UPDATE 之间存在竞态窗口；家长已拒绝后迟到的 TEMP_UNLOCK 可把终态覆盖回
 *    APPROVED，反之 reject 也能把已批准改回 REJECTED。守卫必须落在 DB 的 WHERE 里
 *    （`AND status = 'PENDING'`），与服务端状态机同一纪律。
 */
class UnlockRequestContractTest {

    private fun repositorySource(): String = readText(
        "src/main/kotlin/com/padguard/core/data/repository/UnlockRequestRepository.kt",
        "core/data/src/main/kotlin/com/padguard/core/data/repository/UnlockRequestRepository.kt"
    )

    private fun daoSource(): String = readText(
        "src/main/kotlin/com/padguard/core/data/db/Daos.kt",
        "core/data/src/main/kotlin/com/padguard/core/data/db/Daos.kt"
    )

    @Test
    fun `解锁申请全类禁止裸墙钟`() {
        val src = repositorySource()
        assertFalse(
            "submit 的 createdAt 会进入 UNLOCK_REQUEST 行为日志（审计证据），" +
                "全类时间戳必须来自 TimeProvider.now()，禁止裸墙钟",
            src.contains("System.currentTimeMillis()")
        )
        assertTrue("必须注入 TimeProvider", src.contains("private val timeProvider: TimeProvider"))
        assertTrue("必须真的使用受信钟", src.contains("timeProvider.now()"))
    }

    @Test
    fun `审批状态转移必须带 PENDING 守卫`() {
        val src = daoSource()
        // Kotlin 源码里 SQL 是字符串拼接（"..." + "..."），归一空白后按关键片段断言。
        // 本 DAO 只有一处 unlock_request 的 status UPDATE，片段断言足以钉住守卫。
        val compact = src.replace(Regex("\\s*\"\\s*\\+\\s*\"\\s*"), "").replace(Regex("\\s+"), " ")
        assertTrue(
            "找不到 updateStatus 的 SQL，契约测试路径可能已失效",
            "UPDATE unlock_request SET status = :status, decidedAt = :decidedAt" in compact &&
                "WHERE requestId = :requestId AND status = 'PENDING'" in compact
        )
        assertTrue(
            "终态不可回退：updateStatus 的 WHERE 必须含 AND status = 'PENDING'，" +
                "否则迟到指令能把 REJECTED/APPROVED 终态改回去",
            "AND status = 'PENDING'" in compact
        )
    }

    /** 与 app/core 既有契约测试同款定位：先按相对路径，再向上回溯。 */
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
            "找不到源码文件：${candidates.joinToString()}；cwd=$cwd。" +
                "若源码被移动，请同步修正本契约测试的路径。"
        )
    }
}
