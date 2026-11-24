package com.padguard.server

import com.padguard.server.domain.Command
import com.padguard.server.repository.CommandRepository
import com.padguard.server.service.CommandRedeliveryService
import com.padguard.server.service.ExpiredCommandSweeper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * W2 补发的**真实 JPA** 回归（H2 / PostgreSQL 兼容模式）。
 *
 * ## 为什么 JVM 假仓储不够（本项目已踩过同类坑）
 * `findRedeliverablePending` 是带集合参数 + 分页的 JPQL，`tryRefreshForRedelivery`
 * 是带 `deliveryCount = deliveryCount + 1` 的批量 UPDATE —— 两者语法错误**编译期发现不了**，
 * 而 `collectRedeliverable` 又把它包在正常流程里，运行期抛异常只会在日志里留一条错误，
 * 外部表现是「补发功能看起来在跑，指令却一条都没重放」。必须真跑一次 SQL。
 *
 * 同时验证数据库层的原子抢占语义：这是整套补发在**多实例部署**下不重复下发的基础，
 * 进程内锁替代不了。
 *
 * 不依赖任何外部中间件（local profile 关 MQTT / Flyway，H2 create-drop）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:redelivery;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=integration-test-secret-key-32-bytes-minimum-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/itest-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/itest-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080",
    "padguard.command.redelivery-retention-ms=600000",
    "padguard.command.redelivery-max-attempts=2",
    "padguard.command.redelivery-max-batch=5"
])
class CommandRedeliveryIntegrationTest {

    @Autowired lateinit var repo: CommandRepository
    @Autowired lateinit var redelivery: CommandRedeliveryService
    @Autowired lateinit var sweeper: ExpiredCommandSweeper

    private companion object {
        const val TTL = 300_000L
    }

    @BeforeEach
    fun reset() {
        repo.deleteAll()
        repo.flush()
    }

    private fun cmd(
        id: String,
        type: String = "REFRESH_POLICY",
        expiresAt: Long,
        createdAt: Long,
        deadline: Long?,
        count: Int = 0,
        status: String = "PENDING"
    ) = Command(
        id = id, deviceId = "dev-1", msgId = "msg-$id", type = type,
        priority = "HIGH", payloadJson = null, signature = "sig-$id",
        status = status, expiresAt = expiresAt, createdAt = createdAt,
        deliveryCount = count, redeliveryDeadlineAt = deadline
    )

    @Test
    fun `JPQL really selects only expired retained whitelisted commands and refreshes their window`() {
        val now = System.currentTimeMillis()
        repo.saveAll(
            listOf(
                cmd("eligible", expiresAt = now - 1_000, createdAt = now - 400_000, deadline = now + 100_000),
                cmd("still-valid", expiresAt = now + 50_000, createdAt = now - 10_000, deadline = now + 100_000),
                cmd("past-deadline", expiresAt = now - 1_000, createdAt = now - 400_000, deadline = now - 1),
                cmd("max-attempts", expiresAt = now - 1_000, createdAt = now - 400_000, deadline = now + 100_000, count = 2),
                // 故意手动塞 deadline，验证白名单在 SQL 层仍然生效（不能只看服务端判定）
                cmd("not-whitelisted", type = "UNLOCK", expiresAt = now - 1_000,
                    createdAt = now - 400_000, deadline = now + 100_000),
                cmd("already-done", expiresAt = now - 1_000, createdAt = now - 400_000,
                    deadline = now + 100_000, status = "SUCCESS"),
                // NULL deadline = 存量行，绝不能被重放
                cmd("legacy-row", expiresAt = now - 1_000, createdAt = now - 400_000, deadline = null)
            )
        )
        repo.flush()

        val found = redelivery.collectRedeliverable("dev-1", now)

        assertEquals(1, found.size, "只有真正符合全部条件的那一条能被补发")
        assertEquals("msg-eligible", found[0].command.msgId)

        // 真实 UPDATE 必须落库：新有效期 / 计数 / 时刻，一个都不能少
        val reloaded = repo.findById("eligible").get()
        assertTrue(reloaded.expiresAt > now, "刷新后必须脱离过期区间")
        assertEquals(1, reloaded.deliveryCount)
        assertNotNull(reloaded.lastDeliveryAt)
        // 契约字段一个都不许动
        assertEquals("msg-eligible", reloaded.msgId)
        assertEquals("sig-eligible", reloaded.signature)
        assertEquals("REFRESH_POLICY", reloaded.type)
        assertEquals(now - 400_000, reloaded.createdAt)
    }

