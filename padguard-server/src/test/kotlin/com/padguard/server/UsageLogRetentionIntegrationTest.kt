package com.padguard.server

import com.padguard.server.domain.UsageLog
import com.padguard.server.repository.UsageLogRepository
import com.padguard.server.service.AgreementService
import com.padguard.server.service.UsageLogCleanup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * 用量日志保留期清理的**真实 JPA** 回归（H2 / PostgreSQL 兼容模式）。
 *
 * ## 为什么 JVM 假仓储不够
 * `deleteExpiredExcept` 是 JPQL 批量 delete + 集合参数，语法错误在编译期发现不了；
 * 而 `UsageLogCleanup.cleanup()` 把整段包在 runCatching 里，运行期抛异常只会落成一条
 * error 日志——外部表现是「清理天天在跑、表却一天比一天大」，极难察觉。
 * 因此必须真跑一次 SQL，同时验证：过期用量被删、AGREEMENT 留存、null type 不放过。
 *
 * 不依赖外部中间件（local profile 关 MQTT / Flyway，H2 create-drop）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:retention;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=integration-test-secret-key-32-bytes-minimum-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/itest-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/itest-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080"
])
class UsageLogRetentionIntegrationTest {

    @Autowired lateinit var repo: UsageLogRepository
    @Autowired lateinit var cleanup: UsageLogCleanup

    private val day = 86_400_000L

    private fun log(id: String, type: String?, timestamp: Long) = UsageLog(
        id = id, deviceId = "dev-1", logId = "log-$id", type = type,
        timestamp = timestamp, payloadJson = null, receivedAt = timestamp
    )

    @BeforeEach
    fun reset() {
        repo.deleteAll()
        repo.flush()
    }

    @Test
    fun `bulk delete really removes expired usage logs but keeps compliance evidence`() {
        val now = System.currentTimeMillis()
        val old = now - 40 * day
        repo.saveAll(
            mutableListOf(
                log("usage-old", "USAGE_TOTAL", old),
                log("usage-fresh", "APP_USAGE", now - 2 * day),
                log("dirty-old", null, old),
                log("agree-old", "AGREEMENT", old)
            )
        )
        repo.flush()

        cleanup.cleanup()

        val remaining = repo.findAll().map { it.id }.toSet()
        assertTrue("usage-fresh" in remaining, "保留期内的用量日志不能被删")
        assertTrue("agree-old" in remaining, "AGREEMENT 是合规凭证，必须跨过 30 天保留期")
        assertTrue("usage-old" !in remaining, "过期的用量日志应被清理")
        assertTrue("dirty-old" !in remaining, "type 为空的脏数据不应残留")
        assertEquals(2, remaining.size)
    }

    @Test
    fun `device-shifted timestamps cannot make expired rows immortal`() {
        val now = System.currentTimeMillis()
        val old = now - 40 * day
        // 设备时间被改到未来：旧判据（按 timestamp）会让这行永远不满足过期条件。
        // 判据换成服务端 received_at 后，只要落库够久就必须清掉。
        repo.save(
            UsageLog(
                id = "future-ts", deviceId = "dev-1", logId = "log-future-ts", type = "USAGE_TOTAL",
                timestamp = now + 40 * day, payloadJson = null, receivedAt = old
            )
        )
        repo.flush()

        cleanup.cleanup()

        val remaining = repo.findAll().map { it.id }.toSet()
        assertTrue("future-ts" !in remaining, "落库超过保留期的行必须按 received_at 清掉")
    }

    @Test
    fun `latest agreement lookup returns the newest record`() {
        val now = System.currentTimeMillis()
        repo.saveAll(
            mutableListOf(
                log("agree-1", "AGREEMENT", now - 10 * day),
                log("agree-2", "AGREEMENT", now - 1 * day),
                log("usage", "USAGE_TOTAL", now)
            )
        )
        repo.flush()

        val latest = repo.findFirstByDeviceIdAndTypeOrderByTimestampDesc("dev-1", AgreementService.TYPE)
        assertEquals("agree-2", latest?.id, "查询必须取最近一次签署，而不是随便一条")
    }
}
