package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.AppPolicy
import com.padguard.server.domain.Device
import com.padguard.server.domain.Plan
import com.padguard.server.domain.Policy
import com.padguard.server.domain.SubStatus
import com.padguard.server.domain.Subscription
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.AppPolicyRepository
import com.padguard.server.repository.AuditLogRepository
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.InstalledAppRepository
import com.padguard.server.repository.PolicyRepository
import com.padguard.server.repository.PolicyTemplateRepository
import com.padguard.server.repository.ScreenshotRepository
import com.padguard.server.repository.SubscriptionRepository
import com.padguard.server.repository.TimeRestrictionRepository
import com.padguard.server.repository.UrlCategoryRepository
import com.padguard.server.security.DeviceOwnershipGuard
import com.padguard.server.service.FakeJpaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

/**
 * 权益闸门**端到端**集成测试（真实 PolicyExtensionService + 内存假仓储）。
 *
 * 覆盖 P0 缺陷的完整语义链：订阅失效（EXPIRED/REVOKED）→ syncEntitlement 翻转 →
 * 策略包重建为「放开包」→ 续费后自动恢复；到期沿若设备正被远程锁屏则代为解锁。
 * 纯逻辑部分（放开覆盖的字段级断言）见 [EntitlementGateTest]。
 */
class EntitlementGateIntegrationTest {

    // ---------- 假仓储（与既有测试同款模式） ----------

    private class FakeDeviceRepo : FakeJpaRepository<Device, String>(), DeviceRepository {
        override val table = LinkedHashMap<String, Device>()
        override fun idOf(e: Device) = e.id
        override fun updateEntitlementState(deviceId: String, state: String): Int {
            val d = table[deviceId] ?: return 0
            d.entitlementState = state
            return 1
        }
        override fun clearRemoteLockedIfSet(deviceId: String): Int {
            val d = table[deviceId] ?: return 0
            return if (d.remoteLocked == true) { d.remoteLocked = false; 1 } else 0
        }
        override fun findByUserId(userId: String) = table.values.filter { it.userId == userId }
        override fun countByUserId(userId: String) = table.values.count { it.userId == userId }.toLong()
        override fun countByOnlineStatus(onlineStatus: String) = 0L
        override fun findByOnlineStatus(onlineStatus: String) = emptyList<Device>()
        override fun findByOnlineStatusAndLastOnlineAtLessThan(onlineStatus: String, ts: Long) = emptyList<Device>()
        override fun findByDeviceSn(deviceSn: String) = emptyList<Device>()
        override fun findByLastActiveAtGreaterThan(since: Long) = emptyList<Device>()
        override fun countOnlineSince(staleCut: Long) = 0L
        override fun findCreatedAtSince(since: Long) = emptyList<Long>()
        override fun countGroupByUserIds(userIds: Collection<String>) = emptyList<Array<Any>>()
        override fun findByIdForUpdate(id: String): Device? = table[id]
        override fun searchDevices(keyword: String, kw: String, onlineMode: String, staleCut: Long, pageable: Pageable): Page<Device> =
            pageOf(emptyList(), pageable)
    }

    private class FakeSubRepo : FakeJpaRepository<Subscription, String>(), SubscriptionRepository {
        override val table = LinkedHashMap<String, Subscription>()
        override fun idOf(e: Subscription) = e.id
        override fun findByUserId(userId: String): Subscription? = table.values.firstOrNull { it.userId == userId }
        override fun findByUserIdForUpdate(userId: String): Subscription? = findByUserId(userId)
        override fun markExpiredIfStale(userId: String, expiredStatus: String, now: Long): Int {
            val s = findByUserId(userId) ?: return 0
            if (s.status != "TRIAL" && s.status != "ACTIVE") return 0
            if (s.expiredAt >= now) return 0
            s.status = expiredStatus
            s.updatedAt = now
            return 1
        }
        override fun findByUserIdIn(userIds: Collection<String>) =
            table.values.filter { it.userId in userIds }
        override fun countByStatus(status: String) = table.values.count { it.status == status }.toLong()
        override fun countGroupedByPlan() = emptyList<Array<Any?>>()
        override fun findAllByOrderByExpiredAtDesc() = table.values.sortedByDescending { it.expiredAt }
        override fun findCreatedAtSince(since: Long) = table.values.map { it.createdAt }.filter { it >= since }
        override fun searchSubscriptions(status: String, pageable: Pageable): Page<Subscription> = pageOf(emptyList(), pageable)
    }

