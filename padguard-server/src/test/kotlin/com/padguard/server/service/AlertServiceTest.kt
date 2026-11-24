package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.BizException
import com.padguard.server.domain.Alert
import com.padguard.server.domain.Device
import com.padguard.server.repository.AlertRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.util.LinkedHashMap
import java.util.Optional
import org.springframework.data.domain.Page

/**
 * 风险告警链路的单元测试（D7 完整版的家长端消费面底座）。
 *
 * 背景：此前孩子端上报的高危事件（权限降级 / root / 时间篡改 / 无障碍被关）
 * 在服务端建了告警、开了 `/v1/alerts` 接口，但家长端没有消费面，
 * 告警沉在库里没人看 —— "检测到却说给谁听"等于没检测。
 * D7 完整版把这条链路接到家长端消息中心前，先在服务端把语义钉死：
 *
 * 1. 事件等级映射（HIGH→CRITICAL / NORMAL→WARNING / 其余→INFO）与
 *    [AlertService.EVENT_TITLES] 的中文标题必须正确 —— 家长端直接展示这两个字段；
 * 2. **归属隔离**：list/acknowledge/close 一律以 userId 为准，
 *    家长 A 摸不到家长 B 的告警（IDOR 防线）；
 * 3. 确认/关闭是真实的状态迁移（带时间戳），不是"返回 success 什么都没做"；
 * 4. 建告警即向设备归属家长推 WS —— 家长端轮询之外的第二条触达路径。
 */
class AlertServiceTest {

    // ----------------------------- 内存假仓储 -----------------------------

    private class FakeDeviceRepo : FakeJpaRepository<Device, String>(), DeviceRepository {
    override fun updateEntitlementState(deviceId: String, state: String): Int {
        val d = table[deviceId] ?: return 0
        d.entitlementState = state
        return 1
    }

    override fun clearRemoteLockedIfSet(deviceId: String): Int {
        val d = table[deviceId] ?: return 0
        return if (d.remoteLocked == true) { d.remoteLocked = false; 1 } else 0
    }


        override val table = LinkedHashMap<String, Device>()
        override fun idOf(e: Device) = e.id
        override fun countGroupByUserIds(userIds: Collection<String>): List<Array<Any>> =
            table.values.filter { it.userId in userIds }.groupBy { it.userId }
                .map { arrayOf(it.key, it.value.size.toLong()) as Array<Any> }
        override fun findByIdForUpdate(id: String): Device? = table[id]
        override fun searchDevices(keyword: String, kw: String, onlineMode: String, staleCut: Long, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<Device> {
            var all = table.values.toList()
            if (onlineMode == "1") all = all.filter { it.onlineStatus == "ONLINE" && (it.lastOnlineAt ?: 0) >= staleCut }
            if (onlineMode == "0") all = all.filter { !(it.onlineStatus == "ONLINE" && (it.lastOnlineAt ?: 0) >= staleCut) }
            val sorted = all.sortedByDescending { it.createdAt }
            val from = pageable.offset.toInt()
            val to = minOf(from + pageable.pageSize, sorted.size)
            val content = if (from >= sorted.size) emptyList<Device>() else sorted.subList(from, to)
            return org.springframework.data.domain.PageImpl(content, pageable, sorted.size.toLong())
        }
        override fun findByUserId(userId: String) = table.values.filter { it.userId == userId }
        override fun countByUserId(userId: String) = table.values.count { it.userId == userId }.toLong()
        override fun countByOnlineStatus(onlineStatus: String) = table.values.count { it.onlineStatus == onlineStatus }.toLong()
        override fun findByOnlineStatus(onlineStatus: String) =
            table.values.filter { it.onlineStatus == onlineStatus }
        override fun findByOnlineStatusAndLastOnlineAtLessThan(onlineStatus: String, ts: Long) =
            table.values.filter { it.onlineStatus == onlineStatus && (it.lastOnlineAt ?: Long.MAX_VALUE) < ts }
        override fun findByDeviceSn(deviceSn: String) = table.values.filter { it.deviceSn == deviceSn }
        override fun findByLastActiveAtGreaterThan(since: Long) =
            table.values.filter { (it.lastActiveAt ?: 0L) > since }
    
        override fun countOnlineSince(staleCut: Long): Long = table.values.count {
            it.onlineStatus == "ONLINE" && it.lastOnlineAt != null && it.lastOnlineAt!! >= staleCut
        }.toLong()

        override fun findCreatedAtSince(since: Long): List<Long> =
            table.values.map { it.createdAt }.filter { it >= since }
}

