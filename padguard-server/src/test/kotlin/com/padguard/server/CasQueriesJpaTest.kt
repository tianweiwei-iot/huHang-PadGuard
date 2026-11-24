package com.padguard.server

import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.AppDistribution
import com.padguard.server.domain.Command
import com.padguard.server.domain.Screenshot
import com.padguard.server.domain.UsageSession
import com.padguard.server.repository.AppDistributionRepository
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.repository.ScreenshotRepository
import com.padguard.server.repository.UsageLogRepository
import com.padguard.server.repository.UsageSessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 三条条件 UPDATE / 幂等 INSERT 在**真实数据库**上的执行验证。
 *
 * ## 为什么不能只靠内存 fake
 * 第 2/3/4 批把 `reportRest` / `applyAck` / `start` 从「读-改-写」改成 CAS，
 * 单元测试全部用内存假仓储验证调用语义，全量 `@SpringBootTest` 只保证 Spring 能
 * **解析**这些 JPQL / native SQL。二者之间还有一段空白：JPQL 语法、H2 与 PG 的
 * `on conflict`、`not in :collection`、`case when`、批量 UPDATE 的返回行数，
 * 只有真库执行过才算数。否则上线后第一次日切才报 QuerySyntaxException，
 * 而那时孩子端已经在等服务端返回起点。
 *
 * 这里跑在 local profile 的 H2（MODE=PostgreSQL）上，与 `PolicyVersionConcurrencyTest`
 * 同一套上下文参数；H2 通过不等于 PG16 通过，生产切换前仍需在 PG 上复跑一遍。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:casqueries;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=cas-queries-test-secret-key-32-bytes",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/casqueries-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/casqueries-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080"
])
@DisplayName("CAS 查询在真实 H2 上的执行验证")
class CasQueriesJpaTest {

    @Autowired private lateinit var usageSessionRepository: UsageSessionRepository
    @Autowired private lateinit var commandRepository: CommandRepository
    @Autowired private lateinit var usageLogRepository: UsageLogRepository
    @Autowired private lateinit var deviceEventRepository: DeviceEventRepository
    @Autowired private lateinit var screenshotRepository: ScreenshotRepository
    @Autowired private lateinit var appDistributionRepository: AppDistributionRepository

    private val today: String get() = Instant.now().atZone(SERVER_ZONE_ID).toLocalDate().toString()

    @Test
    @Transactional
    @DisplayName("startFreshIfStale：跨日重置成功一次，第二次影响 0 行且不清零既有写入")
    fun `startFreshIfStale executes and only the first writer wins`() {
        val now = System.currentTimeMillis()
        usageSessionRepository.save(
            UsageSession(
                deviceId = "dev-cas-1", dayKey = "2000-01-01", startEpoch = now - 86_400_000L,
                quotaMinutes = 120, extendedMinutes = 30, restAccumulatedMs = 60_000L,
                restIds = "old-rest", createdAt = now - 172_800_000L, updatedAt = now - 86_400_000L
            )
        )
        assertEquals(1, usageSessionRepository.startFreshIfStale("dev-cas-1", today, now, 90, now))
        // 第二个并发者：条件已不成立，绝不能再用自己的墙钟把起点往后推
        assertEquals(0, usageSessionRepository.startFreshIfStale("dev-cas-1", today, now + 500, 60, now + 500))

        val row = usageSessionRepository.findByDeviceId("dev-cas-1")!!
        assertEquals(today, row.dayKey)
        assertEquals(now, row.startEpoch, "第二次 CAS 不得改动起点")
        assertEquals(90, row.quotaMinutes)
        assertEquals(0, row.extendedMinutes, "跨日重置必须清零昨日延时")
        assertEquals(0L, row.restAccumulatedMs)
        assertEquals("", row.restIds)
        assertEquals(now - 172_800_000L, row.createdAt, "createdAt 是审计字段，非 0 的历史值应保留")
    }

    @Test
    @Transactional
    @DisplayName("insertIfAbsent：并发首建行只有一次生效，后到者不覆盖起点/额度")
    fun `insertIfAbsent is conflict safe`() {
        val now = System.currentTimeMillis()
        assertEquals(1, usageSessionRepository.insertIfAbsent("dev-cas-2", today, now, 120, now))
        assertEquals(0, usageSessionRepository.insertIfAbsent("dev-cas-2", today, now + 1_000, 60, now + 1_000))

        val row = usageSessionRepository.findByDeviceId("dev-cas-2")!!
        assertEquals(now, row.startEpoch)
        assertEquals(120, row.quotaMinutes)
        assertEquals(today, row.dayKey)
        assertEquals(1, usageSessionRepository.count().toInt())
    }

