package com.padguard.server

import com.padguard.server.domain.Alert
import com.padguard.server.domain.Device
import com.padguard.server.domain.DeviceGroup
import com.padguard.server.domain.EnrollmentSession
import com.padguard.server.domain.PaymentOrder
import com.padguard.server.domain.Subscription
import com.padguard.server.domain.User
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.DeviceGroupRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.EnrollmentSessionRepository
import com.padguard.server.repository.ParentErasureDao
import com.padguard.server.repository.ParentErasurePlan
import com.padguard.server.repository.PaymentOrderRepository
import com.padguard.server.repository.SubscriptionRepository
import com.padguard.server.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional
import java.sql.Connection
import javax.sql.DataSource

/**
 * 注销清理在「有外键」形态下的真库验证。
 *
 * 本机没有 PG16 / Docker，无法直连生产库；但真正会让 `DELETE users` 失败的不是 PG 本身，
 * 而是 Flyway 建的 `REFERENCES users(id)` 约束——本地 H2 的 ddl-auto 一张外键都不建，
 * 这正是「本地删得掉、上线 500」的根因。这里在 H2 上按 [ParentErasurePlan.USER_FK_COLUMNS]
 * 手工补出同一组外键，再跑真实的 JPQL 清理 + 删用户，等价验证清理顺序在外键下成立。
 *
 * 顺带补出 H2 原本没有的 `bind_codes` 表：原测试里 native 分支一直走"表不存在→跳过"，
 * 从未真正执行过删除语句，这个覆盖缺口在此补上。
 *
 * 注意：仍不等于 PG16 实测——JPQL 方言、列宽、事务隔离在 PG 上仍需切换生产库前复跑一次。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads-fk",
    "spring.datasource.url=jdbc:h2:mem:parenterasefk;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=parent-erasure-fk-test-secret-key-32",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/parenterasefk-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/parenterasefk-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080",
    "padguard.mqtt.enabled=false"
])
@DisplayName("家长注销：补齐生产外键后的清理顺序验证")
class ParentErasureForeignKeyJpaTest {

    @Autowired private lateinit var dao: ParentErasureDao
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var devices: DeviceRepository
    @Autowired private lateinit var groups: DeviceGroupRepository
    @Autowired private lateinit var subs: SubscriptionRepository
    @Autowired private lateinit var orders: PaymentOrderRepository
    @Autowired private lateinit var alerts: AlertRepository
    @Autowired private lateinit var enrollments: EnrollmentSessionRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var dataSource: DataSource

    @BeforeAll
    fun installProductionLikeForeignKeys() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                // H2/ddl-auto 没有这张表（项目里没有对应 JPA 实体），生产 Flyway 有。
                st.execute(
                    "CREATE TABLE IF NOT EXISTS bind_codes (" +
                        "code VARCHAR(8) PRIMARY KEY, user_id VARCHAR(64) NOT NULL, device_id VARCHAR(64))"
                )
                ParentErasurePlan.USER_FK_COLUMNS.forEach { (table, column) ->
                    if (hasTable(conn, table)) {
                        st.execute(
                            "ALTER TABLE $table ADD CONSTRAINT fk_${table}_erase_user " +
                                "FOREIGN KEY ($column) REFERENCES users(id)"
                        )
                    }
                }
            }
        }
    }

    private fun hasTable(conn: Connection, name: String): Boolean {
        val md = conn.metaData
        return listOf(name.lowercase(), name.uppercase()).any { candidate ->
            md.getTables(conn.catalog, null, candidate, null).use { it.next() }
        }
    }

    @Test
    @Transactional
    @DisplayName("六张 users 外键表都有残留数据时，清理后 DELETE users 不再违反约束")
    fun `erasure order satisfies production foreign keys`() {
        val uid = "U-fk-erasure"
        // 必须立刻落库：下面 bind_codes 是 JDBC 直插，外键会立刻校验 users 行是否存在，
        // 而普通 save() 只进一级缓存、要等 dao.erase 的 em.flush() 才写库。
        users.saveAndFlush(User(uid, "19900006666", "hash"))
        devices.save(Device(id = "D-fk-1", userId = uid))
        groups.save(DeviceGroup(id = "G-fk-1", name = "旧分组", sceneType = "FAMILY", ownerUserId = uid))
        alerts.save(Alert(id = "A-fk-1", deviceId = "D-fk-1", userId = uid))
        enrollments.save(
            EnrollmentSession(
                id = "E-fk-1", userId = uid, pairingCode = "FK0001",
                state = "PENDING", expiresAt = System.currentTimeMillis() + 60_000
            )
        )
        subs.save(Subscription(id = "S-fk-1", userId = uid))
        orders.save(PaymentOrder(id = "O-fk-1", orderNo = "NO-fk-1", userId = uid))
        jdbc.update("insert into bind_codes (code, user_id) values (?, ?)", "FK0001", uid)

        val outcome = dao.erase(uid, "erased_fk_anon")

        // 之前 H2 上这条 native SQL 一直被"表不存在"跳过，这里必须真的删掉。
        assertEquals(1, outcome.counts["bind_codes"], "生产形态下 bind_codes 的删除必须真实执行")
        assertEquals(1, outcome.counts["device_groups"], "分组归属必须置空")
        assertEquals(1, outcome.counts["alerts"], "告警必须删除")
        assertEquals(1, outcome.counts["enrollment_sessions"], "绑定会话必须删除")
        assertEquals(listOf("D-fk-1"), outcome.deviceIds)

        // 关键断言：所有引用解除后，带外键的 users 行可以直接 DELETE（生产 PG 同理）。
        assertEquals(1, jdbc.update("delete from users where id = ?", uid), "用户行应可删除")
        assertEquals(
            0,
            jdbc.queryForObject("select count(*) from bind_codes where user_id = ?", Int::class.java, uid),
            "不能留下悬空的外键引用"
        )
        assertEquals(
            1,
            jdbc.queryForObject("select count(*) from devices where id = ?", Int::class.java, "D-fk-1"),
            "设备台账仍须保留（硬件指纹复用）"
        )
    }
}