    /**
     * 与 AdminServiceTest 里那个"一律返回空列表"的 FakeAlertRepo 不同：
     * 这里 list/acknowledge 是**被测主体**，过滤与分页必须真实生效，
     * 否则"归属隔离"这类断言会全数假通过。
     */
    private class FakeAlertRepo : FakeJpaRepository<Alert, String>(), AlertRepository {
        override val table = LinkedHashMap<String, Alert>()
        override fun idOf(e: Alert) = e.id
        override fun findTop500ByOrderByTriggeredAtDesc(): List<Alert> =
            table.values.sortedByDescending { it.triggeredAt }.take(500)

        private fun List<Alert>.sortedPaged(pageable: Pageable): List<Alert> =
            sortedByDescending { it.triggeredAt }
                .drop(pageable.pageNumber * pageable.pageSize)
                .take(pageable.pageSize)

        override fun findByUserIdAndStatus(userId: String, status: String, pageable: Pageable) =
            table.values.filter { it.userId == userId && it.status == status }.sortedPaged(pageable)

        override fun findByUserId(userId: String, pageable: Pageable) =
            table.values.filter { it.userId == userId }.sortedPaged(pageable)

        override fun countByUserIdAndStatus(userId: String, status: String) =
            table.values.count { it.userId == userId && it.status == status }.toLong()

        override fun countByStatus(status: String) =
            table.values.count { it.status == status }.toLong()

        override fun findByDeviceIdOrderByTriggeredAtDesc(deviceId: String) =
            table.values.filter { it.deviceId == deviceId }.sortedByDescending { it.triggeredAt }

        override fun findByUserIdAndDeviceIdAndStatus(
            userId: String, deviceId: String, status: String, pageable: Pageable
        ) = table.values.filter { it.userId == userId && it.deviceId == deviceId && it.status == status }
            .sortedPaged(pageable)

        override fun findByUserIdAndDeviceId(userId: String, deviceId: String, pageable: Pageable) =
            table.values.filter { it.userId == userId && it.deviceId == deviceId }.sortedPaged(pageable)

        override fun countByUserIdAndStatusAndDeviceId(userId: String, status: String, deviceId: String) =
            table.values.count { it.userId == userId && it.status == status && it.deviceId == deviceId }.toLong()

        override fun deleteByDeviceId(deviceId: String): Int {
            val before = table.size
            table.values.removeIf { it.deviceId == deviceId }
            return before - table.size
        }

        // ---- 状态机条件 UPDATE（与生产 JPQL 语义一致：前驱态不符即 0 行幂等）----

        override fun acknowledgeIfActive(id: String, userId: String, at: Long): Int {
            val a = table[id] ?: return 0
            if (a.userId != userId || a.status != "ACTIVE") return 0
            a.status = "ACKNOWLEDGED"
            a.acknowledgedAt = at
            return 1
        }

        override fun closeIfOpen(id: String, userId: String, at: Long): Int {
            val a = table[id] ?: return 0
            if (a.userId != userId || (a.status != "ACTIVE" && a.status != "ACKNOWLEDGED")) return 0
            a.status = "CLOSED"
            a.resolvedAt = at
            return 1
        }

        override fun appendCloseNoteIfStillClosed(id: String, suffix: String): Int {
            val a = table[id] ?: return 0
            if (a.status != "CLOSED") return 0
            a.message = (a.message ?: "") + suffix
            return 1
        }

        override fun closeActiveByCategory(deviceId: String, category: String, at: Long): Int {
            val targets = table.values.filter { it.deviceId == deviceId && it.category == category && it.status == "ACTIVE" }
            targets.forEach {
                it.status = "CLOSED"
                it.resolvedAt = at
            }
            return targets.size
        }

        override fun resolveIfNotResolved(id: String, at: Long): Int {
            val a = table[id] ?: return 0
            if (a.status == "RESOLVED") return 0
            a.status = "RESOLVED"
            a.resolvedAt = at
            return 1
        }
    }

    /**
     * 计数型假仓储：记录 `findById` / `findAllById` 的调用次数与参数，
     * 用于把"告警列表批量预取设备名（消灭 N+1）"钉死成回归测试。
     */
    private class CountingDeviceRepo : FakeDeviceRepo() {
        var findByIdCalls = 0
        var findAllByIdCalls = 0
        var lastFindAllByIdIds: List<String> = emptyList()
        override fun findById(id: String): Optional<Device> {
            findByIdCalls++
            return super.findById(id)
        }
        override fun findAllById(ids: MutableIterable<String>): MutableList<Device> {
            findAllByIdCalls++
            lastFindAllByIdIds = ids.toList()
            return super.findAllById(ids)
        }
    }

