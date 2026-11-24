package com.padguard.server.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 「`@Modifying` 批量写必须带双标志」的源码契约守门测试（R33）。
 *
 * ## 为什么必须有这份测试
 * R32 刚把全仓几十个批量 `delete ... where device_id = ?` / 条件 UPDATE 补齐了
 * `@Modifying(flushAutomatically = true, clearAutomatically = true)`。这是一条
 * **不变量**，不是一次性修复：
 *
 * - 真实的 `DeviceDataPurgeService.purge` 在同一事务里先 `findByDeviceId` 把实体载入
 *   持久化上下文（PC），再 `deleteByDeviceId` 批量删。**没有 `clearAutomatically`**，
 *   PC 仍持有已删实体的陈旧引用，后续读写据此做脏检查，轻则读到幽灵数据，
 *   重则把已删行整行写回。
 * - **没有 `flushAutomatically`**，批量 UPDATE/DELETE 之前的 `save()` 还在 PC 里没刷出，
 *   批量语句看不到待写改动，出现"改了库但内存仍是旧值"的经典竞态。
 *
 * 单靠 code review 守不住：新增一个批量删方法时极容易照抄裸 `@Modifying`。
 * 本测试扫描全部仓储源码，把这条不变量变成**编译期之后的第二道门禁**：
 * 任何 `@Modifying` 批量 DELETE/UPDATE 漏写双标志，当场红。
 *
 * ## 唯一的例外：native INSERT ... ON CONFLICT DO NOTHING
 * `usage_logs` / `device_events` 的逐行幂等 upsert 刻意用裸 `@Modifying @Transactional`
 * （H2 2.2.224 不支持 `ON CONFLICT (col)`，只能无目标形式）。它们不是"UPDATE/DELETE 后读旧值"
 * 的场景，无需 clear。测试把它显式列为例外，并对例外数量设上界，防止"把所有方法都加进例外"
 * 的假绿。
 */
class ModifyingFlagContractTest {

    private val repoSources: List<Pair<String, String>> by lazy { readAllRepositorySources() }

    @Test
    fun `每个仓储源码都能解析出 Modifying 方法 防止空扫描假绿`() {
        assertTrue(repoSources.isNotEmpty(), "没有找到任何仓储源码，测试失去意义")
        val total = repoSources.sumOf { parseModifyingBlocks(it.second).size }
        assertTrue(total >= 50, "只解析到 $total 个 @Modifying 方法，远低于预期（>=50），解析器可能失效")
    }

