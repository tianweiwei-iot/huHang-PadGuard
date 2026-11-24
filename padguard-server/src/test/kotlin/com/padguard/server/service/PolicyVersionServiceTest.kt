package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.domain.Policy
import com.padguard.server.dto.*
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PolicyRepository
import com.padguard.server.repository.PolicyVersionRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.util.Optional

/**
 * 第二步 · 策略版本历史 / 回滚 / 定时生效 / 批量下发的服务层回归。
 *
 * 重点守住三条「只在该链路成立」的不变量：
 * 1. 回滚必须产出**更高**版本号（孩子端 `PolicyPackage.mergeWith` 丢弃低版本，
 *    直接重发旧版本号会被静默忽略，属于最难排查的假成功）；
 * 2. 回滚行必须打 ROLLBACK 标记（否则 `ensureRebuilt` 会用当前子表把它重建覆盖）；
 * 3. 批量必须对**每一台**设备单独做归属校验，一台越权不能拖垮整批，
 *    也不能因为「批次里有一台是自己的」就放行别人的设备。
 *
 * 依赖策略：只 mock `PolicyRepository` / `PolicyVersionRepository` / `DeviceRepository`
 * 三个**接口**；归属闸门用真实 `DeviceOwnershipGuard`；其余走 [PolicyVersionPorts] 的
 * lambda 端口。这样可以完全避开当前 JVM 上 Mockito inline mock maker 无法修改
 * `PolicyExtensionService` 之类具体类的问题，也让断言落在业务行为而非 mock 框架上。
 */
class PolicyVersionServiceTest {

    private companion object {
        const val USER = "parent-1"
        const val DEV = "device-1"
        const val FOREIGN = "device-foreign"
    }

    private lateinit var policyRepo: PolicyRepository
    private lateinit var versionRepo: PolicyVersionRepository
    private lateinit var deviceRepo: DeviceRepository
    private lateinit var ownership: DeviceOwnershipGuard
    private lateinit var mapper: ObjectMapper
    private lateinit var service: PolicyVersionService

    /** 模拟 policies 表：按插入顺序保存，版本号由 nextVersion 提供 */
    private val rows = mutableListOf<Policy>()
    private val rebuildCalls = mutableListOf<String>()
    private val issuedCommands = mutableListOf<Pair<String, String>>()
    private val auditActions = mutableListOf<String>()
    private val notifications = mutableListOf<Pair<String, Int>>()

