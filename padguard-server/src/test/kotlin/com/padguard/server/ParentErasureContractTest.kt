package com.padguard.server

import com.padguard.server.repository.ErasureKind
import com.padguard.server.repository.ParentErasurePlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 家长账号注销的「档位一致性」守卫。
 *
 * 背景：生产（Flyway / PostgreSQL）有多张表带 `REFERENCES users (id)` 外键，
 * 本地（ddl-auto / H2）一张都不建。旧删除逻辑只清 subscription + user，
 * 于是本地删得掉、生产必违反外键；这类缺陷靠跑一次本地接口永远发现不了。
 *
 * 本测试把「Flyway 外键表清单」与「注销清理清单」锁成同一个事实来源：
 * 任何人新增一张引用 users 的表而没同步 [ParentErasurePlan]，这里立即变红。
 */
@DisplayName("家长账号注销：关联表清单与档位一致性守卫")
class ParentErasureContractTest {

    private val mainSrc = File("src/main/kotlin/com/padguard/server")
    private val migrationDir = File("src/main/resources/db/migration")

    private val createTableRegex =
        Regex("(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_0-9]+)\\s*\\(([^;]*)\\)\\s*;")
    private val usersFkRegex = Regex("(?i)REFERENCES\\s+users\\s*\\(\\s*id\\s*\\)")

    /** 从所有 Flyway 迁移里提取「表体含 users(id) 外键」的物理表名 */
    private fun flywayUserFkTables(): Set<String> =
        migrationDir.walkTopDown()
            .filter { it.isFile && it.extension == "sql" }
            .flatMap { file ->
                createTableRegex.findAll(file.readText())
                    .filter { usersFkRegex.containsMatchIn(it.groupValues[2]) }
                    .map { it.groupValues[1].lowercase() }
            }.toSet()

    @Test
    @DisplayName("Flyway 中每一张 users 外键表都必须出现在注销清单里")
    fun `every users fk table is covered`() {
        val fromSql = flywayUserFkTables()
        assertTrue(fromSql.isNotEmpty(), "未扫到任何 users 外键表，请检查迁移目录路径或正则")
        assertEquals(
            fromSql, ParentErasurePlan.FK_TABLES,
            "迁移脚本中的 users 外键表与 ParentErasurePlan.FK_TABLES 不一致；新增表必须同步注销清单"
        )
        fromSql.forEach { table ->
            assertTrue(
                ParentErasurePlan.STEPS.any { it.table == table },
                "表 $table 有 users 外键，但注销时没有对应清理动作，生产 PG 删除账号会失败"
            )
        }
    }

