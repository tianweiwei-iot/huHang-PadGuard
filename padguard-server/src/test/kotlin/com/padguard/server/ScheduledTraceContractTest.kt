package com.padguard.server

import com.padguard.server.repository.UsageSessionRepository
import jakarta.persistence.LockModeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.Lock
import java.io.File

/**
 * 第 5 批的**源码级契约守卫**：把「靠人记得」的接线固化成 `./gradlew test` 当场失败。
 *
 * 这些约束都无法用编译期类型表达：
 * - `@Scheduled` 方法不经过 HTTP Filter，也不经过 `@Async` 的 TaskDecorator，
 *   新增一个定时任务少写一行包装，日志里就静悄悄少一段 traceId，几个月后才发现；
 * - 行锁查询的 `@Lock(PESSIMISTIC_WRITE)` 被谁顺手删掉，编译照过、单测照绿，
 *   但并发延时申请又回到「两张工单」；
 * - H2 2.2.224 不支持 `ON CONFLICT (col) DO NOTHING`（第 4 批真库实测），
 *   而这条语句只在批写撞唯一约束的降级路径触发，平时永远测不到。
 *
 * 源码从 `src/main/kotlin` 读取：Gradle `test` 任务的工作目录默认就是 `padguard-server/`
 * （`DataInitializerTest` 的口令文件也依赖同一约定）。若工作目录被改，断言会显式失败，
 * 而不是让守卫静默失效。
 */
@DisplayName("定时任务 traceId / 行锁 / SQL 方言契约守卫")
class ScheduledTraceContractTest {

    private val mainSrc = File("src/main/kotlin/com/padguard/server")

    @Test
    @DisplayName("每个 @Scheduled 方法都必须包在 sched trace 作用域内")
    fun `every scheduled method wraps a trace scope`() {
        assertTrue(mainSrc.isDirectory, "测试工作目录应为 padguard-server 项目根，实际 ${mainSrc.absolutePath}")

        val violations = mutableListOf<String>()
        var scheduledCount = 0
        mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                // 只认注解行；KDoc 里以 `*` 开头的 `@Scheduled(...)` 提及不算。
                if (!line.trimStart().startsWith("@Scheduled")) return@forEachIndexed
                scheduledCount++
                val window = lines.subList(index, minOf(index + 12, lines.size)).joinToString("\n")
                if (!window.contains("withNewTrace(\"sched\")")) {
                    violations += "${file.name}:${index + 1}"
                }
            }
        }
        assertTrue(scheduledCount >= 7, "应至少覆盖 7 处定时任务，实际扫到 $scheduledCount 处")
        assertTrue(
            violations.isEmpty(),
            "以下 @Scheduled 方法没有包 TraceIds.withNewTrace(\"sched\")，日志将失去 traceId：$violations"
        )
    }

    @Test
    @DisplayName("延时申请必须读带行锁的会话，且不再用普通读做 check-then-act")
    fun `delay request reads the session under a row lock`() {
        val lines = File(mainSrc, "service/UsageDelayService.kt").readLines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("fun request(") }
        assertTrue(start >= 0, "找不到 UsageDelayService.request")
        // 精确到下一个方法，避免把 approve 里的普通 findByDeviceId 算进 request 的窗口。
        val end = (start + 1 until lines.size).firstOrNull {
            lines[it].trimStart().startsWith("fun approve(")
        } ?: minOf(start + 70, lines.size)
        val body = lines.subList(start, end).joinToString("\n")
        assertTrue(
            body.contains("findByDeviceIdForUpdate"),
            "request 必须用带 PESSIMISTIC_WRITE 的会话读把「查 PENDING → 插工单」串行化"
        )
        assertFalse(
            body.contains("usageSessionRepository.findByDeviceId("),
            "request 里残留普通会话读，并发窗口会重新打开"
        )
    }

    @Test
    @DisplayName("会话行锁查询必须真的标注 PESSIMISTIC_WRITE")
    fun `session lock query declares pessimistic write`() {
        val method = runCatching {
            UsageSessionRepository::class.java.getMethod("findByDeviceIdForUpdate", String::class.java)
        }.getOrNull()
        assertNotNull(method, "UsageSessionRepository 缺少 findByDeviceIdForUpdate")
        val lock = method!!.getAnnotation(Lock::class.java)
        assertNotNull(lock, "findByDeviceIdForUpdate 缺少 @Lock，生成的 SQL 不会带 for update")
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock!!.value, "行锁级别必须是 PESSIMISTIC_WRITE")
    }

    @Test
    @DisplayName("native on conflict 一律不带冲突目标（H2 2.2 语法红线）")
    fun `on conflict never names a conflict target`() {
        val repoDir = File(mainSrc, "repository")
        assertTrue(repoDir.isDirectory, "找不到 repository 源码目录")
        val bad = mutableListOf<String>()
        repoDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    return@forEachIndexed
                }
                if (Regex("on conflict\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                    bad += "${file.name}:${index + 1}"
                }
            }
        }
        assertTrue(
            bad.isEmpty(),
            "H2 2.2.224(MODE=PostgreSQL) 不支持带列名的 on conflict (...)，" +
                "第 4 批已因此修过一次批写降级路径，违规位置：$bad"
        )
    }
}
