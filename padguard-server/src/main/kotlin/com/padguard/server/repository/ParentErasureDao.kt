package com.padguard.server.repository

import jakarta.persistence.EntityManager
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * 家长账号注销时的集中式关联数据清理。
 *
 * ## 为什么必须有这份清单
 * 生产形态（docker/default profile）是 `ddl-auto=none + Flyway`，迁移脚本里有多张表
 * 带 `REFERENCES users (id)` 外键；而 local/home 的 H2 由 `ddl-auto=update` 建表，
 * 实体里的 `user_id` 全是裸 `@Column`、没有 `@ManyToOne`，**根本不建外键**。
 * 于是旧的 `deleteParent` 只删 `subscription` + `user` 也能在本地"删成功"，
 * 但上线后只要任一关联表有该用户的记录，DELETE 就会违反外键约束报 500；
 * 就算侥幸没有外键，也会留下一堆悬空 `user_id` 的孤儿数据。
 *
 * 这里把「解除用户归属」的动作集中成一份有序清单，两个档位执行同一套 JPQL，
 * 行为一致；`ParentErasureContractTest` 会扫描迁移脚本，新增任何 users 外键表
 * 而没同步进清单就直接让测试变红。
 *
 * ## 清理口径
 * - **解绑而非删设备**：`devices.user_id` 置空，设备台账保留以便硬件指纹复用。
 * - **作废旧设备凭据**：`ChildAuthInterceptor` 只比对 `device_token_hash`，**不检查 userId 是否为空**。
 *   只置空归属的话，已解绑的平板拿着旧 deviceToken 仍能通过鉴权继续上报心跳/截图，
 *   写出一批无人认领的幽灵数据。所以同一步里必须把 token/hmac/mqtt/guard 凭据全部轮换。
 * - **孩子隐私不留给下一任**：设备台账既然保留，其下的截图/轨迹/用量/指令/告警等
 *   全部由 `DeviceDataPurgeService.purge` 清除（Service 层在提交前逐台调用），
 *   `devices` 上直接挂的孩子姓名/昵称/头像/最后位置也一并清空。
 * - **财务流水保留**：`payment_orders.user_id` 改成不可逆的匿名键（`erased_<hash>`），
 *   订单本身绝不能删，否则钱收了、流水没了、对账断链。
 * - **审计日志保留**：合规要求的处置留痕，不随账号删除。
 * - 其余直接持有 userId 的业务表：一次批量 DELETE/置空，不留悬空引用。
 */
enum class ErasureKind {
    /** JPQL 批量语句，参数固定为 :uid（订单脱敏额外用 :anon） */
    JPQL,

    /** native SQL，且该表可能不存在（H2 ddl-auto 未建），执行前先探测 */
    NATIVE_IF_TABLE_EXISTS
}

data class ErasureStep(
    /** 物理表名，与 Flyway 迁移里的 CREATE TABLE 一致，供契约守卫比对 */
    val table: String,
    val kind: ErasureKind,
    val sql: String,
    val description: String
)

/**
 * 一次注销的结果。`deviceIds` 必须在**置空归属之前**取出：devices 的 bulk update
 * 执行后再按 userId 查就一条都不剩，而设备历史数据清理需要这些 id。
 */
data class ErasureOutcome(
    val counts: Map<String, Int>,
    val deviceIds: List<String>
)

object ParentErasurePlan {
    /**
     * 生产 Flyway 迁移中所有 `REFERENCES users (id)` 的表。
     * 必须与 `STEPS` 的 table 集合保持包含关系，由 ParentErasureContractTest 双向锁死。
     */
    val FK_TABLES: Set<String> = setOf(
        "devices", "bind_codes", "device_groups", "alerts",
        "unlock_tickets", "enrollment_sessions"
    )