    @Test
    fun `所有 JPQL 批量写都带 flush 与 clear 双标志`() {
        val violations = mutableListOf<String>()
        repoSources.forEach { (file, src) ->
            parseModifyingBlocks(src).forEach eachBlock@{ block ->
                // native INSERT upsert 是唯一允许的裸 @Modifying 例外
                if (block.isNativeInsert) return@eachBlock
                val missing = mutableListOf<String>()
                if (!block.hasFlush) missing += "flushAutomatically"
                if (!block.hasClear) missing += "clearAutomatically"
                if (missing.isNotEmpty()) {
                    violations += "$file :: ${block.methodName}（${block.modifyingText}）缺少 ${missing.joinToString(" + ")}"
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "以下 @Modifying 批量写未带全双标志，会重现「改了库但持久化上下文仍读旧实体」事故：\n  " +
                violations.sorted().joinToString("\n  ")
        )
    }

    @Test
    fun `裸 Modifying 例外只能是 native insert upsert 且数量受限`() {
        val bare = mutableListOf<String>()
        repoSources.forEach { (file, src) ->
            parseModifyingBlocks(src).forEach { block ->
                if (!block.hasFlush || !block.hasClear) {
                    bare += "$file :: ${block.methodName}"
                    assertTrue(
                        block.isNativeInsert,
                        "裸 @Modifying 只允许出现在 native INSERT ... ON CONFLICT upsert，" +
                            "$file :: ${block.methodName} 的查询是 ${block.queryPreview}，不是 INSERT"
                    )
                }
            }
        }
        // 当前恰好两处（usage_logs / device_events 幂等 upsert）。设上界 4 防止有人
        // 把新的批量 DELETE/UPDATE 通过"加进例外"绕过双标志约束。
        assertTrue(
            bare.size <= 4,
            "裸 @Modifying 例外膨胀到 ${bare.size} 处（${bare.sorted()}），" +
                "只允许 native INSERT upsert 且不得随意增加"
        )
    }

    // ==================== 解析 ====================

    private data class ModifyingBlock(
        val methodName: String,
        val modifyingText: String,
        val hasFlush: Boolean,
        val hasClear: Boolean,
        val isNativeInsert: Boolean,
        val queryPreview: String
    )

    /**
     * 逐段切出 `@Modifying` 到其方法及 `@Query` 之间的文本。
     * 以 `@Modifying` 起点 -> 下一个 `@Modifying`（或文件末尾）为一段，
     * 段内首次出现的 `fun xxx(` 即目标方法；`@Query` 的字符串字面量拼接后判类型。
     */
    private fun parseModifyingBlocks(rawSrc: String): List<ModifyingBlock> {
        // 关键：先剥离 KDoc 块注释与行注释。仓储源码的注释里大量以代码字体提到
        // `@Modifying`（如「必须显式 @Transactional：`@Modifying` 查询…」），
        // 不剥离会把注释误当成注解声明。查询体在字符串字面量里，不含 // 或 /*，
        // 因此剥离注释不会破坏 @Query 内容。
        val src = stripComments(rawSrc)
        val starts = findAll(src, "@Modifying")
        val out = mutableListOf<ModifyingBlock>()
        for (idx in starts.indices) {
            val from = starts[idx]
            val to = if (idx + 1 < starts.size) starts[idx + 1] else src.length
            val segment = src.substring(from, to)

            // 注解本体：@Modifying 后若紧跟 (...) 则一并取到，否则单行
            val modText = segment.substringBefore("fun").trim()
            val modifyingText = if (modText.startsWith("@Modifying(")) {
                modText.substring(0, modText.indexOf(')') + 1)
            } else {
                "@Modifying"
            }
            val hasFlush = "flushAutomatically" in modifyingText
            val hasClear = "clearAutomatically" in modifyingText

            val methodName = Regex("""\bfun\s+(\w+)\s*\(""").find(segment)?.groupValues?.get(1)
                ?: "<unknown>"

            // 段内所有字符串字面量拼起来即查询体（@Query 的多行拼接会连成一句）
            val literals = Regex(""""([^"]*)"""").findAll(modText)
                .map { it.groupValues[1] }.joinToString(" ")
            val lower = literals.lowercase()
            // nativeQuery 是注解参数（在 modText 里），"insert into" 才是查询体（在字面量里）
            val isNative = "nativeQuery" in modText
            val startsWithInsert = lower.trimStart().startsWith("insert") || lower.contains("insert into")
            val isNativeInsert = isNative && startsWithInsert
            val queryPreview = literals.take(80).replace(Regex("\\s+"), " ")
            out += ModifyingBlock(methodName, modifyingText, hasFlush, hasClear, isNativeInsert, queryPreview)
        }
        return out
    }

    // ==================== 工具 ====================

    private fun findAll(text: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (true) {
            i = text.indexOf(needle, i)
            if (i < 0) break
            out += i
            i += needle.length
        }
        return out
    }

    /** 剥离块注释（含 KDoc）与行注释，保留字符串字面量。 */
    private fun stripComments(text: String): String = text
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
        .lines().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }

    /** 仓储源码可能在模块目录或仓库根运行，逐级向上找 src/main/kotlin/.../repository。 */
    private fun readAllRepositorySources(): List<Pair<String, String>> {
        val rel = "src/main/kotlin/com/padguard/server/repository"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        val candidates = mutableListOf<File>()
        repeat(8) {
            if (dir == null) return@repeat
            val candidate = File(dir!!, rel)
            if (candidate.isDirectory) candidates += candidate
            dir = dir!!.parentFile
        }
        val repoDir = candidates.firstOrNull() ?: throw AssertionError(
            "找不到仓储源码目录 $rel；cwd=${System.getProperty("user.dir")}"
        )
        return repoDir.listFiles { f -> f.isFile && f.extension == "kt" }
            ?.sortedBy { it.name }
            ?.map { it.name to it.readText() }
            ?: emptyList()
    }
}
