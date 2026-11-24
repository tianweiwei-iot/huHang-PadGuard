package com.padguard.server

import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.repository.UsageSessionRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 用**两个真实并发事务**验证 `findByDeviceIdForUpdate` 的行锁确实生效。
 *
 * 内存 fake 只能验证「service 调用了带锁方法」，验证不了「锁真的挡住了后到者」。
 * 而这正是 P2-01 收官的全部依据：如果 `@Lock` 被删掉、或 Hibernate 没生成 `for update`，
 * 单测与源码守卫都会照绿，延时申请却会重新落两张工单。这里让第一个事务持锁不放，
 * 第二个事务必须在同一行上等待，直到第一个提交才完成。
 *
 * H2（MODE=PostgreSQL）通过不等于 PG16 通过：生产切换前仍需在 PG 上复跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:rowlock;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=rowlock-test-secret-key-32-bytes-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/rowlock-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/rowlock-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080"
])
@DisplayName("延时申请行锁在真实 H2 上的并发串行化")
class SessionRowLockJpaTest {

    @Autowired private lateinit var sessionRepo: UsageSessionRepository
    @Autowired private lateinit var txManager: PlatformTransactionManager

    private val today: String get() = Instant.now().atZone(SERVER_ZONE_ID).toLocalDate().toString()

    @Test
    fun `第二个事务必须等第一个提交后才拿到会话行`() {
        val tx = TransactionTemplate(txManager)
        val now = System.currentTimeMillis()
        tx.executeWithoutResult {
            sessionRepo.insertIfAbsent("dev-rowlock", today, now, 120, now)
        }

        val firstLocked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        val first = Thread {
            try {
                tx.executeWithoutResult {
                    sessionRepo.findByDeviceIdForUpdate("dev-rowlock")
                    firstLocked.countDown()
                    // 持锁不提交：真实业务里这段就是「查 PENDING → 插工单」的窗口。
                    releaseFirst.await(10, TimeUnit.SECONDS)
                }
            } catch (t: Throwable) {
                errors.add(t)
            }
        }
        val second = Thread {
            try {
                tx.executeWithoutResult {
                    secondStarted.countDown()
                    sessionRepo.findByDeviceIdForUpdate("dev-rowlock")
                }
            } catch (t: Throwable) {
                errors.add(t)
            } finally {
                secondFinished.countDown()
            }
        }

        first.start()
        try {
            assertTrue(firstLocked.await(5, TimeUnit.SECONDS), "第一个事务应拿到行锁")
            second.start()
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS), "第二个事务应已启动并进入查询")
            assertFalse(
                secondFinished.await(500, TimeUnit.MILLISECONDS),
                "第二个事务必须在 for update 上等待；若立即完成，说明行锁没生效，" +
                    "延时申请的 check-then-act 窗口仍然敞开"
            )
        } finally {
            // 断言失败也要放行，否则持锁线程会挂到超时，拖垮整轮回归。
            releaseFirst.countDown()
        }

        assertTrue(secondFinished.await(10, TimeUnit.SECONDS), "第一个事务提交后第二个应完成")
        first.join(10_000)
        second.join(10_000)
        assertTrue(errors.isEmpty(), "并发事务不应抛异常：${errors.map { it.message }}")
    }
}
