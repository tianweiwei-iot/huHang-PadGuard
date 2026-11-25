package com.padguard.server

import com.padguard.server.stream.ScreenStreamHub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 容量红线契约：把"配置与代码必须匹配"的约束固化，避免规模上来才发现全站排队。
 *
 * - 推流上行必须与家长开流共用并发准入；两处各自 `count-then-add` 会被并发顶穿；
 * - MVC 异步端点必须有界线程池，且容量不低于并发准入上限；
 * - 调度池线程数必须覆盖当前所有 @Scheduled 任务，慢清理不能拖住秒级巡检。
 */
@DisplayName("服务端容量准入与线程池契约")
class ServerCapacityContractTest {

    private val mainSrc = File("src/main/kotlin/com/padguard/server")
    private val yml = File("src/main/resources/application.yml")

    private fun ymlValue(key: String): Int? {
        assertTrue(yml.exists(), "测试工作目录应为 padguard-server 项目根，实际 ${yml.absolutePath}")
        val line = yml.readLines().firstOrNull { it.trimStart().startsWith("$key:") }
            ?: return null
        return Regex("""$key:\s*(\d+)""").find(line)?.groupValues?.get(1)?.toInt()
    }

    private fun schedulingPoolSize(): Int {
        val lines = yml.readLines()
        val start = lines.indexOfFirst { it.trim() == "scheduling:" }
        assertTrue(start >= 0, "application.yml 缺 spring.task.scheduling 配置段")
        val sizeLine = (start + 1 until lines.size).first {
            lines[it].trimStart().startsWith("size:")
        }
        return Regex("""size:\s*(\d+)""").find(lines[sizeLine])!!.groupValues[1].toInt()
    }

    private fun scheduledTaskCount(): Int {
        assertTrue(mainSrc.isDirectory, "源码目录不存在：${mainSrc.absolutePath}")
        val annotation = Regex("""^\s*@(org\.springframework\.scheduling\.annotation\.)?Scheduled\(""")
        return mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.sumOf { file ->
            file.readLines().count { annotation.containsMatchIn(it) }
        }
    }

    @Test
    @DisplayName("推流准入原子：并发上限不能被同时建连顶穿，同设备重连幂等")
    fun `upload admission is atomic and idempotent per device`() {
        val hub = ScreenStreamHub()
        assertTrue(hub.tryRegisterPublisher("D1", 1), "首台设备应可推流")
        assertFalse(hub.tryRegisterPublisher("D2", 1), "达到上限后新设备必须被拒")
        assertTrue(hub.tryRegisterPublisher("D1", 1), "同一设备重连不能被自己占的名额挡住")
        assertEquals(1, hub.publisherCount())
        hub.unregisterPublisher("D1")
        assertTrue(hub.tryRegisterPublisher("D2", 1), "名额释放后应可立即复用")
    }

    @Test
    @DisplayName("上行端点必须走原子准入并被映射为 429")
    fun `upload endpoint uses atomic admission and child 429`() {
        val text = File(mainSrc, "stream/StreamControllers.kt").readText()
        assertTrue(text.contains("tryRegisterPublisher("), "上行端点缺并发准入")
        assertFalse(text.contains("hub.registerPublisher("), "仍存在 count-then-add 的非原子注册")
        val core = File(mainSrc, "common/Core.kt").readText()
        assertTrue(
            core.contains("e.code == ParentErr.TOO_MANY_REQUESTS || e.code == ChildErr.TOO_MANY_REQUESTS"),
            "孩子端 40902 必须同样映射 429，不能靠 HTTP 200 + 错误码（网关会误判成功）"
        )
    }

    @Test
    @DisplayName("MVC 异步端点使用有界池，且容量不低于推流准入上限")
    fun `mvc async executor is bounded and not smaller than admission limit`() {
        val web = File(mainSrc, "config/WebConfig.kt").readText()
        assertTrue(web.contains("override fun configureAsyncSupport"))
        assertTrue(web.contains("setTaskExecutor(mvcAsyncExecutor)"), "有界池必须真正接线到 MVC")

        val config = File(mainSrc, "config/MvcAsyncConfig.kt").readText()
        assertTrue(config.contains("AbortPolicy"), "容量到顶必须显式拒绝，禁止无限排队")
        assertFalse(config.contains("CallerRunsPolicy"), "CallerRunsPolicy 会让长流占住 Tomcat 请求线程")
        assertTrue(config.contains("maxOf(configuredPoolSize, maxLiveDevices)"), "池容量不得低于准入上限")

        val pool = ymlValue("async-pool-size")
        val admission = ymlValue("max-live-devices")
        assertTrue(pool != null && admission != null, "application.yml 缺 async-pool-size / max-live-devices")
        assertTrue(pool!! >= admission!!, "async-pool-size=$pool 小于 max-live-devices=$admission")
    }

    @Test
    @DisplayName("同步推流上行不得吃光 Tomcat 请求线程")
    fun `stream admission leaves request threads for normal traffic`() {
        // 上行 /stream/up 是同步端点：每路长连接独占 1 个 Tomcat 工作线程直到断开。
        // 准入上限若接近工作线程上限，看屏并发就会让 /health、轮询、指令下发全部排队。
        val admission = ymlValue("max-live-devices")
        val lines = yml.readLines()
        val threadsStart = lines.indexOfFirst { it.trim() == "threads:" }
        assertTrue(threadsStart >= 0, "application.yml 必须显式配置 server.tomcat.threads")
        val maxThreadsLine = lines.drop(threadsStart + 1).first {
            it.trimStart().startsWith("max:")
        }
        val maxThreads = Regex("""max:\s*(\d+)""").find(maxThreadsLine)!!.groupValues[1].toInt()
        assertTrue(admission != null, "application.yml 缺 max-live-devices")
        assertTrue(
            admission!! * 4 <= maxThreads * 3,
            "推流准入=${admission} 必须显著小于 Tomcat 工作线程=$maxThreads，" +
                "至少为非推流请求留出 1/4 余量（旧值 1000 vs 200 会让看屏并发触发全站排队）"
        )
        assertTrue(
            yml.readText().contains("max-days: 14"),
            "accesslog 必须设置 max-days，否则轮转日志永不删除、写满分区后全站写入失败"
        )
    }

    @Test
    @DisplayName("调度池线程数覆盖所有 @Scheduled 任务")
    fun `scheduling pool covers all scheduled tasks`() {
        val count = scheduledTaskCount()
        val size = schedulingPoolSize()
        assertTrue(count >= 9, "应至少扫到 9 个定时任务，实际 $count（源码扫描失效会比容量不足更危险）")
        assertTrue(size >= count, "spring.task.scheduling.pool.size=$size 小于定时任务数 $count，慢清理会拖住秒级巡检")
    }
}
