package com.padguard.server.config

import jakarta.annotation.PostConstruct
import jakarta.persistence.EntityManagerFactory
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 启动时修复"后加列把存量行留成 NULL"的数据损坏（幂等）。
 *
 * ## 要解决的问题
 * 本项目有两条建表路径：
 * - 生产：PostgreSQL + Flyway，迁移脚本里写的是 `ADD COLUMN ... NOT NULL DEFAULT 0`，干净；
 * - 单机/家庭部署：H2 + `ddl-auto=update`（flyway 关闭），表结构由 Hibernate 推断。
 *
 * 后者在**给已存在的表补列**时，只要实体上没有 `@ColumnDefault`，生成的就是一个可空的列，
 * 存量行一律被填成 NULL。这本来是"数据库里没值"，但实体上如果对应的是 Kotlin **非空基本类型**
 * （`var tokenVersion: Long = 0`），Hibernate 读这一行时会抛
 * `Null value was assigned to a property of primitive type` —— 整条查询失败。
 *
 * 症状极具欺骗性：
 * - 服务启动成功、`/health` 是 UP、新建账号一切正常；
 * - 只有**升级前就存在的老账号**在登录时 500（`users.token_version` 就是这么炸的）；
 * - 全新库永远复现不出来，只在"老库 + 新代码"这一种组合下出现。
 *
 * 治本靠实体上的 `@ColumnDefault`（新库不再产生 NULL），但**已经写坏的库**改注解是不动的：
 * 那一列已经存在且已是 NULL，Hibernate 不会去改它。所以必须有一次显式的数据回填。
 *
 * ## 为什么放在 `@PostConstruct` 而不是 `ApplicationRunner`
 * `ApplicationRunner` 在 Tomcat 已经开始接受请求**之后**才执行，回填前的几百毫秒里，
 * 老账号登录仍然会 500。这里用构造参数注入 [EntityManagerFactory] 强制它在 Hibernate
 * 完成 ddl-auto 之后、Web 容器开始服务之前执行，把窗口压到零。
 *
 * ## 安全边界
 * 每条语句都包在 runCatching 里，失败只记日志、绝不阻断启动（数据修复不该让服务起不来）；
 * 全部是 `WHERE ... IS NULL` 的幂等 UPDATE，没有可匹配行时影响 0 行、无副作用；
 * 生产（列上是 NOT NULL）执行同样的语句只会更新 0 行。
 */
@Component
class SchemaColumnRepair(
    private val jdbc: JdbcTemplate,
    @Suppress("UNUSED_PARAMETER") entityManagerFactory: EntityManagerFactory
) {

    private val log = LoggerFactory.getLogger(SchemaColumnRepair::class.java)

    @PostConstruct
    fun repair() {
        for (patch in PATCHES) {
            runCatching { jdbc.update(patch.sql) }
                .onSuccess { rows ->
                    if (rows > 0) {
                        log.warn(
                            "已回填历史空值: {} —— 影响 {} 行。{}",
                            patch.description, rows, patch.reason
                        )
                    }
                }
                .onFailure {
                    log.warn("回填失败（已忽略，不阻断启动）: {} -> {}", patch.description, it.message)
                }
        }
    }

    private data class Patch(val description: String, val sql: String, val reason: String)

    private companion object {
        /**
         * 一个补丁 = 一列"非空基本类型 + 无默认值"的历史遗留。
         * 新增此类列时，正确做法是先在实体上加 `@ColumnDefault`；
         * 只有在列已经以可空形态落到用户机器上时，才需要在这里补一条回填。
         */
        val PATCHES: List<Patch> = listOf(
            Patch(
                description = "users.token_version",
                sql = "UPDATE users SET token_version = 0 WHERE token_version IS NULL",
                reason = "JWT 令牌版本号，NULL 会让老账号登录直接 500（读入非空 Long 失败）"
            ),
            Patch(
                description = "admins.token_version",
                sql = "UPDATE admins SET token_version = 0 WHERE token_version IS NULL",
                reason = "后台管理员令牌版本号，NULL 会让管理员登录 500"
            )
        )
    }
}