    // ----------------------------- 夹具 -----------------------------

    private lateinit var alertRepo: FakeAlertRepo
    private lateinit var deviceRepo: FakeDeviceRepo
    private lateinit var ws: WebSocketPushService
    private lateinit var svc: AlertService

    @BeforeEach
    fun setUp() {
        alertRepo = FakeAlertRepo()
        deviceRepo = FakeDeviceRepo()
        // WebSocketPushService 是 final 类，沿用仓库既有做法 mock 掉以验证推送意图
        ws = mock(WebSocketPushService::class.java)
        svc = AlertService(alertRepo, deviceRepo, ws, ObjectMapper())
        // 两个家长各一台设备：u1/dev1、u2/dev2
        deviceRepo.save(Device(id = "dev1", userId = "u1", name = "小明的平板"))
        deviceRepo.save(Device(id = "dev2", userId = "u2", name = "教室平板-A03"))
    }

    private fun event(deviceId: String, type: String, level: String, detail: Map<String, Any?>? = null): Alert {
        val a = svc.createFromEvent(deviceId, type, level, detail)
        assertNotNull(a, "设备存在时建告警不应返回 null")
        return a!!
    }

    // ----------------------------- 等级与标题映射 -----------------------------

    @Test
    fun `HIGH event becomes CRITICAL alert with Chinese title`() {
        val alert = event("dev1", "PERMISSION_REVOKED", "HIGH", mapOf("from" to "DEVICE_OWNER", "to" to "DEVICE_ADMIN"))
        assertEquals("CRITICAL", alert.level)
        // 家长端直接展示服务端给的标题/文案，映射错一位家长就看到错的说法
        assertEquals("权限被撤销", alert.title)
        assertEquals("管控所需权限被撤销", alert.message)
        assertEquals("ACTIVE", alert.status)
        assertEquals("u1", alert.userId)
        assertTrue(alert.detailJson!!.contains("DEVICE_ADMIN"), "detail 应原样落库，家长端要能展开看现场")
        // 建警即推 WS：家长端轮询之外的第二条触达路径。
        // 注意 any() 后面必须跟 ?: 非空值：Mockito 匹配器返回 null，而 pushToUser 的
        // payload 是 Kotlin 非空参数，直接传会触发 checkNotNullParameter NPE
        //（报 "any(...) must not be null"）。匹配器在 any() 调用时就已注册，
        // 之后传什么实参都不参与匹配，?: 只是把空值链打断。
        verify(ws).pushToUser(eq("u1"), ArgumentMatchers.any() ?: emptyMap())
    }

    @Test
    fun `NORMAL event becomes WARNING and unknown level becomes INFO`() {
        val warning = event("dev1", "USAGE_ACCESS_LOST", "NORMAL")
        assertEquals("WARNING", warning.level)
        assertEquals("使用情况权限被关闭", warning.title)

        val info = event("dev1", "BLACKLIST_APP_LAUNCH", "INFO")
        assertEquals("INFO", info.level)
        assertEquals("打开黑名单应用", info.title)
    }

    /**
     * 未知风险类型必须落到可读中文兜底，不能把英文枚举名原样当标题。
     * 这是"孩子端新增 RiskType 但服务端漏登记"事故的真实行为防线：
     * 直接跑 [AlertService.createFromEvent] 并断言落库结果，而非断言测试内局部常量。
     */
    @Test
    fun `unmapped risk type falls back to readable Chinese title and message`() {
        val alert = event("dev1", "SOME_FUTURE_TYPE", "NORMAL")
        assertEquals("设备事件", alert.title, "未知类型的标题必须是中文兜底，不能是英文枚举名")
        assertTrue(alert.message!!.contains("SOME_FUTURE_TYPE"), "正文要保留原始类型名供排查")
        assertNotEquals("SOME_FUTURE_TYPE", alert.title, "原始枚举名绝不能当标题")
        assertEquals("SOME_FUTURE_TYPE", alert.category, "category 仍存原始枚举名，供后台筛选")
    }

    @Test
    fun `createFromEvent for unknown device is a no-op`() {
        assertNull(svc.createFromEvent("ghost", "ROOT_DETECTED", "HIGH", null))
        assertEquals(0L, alertRepo.count(), "设备不存在时不应留下任何告警残骸")
    }

