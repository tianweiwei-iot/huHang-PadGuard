package com.padguard.server.service

import com.padguard.server.domain.Command
import com.padguard.server.repository.CommandRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.PageRequest

/**
 * W2 离线指令补发的**纯逻辑**回归（JVM + Mockito，不启动 Spring）。
 *
 * 重点钉住三件最容易在后续"顺手优化"时被改坏、且外部现象极隐蔽的事：
 * 1. 白名单必须是 fail-closed：一次性解锁 / 采集类 / 霸屏消息绝不能进补发队列
 *    （设备离线 8 小时后补发"临时解锁 30 分钟" = 白送时长；补发"拍照" = 超范围采集未成年人信息）；
 * 2. 并发下只有抢到原子 UPDATE 的那一个请求能把指令塞进本批响应，
 *    避免 MQTT + HTTP 双通道同时唤醒时同一 msgId 被重复下发；
 * 3. 补发**不能改契约字段**（msgId/type/createdAt/signature），否则终端验签全拒。
 *
 * 真实 JPQL 语法与数据库并发语义另见 CommandRedeliveryIntegrationTest。
 */
class CommandRedeliveryServiceTest {

    private lateinit var repo: CommandRepository

    private companion object {
        const val NOW = 1_000_000L
        const val TTL = 300_000L
        const val RETENTION = 86_400_000L
    }

    @BeforeEach
    fun setUp() {
        repo = Mockito.mock(CommandRepository::class.java)
    }

    private fun service(
        enabled: Boolean = true,
        ttl: Long = TTL,
        retention: Long = RETENTION,
        attempts: Int = 3,
        batch: Int = 10,
        types: String = ""
    ) = CommandRedeliveryService(repo, enabled, ttl, retention, attempts, batch, types)

    private fun cmd(
        id: String = "c1",
        type: String = "REFRESH_POLICY",
        createdAt: Long = NOW - 600_000L,
        expiresAt: Long = NOW - 1L,
        deadline: Long? = NOW + RETENTION,
        deliveryCount: Int = 0,
        status: String = "PENDING"
    ) = Command(
        id = id, deviceId = "dev-1", msgId = "msg-$id", type = type,
        priority = "HIGH", payloadJson = null, signature = "sig-$id",
        status = status, expiresAt = expiresAt, createdAt = createdAt,
        deliveryCount = deliveryCount, redeliveryDeadlineAt = deadline
    )

    // ---------- 白名单：fail-closed ----------

    @Test
    fun `only stateful commands get a redelivery deadline`() {
        val s = service()
        val stateful = listOf(
            "REFRESH_POLICY", "UPDATE_CONFIG", "SET_APP_SUSPENDED", "SET_APP_HIDDEN",
            "SET_RUNTIME_PERMISSION", "SET_FEATURE_POLICY", "SET_WATERMARK", "SET_MANAGEMENT_ACTIVE"
        )
        stateful.forEach { type ->
            assertEquals(NOW + RETENTION, s.deadlineFor(type, NOW), "$type 应可补发")
        }
        // 大小写不敏感：新增指令类型时大小写写错不该静默变成"不补发"
        assertEquals(NOW + RETENTION, s.deadlineFor("refresh_policy", NOW))
    }

    @Test
    fun `one-shot unlock capture and message commands are NEVER redeliverable`() {
        val s = service()
        val forbidden = listOf(
            "UNLOCK", "TEMP_UNLOCK", "SHOW_MESSAGE", "SCREENSHOT", "LOCATE", "PHOTO",
            "RECORD", "STOP_RECORD", "SCREEN_RECORD", "STOP_SCREEN_RECORD",
            "LIVE_VIEW_START", "LIVE_VIEW_STOP", "FLUSH_LOGS", "REVEAL_MANAGEMENT_UI",
            "REBOOT", "SHUTDOWN", "WIPE", "LOCK_SCREEN", "INSTALL_APP", "UNINSTALL_APP",
            "SOME_FUTURE_COMMAND_NOT_YET_CLASSIFIED"
        )
        forbidden.forEach { type ->
            assertNull(s.deadlineFor(type, NOW), "$type 绝不能补发")
            assertFalse(s.isEligible(cmd(type = type), NOW), "$type 绝不能被判定为待补发")
        }
    }

    // ---------- 开关与资格判定 ----------

    @Test
    fun `disabled switch fully falls back to pre-iteration behaviour`() {
        val s = service(enabled = false)
        assertNull(s.deadlineFor("REFRESH_POLICY", NOW))
        assertFalse(s.isEligible(cmd(), NOW))
        assertTrue(s.collectRedeliverable("dev-1", NOW).isEmpty())
        Mockito.verifyNoInteractions(repo)
    }