    @BeforeEach
    fun setUp() {
        rows.clear(); rebuildCalls.clear(); issuedCommands.clear(); auditActions.clear(); notifications.clear()
        policyRepo = mock(PolicyRepository::class.java)
        versionRepo = mock(PolicyVersionRepository::class.java)
        deviceRepo = mock(DeviceRepository::class.java)
        mapper = ObjectMapper()

        // 真实归属闸门：foreign 设备的 userId 不同，天然触发 DEVICE_NOT_OWNED
        doAnswer { inv ->
            val id = inv.getArgument<String>(0)
            Optional.of(Device(id = id, userId = if (id == FOREIGN) "someone-else" else USER))
        }.`when`(deviceRepo).findById(anyString())
        ownership = DeviceOwnershipGuard(deviceRepo)

        doAnswer { inv ->
            val p = inv.getArgument<Policy>(0)
            // 按主键 upsert：setSchedule 会 save 已存在的行，重复插入会让版本列表出现幽灵行
            rows.removeIf { it.id == p.id }
            rows.add(p)
            p
        }.`when`(policyRepo).save(any(Policy::class.java) ?: Policy())

        doAnswer { inv ->
            val id = inv.getArgument<String>(0)
            rows.filter { it.deviceId == id }.maxByOrNull { it.version }
        }.`when`(policyRepo).findFirstByDeviceIdOrderByVersionDesc(anyString())

        doAnswer { inv ->
            val id = inv.getArgument<String>(0)
            (rows.filter { it.deviceId == id }.maxOfOrNull { it.version } ?: 0) + 1
        }.`when`(policyRepo).nextVersion(anyString())

        doAnswer { inv ->
            val id = inv.getArgument<String>(0)
            val limit = inv.getArgument<Pageable>(1).pageSize
            rows.filter { it.deviceId == id }.sortedByDescending { it.version }.take(limit)
        }.`when`(versionRepo).listVersions(anyString(), any(Pageable::class.java) ?: PageRequest.of(0, 1))

        doAnswer { inv ->
            val id = inv.getArgument<String>(0)
            val v = inv.getArgument<Int>(1)
            rows.firstOrNull { it.deviceId == id && it.version == v }
        }.`when`(versionRepo).findVersion(anyString(), anyInt())

        `when`(deviceRepo.findByIdForUpdate(anyString())).thenReturn(Device(id = DEV, userId = USER))

        service = PolicyVersionService(
            policyRepo, versionRepo, deviceRepo, ownership,
            PolicyRebuilder { deviceId ->
                rebuildCalls.add(deviceId)
                // 模拟真实 rebuildPolicy：分配新版本、写 NORMAL 行（退出回滚态）
                val v = (rows.filter { it.deviceId == deviceId }.maxOfOrNull { it.version } ?: 0) + 1
                val prev = rows.filter { it.deviceId == deviceId }.maxByOrNull { it.version }
                rows.add(
                    Policy(
                        id = "rebuilt-$v", deviceId = deviceId, version = v,
                        packageJson = """{"version":$v,"deviceId":"$deviceId"}""",
                        effectiveFrom = prev?.effectiveFrom, effectiveTo = prev?.effectiveTo,
                        source = PolicySource.NORMAL, updatedAt = 2000L + v
                    )
                )
            },
            CommandIssuer { deviceId, type, _, _ ->
                issuedCommands.add(deviceId to type)
                CommandDto(
                    msgId = "cmd_test_$deviceId", type = type, status = "PENDING",
                    payload = null, createdAt = 1L, executedAt = null
                )
            },
            PolicyAuditor { _, _, action, _, _, _ -> auditActions.add(action) },
            PolicyNotifier { deviceId, version -> notifications.add(deviceId to version) },
            mapper
        )
    }