    @Test
    @Transactional
    @DisplayName("appendRestIfUnchanged：旧值不匹配时影响 0 行（第 2 批查询真库复核）")
    fun `appendRestIfUnchanged honours the CAS predicate`() {
        val now = System.currentTimeMillis()
        usageSessionRepository.insertIfAbsent("dev-cas-3", today, now, 120, now)
        assertEquals(
            1,
            usageSessionRepository.appendRestIfUnchanged("dev-cas-3", today, 30_000L, "r1", now, 0L, "")
        )
        assertEquals(
            0,
            usageSessionRepository.appendRestIfUnchanged("dev-cas-3", today, 30_000L, "r1", now, 0L, ""),
            "旧值已变化，第二次必须影响 0 行，否则重复扣减休息时长"
        )
        assertEquals(30_000L, usageSessionRepository.findByDeviceId("dev-cas-3")!!.restAccumulatedMs)
    }

    @Test
    @Transactional
    @DisplayName("applyAckIfOpen：终态不可复活，且按 msgId+deviceId 双键隔离（第 3 批遗留）")
    fun `applyAckIfOpen executes on a real database`() {
        val terminal = listOf("SUCCESS", "UNSUPPORTED", "DUPLICATED", "REJECTED", "EXPIRED")
        // msg_id 在表上有唯一约束（真库实测），因此双键隔离用「错误 deviceId 不得命中」来验，
        // 而不是插两条同 msgId 的行 —— 后者会先撞唯一约束，测不到 CAS 本身。
        commandRepository.save(
            Command(id = "cmd-cas-1", deviceId = "dev-cas-4", msgId = "msg-cas-1", type = "LOCK", status = "PENDING")
        )
        assertEquals(
            0,
            commandRepository.applyAckIfOpen("msg-cas-1", "dev-other", terminal, "SUCCESS", 999L, null, null),
            "知道 msgId 但 deviceId 不匹配，绝不允许改写别人的指令"
        )
        assertEquals("PENDING", commandRepository.findByMsgIdAndDeviceId("msg-cas-1", "dev-cas-4")!!.status)

        assertEquals(
            1,
            commandRepository.applyAckIfOpen("msg-cas-1", "dev-cas-4", terminal, "SUCCESS", 123L, null, null)
        )
        // 终态不可被迟到回执覆盖
        assertEquals(
            0,
            commandRepository.applyAckIfOpen("msg-cas-1", "dev-cas-4", terminal, "FAILED", 456L, "E", "late")
        )
        val row = commandRepository.findByMsgIdAndDeviceId("msg-cas-1", "dev-cas-4")!!
        assertEquals("SUCCESS", row.status)
        assertEquals(123L, row.executedAt)
    }

    @Test
    @Transactional
    @DisplayName("insertIgnoreConflict：S8 批写降级路径在真库可执行（第 4 批实测修正）")
    fun `insertIgnoreConflict executes on a real database`() {
        val now = System.currentTimeMillis()
        assertEquals(
            1,
            usageLogRepository.insertIgnoreConflict(
                "log-cas-1", "dev-cas-6", "logid-cas-1", "USAGE", now, "{}", now
            )
        )
        assertEquals(
            0,
            usageLogRepository.insertIgnoreConflict(
                "log-cas-2", "dev-cas-6", "logid-cas-1", "USAGE", now, "{}", now
            ),
            "重复 logId 应被 on conflict 吞掉，不影响 0 行"
        )
        assertEquals(1, usageLogRepository.count().toInt())
    }

    @Test
    @Transactional
    @DisplayName("findByDeviceIdForUpdate：真库生成 for update，且行不存在时安全返回 null")
    fun `pessimistic lock query executes on a real database`() {
        val now = System.currentTimeMillis()
        usageSessionRepository.insertIfAbsent("dev-cas-7", today, now, 120, now)

        val row = usageSessionRepository.findByDeviceIdForUpdate("dev-cas-7")
        assertNotNull(row, "行锁查询必须能读到已存在的会话行")
        assertEquals(today, row!!.dayKey)
        assertEquals(120, row.quotaMinutes)
        // 会话未建立时两个事务都拿不到锁，也都不会落工单 —— 不能因此报错。
        assertNull(usageSessionRepository.findByDeviceIdForUpdate("dev-not-started"))
    }

