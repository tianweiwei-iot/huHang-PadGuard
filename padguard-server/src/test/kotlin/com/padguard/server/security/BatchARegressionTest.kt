package com.padguard.server.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.ParentErr
import com.padguard.server.common.BizException
import com.padguard.server.domain.Command
import com.padguard.server.domain.Device
import com.padguard.server.domain.Geofence
import com.padguard.server.domain.LocationTrack
import com.padguard.server.mqtt.ChildUplinkHandler
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.DeviceEventRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.GeofenceRepository
import com.padguard.server.repository.LocationTrackRepository
import com.padguard.server.service.AlertService
import com.padguard.server.service.DeviceService
import com.padguard.server.service.FakeJpaRepository
import com.padguard.server.service.GeofenceService
import com.padguard.server.ws.WebSocketPushService
import io.jsonwebtoken.JwtException
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.util.LinkedHashMap

/**
 * 批次 A 验收门禁的回归测试（docs/上线修复实施方案.md 第四节）。
 *
 * 覆盖五个验收点，全部为纯 JVM 单元测试（内存假仓储 + Mockito 接口/类 mock）：
 * 1. S5 JWT typ 隔离：refresh / admin 令牌不能当 access 用，反之亦然；
 * 2. S6 ack 状态机：跨设备伪造回执被丢弃、EXPIRED 终态不可复活、非法状态被丢弃、合法回执正常生效并推 WS；
 * 3. S4 Geofence 归属：非属主读位置/围栏被拒、写围栏被拒且零落库（本轮审计漏网之鱼的回归钉子）；
 * 4. S14 限流：IP / 账号 / 场景三个维度各自独立计数，超限即拒。
 *
 * （S16 mockPay 归属已在 BillingServiceTest 覆盖；S13 读闸门已由 AdminServiceTest 的
 *   真实 RBAC 闸门间接覆盖。）
 */
class BatchARegressionTest {

    // ----------------------------- 内存假仓储 -----------------------------

    private class FakeDeviceRepo : FakeJpaRepository<Device, String>(), DeviceRepository {
        override val table = LinkedHashMap<String, Device>()
        override fun idOf(e: Device) = e.id
        override fun findByUserId(userId: String) = table.values.filter { it.userId == userId }
        override fun countByUserId(userId: String) = table.values.count { it.userId == userId }.toLong()
        override fun countByOnlineStatus(onlineStatus: String) =
            table.values.count { it.onlineStatus == onlineStatus }.toLong()
        override fun findByDeviceSn(deviceSn: String) = table.values.filter { it.deviceSn == deviceSn }
        override fun findByLastActiveAtGreaterThan(since: Long) =
            table.values.filter { (it.lastActiveAt ?: 0L) > since }
    }