    // ----------------------------- 归属隔离 -----------------------------

    @Test
    fun `list only returns own alerts`() {
        event("dev1", "ROOT_DETECTED", "HIGH")
        event("dev1", "CLOCK_TAMPERING", "HIGH")
        event("dev2", "TIME_LIMIT_EXCEEDED", "NORMAL")

        val mine = svc.list("u1", null, null, 0, 20)
        assertEquals(2, mine.size, "u1 只应看到自己设备的两条")
        assertTrue(mine.all { it.deviceId == "dev1" })

        val others = svc.list("u2", null, null, 0, 20)
        assertEquals(1, others.size)
        assertEquals("TIME_LIMIT_EXCEEDED", others.first().category)
    }

    @Test
    fun `list filters by deviceId and status`() {
        val a1 = event("dev1", "ROOT_DETECTED", "HIGH")
        event("dev1", "USB_DEBUG_ENABLED", "HIGH")
        event("dev2", "CLOCK_TAMPERING", "HIGH")

        val byDevice = svc.list("u1", "dev1", null, 0, 20)
        assertEquals(2, byDevice.size)
        assertTrue(byDevice.all { it.deviceId == "dev1" })

        val byStatus = svc.list("u1", null, "ACKNOWLEDGED", 0, 20)
        assertTrue(byStatus.isEmpty())
        svc.acknowledge("u1", a1.id)
        assertEquals(1, svc.list("u1", null, "ACKNOWLEDGED", 0, 20).size)
    }

    /**
     * 告警列表 N+1 回归锁：家长端每 15~30s 拉一次告警，一页若分散在多台设备，
     * 旧实现会逐条 `toDto` → 每条一次 `deviceRepository.findById`，一页 20 条即 20 次查询。
     * 修复后改为一次 `findAllById` 批量预取设备名。本测试确保该优化不被回退。
     */
    @Test
    fun `list batches device-name lookup into one findAllById, no N+1`() {
        val devRepo = CountingDeviceRepo()
        devRepo.save(Device(id = "devA", userId = "u1", name = "平板A"))
        devRepo.save(Device(id = "devB", userId = "u1", name = "平板B"))
        devRepo.save(Device(id = "devC", userId = "u1", name = "平板C"))
        val aRepo = FakeAlertRepo()
        // 9 条告警分散在 3 台设备（每台 3 条），归属同一家长 u1
        var seq = 0L
        for (dev in listOf("devA", "devB", "devC")) {
            for (i in 1..3) {
                seq++
                aRepo.save(Alert("al$seq", dev, "u1", "WARNING", "t", "m", "X", "ACTIVE", null, seq))
            }
        }
        val service = AlertService(aRepo, devRepo, mock(WebSocketPushService::class.java), ObjectMapper())

        val result = service.list("u1", null, null, 0, 100)

        assertEquals(9, result.size, "应返回全部 9 条告警")
        // 设备名只批量预取一次（findAllById），绝不逐条 findById 造成 N+1
        assertEquals(1, devRepo.findAllByIdCalls, "设备名应一次性批量预取，只调一次 findAllById")
        assertEquals(0, devRepo.findByIdCalls, "list 路径不应再逐条 findById")
        assertEquals(setOf("devA", "devB", "devC"), devRepo.lastFindAllByIdIds.toSet())
        // 设备名正确回填（非回退成 deviceId）
        assertTrue(result.all { it.deviceName != it.deviceId && it.deviceName.isNotBlank() })
    }

    // ----------------------------- 状态机 -----------------------------

    @Test
    fun `acknowledge moves ACTIVE to ACKNOWLEDGED with timestamp`() {
        val alert = event("dev1", "ACCESSIBILITY_DISABLED", "HIGH")
        assertEquals(1L, svc.unreadCount("u1"))

        svc.acknowledge("u1", alert.id)

        val reloaded = alertRepo.findById(alert.id).get()
        assertEquals("ACKNOWLEDGED", reloaded.status)
        assertNotNull(reloaded.acknowledgedAt, "确认时间戳必须落库，消息中心要展示'何时确认'")
        assertEquals(0L, svc.unreadCount("u1"), "确认后不再计入未读")
    }

    @Test
    fun `acknowledge rejects foreign alert and leaves it untouched`() {
        val alert = event("dev1", "ROOT_DETECTED", "HIGH")
        assertThrows(BizException::class.java) { svc.acknowledge("u2", alert.id) }
        // 越权不仅要被拒，还不能留下任何副作用
        assertEquals("ACTIVE", alertRepo.findById(alert.id).get().status)
    }

