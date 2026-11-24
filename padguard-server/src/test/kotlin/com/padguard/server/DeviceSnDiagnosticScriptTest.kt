package com.padguard.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.io.File

/**
 * 诊断脚本 `scripts/diagnose_duplicate_device_sn.sql` 的可执行性验证。
 *
 * ## 为什么要有这一条
 * 这份脚本的唯一使命是在**生产库**上跑——出问题的时机必然是"运营准备加唯一约束的那天"，
 * 那时才发现第 ⑤ 段的窗口函数写错、或某张表名对不上，诊断工作直接卡死，
 * 而写脚本的人早已不记得当时的库结构。
 *
 * 所以在这里用 H2（PostgreSQL 模式）真实执行全部语句，并造一组重复 SN 数据，
 * 断言 ① ② ⑤ 真的能查出来——语法对、表名对、语义也对。
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "padguard.initial-password-file=build/test-secrets/admin-pwd.txt",
    "padguard.parent-initial-password-file=build/test-secrets/parent-pwd.txt",
    "spring.datasource.url=jdbc:h2:mem:sndiag;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=sn-diag-test-secret-key-32-bytes-min-ok"
])
class DeviceSnDiagnosticScriptTest {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    /** 去注释 + 按分号拆语句（脚本不含存储过程/字符串内分号，直接拆安全） */
    private fun scriptStatements(): List<String> {
        val path = "src/main/resources/../scripts/diagnose_duplicate_device_sn.sql"
        val file = File("scripts/diagnose_duplicate_device_sn.sql").takeIf { it.exists() }
            ?: File(path).canonicalFile
        val text = file.readText(Charsets.UTF_8)
        val noComments = text.lines()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
        return noComments.split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    @Test
    fun `脚本全部语句可在真库上执行且能查到重复`() {
        // 造数据：同 SN 两条台账（A 场景：同一家长重复台账），外加一条空串 SN
        jdbc.update(
            """
            INSERT INTO devices (id, user_id, name, device_sn, online_status, created_at)
            VALUES ('D-diag-old', 'U-diag', '孩子平板', 'SN-DUP-001', 'OFFLINE', 1000)
            """.trimIndent()
        )
        jdbc.update(
            """
            INSERT INTO devices (id, user_id, name, device_sn, online_status, created_at)
            VALUES ('D-diag-new', 'U-diag', '孩子平板', 'SN-DUP-001', 'ONLINE', 2000)
            """.trimIndent()
        )
        jdbc.update(
            """
            INSERT INTO devices (id, user_id, name, device_sn, online_status, created_at)
            VALUES ('D-diag-blank', 'U-diag', '孩子平板', '', 'OFFLINE', 3000)
            """.trimIndent()
        )
        jdbc.update(
            """
            INSERT INTO alerts (id, device_id, level, title, message, status, triggered_at)
            VALUES ('AL-diag', 'D-diag-old', 'WARN', 'diag', 'diag', 'ACTIVE', 1500)
            """.trimIndent()
        )

        val statements = scriptStatements()
        // 脚本共 5 段：① ② ③ ④ ⑤
        assertEquals(5, statements.size, "脚本段落数变化时，本测试需同步更新（每段都值得真实跑一遍）")

        val results = statements.map { sql ->
            if (sql.uppercase().startsWith("WITH") || sql.contains("GROUP BY", ignoreCase = true) &&
                !sql.uppercase().startsWith("SELECT COUNT")
            ) {
                jdbc.queryForList(sql)
            } else {
                jdbc.queryForList(sql)
            }
        }

        // ① 重复总览：SN-DUP-001 必须出现，且 parents 数为 1（同家长）
        val overview = results[0]
        assertEquals(1, overview.size, "应恰好一组重复 SN")
        assertEquals("SN-DUP-001", overview[0]["device_sn"])
        assertEquals(2L, (overview[0]["device_count"] as Number).toLong())

        // ② 明细：两条台账都在，且保留判断所需字段齐全
        assertEquals(2, results[1].size, "明细应包含重复 SN 的全部台账")

        // ③ 空串：造了一条，必须查出来——这是加唯一约束前的清理项
        val blank = results[2]
        assertEquals(1L, (blank[0]["blank_sn_devices"] as Number).toLong(), "空串 SN 必须能被查出")

        // ④ 指纹：本测试没造重复指纹，应为空结果
        assertTrue(results[3].isEmpty(), "无重复指纹时第④段应为空")

        // ⑤ 预演：旧台账（created_at 更早）应被识别为"要搬数据"的那条，
        //     其 alerts 行数为 1。这个断言同时验证了窗口函数的保留语义没有写反。
        val movePlan = results[4].associate { it["table_name"] to (it["rows_to_move"] as Number).toLong() }
        assertEquals(1L, movePlan["alerts"], "重复组中较旧台账上的告警应计入搬迁量")
    }
}
