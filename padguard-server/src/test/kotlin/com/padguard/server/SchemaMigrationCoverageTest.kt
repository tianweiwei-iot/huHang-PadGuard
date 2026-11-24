package com.padguard.server

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource


/**
 * 「实体列 ↔ Flyway 迁移」对账（防止生产整表读写 500）。
 *
 * ## 为什么必须有这条测试
 * 生产 Profile 跑 `ddl-auto=none` + Flyway：**表结构只由迁移脚本创建**，Hibernate 不会补列。
 * 而本地/发布包的 H2 档位跑 `ddl-auto=update`，缺列会被自动补上 —— 于是"加字段忘了写迁移"
 * 在开发环境完全看不出来，直到上线打生产才炸。这个坑已经踩过两次：
 *
 * - `usage_sessions` 的 rest_* 列（V25 建表、实体后加列，补 V2026110702）；
 * - `devices.entitlement_state`（实体有列、迁移没有，补 V2026110703）。
 *
 * 两次的症状完全一样：`SELECT ... FROM devices` 直接抛 `column does not exist`，
 * 设备列表、心跳、策略下发、绑定/解绑全线 500 —— 一台都管不了。
 *
 * ## 怎么对账
 * 不用正则去猜实体（实体有 `@Embedded`、隐式命名、`@Column(name=)` 三种来源，猜不全），
 * 而是让 Hibernate 按实体把表**真的建出来**（内存库 + `ddl-auto=create`），
 * 再从 `information_schema` 读回物理表名/列名 —— 这与生产 Hibernate 期望的结构**逐字一致**。
 * 然后拿这份结构去比对迁移脚本解析出的列集合，缺任何一列即失败。
 *
 * 反方向（迁移有、实体无）不校验：历史遗留表与已废弃列（如 `geofences`）是正常存在的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    // 独立内存库：表必须由**实体**现建，不能复用任何已有库（否则本用例就失去意义）
    "spring.datasource.url=jdbc:h2:mem:schemacoverage;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create",
    "spring.flyway.enabled=false",
    "padguard.jwt.secret=integration-test-secret-key-32-bytes-minimum-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/itest-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/itest-parent-pwd.txt"
])
class SchemaMigrationCoverageTest {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `每个实体列都必须有对应的 Flyway 迁移`() {
        val migrationColumns = parseMigrationColumns()
        val entityColumns = readEntitySchema()

        // 防止"解析器什么都没解析到"导致用例永远通过（假绿比没有测试更危险）
        assertTrue(migrationColumns.size >= 25, "迁移解析结果异常：只解析到 ${migrationColumns.size} 张表")
        assertTrue(entityColumns.size >= 20, "实体表读取异常：只读到 ${entityColumns.size} 张表")

        val missing = mutableListOf<String>()
        entityColumns.forEach { (table, cols) ->
            val migrated = migrationColumns[table]
            if (migrated == null) {
                missing.add("$table（整张表都没有迁移脚本）")
            } else {
                cols.filterNot { c -> c in migrated }.sorted()
                    .forEach { c -> missing.add("$table.${c}") }
            }
        }

        assertTrue(
            missing.isEmpty(),
            "以下列由实体定义、但**没有任何 Flyway 迁移创建**：\n  " +
                missing.sorted().joinToString("\n  ") +
                "\n\n生产档位是 ddl-auto=none，缺列会让该表所有 SELECT/UPDATE 直接抛 " +
                "\"column does not exist\"（与 V2026110702 / V2026110703 同型事故）。\n" +
                "修复：在 padguard-server/src/main/resources/db/migration 下新增一个版本更高的 " +
                "迁移（ALTER TABLE x ADD COLUMN IF NOT EXISTS ...），并给存量行补 UPDATE 兜底。"
        )
    }

    // ==================== 迁移脚本解析 ====================

    /**
     * 扫描 classpath 下 `db/migration` 目录里的全部 sql 脚本，解析出 `表 -> 列集合`。
     *
     * 只认本项目实际使用的两种句式：
     *  - `CREATE TABLE [IF NOT EXISTS] t (...)`（建表列全量）
     *  - `ALTER TABLE t ADD [COLUMN] [IF NOT EXISTS] c ...`（后加列）
     * 解析不到其它 DDL 是**故意**的：多认句式只会让解析结果虚高，把真缺列掩盖掉。
     * 一旦有人用了 RENAME/DROP 之类的新句式，请在下方同步实现，而不是放宽断言。
     */
    private fun parseMigrationColumns(): Map<String, MutableSet<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        val resources = PathMatchingResourcePatternResolver()
            .getResources("classpath:db/migration/*.sql")
        assertTrue(resources.isNotEmpty(), "没有找到任何 Flyway 迁移脚本，测试无法对账")

        resources.sortedBy { it.filename ?: "" }.forEach { res ->
            val sql = stripComments(res.inputStream.bufferedReader().use { it.readText() })

            // 1) CREATE TABLE
            var from = 0
            while (true) {
                val m = CREATE_TABLE.find(sql, from) ?: break
                val table = m.groupValues[1].lowercase()
                val openParen = m.range.last
                val closeParen = matchingParen(sql, openParen)
                if (closeParen < 0) {
                    from = openParen + 1
                    continue
                }
                val body = sql.substring(openParen + 1, closeParen)
                val cols = result.getOrPut(table) { mutableSetOf() }
                splitTopLevel(body).forEach { item -> columnNameOf(item)?.let { cols.add(it) } }
                from = closeParen + 1
            }

            // 2) ALTER TABLE ... ADD [COLUMN]（**一条语句可以带多个 ADD**，必须按语句扫到底：
            //    V2026110601 就是这么写的，只认第一个 ADD 会把后两列误判成"漏迁移"）
            ALTER_TABLE.findAll(sql).forEach { head ->
                val table = head.groupValues[1].lowercase()
                val stmtEnd = sql.indexOf(';', head.range.last).let { if (it < 0) sql.length else it }
                val stmt = sql.substring(head.range.last + 1, stmtEnd)
                ALTER_ADD.findAll(stmt).forEach { m ->
                    val col = m.groupValues[1].trim('"', '`').lowercase()
                    if (col !in NON_COLUMN_KEYWORDS) {
                        result.getOrPut(table) { mutableSetOf() }.add(col)
                    }
                }
            }
        }
        return result
    }

    /** 去掉 `--` 行注释与块注释：注释里的示例 SQL 不能被当成真实 DDL。 */
    private fun stripComments(sql: String): String =
        sql.replace(Regex("/\\*[\\s\\S]*?\\*/"), " ").replace(Regex("--[^\\n]*"), " ")

    /** 从 `(` 位置找配对的 `)`，跳过字符串字面量里的括号。返回 -1 表示不配对。 */
    private fun matchingParen(s: String, openIndex: Int): Int {
        var depth = 0
        var i = openIndex
        var quote: Char? = null
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** 按顶层逗号切分建表体（列类型里的 `DECIMAL(10,2)` 不能被切开）。 */
    private fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in body) {
            when (c) {
                '(' -> { depth++; sb.append(c) }
                ')' -> { depth--; sb.append(c) }
                ',' -> if (depth == 0) { parts.add(sb.toString()); sb.clear() } else sb.append(c)
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) parts.add(sb.toString())
        return parts
    }

    /**
     * 取建表体里一项的列名；约束子句（PRIMARY KEY / UNIQUE / CONSTRAINT ...）返回 null。
     *
     * 带引号的标识符（`"key"`）一律按列名处理：`key` 本身在约束关键字表里，
     * 但写成 `"key"` 就说明它就是这一列的名字（system_settings 正是如此）。
     */
    private fun columnNameOf(item: String): String? {
        val raw = item.trim().takeWhile { !it.isWhitespace() }
        val unquoted = raw.trim('"', '`')
        if (unquoted.isEmpty()) return null
        val name = unquoted.lowercase()
        if (raw.first() !in QUOTE_CHARS && name in NON_COLUMN_KEYWORDS) return null
        return name
    }

    // ==================== 实体真实结构 ====================

    /** 读 Hibernate 按实体建出来的物理表结构（表名 → 列名，均为小写）。 */
    private fun readEntitySchema(): Map<String, Set<String>> =
        jdbc.query(
            """
            SELECT table_name, column_name
              FROM information_schema.columns
             WHERE table_schema = 'public'
            """.trimIndent(),
            org.springframework.jdbc.core.ResultSetExtractor { rs ->
                val map = mutableMapOf<String, MutableSet<String>>()
                while (rs.next()) {
                    map.getOrPut(rs.getString(1).lowercase()) { mutableSetOf() }
                        .add(rs.getString(2).lowercase())
                }
                map
            }
        )!!

    companion object {
        private val CREATE_TABLE =
            Regex("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")

        private val ALTER_TABLE = Regex("(?i)ALTER\\s+TABLE\\s+([A-Za-z_][A-Za-z0-9_]*)")

        /** 单条 `ADD [COLUMN] [IF NOT EXISTS] name`；在一条 ALTER 语句里可能重复出现多次。 */
        private val ALTER_ADD = Regex(
            "(?i)\\bADD\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z_\"`][A-Za-z0-9_\"]*)"
        )

        private val QUOTE_CHARS = charArrayOf('"', '`')

        /** 建表体/ALTER 里出现这些词说明不是列定义（防止把 `CONSTRAINT` 当列名记进去）。 */
        private val NON_COLUMN_KEYWORDS = setOf(
            "constraint", "primary", "unique", "foreign", "check", "key", "index", "exclude", "like"
        )
    }
}
