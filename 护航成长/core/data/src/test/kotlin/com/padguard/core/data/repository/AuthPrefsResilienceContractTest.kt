package com.padguard.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R43：授权 DataStore 读侧自愈契约（纯 JVM 源码扫描）。
 *
 * 原实现全仓 24 处 `store.data.catch { emit(emptyPreferences()) }` 是**完结式降级**：
 * DataStore 读失败（文件损坏 / IO 异常）时发完一张空表，Flow 就此完结——
 * 长驻收集方（入口分流、GuardService）从此**永久失聪**，之后的任何写入都收不到，
 * 绑定/登录/授权状态定格在空表快照，只有杀进程重启才恢复。
 *
 * 修复后统一走 [AuthRepository.preferences] 自愈订阅：失败发空表（保留一次性读取默认值语义）
 * → 延时重订阅 → 恢复即自动追上；取消原样上抛（与 editSafely 同口径）。
 */
class AuthPrefsResilienceContractTest {

    @Test
    fun `读侧必须走自愈订阅循环而非完结式 catch`() {
        val src = stripComments(readAuthRepository())
        assertFalse(
            "禁止完结式降级 store.data.catch（读失败后 Flow 完结，长驻收集方失聪）",
            src.contains("store.data.catch")
        )
        assertTrue(
            "必须有自愈订阅收口 helper",
            src.contains("private fun preferences(): Flow<Preferences>")
        )
        val helper = src.substringAfter("private fun preferences(): Flow<Preferences>")
            .substringBefore("private suspend fun editSafely")
        assertTrue("必须在循环内重订阅 store.data", helper.contains("store.data.collect { emit(it) }"))
        assertTrue("重订阅必须处于循环中（否则失败一次仍完结）", helper.contains("while (true)"))
        assertTrue("必须放行 CancellationException", helper.contains("catch (e: CancellationException)"))
        assertTrue("取消必须原样上抛", helper.contains("throw e"))
        assertTrue("失败后必须延时重试（防持续损坏时空转 IO）", helper.contains("delay(PREFS_READ_RETRY_MS)"))
        assertTrue(
            "失败降级值必须与旧语义一致（一次性读取拿到默认值）",
            helper.contains("emit(emptyPreferences())")
        )
        assertTrue("持续失败空表不得反复下发（下游状态不抖动）", helper.trim().endsWith(".distinctUntilChanged()"))
    }

    @Test
    fun `全部读取点必须统一走 preferences 收口`() {
        val src = stripComments(readAuthRepository())
        // 代码正文（剥注释后）只允许 helper 内部出现一次 store.data；
        // 其余任何直接读 store.data 的点都会绕开自愈收口（读失败即失聪）。
        assertEquals(
            "读侧不允许出现收口之外的裸 store.data 读取点",
            1,
            Regex("store\\.data").findAll(src).count()
        )
        // 一次性读取（getDeviceToken 等 7 个 getter）必须走同一收口。
        assertTrue(
            "一次性读取必须走 preferences().first()",
            Regex("preferences\\(\\)\\.first\\(\\)").containsMatchIn(src)
        )
        // 状态类 Flow 属性必须走收口（抽查绑定与重新授权两条入口分流关键链路）。
        assertTrue(
            "deviceId 状态流必须走收口",
            src.contains("val deviceId: Flow<String> = preferences()")
        )
        assertTrue(
            "reauthRequired 状态流必须走收口",
            src.contains("val reauthRequired: Flow<Boolean> = preferences()")
        )
    }

    /** 注释里会引用旧实现作反例，扫描代码前先剥离，避免"注释命中"的假红。 */
    private fun stripComments(text: String): String = text
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
        .lines().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }

    private fun readAuthRepository(): String {
        val candidates = arrayOf(
            "src/main/kotlin/com/padguard/core/data/repository/AuthRepository.kt",
            "core/data/src/main/kotlin/com/padguard/core/data/repository/AuthRepository.kt"
        )
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