    @Test
    fun `close moves alert to CLOSED with resolved timestamp`() {
        val alert = event("dev1", "CLOCK_TAMPERING", "HIGH")
        svc.close("u1", alert.id, "已与孩子沟通")
        val reloaded = alertRepo.findById(alert.id).get()
        assertEquals("CLOSED", reloaded.status)
        assertNotNull(reloaded.resolvedAt)
        assertTrue(reloaded.message!!.contains("已与孩子沟通"), "关闭原因应追加到 message，留处理痕迹")
    }

    @Test
    fun `acknowledge unknown alert raises PARAM_ERROR`() {
        assertThrows(BizException::class.java) { svc.acknowledge("u1", "no-such-alert") }
    }

    // ----------------------------- 恢复即关闭闭环 -----------------------------

    @Test
    fun `resolveActiveByCategory closes only ACTIVE of device and category`() {
        val a1 = event("dev1", "ACCESSIBILITY_DISABLED", "HIGH")
        val aGpd = event("dev1", "GUARD_PERMISSION_DROPPED", "HIGH")
        val a3 = event("dev2", "ACCESSIBILITY_DISABLED", "HIGH")
        val a4 = event("dev1", "ACCESSIBILITY_DISABLED", "HIGH")

        // 先把 dev1 的 GUARD_PERMISSION_DROPPED 处置掉，验证 resolve 不碰已 CLOSED
        svc.close("u1", aGpd.id, "家长已处理")

        svc.resolveActiveByCategory("dev1", "ACCESSIBILITY_DISABLED", "已自动恢复")

        // 同设备同类别 ACTIVE 全关，且写 resolvedAt
        assertEquals("CLOSED", alertRepo.findById(a1.id).get().status)
        assertEquals("CLOSED", alertRepo.findById(a4.id).get().status)
        assertNotNull(alertRepo.findById(a1.id).get().resolvedAt)
        // 另一设备的同类应保留
        assertEquals("ACTIVE", alertRepo.findById(a3.id).get().status)
        // 已 CLOSED（不同类别或家长已处置）不受影响
        assertEquals("CLOSED", alertRepo.findById(aGpd.id).get().status)
        // 未读：u1 两条 ACCESSIBILITY_DISABLED 已关，u2 的 a3 仍 ACTIVE
        assertEquals(0L, svc.unreadCount("u1"))
        assertEquals(1L, svc.unreadCount("u2"))
    }

    @Test
    fun `resolveActiveByCategory leaves unrelated category untouched`() {
        event("dev1", "ACCESSIBILITY_DISABLED", "HIGH")
        // 以不相关的类别请求关闭，不应误伤真正的告警
        svc.resolveActiveByCategory("dev1", "ROOT_DETECTED", "自愈")
        assertEquals(1L, svc.unreadCount("u1"))
        assertEquals("ACTIVE", alertRepo.findByUserIdAndDeviceId("u1", "dev1", PageRequest.of(0, 20)).first().status)
    }

    // ----------------------------- 未读数 -----------------------------

    @Test
    fun `unreadCount counts only ACTIVE`() {
        val a1 = event("dev1", "ROOT_DETECTED", "HIGH")
        event("dev1", "USB_DEBUG_ENABLED", "HIGH")
        event("dev2", "TIME_LIMIT_EXCEEDED", "NORMAL")
        assertEquals(3L, svc.unreadCount("u1") + svc.unreadCount("u2"))

        svc.acknowledge("u1", a1.id)
        assertEquals(1L, svc.unreadCount("u1"))
        assertEquals(1L, svc.unreadCount("u2"))
        assertFalse(svc.unreadCount("nobody") > 0)
    }

    // ----------------------------- 分页 -----------------------------

    @Test
    fun `create suppresses duplicate category within cooldown window`() {
        val a1 = svc.create("dev1", "WARNING", "t", "m", "DUP_CAT")
        assertNotNull(a1, "首次建警应成功")
        // 同一设备+类别在冷却窗口内：应被抑制，避免告警风暴（R-P2-14）
        val a2 = svc.create("dev1", "WARNING", "t", "m", "DUP_CAT")
        assertNull(a2, "同设备同类别在冷却窗口内应被抑制")
        // 同设备不同类别：允许
        assertNotNull(svc.create("dev1", "WARNING", "t", "m", "OTHER_CAT"))
        // 不同设备同类别：允许
        assertNotNull(svc.create("dev2", "WARNING", "t", "m", "DUP_CAT"))
    }