    @Test
    @Transactional
    @DisplayName("rebaseExpiredSession：额度耗尽后批准加时，剩余精确等于批准分钟数且幂等")
    fun `rebaseExpiredSession executes and grants full minutes after expiry`() {
        val now = System.currentTimeMillis()
        val quota = 60
        val minuteMs = 60_000L
        // 2 小时前起表、基础额度 60 分钟：额度耗尽后孩子又在锁屏页等了 60 分钟才被批准。
        // 这段设备不可用的等待本不该算"使用"，否则新增的 30 分钟会被历史超支整笔吃掉。
        usageSessionRepository.save(
            UsageSession(
                deviceId = "dev-cas-rebase", dayKey = today,
                startEpoch = now - 120 * minuteMs, quotaMinutes = quota,
                extendedMinutes = 0, restAccumulatedMs = 0L, restIds = "",
                createdAt = now - 120 * minuteMs, updatedAt = now - 120 * minuteMs
            )
        )
        assertEquals(1, usageSessionRepository.addExtendedMinutes("dev-cas-rebase", 30, now, 120))
        assertEquals(
            1,
            usageSessionRepository.rebaseExpiredSession("dev-cas-rebase", today, now, quota, 30, minuteMs),
            "JPQL 批量 UPDATE 必须能在真库执行并命中已耗尽会话"
        )

        val row = usageSessionRepository.findByDeviceId("dev-cas-rebase")!!
        assertEquals(30, row.extendedMinutes)
        assertEquals(now - quota * minuteMs, row.startEpoch!!, "起点应后移到「加时前已用满额度」的时刻")
        val remaining = (quota + row.extendedMinutes) * minuteMs - (now - row.startEpoch!!) - row.restAccumulatedMs
        assertEquals(30 * minuteMs, remaining, "批准的 30 分钟必须完整变成剩余时长")

        // 幂等：条件已不成立，重复调用不得再推起点（否则每批准一次就白送一段时长）。
        assertEquals(0, usageSessionRepository.rebaseExpiredSession("dev-cas-rebase", today, now, quota, 30, minuteMs))
    }

    @Test
    @Transactional
    @DisplayName("rebaseExpiredSession：额度未耗尽时不得改动起点")
    fun `rebaseExpiredSession is a no-op when quota remains`() {
        val now = System.currentTimeMillis()
        val minuteMs = 60_000L
        usageSessionRepository.insertIfAbsent("dev-cas-rebase-2", today, now, 60, now)
        assertEquals(1, usageSessionRepository.addExtendedMinutes("dev-cas-rebase-2", 30, now, 120))
        assertEquals(0, usageSessionRepository.rebaseExpiredSession("dev-cas-rebase-2", today, now, 60, 30, minuteMs))
        assertEquals(now, usageSessionRepository.findByDeviceId("dev-cas-rebase-2")!!.startEpoch!!)
    }

    @Test
    @Transactional
    @DisplayName("device_events 幂等插入降级路径在真库可执行（第 5 批补齐第 4 批漏改）")
    fun `device event insert ignore conflict executes on a real database`() {
        val now = System.currentTimeMillis()
        assertEquals(
            1,
            deviceEventRepository.insertIgnoreConflict(
                "ev-cas-1", "dev-cas-8", "evt-cas-1", "INFO", "INFO", "{}", now
            )
        )
        assertEquals(
            0,
            deviceEventRepository.insertIgnoreConflict(
                "ev-cas-2", "dev-cas-8", "evt-cas-1", "INFO", "INFO", "{}", now
            ),
            "重复 eventId 应被 on conflict 吞掉；带列名的旧写法在 H2 上会直接抛语法错"
        )
        assertEquals(1, deviceEventRepository.count().toInt())
    }

