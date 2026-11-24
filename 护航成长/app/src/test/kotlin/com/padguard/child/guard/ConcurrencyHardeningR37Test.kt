package com.padguard.child.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R37 结构化并发加固契约：
 *
 * 1. `runCatching` / `catch (Exception)` 会把 `CancellationException` 当普通失败吞掉，
 *    已取消的协程继续执行后续逻辑——HTTP 收口、DataStore 写入、绑定保存三处必须放行取消。
 * 2. 应用台账差分的**事件上报必须先于基线推进**：上报失败（含取消）基线不动，
 *    下一轮重新检出，装卸事件永不静默丢失。
 *
 * 本测试用源码扫描锁死上述时序 / 放行语义，防止回归。
 */
class ConcurrencyHardeningR37Test {

    @Test
    fun `ApiCaller 取消必须放行且先于 catch Exception`() {
        val src = readSource("core/transport/src/main/kotlin/com/padguard/core/transport/http/ApiCaller.kt")
        assertTrue(
            "必须 import CancellationException",
            src.contains("import kotlin.coroutines.cancellation.CancellationException")
        )
        val cancelIdx = src.indexOf("catch (e: CancellationException)")
        val exIdx = src.indexOf("catch (e: Exception)")
        assertTrue("必须存在 CancellationException 捕获", cancelIdx > 0)
        assertTrue("CancellationException 必须先于 Exception 捕获", cancelIdx < exIdx)
        val body = src.substring(cancelIdx, exIdx.coerceAtLeast(cancelIdx + 1))
        assertTrue("取消分支必须 rethrow", body.contains("throw e"))
    }

    @Test
    fun `台账差分事件消费必须先于基线落盘`() {
        val src = readSource("app/src/main/kotlin/com/padguard/child/monitor/AppInventoryDeltaTracker.kt")
        assertTrue(
            "diff 必须带 onEvents 回调",
            src.contains("onEvents: suspend (AppInventoryChange) -> Unit")
        )
        val onEventsIdx = src.indexOf("if (!change.baselineOnly) onEvents(change)")
        val writeIdx = src.indexOf("writeBaseline(current)")
        assertTrue("必须显式调用 onEvents", onEventsIdx > 0)
        assertTrue("writeBaseline 必须仍在（基线最终要落）", writeIdx > onEventsIdx)
        assertTrue("必须保留采集失败保护（空清单不推进基线）", src.contains("skip delta"))
    }

    @Test
    fun `GuardService 台账上报经回调且取消放行`() {
        val src = readSource("app/src/main/kotlin/com/padguard/child/service/GuardService.kt")
        val fnStart = src.indexOf("private suspend fun recordInventoryChanges")
        assertTrue(fnStart > 0)
        val fnEnd = src.indexOf("\n    }", fnStart)
        val body = src.substring(fnStart, fnEnd)
        assertTrue("上报必须经 onEvents 回调", body.contains("appInventoryDelta.diff(apps) { delta ->"))
        assertTrue("取消必须放行", body.contains("CancellationException") && body.contains("throw e"))
        assertTrue("runCatching 不得再包裹整段（会吞取消）", !body.contains("runCatching"))
    }

    @Test
    fun `水印 deviceSn 读取取消放行`() {
        val src = readSource("app/src/main/kotlin/com/padguard/child/service/GuardService.kt")
        assertTrue(
            "旧 runCatching 吞取消写法必须移除",
            !src.contains("runCatching { authRepository.deviceSn.first() }")
        )
        assertTrue("新写法必须 try/catch 放行取消", src.contains("val deviceSn = try"))
    }

    @Test
    fun `AuthRepository DataStore 写入统一收口且放行取消`() {
        val src = readSource("core/data/src/main/kotlin/com/padguard/core/data/repository/AuthRepository.kt")
        assertTrue("必须有 editSafely 收口", src.contains("private suspend fun editSafely("))
        val helperStart = src.indexOf("private suspend fun editSafely(")
        val helperEnd = src.indexOf("\n    }", helperStart)
        val helper = src.substring(helperStart, helperEnd)
        assertTrue(
            "收口必须放行 CancellationException",
            helper.contains("CancellationException") && helper.contains("throw e")
        )
        assertTrue(
            "不得再有 runCatching 直接包 store.edit（吞取消）",
            !src.contains("runCatching { store.edit")
        )
        // 六个写入点全部走收口：DA ack / DO ack / set / clear / clearAll / replace
        assertTrue(src.contains("editSafely(\"set device admin prompt ack\")"))
        assertTrue(src.contains("editSafely(\"set device owner prompt ack\")"))
        assertTrue(src.contains("editSafely(\"set reauth required(\$source)\")"))
        assertTrue(src.contains("editSafely(\"clear reauth required(\$source)\")"))
        assertTrue(src.contains("editSafely(\"clear all reauth required\")"))
        assertTrue(src.contains("editSafely(\"replace reauth sources(\$prefix)\")"))
    }

    @Test
    fun `绑定保存失败分支放行取消`() {
        val src = readSource("app/src/main/kotlin/com/padguard/child/bind/BindViewModel.kt")
        val rethrows = Regex("""if \(e is kotlinx\.coroutines\.CancellationException\) throw e""").findAll(src).count()
        assertTrue("账密 + 绑定码两条保存路径都必须放行取消（实际 $rethrows 处）", rethrows >= 2)
    }

    @Test
    fun `GuardService 跨协程 HTTP 失败计数必须原子`() {
        val src = readSource("app/src/main/kotlin/com/padguard/child/service/GuardService.kt")
        assertTrue(
            "心跳主循环与指令兜底拉取循环分属不同线程，失败计数必须用 AtomicInteger，" +
                "普通 Int 的 ++ 与清零交错会丢更新、拖后 HTTPS 不可达判定",
            src.contains("private val consecutiveHttpFails = AtomicInteger(0)") &&
                !src.contains("private var consecutiveHttpFails")
        )
        assertTrue("清零必须用 set(0)", src.contains("consecutiveHttpFails.set(0)"))
        assertTrue(
            "自增判定必须用 incrementAndGet",
            src.contains("consecutiveHttpFails.incrementAndGet() >= HTTP_UNREACHABLE_THRESHOLD")
        )
        // 两处自增都要改到，漏一处就仍存在数据竞争
        assertEquals(
            2,
            Regex("consecutiveHttpFails\\.incrementAndGet\\(\\) >= HTTP_UNREACHABLE_THRESHOLD").findAll(src).count()
        )
    }

    // ==================== 辅助 ====================

    private fun readSource(rel: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(rel, "护航成长/$rel")
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isFile) return direct.readText()
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir!!, relative)
                if (candidate.isFile) return candidate.readText()
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到源码文件 $rel；cwd=$cwd")
    }
}
