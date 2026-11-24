package com.padguard.server

import com.padguard.server.domain.Device
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PolicyRepository
import com.padguard.server.service.PolicyService
import org.junit.jupiter.api.Assertions.assertEquals
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
 * 策略版本号并发分配的集成测试。
 *
 * ## 为什么需要它
 * 改造前"下一个版本号"是 `policy.version + 1`（读最新行 → 加一 → 插新行）。
 * 家长连续点两下开关、家长端与孩子端同时触发策略重建，都会让两个事务读到同一个旧版本，
 * 插出**两条同号**策略行。孩子端按版本号对账、家长端按最新行回显，
 * 同号两行取到哪条取决于数据库返回顺序 —— 表现为"设置保存成功但设备没变"，
 * 全程没有任何异常日志。这种缺陷靠单线程用例永远测不出来。
 *
 * 这里用真实数据库 + 真实事务并发压同一台设备的策略写入，断言：
 * 1. 版本号严格递增且不重复；
 * 2. 行数与写入次数一致（没有被覆盖而丢失的写入）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:policyver;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=policy-version-test-secret-key-32-bytes",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/policyver-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/policyver-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080"
])
class PolicyVersionConcurrencyTest {

    @Autowired private lateinit var policyService: PolicyService
    @Autowired private lateinit var deviceRepository: DeviceRepository
    @Autowired private lateinit var policyRepository: PolicyRepository

    private val deviceId = "DEV-POLICY-CONC"

    @BeforeEach
    fun setUp() {
        if (deviceRepository.findById(deviceId).isEmpty) {
            deviceRepository.save(
                Device(
                    id = deviceId, userId = "U-conc", name = "并发测试机",
                    scene = "FAMILY", onlineStatus = "OFFLINE", createdAt = System.currentTimeMillis()
                )
            )
            policyService.createDefault(deviceId)
        }
    }

    @Test
    fun `concurrent mode switches never produce duplicate policy versions`() {
        val threads = 8
        val perThread = 3
        val total = threads * perThread
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(total)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        repeat(total) { i ->
            pool.submit {
                try {
                    start.await()
                    // 模式值必须是 applyMode 白名单内的合法值（NORMAL/LEARNING/FOCUS）：
                    // 本用例只关心版本号分配是否并发安全，不需要非法输入
                    policyService.applyMode(deviceId, if (i % 2 == 0) "FOCUS" else "NORMAL")
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        val finished = done.await(60, TimeUnit.SECONDS)
        pool.shutdownNow()

        assertTrue(finished, "并发写入未在 60 秒内完成，可能存在锁等待死锁")
        assertTrue(errors.isEmpty(), "并发写入出现异常：${errors.firstOrNull()}")

        val versions = policyRepository.findAll()
            .filter { it.deviceId == deviceId }
            .map { it.version }
            .sorted()

        assertEquals(versions.distinct().size, versions.size, "出现重复策略版本号：$versions")
        // 默认策略 1 条 + 并发写入 total 条
        assertEquals(total + 1, versions.size, "策略行数与写入次数不符（有写入被覆盖）：$versions")
        assertEquals((1..total + 1).toList(), versions, "版本号必须连续递增，实际=$versions")
    }
}