    private fun seed(version: Int, json: String, source: String = PolicySource.NORMAL, rollbackOf: Int? = null) {
        rows.add(
            Policy(
                id = "p$version", deviceId = DEV, version = version, packageJson = json,
                scene = "FAMILY", source = source, rollbackOf = rollbackOf, updatedAt = 1000L + version
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun pkgOf(p: Policy): Map<String, Any?> =
        mapper.readValue(p.packageJson, Map::class.java) as Map<String, Any?>

    private fun lastRow() = rows.last()

    // ---------- 回滚 ----------

    @Test
    fun `rollback produces a strictly higher version carrying the historical snapshot`() {
        seed(1, """{"version":1,"deviceId":"$DEV","app":{"mode":"WHITELIST"}}""")
        seed(2, """{"version":2,"deviceId":"$DEV","app":{"mode":"BLACKLIST"}}""")

        val result = service.rollback(USER, DEV, RollbackPolicyRequest(targetVersion = 1))

        assertEquals(3, result.version, "回滚必须分配更高版本号，旧版本号会被孩子端丢弃")
        assertEquals(1, result.rolledBackTo)
        assertTrue(result.rollbackActive)
        assertFalse(result.idempotent)

        val pkg = pkgOf(lastRow())
        assertEquals(3, (pkg["version"] as Number).toInt())
        assertEquals(DEV, pkg["deviceId"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("WHITELIST", (pkg["app"] as Map<String, Any?>)["mode"])
        assertEquals(listOf(DEV to 3), notifications, "回滚后必须通知设备重新拉策略")
    }

    @Test
    fun `rollback row is marked so ensureRebuilt skips it`() {
        seed(1, """{"version":1,"deviceId":"$DEV"}""")
        service.rollback(USER, DEV, RollbackPolicyRequest(targetVersion = 1))

        assertEquals(PolicySource.ROLLBACK, lastRow().source)
        assertEquals(1, lastRow().rollbackOf)
        assertTrue(auditActions.contains(AuditAction.POLICY_ROLLBACK), "回滚必须留审计")
    }

    @Test
    fun `repeated rollback to the same target is idempotent`() {
        seed(1, """{"version":1,"deviceId":"$DEV"}""")
        val first = service.rollback(USER, DEV, RollbackPolicyRequest(targetVersion = 1))
        val rowsAfterFirst = rows.size
        val second = service.rollback(USER, DEV, RollbackPolicyRequest(targetVersion = 1))

        assertEquals(first.version, second.version)
        assertTrue(second.idempotent)
        assertEquals(rowsAfterFirst, rows.size, "幂等回滚不应再插入版本行")
    }

    @Test
    fun `unknown target version is rejected`() {
        seed(1, """{"version":1,"deviceId":"$DEV"}""")
        val ex = assertThrows<BizException> {
            service.rollback(USER, DEV, RollbackPolicyRequest(targetVersion = 99))
        }
        assertEquals(ParentErr.POLICY_NOT_FOUND, ex.code)
    }

    @Test
    fun `invalid target version is rejected`() {
        seed(1, """{"version":1,"deviceId":"$DEV"}""")
        assertThrows<BizException> {
            service.rollback(USER, DEV, RollbackPolicyRequest(targetVersion = 0))
        }
    }

    @Test
    fun `corrupted historical package is rejected instead of writing an empty policy`() {
        seed(1, "not-a-json")
        val ex = assertThrows<BizException> {
            service.rollback(USER, DEV, RollbackPolicyRequest(targetVersion = 1))
        }
        assertEquals(ParentErr.POLICY_CONFLICT, ex.code)
        assertEquals(1, rows.size, "不得写入空策略包（会把设备放开成不管控）")
    }

    @Test
    fun `rolling back to a device owned by someone else is denied`() {
        seed(1, """{"version":1,"deviceId":"$DEV"}""")
        assertThrows<BizException> {
            service.rollback(USER, FOREIGN, RollbackPolicyRequest(targetVersion = 1))
        }
        assertEquals(1, rows.size)
    }

    // ---------- 定时生效窗口 ----------

    @Test
    fun `effective window is written into the package the child actually reads`() {
        seed(1, """{"version":1,"deviceId":"$DEV"}""")
        service.rollback(USER, DEV, RollbackPolicyRequest(targetVersion = 1, effectiveFrom = 1000L, effectiveTo = 9000L))

        val pkg = pkgOf(lastRow())
        assertEquals(1000L, (pkg["effectiveFrom"] as Number).toLong())
        assertEquals(9000L, (pkg["effectiveTo"] as Number).toLong())
        assertEquals(1000L, lastRow().effectiveFrom)
        assertEquals(9000L, lastRow().effectiveTo)
    }

    @Test
    fun `effectiveTo must be later than effectiveFrom`() {
        seed(1, """{"version":1,"deviceId":"$DEV"}""")
        assertThrows<BizException> {
            service.rollback(USER, DEV, RollbackPolicyRequest(targetVersion = 1, effectiveFrom = 9000L, effectiveTo = 1000L))
        }
    }

    @Test
    fun `setSchedule persists window and triggers a rebuild that inherits it`() {
        seed(1, """{"version":1,"deviceId":"$DEV"}""")
        service.setSchedule(USER, DEV, PolicyScheduleRequest(effectiveFrom = 2000L, effectiveTo = 8000L))

        assertEquals(listOf(DEV), rebuildCalls)
        assertEquals(2000L, lastRow().effectiveFrom, "重建产生的新版本必须继承窗口，不能改一次设置就丢")
        assertEquals(8000L, lastRow().effectiveTo)
        assertEquals(PolicySource.NORMAL, lastRow().source, "主动改设置会退出回滚态")
        assertTrue(auditActions.contains(AuditAction.POLICY_SCHEDULE_UPDATE))
    }

    @Test
    fun `setSchedule with nulls clears the window`() {
        seed(1, """{"version":1,"deviceId":"$DEV"}""")
        rows.first().effectiveFrom = 1000L
        service.setSchedule(USER, DEV, PolicyScheduleRequest(effectiveFrom = null, effectiveTo = null))
        assertNull(lastRow().effectiveFrom)
        assertNull(lastRow().effectiveTo)
    }

    // ---------- 版本历史 ----------

    @Test
    fun `version list is descending and marks current plus rollback state`() {
        seed(1, """{"version":1}""")
        seed(2, """{"version":2}""")
        service.rollback(USER, DEV, RollbackPolicyRequest(targetVersion = 1))

        val list = service.listVersions(USER, DEV, 20)
        assertEquals(listOf(3, 2, 1), list.versions.map { it.version })
        assertEquals(3, list.currentVersion)
        assertTrue(list.rollbackActive)
        assertTrue(list.versions.first().current)
        assertEquals(1, list.versions.first().rollbackOf)
        assertFalse(list.versions[1].current)
    }

    @Test
    fun `version list limit is capped`() {
        repeat(5) { seed(it + 1, """{"version":${it + 1}}""") }
        assertEquals(3, service.listVersions(USER, DEV, 3).versions.size)
        // 超大 limit 被钳到 MAX_POLICY_VERSIONS，但表里只有 5 行，不能凭空造行
        assertEquals(5, service.listVersions(USER, DEV, 99999).versions.size)
    }

    // ---------- 批量下发 ----------

    @Test
    fun `batch publish deduplicates ids and isolates a foreign device`() {
        seed(1, """{"version":1}""")
        val result = service.batchPublish(USER, BatchPublishRequest(listOf(DEV, DEV, FOREIGN)))

        assertEquals(2, result.total, "deviceIds 必须去重")
        assertEquals(1, result.succeeded)
        assertEquals(1, result.failed)
        assertTrue(result.results.first { it.deviceId == DEV }.ok)
        val denied = result.results.first { it.deviceId == FOREIGN }
        assertFalse(denied.ok)
        assertEquals("设备不属于当前用户", denied.message)
        assertEquals(listOf(DEV), rebuildCalls, "越权设备绝不能触发策略重建")
        assertTrue(auditActions.contains(AuditAction.POLICY_BATCH_PUBLISH))
    }

    @Test
    fun `batch publish rejects empty and oversized batches`() {
        assertThrows<BizException> { service.batchPublish(USER, BatchPublishRequest(emptyList())) }
        assertThrows<BizException> {
            service.batchPublish(USER, BatchPublishRequest((1..MAX_BATCH_DEVICES + 1).map { "d$it" }))
        }
    }

    @Test
    fun `batch command only allows reviewed command types`() {
        assertThrows<BizException> {
            service.batchCommand(USER, BatchCommandRequest(listOf(DEV), "SET_MANAGEMENT_ACTIVE"))
        }
        assertThrows<BizException> {
            service.batchCommand(USER, BatchCommandRequest(listOf(DEV), "WIPE"))
        }
        assertTrue(issuedCommands.isEmpty())
    }

    @Test
    fun `batch command issues one command per owned device and returns msgIds`() {
        val result = service.batchCommand(USER, BatchCommandRequest(listOf(DEV, FOREIGN), CommandType.REFRESH_POLICY))
        assertEquals(2, result.total)
        assertEquals(1, result.succeeded)
        assertEquals("cmd_test_$DEV", result.results.first { it.deviceId == DEV }.msgId)
        assertEquals(listOf(DEV to CommandType.REFRESH_POLICY), issuedCommands)
        assertTrue(auditActions.contains(AuditAction.COMMAND_BATCH_ISSUE))
    }
}