    @Test
    fun `eligibility honours deadline attempt count and whitelist`() {
        val s = service()
        assertTrue(s.isEligible(cmd(), NOW), "保留期内、未补发过、白名单内 → 保留")
        assertFalse(s.isEligible(cmd(deadline = NOW - 1), NOW), "超过保留期 → 收敛")
        assertFalse(s.isEligible(cmd(deadline = null), NOW), "NULL deadline（存量行）→ 不补发")
        assertFalse(s.isEligible(cmd(deliveryCount = 3), NOW), "达到最大次数 → 收敛")
        assertFalse(s.isEligible(cmd(type = "UNLOCK"), NOW), "不在白名单 → 收敛")
        assertFalse(s.isEligible(cmd(status = "SUCCESS"), NOW), "已终态不该由本判定放过")
    }

    // ---------- 原子抢占 ----------

    @Test
    fun `only the request that wins the atomic update gets the command`() {
        val s = service()
        val a = cmd(id = "a")
        val b = cmd(id = "b")
        Mockito.`when`(
            repo.findRedeliverablePending("dev-1", "PENDING", NOW, 3, s.redeliveryTypes, PageRequest.of(0, 10))
        ).thenReturn(listOf(a, b))
        Mockito.`when`(repo.tryRefreshForRedelivery("a", "PENDING", NOW, NOW + TTL)).thenReturn(1)
        // 另一个并发轮询已抢先刷新 b，本请求必须跳过它
        Mockito.`when`(repo.tryRefreshForRedelivery("b", "PENDING", NOW, NOW + TTL)).thenReturn(0)

        val result = s.collectRedeliverable("dev-1", NOW)

        assertEquals(1, result.size)
        assertEquals("msg-a", result[0].command.msgId)
        assertEquals(NOW + TTL, result[0].refreshedExpiresAt, "必须用刷新后的新有效期下发")
    }

    @Test
    fun `redelivery keeps every contract field untouched`() {
        val s = service()
        val original = cmd(id = "a", createdAt = NOW - 3_600_000L)
        Mockito.`when`(
            repo.findRedeliverablePending("dev-1", "PENDING", NOW, 3, s.redeliveryTypes, PageRequest.of(0, 10))
        ).thenReturn(listOf(original))
        Mockito.`when`(repo.tryRefreshForRedelivery("a", "PENDING", NOW, NOW + TTL)).thenReturn(1)

        val result = s.collectRedeliverable("dev-1", NOW).single()

        // 终端 CommandGate 验签用 msgId|type|timestamp|canonicalPayload，且按 msgId 幂等。
        // 这四个字段任一改变，补发的指令会被判伪造或当成一条新指令重复执行。
        assertEquals("msg-a", result.command.msgId)
        assertEquals("REFRESH_POLICY", result.command.type)
        assertEquals(NOW - 3_600_000L, result.command.createdAt)
        assertEquals("sig-a", result.command.signature)
        assertEquals("HIGH", result.command.priority)
        assertEquals("PENDING", result.command.status)
        assertEquals(NOW + TTL, result.refreshedExpiresAt)
    }

    @Test
    fun `FIFO order is preserved and batch is bounded at repository level`() {
        val s = service(batch = 2)
        val older = cmd(id = "older", createdAt = NOW - 900_000L)
        val newer = cmd(id = "newer", createdAt = NOW - 600_000L)
        Mockito.`when`(
            repo.findRedeliverablePending("dev-1", "PENDING", NOW, 3, s.redeliveryTypes, PageRequest.of(0, 2))
        ).thenReturn(listOf(older, newer))
        // 用具体值 stub：Kotlin 非空参数下 Mockito.eq() 返回 null 会触发 NPE 并泄漏 matcher
        Mockito.`when`(repo.tryRefreshForRedelivery("older", "PENDING", NOW, NOW + TTL)).thenReturn(1)
        Mockito.`when`(repo.tryRefreshForRedelivery("newer", "PENDING", NOW, NOW + TTL)).thenReturn(1)

        val result = s.collectRedeliverable("dev-1", NOW)

        assertEquals(listOf("msg-older", "msg-newer"), result.map { it.command.msgId })
    }

    @Test
    fun `empty whitelist short-circuits before issuing an invalid IN () query`() {
        // 配置显式写空 = 主动关闭补发；绝不能退化成"不带类型过滤、全量重放"
        val s = service(types = "  ,  , ")
        assertTrue(s.collectRedeliverable("dev-1", NOW).isEmpty())
        Mockito.verifyNoInteractions(repo)
    }

    @Test
    fun `custom whitelist from configuration is honoured`() {
        val s = service(types = "UPDATE_CONFIG, LOCK_SCREEN")
        assertEquals(NOW + RETENTION, s.deadlineFor("LOCK_SCREEN", NOW))
        assertEquals(NOW + RETENTION, s.deadlineFor("UPDATE_CONFIG", NOW))
        assertNull(s.deadlineFor("REFRESH_POLICY", NOW))
    }
}
