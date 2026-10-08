package com.padguard.server.config

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 启动时一次性回填 [com.padguard.server.domain.Device.lastActiveAt] 为 NULL 的历史记录。
 *
 * 老版本 schema 新增该列时未回填，导致 `last_active_at` 为 NULL；而该字段曾被定义为基元
 * `Long`，Hibernate 把 NULL 映射到基元类型会抛 IllegalArgumentException，使绑定 / 局域网发现等
 * 加载到这条记录时整体 500。字段已改为可空 [Long?]（治本），此处负责把存量脏数据清掉（治标）。
 *
 * 幂等：只在仍为 NULL 的行上置 0，重复执行无副作用。覆盖所有 profile（local 不跑 Flyway，
 * 故用此 Runner 兜底；docker/default 即便 Flyway 已回填也只影响 0 行）。
 */
@Component
class DeviceDataRepair(
    @PersistenceContext private val em: EntityManager
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(DeviceDataRepair::class.java)

    @Transactional
    override fun run(args: ApplicationArguments?) {
        val updated = em.createQuery(
            "UPDATE Device d SET d.lastActiveAt = 0 WHERE d.lastActiveAt IS NULL"
        ).executeUpdate()
        if (updated > 0) {
            log.info("DeviceDataRepair: 回填 {} 条 last_active_at 为 NULL 的设备记录为 0", updated)
        }
    }
}