    /**
     * 生产 Flyway 里指向 users 的外键列。普通删除（非注销）必须先确认这些表
     * 没有残留引用，否则 PG 上 `DELETE users` 会直接违反约束变成 500，
     * 而 H2/ddl-auto 无外键却能"删成功"并留下孤儿行——两档行为不一致。
     * 列名不统一（device_groups 用 owner_user_id），故显式映射而非拼接。
     */
    val USER_FK_COLUMNS: Map<String, String> = linkedMapOf(
        "devices" to "user_id",
        "bind_codes" to "user_id",
        "device_groups" to "owner_user_id",
        "alerts" to "user_id",
        "unlock_tickets" to "user_id",
        "enrollment_sessions" to "user_id"
    )

    /** 顺序即执行顺序：先清引用方，最后才轮到用户行本身（由 Service 删除）。 */
    val STEPS: List<ErasureStep> = listOf(
        ErasureStep(
            "bind_codes", ErasureKind.NATIVE_IF_TABLE_EXISTS,
            "delete from bind_codes where user_id = ?",
            "删除该用户名下未使用的绑定码（H2 ddl-auto 无此表时自动跳过）"
        ),
        ErasureStep(
            "enrollment_sessions", ErasureKind.JPQL,
            "delete from EnrollmentSession e where e.userId = :uid",
            "删除未完成的绑定会话（userId 非空，无法置空）"
        ),
        ErasureStep(
            "app_catalog", ErasureKind.JPQL,
            "delete from AppCatalog a where a.userId = :uid",
            "删除该用户私有应用目录"
        ),
        ErasureStep(
            "app_distribution", ErasureKind.JPQL,
            "delete from AppDistribution a where a.userId = :uid",
            "删除该用户的应用分发配置"
        ),
        ErasureStep(
            "alerts", ErasureKind.JPQL,
            "delete from Alert a where a.userId = :uid",
            "删除含个人信息的历史告警"
        ),
        ErasureStep(
            "unlock_tickets", ErasureKind.JPQL,
            "delete from UnlockTicket t where t.userId = :uid",
            "删除解锁申请工单"
        ),
        ErasureStep(
            "usage_delay_tickets", ErasureKind.JPQL,
            "delete from UsageDelayTicket t where t.userId = :uid",
            "删除延时申请工单"
        ),
        ErasureStep(
            "devices", ErasureKind.JPQL,
            "update Device d set d.userId = null, d.deviceTokenHash = :revoked, " +
                "d.hmacSecret = :revoked, d.mqttPassword = :revoked, d.guardPasswordHash = '', " +
                "d.onlineStatus = 'OFFLINE', d.groupId = null, d.childName = null, " +
                "d.childNickname = null, d.childAvatar = null, d.lastIp = null, " +
                "d.latitude = null, d.longitude = null where d.userId = :uid",
            "解绑设备并作废旧凭据（保留台账、清空孩子 PII；旧 deviceToken 立即失效）"
        ),
        ErasureStep(
            "device_groups", ErasureKind.JPQL,
            "update DeviceGroup g set g.ownerUserId = null where g.ownerUserId = :uid",
            "设备分组解除归属"
        ),
        ErasureStep(
            "uploaded_files", ErasureKind.JPQL,
            "update UploadedFile f set f.ownerUserId = null where f.ownerUserId = :uid",
            "上传文件解除归属（文件本体由孤儿扫盘按保留期处理）"
        ),
        ErasureStep(
            "payment_orders", ErasureKind.JPQL,
            "update PaymentOrder o set o.userId = :anon where o.userId = :uid",
            "订单归属替换为匿名键，财务流水保留"
        ),
        ErasureStep(
            "subscriptions", ErasureKind.JPQL,
            "delete from Subscription s where s.userId = :uid",
            "删除订阅台账（账号已不存在，权益自然终止）"
        )
    )
}

