package com.padguard.server

import com.padguard.server.repository.AppDistributionRepository
import com.padguard.server.repository.ScreenshotRepository
import com.padguard.server.repository.UserSessionRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.io.File

/**
 * 第 7 批的**源码级契约守卫**：把三处「状态终态不可回退 / 冷却表失败路径」固化成测试。
 *
 * 这些约束都无法用类型表达：把条件 UPDATE 改回 `save(整行)`、删掉 `@Modifying`、
 * 把 `WHERE status = ...` 去掉、或者把冷却占位重新挪到设备存在性校验之前，编译照过、
 * 单线程业务测试照绿，只有并发或异常路径下才爆 —— 而那时已经在生产了。
 *
 * 源码从 `src/main/kotlin` 读取，与 `ScheduledTraceContractTest` 同一约定。
 */
@DisplayName("状态终态与冷却表接线契约守卫")
class StateTransitionContractTest {

    private val mainSrc = File("src/main/kotlin/com/padguard/server")

    private fun isCode(line: String): Boolean {
        val t = line.trimStart()
        return t.isNotEmpty() && !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*")
    }

    private fun methodBody(file: File, startMarker: String, endMarker: String): String {
        val lines = file.readLines()
        val start = lines.indexOfFirst { it.trimStart().startsWith(startMarker) }
        assertTrue(start >= 0, "${file.name} 找不到 $startMarker")
        val end = (start + 1 until lines.size).firstOrNull {
            lines[it].trimStart().startsWith(endMarker)
        } ?: minOf(start + 90, lines.size - 1)
        return lines.subList(start, minOf(end, lines.size)).joinToString("\n")
    }

    @Test
    @DisplayName("截图收敛不得回退到整行 save，必须走条件 UPDATE")
    fun `screenshot convergence never uses a whole-row save`() {
        val body = methodBody(
            File(mainSrc, "service/ExpiredCommandSweeper.kt"),
            "private fun convergeRelatedRecords(", "private fun readPayload("
        )
        assertTrue(body.contains("markExpiredIfPending"), "截图状态必须由条件 UPDATE 决定")
        assertFalse(body.contains("screenshotRepository.save("), "整行 merge 会抹掉刚写好的 fileUrl / READY 状态")
        assertFalse(body.contains("findFirstByDeviceIdAndShotId("), "先查再改就是 check-then-act 窗口")
    }

    @Test
    @DisplayName("分发台账两处收敛都不得整行 save")
    fun `distribution convergence never uses a whole-row save`() {
        val code = File(mainSrc, "service/AppDistributionConverger.kt")
            .readLines().filter { isCode(it) }.joinToString("\n")
        assertFalse(
            code.contains("appDistributionRepository.save("),
            "终态不可回退必须靠数据库条件；内存读判 `if (status == SUCCESS) continue` 挡不住并发"
        )
        assertTrue(code.contains("applyAckIfNotSuccess"), "回执收敛缺少条件 UPDATE")
        assertTrue(code.contains("markSuccessIfOpen"), "台账收敛缺少条件 UPDATE")
    }

    @Test
    @DisplayName("告警冷却必须原子占位，且失败路径精确回滚")
    fun `alert cooldown claims atomically and rolls back on failure`() {
        val body = methodBody(File(mainSrc, "service/AlertService.kt"), "fun create(", "fun list(")
        assertTrue(body.contains("claimCooldown("), "并发下两个请求都读到 prev=null 会各建一条告警")
        assertTrue(body.contains("remove(key, now)"), "失败路径不回滚会留下幽灵冷却 key")
        assertFalse(
            body.contains("lastRaisedAt[key] ="),
            "直接赋值把名额落在设备存在性校验之前，设备不存在时也会占坑"
        )
        val whole = File(mainSrc, "service/AlertService.kt").readText()
        assertTrue(whole.contains("MAX_COOLDOWN_KEYS"), "冷却表缺少软上界淘汰，伪造 deviceId 可无界累积")
    }