    @Test
    fun `list paginates by triggeredAt desc`() {
        repeat(5) { i ->
            val a = svc.createFromEvent("dev1", "ROOT_DETECTED", "HIGH", null)!!
            a.triggeredAt = 1000L + i // 直接改字段模拟不同触发时间
        }
        val page0 = svc.list("u1", null, null, 0, 2)
        assertEquals(2, page0.size)
        assertEquals(1004L, page0[0].triggeredAt, "最新的排最前")
        assertEquals(1003L, page0[1].triggeredAt)
        val page1 = svc.list("u1", null, null, 1, 2)
        assertEquals(1002L, page1[0].triggeredAt)
    }

    // ----------------------------- 终态不可回退（R45） -----------------------------

    /**
     * 状态迁移必须带前驱条件（项目红线：终态不可回退靠 DB WHERE）。
     * 旧实现"读实体→改字段→整行 save"允许 UI 残留入口重放把 CLOSED 拉回 ACKNOWLEDGED，
     * 家长端红点死而复生；本组用例锁死条件 UPDATE 的前驱语义。
     */
    @Test
    fun `acknowledge cannot resurrect a CLOSED alert`() {
        val alert = event("dev1", "CLOCK_TAMPERING", "HIGH")
        svc.close("u1", alert.id, null)

        // UI 残留入口 / 双击重放：不抛错（幂等），但绝不能把 CLOSED 拉回 ACKNOWLEDGED
        svc.acknowledge("u1", alert.id)

        val reloaded = alertRepo.findById(alert.id).get()
        assertEquals("CLOSED", reloaded.status, "CLOSED 是终态，重放 acknowledge 不得复活红点")
        assertNull(reloaded.acknowledgedAt, "终态复活的同时不得伪造确认时间戳")
    }

    @Test
    fun `close cannot overwrite a RESOLVED alert`() {
        val alert = event("dev1", "ROOT_DETECTED", "HIGH")
        val resolvedAt = 1234567890123L
        alertRepo.acknowledgeIfActive(alert.id, "u1", System.currentTimeMillis())
        alertRepo.resolveIfNotResolved(alert.id, resolvedAt) // 模拟后台已处置

        svc.close("u1", alert.id, "重复处理")

        val reloaded = alertRepo.findById(alert.id).get()
        assertEquals("RESOLVED", reloaded.status, "后台已 RESOLVED 的告警是终态，家长 close 不得覆盖")
        assertEquals(resolvedAt, reloaded.resolvedAt, "终态时间戳不得被改写")
        assertFalse(reloaded.message.orEmpty().contains("[处理:"), "处理说明不得追加到已 RESOLVED 的告警")
    }

    @Test
    fun `resolveActiveByCategory skips ACKNOWLEDGED alerts and pushes closed count`() {
        val active = event("dev1", "ACCESSIBILITY_DISABLED", "HIGH")
        val acknowledged = event("dev1", "ACCESSIBILITY_DISABLED", "HIGH")
        alertRepo.acknowledgeIfActive(acknowledged.id, "u1", System.currentTimeMillis())

        svc.resolveActiveByCategory("dev1", "ACCESSIBILITY_DISABLED")

        assertEquals("CLOSED", alertRepo.findById(active.id).get().status)
        assertEquals(
            "ACKNOWLEDGED", alertRepo.findById(acknowledged.id).get().status,
            "家长已确认的告警保留 ACKNOWLEDGED，自愈批量关闭只动 ACTIVE"
        )
        // 推送的 count 必须是真实关闭数（1），不能把跳过的 ACKNOWLEDGED 也算进去。
        // argThat 对 Kotlin 非空参数返回 null，需 ?: 打断空值链（同上 HIGH event 用例的注释）。
        verify(ws).pushToUser(eq("u1"), argThat<Map<String, Any?>> { it["count"] == 1 } ?: emptyMap())
    }

    @Test
    fun `double acknowledge is idempotent and keeps first timestamp`() {
        val alert = event("dev1", "PERMISSION_REVOKED", "HIGH")
        svc.acknowledge("u1", alert.id)
        val first = alertRepo.findById(alert.id).get().acknowledgedAt

        Thread.sleep(2) // 保证时间戳可区分
        svc.acknowledge("u1", alert.id)

        assertEquals("ACKNOWLEDGED", alertRepo.findById(alert.id).get().status)
        assertEquals(first, alertRepo.findById(alert.id).get().acknowledgedAt, "重复确认不得改写首次确认时间")
    }
}
