package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.domain.Policy
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PolicyRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.DeviceOwnershipGuard
import com.padguard.server.stream.ScreenStreamHub
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.util.Optional

/**
 * `DeviceService` 是设备归属校验的核心守门人（getDevice / unbind / rename / discoverLanDevices
 * 等几乎所有设备入口都先校验归属，防止跨家长越权），也是在线态收敛（setOffline /
 * handleBrokerOffline）的落库与推送点。此前**零单测**。
 *
 * 覆盖（聚焦归属守卫 + 纯逻辑，不依赖 Spring 事务代理）：
 * - `getDevice` / `getUserId` / `getHmacSecret` / `policyVersionOf` 的查不到与归属分支；
 * - `handleBrokerOffline`：presence 新鲜则忽略、陈旧则置离线并推 WS；
 * - `discoverLanDevices`：只列同网段 / 近期活跃 / 无主设备，排除自己与他人的；
 * - `unbind`：不存在 / 他人所有拒绝，孤儿接管成功且吊销令牌 + purge + 审计 + 推解绑；
 * - `rename`：空名 / 超长校验，成功落库并下推设备名。
 *
 * 16 个依赖全 mock；`@Transactional` 在无 Spring 代理下按普通方法执行，`afterCommit` 立即执行，
 * 故 WS/MQTT 副作用直接落到 mock。沿用既定 Mockito 占位范式（非空参数 matcher 补 `?: 默认值`）。
 */
