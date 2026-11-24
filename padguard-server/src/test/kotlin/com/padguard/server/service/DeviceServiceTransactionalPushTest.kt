package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.Device
import com.padguard.server.dto.HeartbeatDto
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PolicyRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.DeviceOwnershipGuard
import com.padguard.server.stream.ScreenStreamHub
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional

/**
 * [DeviceService.applyHeartbeat] / [DeviceService.setOffline] 都带 `@Transactional`，
 * 却在方法体内直接推 WS。事务提交前就推送，回滚时家长端会收到数据库里并不存在的
 * `device.online` / `device.offline`，表现为「一会儿上线一会儿掉线」。
 *
 * 这里手工激活 Spring 同步（纯 JVM 单测没有事务代理），用提交回调的触发/不触发
 * 分别模拟提交与回滚，锁死「提交前不推、提交后恰好一次、回滚不推」。
 */
class DeviceServiceTransactionalPushTest {

    private val DEVICE = "dev-tx-1"
    private val USER = "user-tx-1"

    private class RecordingPush : WebSocketPushService(ObjectMapper(), users = com.padguard.server.support.PushTestUsers.permissive()) {
        val events = mutableListOf<Map<String, Any?>>()
        override fun pushToUser(userId: String?, payload: Map<String, Any?>) {
            if (userId != null) events += payload
        }
    }

    private val devices = Mockito.mock(DeviceRepository::class.java)
    private val users = Mockito.mock(UserRepository::class.java)
    private val push = RecordingPush()

    private val service: DeviceService by lazy {
        DeviceService(
            devices,
            DeviceOwnershipGuard(devices),
            Mockito.mock(EnrollmentService::class.java),
            Mockito.mock(PolicyService::class.java),
            push,
            Mockito.mock(MqttGateway::class.java),
            users,
            Mockito.mock(PolicyRepository::class.java),
            Mockito.mock(AuditService::class.java),
            30L,
            "",
            "",
            Mockito.mock(BillingService::class.java),
            Mockito.mock(DeviceDataPurgeService::class.java),
            DevicePresenceRegistry(),
            ScreenStreamHub(),
            TransactionTemplate(Mockito.mock(PlatformTransactionManager::class.java))
        )
    }

    private fun stubDevice(onlineStatus: String) {
        Mockito.`when`(devices.findById(DEVICE)).thenReturn(
            Optional.of(Device(id = DEVICE, userId = USER, onlineStatus = onlineStatus))
        )
    }

    private fun fireCommit() =
        TransactionSynchronizationManager.getSynchronizations().toList().forEach { it.afterCommit() }

    @AfterEach
    fun clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
        push.events.clear()
    }

    @Test
    @DisplayName("setOffline：提交前不推 device.offline，提交后恰好一次")
    fun `setOffline defers push to after commit`() {
        stubDevice("ONLINE")
        TransactionSynchronizationManager.initSynchronization()
        service.setOffline(DEVICE)
        assertTrue(push.events.isEmpty(), "事务未提交就推送，回滚时家长会看到并不存在的离线事件")
        fireCommit()
        assertEquals(1, push.events.size)
        assertEquals("device.offline", push.events.single()["type"])
    }

    @Test
    @DisplayName("setOffline：事务回滚（不触发提交回调）时不得推送")
    fun `setOffline pushes nothing on rollback`() {
        stubDevice("ONLINE")
        TransactionSynchronizationManager.initSynchronization()
        service.setOffline(DEVICE)
        TransactionSynchronizationManager.clearSynchronization() // 模拟回滚
        assertTrue(push.events.isEmpty(), "回滚后不得留下推送")
    }

    @Test
    @DisplayName("applyHeartbeat：上线事件同样推迟到提交后")
    fun `applyHeartbeat defers device online push to after commit`() {
        stubDevice("OFFLINE")
        TransactionSynchronizationManager.initSynchronization()
        service.applyHeartbeat(DEVICE, HeartbeatDto())
        assertTrue(push.events.isEmpty(), "心跳在事务内，上线事件必须等提交")
        fireCommit()
        assertEquals(1, push.events.size)
        assertEquals("device.online", push.events.single()["type"])
    }

    @Test
    @DisplayName("无事务路径行为不变：立即推送（兼容既有直调/单测语义）")
    fun `immediate push without synchronization`() {
        stubDevice("ONLINE")
        service.setOffline(DEVICE)
        assertEquals(1, push.events.size, "没有事务上下文时必须立即执行")
    }
}