@Repository
class ParentErasureDao(
    private val em: EntityManager,
    private val jdbc: JdbcTemplate
) {
    /**
     * 查出该用户名下的设备 id。必须在 [erase] 之前调用：erase 之后 userId 已置空就查不到了。
     * 单独暴露是因为设备历史清理走 REQUIRES_NEW 独立事务，**必须先于**外层事务的
     * 批量 DELETE 执行——否则两边都删 alerts/工单等同一批行，独立事务会等外层释放行锁，
     * 而外层又在等它返回，PG 上直接死锁（H2 则表现为锁超时）。
     */
    fun findDeviceIds(userId: String): List<String> =
        em.createQuery("select d.id from Device d where d.userId = :uid", String::class.java)
            .setParameter("uid", userId)
            .resultList

    /**
     * 在调用方的事务内执行全部清理，返回每张表的影响行数（供审计留痕）与被解绑的设备 id。
     * 不做 `em.clear()`：调用方随后还要用同一个托管实体删除用户行，
     * 清了上下文会让 `userRepository.delete(u)` 作用在脱管对象上。
     */
    fun erase(userId: String, anonKey: String, knownDeviceIds: List<String>? = null): ErasureOutcome {
        em.flush()
        val deviceIds = knownDeviceIds ?: findDeviceIds(userId)
        // 统一写一个不可能命中任何真实 sha256 的值即可让旧 deviceToken 失效；
        // 长度 52 < device_token_hash/hmac_secret(128) 与 mqtt_password(64) 的列宽。
        val revoked = "revoked_erasure_" + UUID.randomUUID().toString()
        val counts = LinkedHashMap<String, Int>()
        ParentErasurePlan.STEPS.forEach { step ->
            counts[step.table] = when (step.kind) {
                ErasureKind.JPQL -> em.createQuery(step.sql).apply {
                    setParameter("uid", userId)
                    if (step.sql.contains(":anon")) setParameter("anon", anonKey)
                    if (step.sql.contains(":revoked")) setParameter("revoked", revoked)
                }.executeUpdate()

                ErasureKind.NATIVE_IF_TABLE_EXISTS ->
                    if (tableExists(step.table)) jdbc.update(step.sql, userId) else 0
            }
        }
        return ErasureOutcome(counts, deviceIds)
    }

    /**
     * 普通删除前的残留引用探测：返回仍持有该 userId 的**生产外键表**名。
     * 表可能不存在（H2 的 ddl-auto 不建 bind_codes），探测不到就跳过，
     * 但一旦任何一张存在且有引用，普通删除就必须改走「注销账号」。
     */
    fun residualReferences(userId: String): List<String> =
        ParentErasurePlan.USER_FK_COLUMNS.filter { (table, column) ->
            // 纵深防御：表名/列名直接插值进 SQL，本身来自硬编码清单、当前安全，
            // 但一旦该清单未来被外部输入污染即变为真实 SQL 注入。先做强校验再拼接。
            assertSafeSqlIdentifier(table)
            assertSafeSqlIdentifier(column)
            tableExists(table) &&
                (jdbc.queryForObject("select count(*) from $table where $column = ?", Int::class.java, userId) ?: 0) > 0
        }.keys.toList()

    private companion object {
        private val SAFE_SQL_IDENTIFIER = Regex("^[a-zA-Z_][a-zA-Z0-9_]*\$")
        private fun assertSafeSqlIdentifier(name: String) {
            require(SAFE_SQL_IDENTIFIER.matches(name)) {
                "非法的 SQL 标识符「$name」：表名/列名必须来自硬编码清单，禁止外部输入"
            }
        }
    }

    /**
     * H2 的 ddl-auto 形态没有 bind_codes 表，PG 的 Flyway 形态有。
     * 用 JDBC 元数据探测而不是吞异常：吞掉 SQLException 会同时掩盖真实的权限/锁错误。
     */
    private fun tableExists(name: String): Boolean {
        val candidates = jdbc.execute(ConnectionCallback<List<String>> { conn ->
            val md = conn.metaData
            when {
                md.storesUpperCaseIdentifiers() -> listOf(name.uppercase())
                md.storesLowerCaseIdentifiers() -> listOf(name.lowercase())
                else -> listOf(name.lowercase(), name.uppercase())
            }
        }) ?: emptyList()
        return candidates.any { candidate ->
            jdbc.execute(ConnectionCallback<Boolean> { conn ->
                val rs = conn.metaData.getTables(conn.catalog, null, candidate, null)
                try {
                    rs.next()
                } finally {
                    rs.close()
                }
            }) ?: false
        }
    }
}