    @Test
    @DisplayName("三个条件 UPDATE 必须带 @Modifying / @Transactional 且 WHERE 里真的有状态条件")
    fun `conditional updates keep their annotations and predicates`() {
        val checks = listOf(
            Triple(ScreenshotRepository::class.java, "markExpiredIfPending", "s.status = :pending"),
            Triple(AppDistributionRepository::class.java, "applyAckIfNotSuccess", "d.status <> :success"),
            Triple(AppDistributionRepository::class.java, "markSuccessIfOpen", "d.status in :openStatuses"),
            // refresh token 原子轮换：WHERE 按旧 jti 认领，是"单次使用/防并发重放"的原子点
            Triple(UserSessionRepository::class.java, "rotateRefreshJti", "s.refreshJti = :oldJti")
        )
        checks.forEach { (type, name, predicate) ->
            val method = type.declaredMethods.firstOrNull { it.name == name }
            assertNotNull(method, "$type 缺少 $name")
            val modifying = method!!.getAnnotation(Modifying::class.java)
            assertNotNull(modifying, "$name 缺少 @Modifying")
            assertTrue(
                modifying!!.flushAutomatically,
                "$name 必须 flushAutomatically：先前的 save 未落库时，条件 UPDATE 会找不到行"
            )
            assertTrue(
                modifying.clearAutomatically,
                "$name 必须 clearAutomatically：批量 UPDATE 绕过持久化上下文，不清会让同事务读到旧实体"
            )
            assertNotNull(method.getAnnotation(Transactional::class.java), "$name 缺少 @Transactional")
            val query = method.getAnnotation(Query::class.java) ?: error("$name 缺少 @Query")
            // 走 Java 反射取注解值：本项目 Spring Data 的 Query.value() 是**单个 String**
            // （各处 @Query 都是 "..." + "..." 拼成的一整句），不是 String[]。
            // 直接遍历会被 Kotlin 当 CharSequence 逐字符迭代，拼出每个字母间都带空格的假 SQL。
            val sql = Query::class.java.getMethod("value").invoke(query) as String
            assertTrue(
                sql.contains(predicate),
                "$name 的 WHERE 条件被改掉了：'$predicate' 不存在于 $sql，终态保护会失效"
            )
        }
    }

    @Test
    @DisplayName("refresh 轮换必须原子认领旧 jti 且带归属/未吊销守卫，refresh() 不得退回读改写")
    fun `refresh rotation is atomic CAS with ownership guard`() {
        // 1) CAS 方法的 WHERE 必须同时钉住：旧 jti（防并发重放）、userId（防跨租户认领）、
        //    revoked=false（防已吊销会话续期）。少任一条都是可复现的安全洞。
        val method = UserSessionRepository::class.java.declaredMethods
            .firstOrNull { it.name == "rotateRefreshJti" }
        assertNotNull(method, "UserSessionRepository 缺少 rotateRefreshJti 原子轮换方法")
        val query = method!!.getAnnotation(Query::class.java) ?: error("rotateRefreshJti 缺少 @Query")
        val sql = Query::class.java.getMethod("value").invoke(query) as String
        listOf("s.refreshJti = :oldJti", "s.userId = :userId", "s.revoked = false").forEach { cond ->
            assertTrue(sql.contains(cond), "rotateRefreshJti 的 WHERE 缺少 '$cond'：$sql")
        }

        // 2) refresh() 必须调用 CAS，且不得退回「读 jti → 改实体字段 → save」的读改写窗口
        val authSrc = File(mainSrc, "service/AuthService.kt").readText()
        val refreshBody = methodBody(File(mainSrc, "service/AuthService.kt"), "fun refresh(", "fun logout(")
        assertTrue(refreshBody.contains("rotateRefreshJti("), "refresh() 必须走原子 CAS 轮换")
        // @Transactional 在 fun refresh( 之前一行，必须锚定 refresh 自身，不能用 methodBody
        // （后者会误把下一个方法 logout 前的 @Transactional 算进来，造成假绿）。
        assertTrue(
            Regex("""@Transactional\s+fun refresh\(""").containsMatchIn(authSrc),
            "refresh() 必须带事务，CAS 与签新令牌同一事务边界"
        )
        assertFalse(refreshBody.contains("findByRefreshJtiAndRevokedFalse"), "refresh() 不得读实体——读改写会重新打开并发重放窗口")
        assertFalse(refreshBody.contains("session.refreshJti ="), "refresh() 不得改实体字段再 save，CAS 方法绝不碰实体字段")
    }
}