    private class FakeCommandRepo : FakeJpaRepository<Command, String>(), CommandRepository {
        override val table = LinkedHashMap<String, Command>()
        override fun idOf(e: Command) = e.id
        override fun findByMsgId(msgId: String): Command? = table.values.firstOrNull { it.msgId == msgId }
        override fun findByMsgIdAndDeviceId(msgId: String, deviceId: String): Command? =
            table.values.firstOrNull { it.msgId == msgId && it.deviceId == deviceId }
        override fun findByDeviceIdOrderByCreatedAtDesc(deviceId: String) =
            table.values.filter { it.deviceId == deviceId }.sortedByDescending { it.createdAt }
        override fun findByDeviceIdOrderByCreatedAtAsc(deviceId: String) =
            table.values.filter { it.deviceId == deviceId }.sortedBy { it.createdAt }
        override fun findPendingForPolling(deviceId: String, status: String, now: Long, cursor: Long, pageable: Pageable) =
            emptyList<Command>()
        override fun findExpiredPending(deviceId: String, status: String, now: Long) = emptyList<Command>()
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }
    }

    private class FakeTrackRepo : FakeJpaRepository<LocationTrack, String>(), LocationTrackRepository {
        override val table = LinkedHashMap<String, LocationTrack>()
        override fun idOf(e: LocationTrack) = e.id
        override fun findByDeviceIdOrderByTsDesc(deviceId: String, pageable: Pageable) =
            table.values.filter { it.deviceId == deviceId }.sortedByDescending { it.ts }
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }
    }

    private class FakeGeofenceRepo : FakeJpaRepository<Geofence, String>(), GeofenceRepository {
        override val table = LinkedHashMap<String, Geofence>()
        override fun idOf(e: Geofence) = e.deviceId
        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }
    }

    private class FakeDeviceEventRepo : FakeJpaRepository<com.padguard.server.domain.DeviceEvent, String>(),
        DeviceEventRepository {
        override val table = LinkedHashMap<String, com.padguard.server.domain.DeviceEvent>()
        override fun idOf(e: com.padguard.server.domain.DeviceEvent) = e.id
        override fun existsByEventId(eventId: String) = table.values.any { it.eventId == eventId }
        override fun findByDeviceIdAndTimestampBetween(deviceId: String, start: Long, end: Long) = emptyList<com.padguard.server.domain.DeviceEvent>()
        override fun findExistingEventIds(eventIds: Collection<String>) =
            table.values.filter { it.eventId in eventIds }.map { it.eventId }.toSet()
        override fun insertIgnoreConflict(
            id: String, deviceId: String, eventId: String, type: String?,
            level: String?, detailJson: String?, timestamp: Long?
        ): Int {
            if (table.values.any { it.eventId == eventId }) return 0
            table[id] = com.padguard.server.domain.DeviceEvent(
                id = id, deviceId = deviceId, eventId = eventId, type = type,
                level = level, detailJson = detailJson, timestamp = timestamp
            )
            return 1
        }
    }

    // ----------------------------- S5：JWT typ 隔离 -----------------------------

    private fun jwt() = JwtTokenProvider("padguard-unit-test-secret-0123456789abcdef", 120, 30)

    @Test
    fun `refresh token cannot be used as access token (s5)`() {
        val p = jwt()
        val refresh = p.createRefreshToken("u1")
        val ex = assertThrows(JwtException::class.java) { p.parseAccessToken(refresh) }
        assertTrue(ex.message!!.contains("typ mismatch"), "必须以 typ 不符为由拒绝，而不是其他解析错误")
    }

    @Test
    fun `access token cannot refresh or act as admin (s5)`() {
        val p = jwt()
        val access = p.createAccessToken("u1")
        assertThrows(JwtException::class.java) { p.parseRefreshToken(access) }
        assertThrows(JwtException::class.java) { p.parseAdminToken(access) }
    }

    @Test
    fun `matching typ parses the subject (s5)`() {
        val p = jwt()
        assertEquals("u1", p.parseAccessToken(p.createAccessToken("u1")))
        assertEquals("u1", p.parseRefreshToken(p.createRefreshToken("u1")))
        assertEquals("A1", p.parseAdminToken(p.createAdminToken("A1")))
    }

    // ----------------------------- S6：ack 状态机 -----------------------------

    private fun ackFixture(): Triple<ChildUplinkHandler, FakeCommandRepo, WebSocketPushService> {
        val commands = FakeCommandRepo()
        val deviceService = Mockito.mock(DeviceService::class.java)
        // 本组用例设备统一为 dev-A；用具体值 stub —— Mockito inline 对 Kotlin 非空参数
        // 的 matcher 匹配会在入口 null 检查处先失败，因此全程不用参数 matcher。
        Mockito.`when`(deviceService.getUserId("dev-A")).thenReturn("parent-1")
        val ws = Mockito.mock(WebSocketPushService::class.java)
        val alerts = Mockito.mock(AlertService::class.java)
        val handler = ChildUplinkHandler(
            null, ObjectMapper(), commands, FakeDeviceEventRepo(), deviceService, ws, alerts
        )
        return Triple(handler, commands, ws)
    }

    /** applyAck 成功路径推给家长端的 WS 报文（与 ChildUplinkHandler.pushToUser 载荷逐字段一致） */
    private fun expectedPush(deviceId: String, msgId: String, status: String) =
        mapOf("type" to "command.result", "deviceId" to deviceId, "msgId" to msgId, "status" to status)

    private fun seedCommand(commands: FakeCommandRepo, msgId: String, deviceId: String, status: String) {
        commands.table[msgId] = Command(
            id = "id-$msgId", deviceId = deviceId, msgId = msgId, type = "LOCK",
            status = status, expiresAt = Long.MAX_VALUE, createdAt = 0L
        )
    }

    private fun ackJson(msgId: String, status: String) =
        """{"msgId":"$msgId","status":"$status","executedAt":123}"""

    @Test
    fun `ack from another device is dropped (s6 cross-device forgery)`() {
        val (handler, commands, ws) = ackFixture()
        seedCommand(commands, "m1", deviceId = "dev-A", status = "PENDING")
        // 设备 B 冒充回执 dev-A 的指令：双键匹配查不到，状态不得被改写
        handler.handleAck("dev-B", ackJson("m1", "SUCCESS"))
        assertEquals("PENDING", commands.table["m1"]!!.status, "跨设备回执必须被丢弃")
        verify(ws, never()).pushToUser("parent-1", expectedPush("dev-A", "m1", "SUCCESS"))
    }

    @Test
    fun `expired command cannot be revived by a late ack (s6 one-way)`() {
        val (handler, commands, ws) = ackFixture()
        seedCommand(commands, "m2", deviceId = "dev-A", status = "EXPIRED")
        handler.handleAck("dev-A", ackJson("m2", "SUCCESS"))
        assertEquals("EXPIRED", commands.table["m2"]!!.status, "EXPIRED 是终态，迟到回执不得复活指令")
        verify(ws, never()).pushToUser("parent-1", expectedPush("dev-A", "m2", "SUCCESS"))
    }

    @Test
    fun `illegal status is dropped (s6 whitelist)`() {
        val (handler, commands, ws) = ackFixture()
        seedCommand(commands, "m3", deviceId = "dev-A", status = "PENDING")
        handler.handleAck("dev-A", ackJson("m3", "HACKED"))
        assertEquals("PENDING", commands.table["m3"]!!.status, "白名单外的垃圾状态必须被丢弃")
        verify(ws, never()).pushToUser("parent-1", expectedPush("dev-A", "m3", "HACKED"))
    }

    @Test
    fun `valid pending ack is applied and pushed (s6 happy path)`() {
        val (handler, commands, ws) = ackFixture()
        seedCommand(commands, "m4", deviceId = "dev-A", status = "PENDING")
        handler.handleAck("dev-A", ackJson("m4", "SUCCESS"))
        assertEquals("SUCCESS", commands.table["m4"]!!.status, "合法回执必须真实落库")
        assertEquals(123L, commands.table["m4"]!!.executedAt)
        verify(ws, times(1)).pushToUser("parent-1", expectedPush("dev-A", "m4", "SUCCESS"))
    }

    @Test
    fun `success can upgrade a failed ack (s6 retry semantics)`() {
        val (handler, commands, ws) = ackFixture()
        seedCommand(commands, "m5", deviceId = "dev-A", status = "FAILED")
        handler.handleAck("dev-A", ackJson("m5", "SUCCESS"))
        assertEquals("SUCCESS", commands.table["m5"]!!.status, "FAILED 可重试，允许升级为 SUCCESS")
        verify(ws, times(1)).pushToUser("parent-1", expectedPush("dev-A", "m5", "SUCCESS"))
    }

    // ----------------------------- S4：Geofence 归属（审计漏网钉子） -----------------------------

    private fun geofenceFixture(): Quad<GeofenceService, FakeDeviceRepo, FakeGeofenceRepo, FakeTrackRepo> {
        val devices = FakeDeviceRepo()
        val tracks = FakeTrackRepo()
        val geofences = FakeGeofenceRepo()
        val guard = DeviceOwnershipGuard(devices)
        val svc = GeofenceService(devices, tracks, geofences, Mockito.mock(AlertService::class.java), guard)
        return Quad(svc, devices, geofences, tracks)
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun seedOwnedDevice(devices: FakeDeviceRepo) {
        devices.table["dev-1"] = Device("dev-1").also { it.userId = "parent-A" }
    }

    @Test
    fun `non owner cannot read location geofence or track (s4 idor regression)`() {
        val (svc, devices, _, _) = geofenceFixture()
        seedOwnedDevice(devices)
        listOf(
            { svc.currentLocation("parent-B", "dev-1") },
            { svc.trackHistory("parent-B", "dev-1", 10) },
            { svc.getGeofence("parent-B", "dev-1") }
        ).forEach { call ->
            val ex = assertThrows(BizException::class.java) { call() }
            assertEquals(ParentErr.DEVICE_NOT_OWNED, ex.code, "非属主读取必须按越权拒绝")
        }
        // 不存在的设备按"设备不存在"而非"越权"返回（不泄露设备存在性）
        val nf = assertThrows(BizException::class.java) { svc.currentLocation("parent-A", "ghost") }
        assertEquals(ParentErr.DEVICE_NOT_FOUND, nf.code)
    }

    @Test
    fun `non owner cannot update geofence and nothing is persisted (s4 idor regression)`() {
        val (svc, devices, geofences, _) = geofenceFixture()
        seedOwnedDevice(devices)
        val req = com.padguard.server.dto.GeofenceDto(
            deviceId = "dev-1", enabled = true, name = "家",
            centerLatitude = 31.0, centerLongitude = 121.0, radiusMeters = 200, alertOnExit = true
        )
        val ex = assertThrows(BizException::class.java) { svc.updateGeofence("parent-B", "dev-1", req) }
        assertEquals(ParentErr.DEVICE_NOT_OWNED, ex.code)
        assertTrue(geofences.table.isEmpty(), "越权写围栏不得落库")
        // 属主本人正常写入
        svc.updateGeofence("parent-A", "dev-1", req)
        assertEquals(1, geofences.table.size, "属主写围栏应正常落库")
    }

    // ----------------------------- S14：限流 -----------------------------

    private fun requestFrom(ip: String): HttpServletRequest =
        Mockito.mock(HttpServletRequest::class.java).also {
            Mockito.`when`(it.getHeader("X-Forwarded-For")).thenReturn(ip)
            Mockito.`when`(it.remoteAddr).thenReturn(ip)
        }

    @Test
    fun `eleventh login attempt from same ip is rejected (s14)`() {
        val rl = RateLimit()
        val req = requestFrom("10.0.0.1")
        repeat(10) { rl.require("login", req, "13800000001") }
        val ex = assertThrows(BizException::class.java) { rl.require("login", req, "13800000001") }
        assertEquals(ParentErr.TOO_MANY_REQUESTS, ex.code, "同 IP 同账号第 11 次必须被拒")
    }

    @Test
    fun `account dimension is independent of ip (s14)`() {
        val rl = RateLimit()
        // 换 10 个不同 IP 打同一账号：每次 IP 预算全新，但账号预算耗尽
        repeat(10) { i -> rl.require("login", requestFrom("10.0.0.$i"), "13800000002") }
        val ex = assertThrows(BizException::class.java) { rl.require("login", requestFrom("10.0.0.11"), "13800000002") }
        assertEquals(ParentErr.TOO_MANY_REQUESTS, ex.code, "换 IP 不能绕过账号维度频控")
    }

    @Test
    fun `different scenario has its own budget (s14)`() {
        val rl = RateLimit()
        val req = requestFrom("10.0.1.1")
        repeat(10) { rl.require("login", req, "13800000003") }
        // login 已耗尽，但 admin_login / bind 是独立计数
        rl.require("admin_login", req, "admin")
        rl.require("bind", req, "123456", com.padguard.server.common.Audience.CHILD)
    }

    @Test
    fun `different ip is not affected by another ip budget (s14)`() {
        val rl = RateLimit()
        val reqA = requestFrom("10.0.2.1")
        repeat(10) { rl.require("login", reqA, "13800000004") }
        assertThrows(BizException::class.java) { rl.require("login", reqA, "13800000004") }
        // IP 预算互不影响：新 IP + 新账号正常放行
        rl.require("login", requestFrom("10.0.2.2"), "13800000005")
        // 新 IP + 同账号仍被拒：账号维度预算独立于 IP（与上一用例互证）
        assertThrows(BizException::class.java) { rl.require("login", requestFrom("10.0.2.2"), "13800000004") }
    }
}