    @Test
    fun `atomic conditional update wins exactly once under concurrent polls`() {
        val now = System.currentTimeMillis()
        repo.saveAndFlush(
            cmd("race", expiresAt = now - 1_000, createdAt = now - 400_000, deadline = now + 100_000)
        )

        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = (1..2).map {
                pool.submit<Int> {
                    start.await()
                    repo.tryRefreshForRedelivery("race", "PENDING", now, now + TTL)
                }
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == 1 }, "并发下必须恰好一个请求抢到补发权")
            assertEquals(1, results.count { it == 0 }, "另一个必须被条件 UPDATE 挡住")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a second redelivery attempt after the new window expires is allowed until max attempts`() {
        val now = System.currentTimeMillis()
        // deadline 必须覆盖两轮补发窗口（每轮 TTL=5min），否则第二轮会因超保留期被正确拒绝，
        // 那测到的就不是「次数上限」而是「保留期」了。
        repo.saveAndFlush(
            cmd("twice", expiresAt = now - 1_000, createdAt = now - 400_000, deadline = now + TTL * 4)
        )
        assertEquals(1, redelivery.collectRedeliverable("dev-1", now).size)

        // 模拟「补发后设备又没上线、新窗口再次过期」，时间仍在保留期内且次数未达上限
        val later = now + TTL + 1_000
        assertEquals(1, redelivery.collectRedeliverable("dev-1", later).size)
        assertEquals(2, repo.findById("twice").get().deliveryCount)

        // 达到配置上限（2）后不再补发
        val evenLater = later + TTL + 1_000
        assertEquals(0, redelivery.collectRedeliverable("dev-1", evenLater).size)
    }

    @Test
    fun `sweeper keeps eligible commands PENDING but converges everything else`() {
        val now = System.currentTimeMillis()
        repo.saveAll(
            listOf(
                cmd("keep", expiresAt = now - 1_000, createdAt = now - 400_000, deadline = now + 100_000),
                cmd("expire", type = "UNLOCK", expiresAt = now - 1_000,
                    createdAt = now - 400_000, deadline = now + 100_000),
                cmd("past", expiresAt = now - 1_000, createdAt = now - 400_000, deadline = now - 1)
            )
        )
        repo.flush()

        sweeper.sweep("dev-1", now)

        assertEquals("PENDING", repo.findById("keep").get().status, "可补发的必须留着等上线重放")
        assertEquals("EXPIRED", repo.findById("expire").get().status, "一次性指令过期即终态")
        assertEquals("EXPIRED", repo.findById("past").get().status, "超保留期必须收敛，不能永久挂 PENDING")
    }

    @Test
    fun `batch limit is enforced in SQL not only in memory`() {
        val now = System.currentTimeMillis()
        repo.saveAll(
            (1..8).map { i ->
                cmd("bulk-$i", expiresAt = now - 1_000, createdAt = now - 400_000 + i, deadline = now + 100_000)
            }
        )
        repo.flush()

        // 配置 redelivery-max-batch=5：单次最多补发 5 条，剩下的留给下一轮，
        // 防设备长期离线后上线瞬间被打爆
        assertEquals(5, redelivery.collectRedeliverable("dev-1", now).size)
    }
}
