package com.padguard.server

import com.padguard.server.domain.Alert
import com.padguard.server.domain.Device
import com.padguard.server.domain.EnrollmentSession
import com.padguard.server.domain.PaymentOrder
import com.padguard.server.domain.Subscription
import com.padguard.server.domain.User
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.EnrollmentSessionRepository
import com.padguard.server.repository.ParentErasureDao
import com.padguard.server.repository.PaymentOrderRepository
import com.padguard.server.repository.SubscriptionRepository
import com.padguard.server.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional

/**
 * 注销清理语句在**真实数据库**上的执行验证。
 *
 * 纯 JVM 契约测试只能保证"清单没漏表"，保证不了 JPQL 的实体名/字段名拼对、
 * bulk update 语法在真库上跑得通 —— 这些只有执行过才算数（编译期完全静默）。
 * 这里跑 local profile 的 H2（ddl-auto=create-drop，无外键），与 CasQueriesJpaTest 同一套参数；
 * H2 通过不等于 PG16 通过，但配合 ParentErasureContractTest 的外键表清单守卫，
 * 已覆盖"生产因外键删不掉"的那一类失效。PG 上的复跑仍应在切换生产库前做一次。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:parenterase;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=parent-erasure-test-secret-key-32-bytes",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/parenterase-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/parenterase-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080",
    "padguard.mqtt.enabled=false"
])
@DisplayName("家长账号注销清理在真实 H2 上执行")
class ParentErasureJpaTest {

    @Autowired private lateinit var dao: ParentErasureDao
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var devices: DeviceRepository
    @Autowired private lateinit var subs: SubscriptionRepository
    @Autowired private lateinit var orders: PaymentOrderRepository
    @Autowired private lateinit var alerts: AlertRepository
    @Autowired private lateinit var enrollments: EnrollmentSessionRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    @Transactional
    @DisplayName("全部清理语句可执行：设备保留并解绑、订单脱敏、其余关联行清除")
    fun `erasure statements execute and keep assets`() {
        val uid = "U-erasure-1"
        users.save(User(uid, "19900007777", "hash"))
        devices.save(Device(id = "D-erasure-1", userId = uid).apply {
            deviceTokenHash = "old_sha256_token_hash"
            hmacSecret = "old_hmac_secret"
            mqttPassword = "old_mqtt_password"
            guardPasswordHash = "old_guard_hash"
            childName = "旧孩子姓名"
            childNickname = "旧昵称"
            childAvatar = "http://old.example/avatar.png"
            lastIp = "192.168.1.10"
            onlineStatus = "ONLINE"
            groupId = "G-old"
        })
        subs.save(Subscription(id = "S-erasure-1", userId = uid))
        orders.save(PaymentOrder(id = "O-erasure-1", orderNo = "NO-erasure-1", userId = uid))
        alerts.save(Alert(id = "A-erasure-1", deviceId = "D-erasure-1", userId = uid))
        enrollments.save(
            EnrollmentSession(
                id = "E-erasure-1", userId = uid, pairingCode = "ERASE1",
                state = "PENDING", expiresAt = System.currentTimeMillis() + 60_000
            )
        )

        val anon = "erased_test_anon_key"
        val outcome = dao.erase(uid, anon)
        val counts = outcome.counts
        assertEquals(listOf("D-erasure-1"), outcome.deviceIds, "设备 id 必须在置空归属前取出，供后续历史数据清理")

        // 每条语句都真正执行了（不是"写了但没跑"），且影响到了预置的那一行。
        assertEquals(1, counts["devices"], "设备应被解绑")
        assertEquals(1, counts["payment_orders"], "订单归属应被替换")
        assertEquals(1, counts["subscriptions"], "订阅应被删除")
        assertEquals(1, counts["alerts"], "告警应被删除")
        assertEquals(1, counts["enrollment_sessions"], "绑定会话应被删除")
        // H2 的 ddl-auto 形态没有 bind_codes 表，native 分支必须安全跳过而不是报表不存在。
        assertEquals(0, counts["bind_codes"])

        // 绕过 JPA 一级缓存直接看库：bulk 语句不会刷新已托管实体。
        assertNull(
            jdbc.queryForObject("select user_id from devices where id = ?", String::class.java, "D-erasure-1"),
            "设备行必须保留，只是解除归属"
        )
        // 只置空 userId 不够：ChildAuthInterceptor 只比对 deviceTokenHash、不检查归属，
        // 旧令牌不作废的话，已解绑平板仍能通过鉴权继续上报，写出一批无人认领的幽灵数据。
        val dev = jdbc.queryForMap("select * from devices where id = ?", "D-erasure-1")
        assertTrue(
            (dev["device_token_hash"] as String).startsWith("revoked_erasure_"),
            "旧 deviceToken 必须立即失效，否则孩子端仍能凭旧令牌上行"
        )
        assertTrue((dev["hmac_secret"] as String).startsWith("revoked_erasure_"), "HMAC 密钥必须轮换")
        assertTrue((dev["mqtt_password"] as String).startsWith("revoked_erasure_"), "MQTT 口令必须轮换")
        assertEquals("", dev["guard_password_hash"], "守护密码哈希不能留给下一任家长")
        assertEquals("OFFLINE", dev["online_status"], "解绑后设备应立刻离线上报")
        assertNull(dev["child_name"], "孩子姓名属于旧家庭隐私，不能继承")
        assertNull(dev["child_nickname"])
        assertNull(dev["child_avatar"])
        assertNull(dev["last_ip"])
        assertNull(dev["group_id"])
        assertEquals(
            anon,
            jdbc.queryForObject("select user_id from payment_orders where id = ?", String::class.java, "O-erasure-1"),
            "财务流水必须保留，归属换成匿名键"
        )
        assertEquals(0, countRows("subscriptions", uid))
        assertEquals(0, countRows("alerts", uid))
        assertEquals(0, countRows("enrollment_sessions", uid))
        assertTrue(
            jdbc.queryForObject("select count(*) from devices where id = ?", Int::class.java, "D-erasure-1") == 1,
            "设备行不能被误删"
        )
    }

    @Test
    @Transactional
    @DisplayName("目标用户无关联数据时，所有语句仍可安全执行且不误伤他人")
    fun `erasure is a no-op for a clean user`() {
        val uid = "U-erasure-empty"
        users.save(User(uid, "19900007778", "hash"))
        val other = "U-erasure-other"
        users.save(User(other, "19900007779", "hash"))
        devices.save(Device(id = "D-erasure-other", userId = other))

        val outcome = dao.erase(uid, "erased_none")
        val counts = outcome.counts

        assertEquals(0, counts.values.sum(), "干净账号不应影响任何行")
        assertTrue(outcome.deviceIds.isEmpty(), "干净账号没有待清理的设备")
        assertEquals(
            other,
            jdbc.queryForObject("select user_id from devices where id = ?", String::class.java, "D-erasure-other"),
            "不能误伤其他家长的设备归属"
        )
    }

    private fun countRows(table: String, userId: String): Int =
        jdbc.queryForObject("select count(*) from $table where user_id = ?", Int::class.java, userId) ?: 0
}