    @Test
    @Transactional
    @DisplayName("第 7 批：截图条件 UPDATE 只改 status，READY 行与 fileUrl 不受影响")
    fun `screenshot markExpiredIfPending executes on a real database`() {
        screenshotRepository.save(
            Screenshot(
                id = "shot-cas-1", deviceId = "dev-cas-9", shotId = "s9",
                status = "PENDING", fileUrl = "/v1/files/shot-9.jpg"
            )
        )
        screenshotRepository.save(
            Screenshot(
                id = "shot-cas-2", deviceId = "dev-cas-9", shotId = "s10",
                status = "READY", fileUrl = "/v1/files/shot-10.jpg"
            )
        )

        assertEquals(1, screenshotRepository.markExpiredIfPending("dev-cas-9", "s9", "PENDING", "EXPIRED"))
        assertEquals(
            0, screenshotRepository.markExpiredIfPending("dev-cas-9", "s9", "PENDING", "EXPIRED"),
            "已是 EXPIRED，第二次不得再命中"
        )
        assertEquals(
            0, screenshotRepository.markExpiredIfPending("dev-cas-9", "s10", "PENDING", "EXPIRED"),
            "READY 行绝不能被清扫器收敛"
        )
        // 跨设备隔离：知道 shotId 但 deviceId 不对，影响 0 行
        assertEquals(0, screenshotRepository.markExpiredIfPending("dev-other", "s10", "PENDING", "EXPIRED"))

        val expired = screenshotRepository.findById("shot-cas-1").get()
        assertEquals("EXPIRED", expired.status)
        assertEquals("/v1/files/shot-9.jpg", expired.fileUrl, "条件 UPDATE 只改 status，不能抹掉 fileUrl")
        assertEquals("READY", screenshotRepository.findById("shot-cas-2").get().status)
    }

    @Test
    @Transactional
    @DisplayName("第 7 批：分发回执条件 UPDATE 真库执行（CASE WHEN 保留旧错误码 + SUCCESS 不可回退）")
    fun `applyAckIfNotSuccess executes on a real database`() {
        val now = System.currentTimeMillis()
        appDistributionRepository.save(
            AppDistribution(
                id = "dist-cas-1", userId = "u1", deviceId = "dev-cas-10",
                packageName = "com.example.a", msgId = "m-cas-1", status = "PENDING",
                createdAt = now, errorCode = "OLD", errorMessage = "旧错误"
            )
        )
        appDistributionRepository.save(
            AppDistribution(
                id = "dist-cas-2", userId = "u1", deviceId = "dev-cas-10",
                packageName = "com.example.b", msgId = "m-cas-2", status = "SUCCESS", createdAt = now
            )
        )

        // hasError=0：新回执不带错误，必须保留旧错误码（与原整行 merge 语义一致）
        assertEquals(
            1,
            appDistributionRepository.applyAckIfNotSuccess(
                "dist-cas-1", "DISPATCHED", null, null, 0, null, "SUCCESS"
            )
        )
        var row = appDistributionRepository.findById("dist-cas-1").get()
        assertEquals("DISPATCHED", row.status)
        assertEquals("OLD", row.errorCode, "CASE WHEN 的 else 分支必须保留旧错误码")

        // hasError=1：写入新错误
        assertEquals(
            1,
            appDistributionRepository.applyAckIfNotSuccess(
                "dist-cas-1", "FAILED", "NEW", "新错误", 1, null, "SUCCESS"
            )
        )
        row = appDistributionRepository.findById("dist-cas-1").get()
        assertEquals("FAILED", row.status)
        assertEquals("NEW", row.errorCode)

        // 终态不可回退
        assertEquals(
            0,
            appDistributionRepository.applyAckIfNotSuccess(
                "dist-cas-2", "FAILED", "LATE", "迟到回执", 1, null, "SUCCESS"
            )
        )
        assertEquals("SUCCESS", appDistributionRepository.findById("dist-cas-2").get().status)
    }

    @Test
    @Transactional
    @DisplayName("第 7 批：台账成功条件 UPDATE 只接受开放态，且幂等")
    fun `markSuccessIfOpen executes on a real database`() {
        val now = System.currentTimeMillis()
        appDistributionRepository.save(
            AppDistribution(
                id = "dist-cas-3", userId = "u1", deviceId = "dev-cas-11",
                packageName = "com.example.c", msgId = "m-cas-3", status = "DISPATCHED", createdAt = now
            )
        )
        val open = listOf("PENDING", "DISPATCHED", "FAILED")
        assertEquals(1, appDistributionRepository.markSuccessIfOpen("dist-cas-3", now, "SUCCESS", open))
        assertEquals(
            0, appDistributionRepository.markSuccessIfOpen("dist-cas-3", now + 1, "SUCCESS", open),
            "已是 SUCCESS，第二次不得再命中"
        )
        val row = appDistributionRepository.findById("dist-cas-3").get()
        assertEquals("SUCCESS", row.status)
        assertEquals(now, row.finishedAt)
    }
}