class DeviceServiceTest {
    private val deviceRepository = mock(DeviceRepository::class.java)
    private val ownershipGuard = mock(DeviceOwnershipGuard::class.java)
    private val enrollmentService = mock(EnrollmentService::class.java)
    private val policyService = mock(PolicyService::class.java)
    private val webSocketPush = mock(WebSocketPushService::class.java)
    private val mqttGateway = mock(MqttGateway::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val policyRepository = mock(PolicyRepository::class.java)
    private val auditService = mock(AuditService::class.java)
    private val billingService = mock(BillingService::class.java)
    private val deviceDataPurgeService = mock(DeviceDataPurgeService::class.java)
    private val presence = mock(DevicePresenceRegistry::class.java)
    private val streamHub = mock(ScreenStreamHub::class.java)
    // 真 TransactionTemplate + mock 事务管理器：解绑现在必须在事务内持键锁，
    // 用纯 mock 会让 execute 回调根本不执行（假绿）。getTransaction 必须返回非 null 状态，
    // 否则 Kotlin 对 TransactionTemplate 回调的非空 TransactionStatus 入参检查会直接 NPE。
    private val txManager = mock(PlatformTransactionManager::class.java).apply {
        `when`(getTransaction(any(TransactionDefinition::class.java) ?: DefaultTransactionDefinition()))
            .thenAnswer { SimpleTransactionStatus() }
    }
    private val txTemplate = TransactionTemplate(txManager)

    private fun svc(): DeviceService = DeviceService(
        deviceRepository, ownershipGuard, enrollmentService, policyService,
        webSocketPush, mqttGateway, userRepository, policyRepository,
        auditService, 30L, "", "",
        billingService, deviceDataPurgeService, presence, streamHub, txTemplate
    )

    // ---- getDevice / getUserId / getHmacSecret / policyVersionOf ----

    @Test
    fun getDevice_notFound_throws() {
        `when`(deviceRepository.findById("d1")).thenReturn(Optional.empty())
        val ex = assertThrows(BizException::class.java) { svc().getDevice("u1", "d1") }
        assertEquals(ParentErr.DEVICE_NOT_FOUND, ex.code)
    }

    @Test
    fun getDevice_ownedByOther_throws() {
        `when`(deviceRepository.findById("d1")).thenReturn(Optional.of(Device(id = "d1", userId = "other")))
        val ex = assertThrows(BizException::class.java) { svc().getDevice("u1", "d1") }
        assertEquals(ParentErr.DEVICE_NOT_OWNED, ex.code)
    }

    @Test
    fun getDevice_success_returnsDto() {
        `when`(deviceRepository.findById("d1")).thenReturn(Optional.of(Device(id = "d1", userId = "u1", onlineStatus = "ONLINE")))
        val dto = svc().getDevice("u1", "d1")
        assertEquals("d1", dto.deviceId)
    }

    @Test
    fun getUserId_returnsOwner() {
        `when`(deviceRepository.findById("d1")).thenReturn(Optional.of(Device(id = "d1", userId = "u1")))
        assertEquals("u1", svc().getUserId("d1"))
    }

    @Test
    fun getUserId_unknown_returnsNull() {
        `when`(deviceRepository.findById("d1")).thenReturn(Optional.empty())
        assertNull(svc().getUserId("d1"))
    }

    @Test
    fun getHmacSecret_returnsSecretWhenPresent() {
        `when`(deviceRepository.findById("d1")).thenReturn(Optional.of(Device(id = "d1", hmacSecret = "sec123")))
        assertEquals("sec123", svc().getHmacSecret("d1"))
    }

    @Test
    fun getHmacSecret_blankReturnsNull() {
        `when`(deviceRepository.findById("d1")).thenReturn(Optional.of(Device(id = "d1", hmacSecret = "")))
        assertNull(svc().getHmacSecret("d1"))
    }

    @Test
    fun policyVersionOf_returnsMaxVersion() {
        `when`(policyRepository.findFirstByDeviceIdOrderByVersionDesc("d1")).thenReturn(Policy(version = 7))
        assertEquals(7, svc().policyVersionOf("d1"))
    }

    @Test
    fun policyVersionOf_noPolicy_returnsZero() {
        `when`(policyRepository.findFirstByDeviceIdOrderByVersionDesc("d1")).thenReturn(null)
        assertEquals(0, svc().policyVersionOf("d1"))
    }

    // ---- handleBrokerOffline ----

    @Test
    fun handleBrokerOffline_ignoresWhenPresenceFresh() {
        `when`(presence.lastSeenAt("d1")).thenReturn(System.currentTimeMillis() - 1000)
        svc().handleBrokerOffline("d1")
        verify(deviceRepository, never()).findById(anyString())
        verify(presence, never()).markOffline(anyString())
    }

    @Test
    fun handleBrokerOffline_marksOfflineWhenStale() {
        `when`(presence.lastSeenAt("d1")).thenReturn(System.currentTimeMillis() - 10_000)
        val dev = Device(id = "d1", userId = "u1", onlineStatus = "ONLINE")
        `when`(deviceRepository.findById("d1")).thenReturn(Optional.of(dev))
        var saved: Device? = null
        `when`(deviceRepository.save(any(Device::class.java))).thenAnswer { a ->
            saved = a.arguments[0] as Device
            a.arguments[0]
        }
        svc().handleBrokerOffline("d1")
        verify(presence).markOffline("d1")
        verify(deviceRepository).save(any(Device::class.java))
        verify(webSocketPush).pushToUser(eq("u1") ?: "u1", any() ?: emptyMap())
        assertEquals("OFFLINE", saved!!.onlineStatus)
    }

    // ---- discoverLanDevices ----

    @Test
    fun discoverLanDevices_listsOnlyOrphanInSameSubnetRecent() {
        val now = System.currentTimeMillis()
        val allRows = listOf(
            Device(id = "a", userId = null, deviceSn = "snA", model = "M", lastIp = "192.168.1.20", lastActiveAt = now),
            Device(id = "b", userId = "u1", lastIp = "192.168.1.21", lastActiveAt = now),
            Device(id = "c", userId = "other", lastIp = "192.168.1.22", lastActiveAt = now),
            Device(id = "d", userId = null, lastIp = "10.0.0.5", lastActiveAt = now),
            Device(id = "e", userId = null, lastIp = "192.168.1.30", lastActiveAt = now - 600_000)
        )
        `when`(deviceRepository.findByUserId("u1")).thenReturn(listOf(Device(id = "mine", userId = "u1")))
        `when`(userRepository.existsById(anyString())).thenReturn(true)
        `when`(deviceRepository.findByLastActiveAtGreaterThan(anyLong())).thenAnswer { a ->
            val since = a.arguments[0] as Long
            allRows.filter { (it.lastActiveAt ?: 0) > since }
        }
        val result = svc().discoverLanDevices("u1", "192.168.1.5")
        assertEquals(1, result.size)
        assertEquals("snA", result[0].deviceId)
    }

    // ---- unbind ----

    @Test
    fun unbind_notFound_throws() {
        `when`(deviceRepository.findById("d1")).thenReturn(Optional.empty())
        assertThrows(BizException::class.java) { svc().unbind("u1", "d1") }
        verify(deviceDataPurgeService, never()).purge(anyString(), anyString())
        verify(auditService, never()).record(any(), any(), any() ?: "", any(), any(), any(), any() ?: "SUCCESS", any())
    }

    @Test
    fun unbind_ownedByOther_throws() {
        val dev = Device(id = "d1", userId = "other", deviceTokenHash = "abc")
        `when`(deviceRepository.findById("d1")).thenReturn(Optional.of(dev))
        `when`(userRepository.existsById("other")).thenReturn(true)
        assertThrows(BizException::class.java) { svc().unbind("u1", "d1") }
        verify(deviceDataPurgeService, never()).purge(anyString(), anyString())
    }

    @Test
    fun unbind_orphanOwnerAllowedAndPurges() {
        val dev = Device(id = "d1", userId = "oldOwner", deviceTokenHash = "abc", guardPasswordHash = "x")
        `when`(deviceRepository.findById("d1")).thenReturn(Optional.of(dev))
        `when`(userRepository.existsById("oldOwner")).thenReturn(false) // 归属账号已不存在 → 孤儿接管
        var saved: Device? = null
        `when`(deviceRepository.save(any(Device::class.java))).thenAnswer { a ->
            saved = a.arguments[0] as Device
            a.arguments[0]
        }
        svc().unbind("u1", "d1")
        verify(deviceDataPurgeService).purge(eq("d1") ?: "d1", eq("UNBIND") ?: "UNBIND")
        verify(auditService).record(eq("u1") ?: "u1", eq("d1") ?: "d1", eq(AuditAction.DEVICE_UNBIND) ?: AuditAction.DEVICE_UNBIND, any(), any(), any(), any() ?: "SUCCESS", any())
        verify(mqttGateway).clearRetained("d1")
        verify(mqttGateway).publishConfig(eq("d1") ?: "d1", any() ?: emptyMap())
        assertNull(saved!!.userId)
        assertTrue(saved!!.deviceTokenHash.startsWith("revoked_"))
        assertEquals("", saved!!.guardPasswordHash)
    }

    @Test
    fun unbind_usesBindLockInsideTransaction() {
        // 行为测试无法直接观测锁，这里钉住接线：锁键必须与绑定流程同源，且加锁发生在事务内。
        val src = File("src/main/kotlin/com/padguard/server/service/DeviceService.kt").readText()
        val body = src.substringAfter("fun unbind(").substringBefore("private fun clearOwnershipForUnbind")
        assertTrue(
            body.contains("DeviceBindLocks.bindKey(seed.deviceSn, seed.fingerprint)"),
            "解绑必须复用绑定的 SN/指纹键锁，否则与 bindChildWithUser 并发时仍会覆盖归属"
        )
        assertTrue(
            body.indexOf("txTemplate.execute") >= 0 &&
                body.indexOf("txTemplate.execute") < body.indexOf("DeviceBindLocks.withKey"),
            "锁必须在事务内获取：PG 咨询锁是事务级锁，事务外加锁会在方法返回时提前释放"
        )
    }

    @Test
    fun unbind_rechecksOwnershipInsideLock() {
        // seed（锁外）还是自己的设备，但持锁后重新读到已被并发重绑给 u2：
        // 必须拒绝，不能用旧快照整行 save 覆盖新归属。
        val stale = Device(id = "d1", userId = "u1", deviceSn = "sn1", deviceTokenHash = "old")
        val fresh = Device(id = "d1", userId = "u2", deviceSn = "sn1", deviceTokenHash = "new")
        `when`(deviceRepository.findById("d1")).thenReturn(Optional.of(stale), Optional.of(fresh))
        `when`(userRepository.existsById("u2")).thenReturn(true)

        val ex = assertThrows(BizException::class.java) { svc().unbind("u1", "d1") }

        assertEquals(ParentErr.DEVICE_NOT_OWNED, ex.code)
        verify(deviceRepository, never()).save(any(Device::class.java))
        verify(deviceDataPurgeService, never()).purge(anyString(), anyString())
        verify(mqttGateway, never()).publishConfig(anyString(), any() ?: emptyMap())
    }

    // ---- rename ----

    @Test
    fun rename_emptyName_throws() {
        `when`(ownershipGuard.requireOwned(eq("u1") ?: "u1", eq("d1") ?: "d1")).thenReturn(Device(id = "d1", userId = "u1"))
        assertThrows(BizException::class.java) { svc().rename("u1", "d1", "") }
        verify(deviceRepository, never()).save(any(Device::class.java))
    }

    @Test
    fun rename_tooLong_throws() {
        `when`(ownershipGuard.requireOwned(eq("u1") ?: "u1", eq("d1") ?: "d1")).thenReturn(Device(id = "d1", userId = "u1"))
        assertThrows(BizException::class.java) { svc().rename("u1", "d1", "x".repeat(65)) }
    }

    @Test
    fun rename_success_savesAndPushes() {
        val dev = Device(id = "d1", userId = "u1")
        `when`(ownershipGuard.requireOwned(eq("u1") ?: "u1", eq("d1") ?: "d1")).thenReturn(dev)
        svc().rename("u1", "d1", "新平板")
        assertEquals("新平板", dev.name)
        verify(deviceRepository).save(dev)
        verify(mqttGateway).publishConfig(eq("d1") ?: "d1", any() ?: emptyMap())
    }
}