    @Test
    @DisplayName("清理清单无重复、每条 JPQL 都引用真实实体且带 uid 条件")
    fun `steps are well formed`() {
        val tables = ParentErasurePlan.STEPS.map { it.table }
        assertEquals(tables.size, tables.toSet().size, "同一张表被重复清理")

        val entities = mainSrc.resolve("domain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { Regex("class\\s+([A-Z][A-Za-z0-9]*)\\s*\\(").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
        assertTrue(entities.isNotEmpty())

        ParentErasurePlan.STEPS.filter { it.kind == ErasureKind.JPQL }.forEach { step ->
            val entity = Regex("(?:from|update)\\s+([A-Z][A-Za-z0-9]*)").find(step.sql)?.groupValues?.get(1)
            assertNotNull(entity, "${step.table} 的语句解析不出实体名: ${step.sql}")
            assertTrue(
                entities.contains(entity),
                "${step.table} 引用了不存在的实体 $entity；JPQL 只有跑起来才会炸，必须挡在编译期守卫里"
            )
            assertTrue(step.sql.contains(":uid"), "${step.table} 缺少 :uid 条件，会全表清理")
            assertTrue(step.sql.contains(" where ", ignoreCase = true), "${step.table} 缺少 where，会全表清理")
        }
    }

    @Test
    @DisplayName("设备解绑而非删除、订单脱敏而非删除（资产与财务流水必须保留）")
    fun `assets and finance rows are preserved`() {
        val devices = ParentErasurePlan.STEPS.single { it.table == "devices" }
        assertTrue(devices.sql.startsWith("update ", ignoreCase = true))
        assertTrue(devices.sql.contains("= null"), "设备必须置空 userId 而不是删行，否则制造孤儿设备")
        // ChildAuthInterceptor 只比对 deviceTokenHash、不检查 userId 是否为空。
        // 少了这一步，注销后的旧平板仍凭旧令牌通过鉴权继续上报，并留下孩子 PII。
        listOf(
            "d.deviceTokenHash = :revoked" to "旧 deviceToken 必须轮换",
            "d.hmacSecret = :revoked" to "HMAC 密钥必须轮换",
            "d.mqttPassword = :revoked" to "MQTT 口令必须轮换",
            "d.guardPasswordHash = ''" to "守护密码哈希必须清空",
            "d.childName = null" to "孩子姓名必须清空",
            "d.childAvatar = null" to "孩子头像必须清空",
            "d.onlineStatus = 'OFFLINE'" to "设备必须立即离线上报"
        ).forEach { (needle, why) -> assertTrue(devices.sql.contains(needle), "$why（缺少 $needle）") }

        val orders = ParentErasurePlan.STEPS.single { it.table == "payment_orders" }
        assertTrue(orders.sql.startsWith("update ", ignoreCase = true))
        assertTrue(orders.sql.contains(":anon"), "订单必须换成匿名键，财务流水不能删")

        assertFalse(
            ParentErasurePlan.STEPS.any { Regex("(?i)delete\\s+from\\s+User\\b").containsMatchIn(it.sql) },
            "用户行由 Service 在所有引用解除之后删除，不能提前放进 Dao 清单"
        )
    }

    @Test
    @DisplayName("注销入口保持单事务、双闸与手机号二次确认")
    fun `service keeps transaction guards`() {
        val src = mainSrc.resolve("service/AdminParentService.kt").readText()
        val start = src.indexOf("fun eraseParent(")
        assertTrue(start > 0, "eraseParent 不存在")
        val annotation = src.substring(kotlin.math.max(0, start - 200), start)
        assertTrue(annotation.contains("@Transactional"), "清理与删用户必须在同一事务，失败不能留下半清理状态")

        val body = src.substring(start).substringBefore("\n    }")
        listOf(
            "requireSuper(adminId)" to "注销必须限超级管理员",
            "\"parents\", \"delete\"" to "注销必须过常规 RBAC 闸门",
            "confirmPhone.trim()" to "必须有完整手机号二次确认",
            "dao.erase(" to "必须执行集中清理",
            "userRepository.delete(u)" to "必须删除用户行",
            "webSocketPush?.revokeUser(id)" to "必须踢掉已建立的 WS 长连接",
            "AdminAudit.PARENTS_ERASE" to "必须落审计"
        ).forEach { (needle, why) -> assertTrue(body.contains(needle), "$why（缺少 $needle）") }
    }

    @Test
    @DisplayName("注销必须逐台清设备历史、普通删除必须探测残留外键引用")
    fun `device history purge and residual guard are wired`() {
        val src = mainSrc.resolve("service/AdminParentService.kt").readText()

        val erase = src.substring(src.indexOf("fun eraseParent(")).substringBefore("\n    }")
        listOf(
            "outcome.deviceIds" to "必须拿到被解绑设备 id 列表（置空归属后就查不到了）",
            "purgeDeviceHistory(purge, deviceId)" to "必须逐台清理设备历史数据",
            "deviceDataPurge" to "必须注入解绑/易主的唯一清理入口",
            "purgedDeviceRows" to "清理规模必须回传与留痕"
        ).forEach { (needle, why) -> assertTrue(erase.contains(needle), "$why（缺少 $needle）") }
        assertTrue(
            src.contains("private var deviceDataPurge: DeviceDataPurgeService?"),
            "必须复用 DeviceDataPurgeService，不能另起一套不完整的清理清单"
        )

        val purgeFn = src.substring(src.indexOf("private fun purgeDeviceHistory(")).substringBefore("\n    }")
        assertTrue(purgeFn.contains("repeat(3)"), "瞬时失败应有重试，不能一次失败就整单回滚")
        assertTrue(purgeFn.contains("throw BizException"), "重试全败必须上抛让注销回滚，不能返回假成功")
        assertTrue(purgeFn.contains("\"ACCOUNT_ERASE\""), "清理原因必须可追溯")

        // 生产 PG 上只要 alerts / enrollment_sessions / bind_codes 等还有一行，
        // DELETE users 就违反外键变 500；H2 无外键却会"删成功"留孤儿。两边都要拦。
        val del = src.substring(src.indexOf("fun deleteParent(")).substringBefore("\n    }")
        assertTrue(
            del.contains("residualReferences(id)"),
            "普通删除必须探测生产外键表残留引用并引导到注销，否则 PG 上是 500"
        )
        assertEquals(
            ParentErasurePlan.FK_TABLES, ParentErasurePlan.USER_FK_COLUMNS.keys,
            "外键表清单与残留探测清单必须完全一致，新增迁移表时两边都要同步"
        )
    }

    @Test
    @DisplayName("审计动作表已登记注销动作，否则按领域筛选时该记录会消失")
    fun `audit action is registered`() {
        val shared = mainSrc.resolve("service/AdminShared.kt").readText()
        assertTrue(shared.contains("const val PARENTS_ERASE"))
        assertTrue(shared.contains("AdminAudit.PARENTS_ERASE to (\"parents\""))
    }
}