    private class FakePolicyRepo : FakeJpaRepository<Policy, String>(), PolicyRepository {
        override val table = LinkedHashMap<String, Policy>()
        override fun idOf(entity: Policy) = entity.id
        override fun findFirstByDeviceIdOrderByVersionDesc(deviceId: String): Policy? =
            table.values.filter { it.deviceId == deviceId }.maxByOrNull { it.version }
        override fun nextVersion(deviceId: String): Int =
            (table.values.filter { it.deviceId == deviceId }.maxOfOrNull { it.version } ?: 0) + 1
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }
    }

    private class FakeAppPolicyRepo : FakeJpaRepository<AppPolicy, String>(), AppPolicyRepository {
        override val table = LinkedHashMap<String, AppPolicy>()
        override fun idOf(e: AppPolicy) = e.id
        override fun findByDeviceId(deviceId: String) = table.values.filter { it.deviceId == deviceId }
        override fun findByDeviceIdAndPackageName(deviceId: String, packageName: String): AppPolicy? =
            table.values.firstOrNull { it.deviceId == deviceId && it.packageName == packageName }
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }
    }

    private class FakeTimeRepo : FakeJpaRepository<com.padguard.server.domain.TimeRestriction, String>(), TimeRestrictionRepository {
        override val table = LinkedHashMap<String, com.padguard.server.domain.TimeRestriction>()
        override fun idOf(e: com.padguard.server.domain.TimeRestriction) = e.id
        override fun findByDeviceId(deviceId: String) = table.values.filter { it.deviceId == deviceId }
        override fun findByDeviceIdAndId(deviceId: String, id: String) =
            java.util.Optional.ofNullable(table.values.firstOrNull { it.deviceId == deviceId && it.id == id })
        override fun deleteByDeviceIdAndId(deviceId: String, id: String) {
            table.values.removeIf { it.deviceId == deviceId && it.id == id }
        }
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }
    }

    private class FakeSettingRepo : FakeJpaRepository<com.padguard.server.domain.DeviceSetting, String>(), com.padguard.server.repository.DeviceSettingRepository {
        override val table = LinkedHashMap<String, com.padguard.server.domain.DeviceSetting>()
        override fun idOf(e: com.padguard.server.domain.DeviceSetting) = e.deviceId
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }
    }

    private class FakeTemplateRepo : FakeJpaRepository<com.padguard.server.domain.PolicyTemplate, String>(), PolicyTemplateRepository {
        override val table = LinkedHashMap<String, com.padguard.server.domain.PolicyTemplate>()
        override fun idOf(e: com.padguard.server.domain.PolicyTemplate) = e.id
        override fun findBySceneType(sceneType: String) = emptyList<com.padguard.server.domain.PolicyTemplate>()
    }

    private class FakeInstalledAppRepo : FakeJpaRepository<com.padguard.server.domain.InstalledApp, String>(), InstalledAppRepository {
        override val table = LinkedHashMap<String, com.padguard.server.domain.InstalledApp>()
        override fun idOf(e: com.padguard.server.domain.InstalledApp) = e.id
        override fun findByDeviceId(deviceId: String) = emptyList<com.padguard.server.domain.InstalledApp>()
        override fun findByDeviceIdAndPackageName(deviceId: String, packageName: String) = null
        override fun findByDeviceIdAndInstalledTrue(deviceId: String) = emptyList<com.padguard.server.domain.InstalledApp>()
        override fun deleteByDeviceId(deviceId: String): Int = 0
        override fun findAllIconUrls(): List<String> = emptyList()
    }

    private class FakeUrlCategoryRepo : FakeJpaRepository<com.padguard.server.domain.UrlCategory, String>(), UrlCategoryRepository {
        override val table = LinkedHashMap<String, com.padguard.server.domain.UrlCategory>()
        override fun idOf(e: com.padguard.server.domain.UrlCategory) = e.id
    }
    private class FakeSensitiveWordRepo : FakeJpaRepository<com.padguard.server.domain.SensitiveWord, String>(), com.padguard.server.repository.SensitiveWordRepository {
        override val table = LinkedHashMap<String, com.padguard.server.domain.SensitiveWord>()
        override fun idOf(e: com.padguard.server.domain.SensitiveWord) = e.id
        override fun findByWord(word: String) = null
    }

    private class FakeCommandRepo : FakeJpaRepository<com.padguard.server.domain.Command, String>(), CommandRepository {
        override val table = LinkedHashMap<String, com.padguard.server.domain.Command>()
        override fun idOf(e: com.padguard.server.domain.Command) = e.id
        override fun findByMsgId(msgId: String) = table.values.firstOrNull { it.msgId == msgId }
        override fun findByMsgIdAndDeviceId(msgId: String, deviceId: String) =
            table.values.firstOrNull { it.msgId == msgId && it.deviceId == deviceId }
        override fun findByDeviceIdOrderByCreatedAtDesc(deviceId: String) =
            table.values.filter { it.deviceId == deviceId }.sortedByDescending { it.createdAt }
        override fun findByDeviceIdOrderByCreatedAtAsc(deviceId: String) =
            table.values.filter { it.deviceId == deviceId }.sortedBy { it.createdAt }
        override fun findPendingForPolling(deviceId: String, status: String, now: Long, cursor: Long, pageable: Pageable) =
            emptyList<com.padguard.server.domain.Command>()
        override fun findExpiredPending(deviceId: String, status: String, now: Long) =
            emptyList<com.padguard.server.domain.Command>()
        /** F01 CAS 的内存等价实现：只有仍是 PENDING 才改写并返回影响行数 1 */
        override fun markExpiredIfPending(id: String, pending: String, expired: String): Int {
            val cmd = table[id] ?: return 0
            if (cmd.status != pending) return 0
            cmd.status = expired
            return 1
        }
        override fun applyAckIfOpen(
            msgId: String, deviceId: String, terminal: Collection<String>, status: String,
            executedAt: Long?, errorCode: String?, errorMessage: String?
        ): Int {
            val cmd = table.values.firstOrNull { it.msgId == msgId && it.deviceId == deviceId } ?: return 0
            if (cmd.status in terminal) return 0
            cmd.status = status
            cmd.executedAt = executedAt
            cmd.errorCode = errorCode
            cmd.errorMessage = errorMessage
            return 1
        }
        // W2 补发查询：本组测试不涉及补发，恒返回空/0 即可保持原有断言语义
        override fun findRedeliverablePending(deviceId: String, status: String, now: Long, maxAttempts: Int, types: Collection<String>, pageable: Pageable) =
            emptyList<com.padguard.server.domain.Command>()
        override fun tryRefreshForRedelivery(id: String, status: String, now: Long, newExpiresAt: Long): Int = 0
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }
    }

    private class FakeScreenshotRepo : FakeJpaRepository<com.padguard.server.domain.Screenshot, String>(), ScreenshotRepository {
        override val table = LinkedHashMap<String, com.padguard.server.domain.Screenshot>()
        override fun idOf(e: com.padguard.server.domain.Screenshot) = e.id
        override fun findByDeviceIdOrderByCapturedAtDesc(deviceId: String, pageable: Pageable) = emptyList<com.padguard.server.domain.Screenshot>()
        override fun findFirstByDeviceIdAndShotId(deviceId: String, shotId: String) = null
        override fun deleteByDeviceId(deviceId: String): Int = 0
        override fun findReceivedAtBefore(ts: Long) = emptyList<com.padguard.server.domain.Screenshot>()
        override fun markExpiredIfPending(deviceId: String, shotId: String, pending: String, expired: String): Int {
            val row = table.values.firstOrNull {
                it.deviceId == deviceId && it.shotId == shotId && it.status == pending
            } ?: return 0
            row.status = expired
            return 1
        }
    }

    private class FakeAuditLogRepo : FakeJpaRepository<com.padguard.server.domain.AuditLog, String>(), AuditLogRepository {
        override val table = LinkedHashMap<String, com.padguard.server.domain.AuditLog>()
        override fun idOf(e: com.padguard.server.domain.AuditLog) = e.id
        override fun findByUserIdAndCreatedAtBetween(userId: String, start: Long, end: Long) = emptyList<com.padguard.server.domain.AuditLog>()
        override fun findByDeviceIdAndCreatedAtBetween(deviceId: String, start: Long, end: Long) = emptyList<com.padguard.server.domain.AuditLog>()
        override fun findAllByOrderByCreatedAtDesc() = emptyList<com.padguard.server.domain.AuditLog>()
        override fun findByDeviceIdOrderByCreatedAtDesc(deviceId: String, pageable: Pageable) = emptyList<com.padguard.server.domain.AuditLog>()
        override fun findByDeviceIdAndActionOrderByCreatedAtDesc(deviceId: String, action: String, pageable: Pageable) = emptyList<com.padguard.server.domain.AuditLog>()
        override fun findByUserIdOrderByCreatedAtDesc(userId: String, pageable: Pageable) = emptyList<com.padguard.server.domain.AuditLog>()
        override fun findByUserIdAndActionOrderByCreatedAtDesc(userId: String, action: String, pageable: Pageable) = emptyList<com.padguard.server.domain.AuditLog>()
        override fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<com.padguard.server.domain.AuditLog> = pageOf(emptyList(), pageable)
        override fun findByActionInOrderByCreatedAtDesc(actions: Collection<String>, pageable: Pageable): Page<com.padguard.server.domain.AuditLog> = pageOf(emptyList(), pageable)
    }

    // ---------- 夹具 ----------

    private val objectMapper = ObjectMapper()

    private class Fx(
        val svc: PolicyExtensionService,
        val devices: FakeDeviceRepo,
        val subs: FakeSubRepo,
        val policies: FakePolicyRepo,
        val commands: FakeCommandRepo
    )

    private fun fixture(): Fx {
        val devices = FakeDeviceRepo()
        val subs = FakeSubRepo()
        val policies = FakePolicyRepo()
        val appPolicies = FakeAppPolicyRepo()
        val times = FakeTimeRepo()
        val settings = FakeSettingRepo()
        val templates = FakeTemplateRepo()
        val installedApps = FakeInstalledAppRepo()
        val commands = FakeCommandRepo()

        devices.table["d1"] = Device(
            id = "d1", userId = "p1", name = "孩子的平板",
            remoteLocked = true,  // 到期沿应被代理解锁
            // 绑定流程会分配非空 HMAC 密钥（指令签名必需）；夹具必须模拟这一点，
            // 否则 issueCommand 签名抛 "Empty key" 测不到真实路径
            hmacSecret = "test-secret",
            createdAt = 1L
        )
        subs.table["s1"] = Subscription(
            id = "s1", userId = "p1", plan = Plan.TRIAL.key, status = SubStatus.ACTIVE.name,
            expiredAt = System.currentTimeMillis() + 86_400_000L
        )
        appPolicies.table["ap1"] = AppPolicy(id = "ap1", deviceId = "d1", packageName = "com.game", isBlocked = true)
        settings.table["d1"] = com.padguard.server.domain.DeviceSetting(deviceId = "d1")

        val ownership = DeviceOwnershipGuard(devices)
        val auditService = AuditService(FakeAuditLogRepo(), ownership)
        val mqtt = MqttGateway(null, objectMapper, "test")
        // W2：补发服务用默认配置 + 假仓储（findRedeliverablePending 恒空），不改变本组用例语义
        val commandRedelivery = CommandRedeliveryService(commands)
        val sweeper = ExpiredCommandSweeper(commands, FakeScreenshotRepo(), objectMapper, commandRedelivery)
        val commandService = CommandService(devices, commands, mqtt, objectMapper, sweeper, auditService, ownership, commandRedelivery)
        // ContentFilterService 是 final 类且与 PolicyExtensionService 构造循环（@Lazy），
        // 测试只关心 buildPolicyPackage 用到的 blockedCategoriesForDevice → 直接 mock
        val contentFilter = Mockito.mock(ContentFilterService::class.java).also {
            Mockito.`when`(it.blockedCategoriesForDevice(Mockito.anyString())).thenReturn(emptySet())
        }

        val svc = PolicyExtensionService(
            devices, ownership, policies, appPolicies, times, settings, templates, installedApps,
            subs, commandService, mqtt, objectMapper, contentFilter, auditService
        )
        return Fx(svc, devices, subs, policies, commands)
    }

    @Suppress("UNCHECKED_CAST")
    private fun latestPackageJson(fx: Fx): Map<String, Any?> {
        val latest = fx.policies.findFirstByDeviceIdOrderByVersionDesc("d1")
            ?: error("no policy stored")
        return objectMapper.readValue(latest.packageJson, Map::class.java) as Map<String, Any?>
    }

    @Test
    fun `active subscription keeps normal policy`() {
        val f = fixture()
        f.svc.rebuildPolicy("d1")
        val pkg = latestPackageJson(f)
        @Suppress("UNCHECKED_CAST")
        val app = pkg["app"] as Map<String, Any?>
        assertTrue((app["blacklist"] as List<*>).contains("com.game"), "正常权益必须保留家长配置的黑名单")
        @Suppress("UNCHECKED_CAST")
        val limit = pkg["appLimit"] as Map<String, Any?>
        assertEquals(true, limit["enabled"])
        assertEquals("ACTIVE", f.devices.table["d1"]!!.entitlementState)
    }

    @Test
    fun `expiry releases controls and unlocks remote-locked device`() {
        val f = fixture()
        f.svc.rebuildPolicy("d1")
        val versionBefore = f.policies.findFirstByDeviceIdOrderByVersionDesc("d1")!!.version

        // 订阅到期
        f.subs.table["s1"]!!.status = SubStatus.EXPIRED.name
        val entitled = f.svc.syncEntitlement("d1")

        assertFalse(entitled)
        assertEquals("SUSPENDED", f.devices.table["d1"]!!.entitlementState, "翻转沿必须更新记账位")
        assertEquals(false, f.devices.table["d1"]!!.remoteLocked, "到期沿设备正被远程锁屏时必须代理解锁——家长端接口已被订阅拦截器挡住")
        assertTrue(
            f.commands.table.values.any { it.deviceId == "d1" && it.type == CommandType.TEMP_UNLOCK },
            "必须下发 TEMP_UNLOCK 让孩子端立即退出锁屏"
        )
        assertTrue(
            f.policies.findFirstByDeviceIdOrderByVersionDesc("d1")!!.version > versionBefore,
            "放开包必须推进版本号，孩子端才会拉取"
        )

        val pkg = latestPackageJson(f)
        @Suppress("UNCHECKED_CAST")
        val app = pkg["app"] as Map<String, Any?>
        assertEquals(emptyList<Any>(), app["blacklist"], "到期后黑名单必须清空——否则管控无限延续")
        @Suppress("UNCHECKED_CAST")
        val limit = pkg["appLimit"] as Map<String, Any?>
        assertEquals(false, limit["enabled"])
        assertEquals(emptyList<Any>(), limit["rules"])
        @Suppress("UNCHECKED_CAST")
        val mon = pkg["monitoring"] as Map<String, Any?>
        assertEquals(0, mon["locationIntervalSec"], "到期后付费的定位上报必须停止")
        @Suppress("UNCHECKED_CAST")
        val minor = pkg["minorMode"] as Map<String, Any?>
        assertEquals(false, minor["enabled"], "未成年人模式必须显式关，否则分龄派生把限制又加回来")
        @Suppress("UNCHECKED_CAST")
        val lock = pkg["systemLock"] as Map<String, Any?>
        assertEquals("ALLOW", lock["developerOptions"])
    }

    @Test
    fun `renewal restores normal policy`() {
        val f = fixture()
        f.svc.rebuildPolicy("d1")
        f.subs.table["s1"]!!.status = SubStatus.EXPIRED.name
        f.svc.syncEntitlement("d1")

        // 续费
        f.subs.table["s1"]!!.status = SubStatus.ACTIVE.name
        f.subs.table["s1"]!!.expiredAt = System.currentTimeMillis() + 86_400_000L
        val entitled = f.svc.syncEntitlement("d1")

        assertTrue(entitled)
        assertEquals("ACTIVE", f.devices.table["d1"]!!.entitlementState)
        val pkg = latestPackageJson(f)
        @Suppress("UNCHECKED_CAST")
        val app = pkg["app"] as Map<String, Any?>
        assertTrue((app["blacklist"] as List<*>).contains("com.game"), "续费后家长配置必须自动恢复（子表数据从未删除）")
        @Suppress("UNCHECKED_CAST")
        val limit = pkg["appLimit"] as Map<String, Any?>
        assertEquals(true, limit["enabled"])
    }

    @Test
    fun `sync without state flip is a no-op`() {
        val f = fixture()
        f.svc.rebuildPolicy("d1")
        val versionBefore = f.policies.findFirstByDeviceIdOrderByVersionDesc("d1")!!.version
        val commandsBefore = f.commands.table.size

        assertTrue(f.svc.syncEntitlement("d1"))
        assertTrue(f.svc.syncEntitlement("d1"))
        assertEquals(versionBefore, f.policies.findFirstByDeviceIdOrderByVersionDesc("d1")!!.version, "无翻转不得重建策略（心跳 30s 一次，重建会刷爆版本号）")
        assertEquals(commandsBefore, f.commands.table.size, "无翻转不得下发指令")
    }
}
