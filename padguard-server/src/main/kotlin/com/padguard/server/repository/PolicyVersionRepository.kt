package com.padguard.server.repository

import com.padguard.server.domain.Policy
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * 策略版本历史查询的**独立仓储**。
 *
 * 为什么不直接往 `PolicyRepository` 上加方法：`PolicyRepository` 被服务端单测里
 * 多个 `FakeJpaRepository` 子类实现（`PolicyServiceTest`、`PolicyVersionConcurrencyTest` 等），
 * 每加一个抽象方法就要同步改所有 Fake，属于「为了一个只读功能破坏全部测试夹具」。
 * 独立仓储指向同一张 `policies` 表，Spring Data 允许多个 Repository 映射同一实体，
 * 且测试中只需实现本接口的 2 个方法。
 */
@Repository
interface PolicyVersionRepository : JpaRepository<Policy, String> {

    /** 版本倒序分页；`findFirstByDeviceIdOrderByVersionDesc` 只能取一条，无法列历史 */
    @Query("select p from Policy p where p.deviceId = :deviceId order by p.version desc")
    fun listVersions(@Param("deviceId") deviceId: String, pageable: Pageable): List<Policy>

    @Query("select p from Policy p where p.deviceId = :deviceId and p.version = :version")
    fun findVersion(@Param("deviceId") deviceId: String, @Param("version") version: Int): Policy?
}
