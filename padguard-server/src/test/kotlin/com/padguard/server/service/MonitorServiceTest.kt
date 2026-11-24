package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.domain.DeviceSetting
import com.padguard.server.domain.MediaTask
import com.padguard.server.domain.Screenshot
import com.padguard.server.dto.CommandDto
import com.padguard.server.dto.ScreenMonitorSettingsDto
import com.padguard.server.dto.StartRecordRequest
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.DeviceSettingRepository
import com.padguard.server.repository.MediaTaskRepository
import com.padguard.server.repository.ScreenshotRepository
import com.padguard.server.security.DeviceOwnershipGuard
import com.padguard.server.stream.ScreenStreamHub
import com.padguard.server.ws.WebSocketPushService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * `MonitorService` 是家长端「看屏 / 截图 / 录音录屏 / 定位」的唯一服务端入口，此前**自身方法零覆盖**。
 *
 * 信任关键点：
 * - 所有家长端方法首行过 [DeviceOwnershipGuard]，越权下发一条指令就是跨家庭管控；
 * - [finishMediaTask] 必须校验「上报设备 == 任务归属设备」，否则枚举 taskId 可跨设备劫持媒体任务；
 * - 实时看屏会话自愈（TTL / 重发冷却 / 僵尸连接）决定「杀进程后画面能否自动恢复」；
 * - 并发准入上限防止出口带宽被打满；
 * - 停止录屏必须**真下发** STOP 指令（历史上只查库返回 200，孩子端继续录）。
 *
 * 不加载 Spring 上下文：9 个依赖全部 Mockito 桩，规避全量集成测试的沙箱 OOM。
 */
class MonitorServiceTest {

    private lateinit var devices: DeviceRepository
    private lateinit var guard: DeviceOwnershipGuard
    private lateinit var commands: CommandService
    private lateinit var screenshots: ScreenshotRepository
    private lateinit var mediaTasks: MediaTaskRepository
    private lateinit var settings: DeviceSettingRepository
    private lateinit var push: WebSocketPushService
    private lateinit var hub: ScreenStreamHub
    private lateinit var policyExt: PolicyExtensionService
    private lateinit var svc: MonitorService

    private val maxLiveDevices = 3

    @BeforeEach
    fun setUp() {
        devices = mock(DeviceRepository::class.java)
        guard = mock(DeviceOwnershipGuard::class.java)
        commands = mock(CommandService::class.java)
        screenshots = mock(ScreenshotRepository::class.java)
        mediaTasks = mock(MediaTaskRepository::class.java)
        settings = mock(DeviceSettingRepository::class.java)
        push = mock(WebSocketPushService::class.java)
        hub = mock(ScreenStreamHub::class.java)
        policyExt = mock(PolicyExtensionService::class.java)

        // 非空返回值的依赖一律给真实桩，否则 Kotlin 非空检查会 NPE 并污染 matcher 栈
        `when`(guard.requireOwned(anyString(), anyString()))
            .thenReturn(Device(id = "d1", userId = "u1"))
        `when`(devices.findById(anyString()))
            .thenReturn(Optional.of(Device(id = "d1", userId = "u1")))
        `when`(settings.findById(anyString()))
            .thenReturn(Optional.of(DeviceSetting(deviceId = "d1")))
        `when`(screenshots.findByDeviceIdOrderByCapturedAtDesc(anyString(), any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(emptyList())
        `when`(mediaTasks.findByDeviceIdAndKindOrderByStartedAtDesc(anyString(), anyString(), any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(emptyList())
        `when`(mediaTasks.findById(anyString())).thenReturn(Optional.empty())
        // issueCommand 是 4 参（priority 有默认值），桩必须给满 4 位 matcher
        `when`(commands.issueCommand(anyString(), anyString(), any(), anyString()))
            .thenReturn(CommandDto("m1", "X", "PENDING", emptyMap(), 0L, null))

        `when`(hub.publisherCount()).thenReturn(0)
        `when`(hub.isPublishing(anyString())).thenReturn(false)
        `when`(hub.lastFrameAt(anyString())).thenReturn(0L)

        svc = MonitorService(
            devices, guard, commands, screenshots, mediaTasks, settings,
            push, hub, policyExt, maxLiveDevices
        )
    }

    private fun denyOwnership() {
        doThrow(BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于该家长", com.padguard.server.common.Audience.PARENT))
            .`when`(guard).requireOwned(anyString(), anyString())
    }

    /** 抓取 issueCommand 的 payload（Map<String, Any?>），避免 Map<*,*> key 不变性编译问题 */
    @Suppress("UNCHECKED_CAST")
    private fun payloadCaptor(): ArgumentCaptor<Map<String, Any?>> =
        ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun liveSessions(): ConcurrentHashMap<String, Any> {
        val f = MonitorService::class.java.getDeclaredField("liveSessions")
        f.isAccessible = true
        return f.get(svc) as ConcurrentHashMap<String, Any>
    }

    private fun expireSession(deviceId: String = "d1") {
        val session = liveSessions()[deviceId] ?: return
        val f = session.javaClass.getDeclaredField("expiresAt")
        f.isAccessible = true
        f.set(session, System.currentTimeMillis() - 1L)
    }

    // ---------- IDOR 归属闸门 ----------

    @Test
    fun `requestScreenshot 越权不下发任何指令且不落截图记录`() {
        denyOwnership()
        assertThrows(BizException::class.java) { svc.requestScreenshot("u1", "d1") }
        verify(commands, never()).issueCommand(anyString(), anyString(), any(), anyString())
        verify(screenshots, never()).save(any(Screenshot::class.java))
    }

    @Test
    fun `listScreenshots 越权不读库`() {
        denyOwnership()
        assertThrows(BizException::class.java) { svc.listScreenshots("u1", "d1", 20) }
        verify(screenshots, never())
            .findByDeviceIdOrderByCapturedAtDesc(anyString(), any(Pageable::class.java) ?: PageRequest.of(0, 1))
    }

    @Test
    fun `startLiveView 越权不登记会话也不下发开流指令`() {
        denyOwnership()
        assertThrows(BizException::class.java) { svc.startLiveView("u1", "d1") }
        assertTrue(liveSessions().isEmpty())
        verify(commands, never()).issueCommand(anyString(), anyString(), any(), anyString())
    }

    @Test
    fun `getMediaTask 越权被拒`() {
        `when`(mediaTasks.findById("t1"))
            .thenReturn(Optional.of(MediaTask(id = "t1", deviceId = "d1", kind = "RECORD")))
        denyOwnership()
        assertThrows(BizException::class.java) { svc.getMediaTask("u1", "t1") }
    }

    @Test
    fun `getScreenSettings 越权被拒`() {
        denyOwnership()
        assertThrows(BizException::class.java) { svc.getScreenSettings("u1", "d1") }
    }

    // ---------- 指令契约与参数边界 ----------

    @Test
    fun `requestScreenshot 落 PENDING 记录并下发带 shotId 的 SCREENSHOT 指令`() {
        val res = svc.requestScreenshot("u1", "d1")
        assertEquals("PENDING", res.status)
        assertEquals("d1", res.deviceId)

        val cap = payloadCaptor()
        verify(commands).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.SCREENSHOT) ?: "", cap.capture(), eq("HIGH") ?: "HIGH"
        )
        val p = cap.value
        assertEquals(res.taskId, p[CommandKey.SHOT_ID])
        assertEquals("REMOTE", p[CommandKey.TRIGGER_TYPE])

        val row = ArgumentCaptor.forClass(Screenshot::class.java)
        verify(screenshots).save(row.capture())
        assertEquals("PENDING", row.value.status)
        assertEquals(res.taskId, row.value.shotId)
    }

    @Test
    fun `listScreenshots limit 钳制到 1 到 100`() {
        svc.listScreenshots("u1", "d1", 0)
        svc.listScreenshots("u1", "d1", -7)
        svc.listScreenshots("u1", "d1", 9999)

        val cap = ArgumentCaptor.forClass(Pageable::class.java)
        verify(screenshots, times(3))
            .findByDeviceIdOrderByCapturedAtDesc(eq("d1") ?: "d1", cap.capture() ?: PageRequest.of(0, 1))
        assertEquals(listOf(1, 1, 100), cap.allValues.map { it.pageSize })
    }

    @Test
    fun `requestLocate 不落 PENDING 记录但下发 LOCATE 指令`() {
        val res = svc.requestLocate("u1", "d1")
        assertEquals("LOCATE", res.kind)
        assertEquals("SENT", res.status)
        verify(commands).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.LOCATE) ?: "", any(), eq("HIGH") ?: "HIGH"
        )
        // 定位靠周期上报回填，造 PENDING 记录会留下永远不会被回填的悬空行
        verify(mediaTasks, never()).save(any(MediaTask::class.java))
        verify(screenshots, never()).save(any(Screenshot::class.java))
    }

    @Test
    fun `startScreenRecord 只下发一条带全量参数的指令`() {
        val req = StartRecordRequest(resolution = "FHD_1080P", withAudio = false)
        val res = svc.startScreenRecord("u1", "d1", req)
        assertEquals("SCREEN_RECORD", res.kind)

        val cap = payloadCaptor()
        verify(commands, times(1)).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.SCREEN_RECORD) ?: "", cap.capture(), eq("NORMAL") ?: "NORMAL"
        )
        val p = cap.value
        assertEquals(res.taskId, p[CommandKey.TASK_ID])
        assertEquals("FHD_1080P", p[CommandKey.RESOLUTION])
        assertEquals(false, p[CommandKey.WITH_AUDIO])
    }

    @Test
    fun `requestPhoto 与 startRecording 各下发唯一一条对应指令`() {
        svc.requestPhoto("u1", "d1")
        svc.startRecording("u1", "d1")
        verify(commands).issueCommand(eq("d1") ?: "d1", eq(CommandType.PHOTO) ?: "", any(), eq("NORMAL") ?: "NORMAL")
        verify(commands).issueCommand(eq("d1") ?: "d1", eq(CommandType.RECORD) ?: "", any(), eq("NORMAL") ?: "NORMAL")
        verify(commands, times(2)).issueCommand(anyString(), anyString(), any(), anyString())
    }

    // ---------- 实时看屏：并发准入 / 会话自愈 ----------

    @Test
    fun `startLiveView 达到并发上限抛 LIVE_DEVICES_FULL 且不登记会话`() {
        `when`(hub.publisherCount()).thenReturn(maxLiveDevices)
        val ex = assertThrows(BizException::class.java) { svc.startLiveView("u1", "d1") }
        assertEquals(ParentErr.LIVE_DEVICES_FULL, ex.code)
        assertTrue(liveSessions().isEmpty())
        verify(commands, never()).issueCommand(anyString(), anyString(), any(), anyString())
    }

    @Test
    fun `startLiveView 正常下发 LIVE_VIEW_START 且携带画质档位`() {
        `when`(settings.findById("d1")).thenReturn(
            Optional.of(DeviceSetting(deviceId = "d1").apply { highDefinition = true })
        )
        val res = svc.startLiveView("u1", "d1")
        assertEquals(true, res["live"])
        assertEquals(true, res["highDefinition"])

        val cap = payloadCaptor()
        verify(commands).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.LIVE_VIEW_START) ?: "", cap.capture(), eq("HIGH") ?: "HIGH"
        )
        assertEquals(true, cap.value[CommandKey.HIGH_DEFINITION])
        assertTrue(liveSessions().containsKey("d1"))
    }

    @Test
    fun `startLiveView 设置档位读库失败回落标准画质而不抛`() {
        `when`(settings.findById("d1")).thenThrow(RuntimeException("db down"))
        val res = svc.startLiveView("u1", "d1")
        assertEquals(false, res["highDefinition"])
    }

    @Test
    fun `stopLiveView 清除会话并下发 LIVE_VIEW_STOP`() {
        svc.startLiveView("u1", "d1")
        assertTrue(liveSessions().containsKey("d1"))
        val res = svc.stopLiveView("u1", "d1")
        assertEquals(false, res["live"])
        assertFalse(liveSessions().containsKey("d1"))
        verify(commands).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.LIVE_VIEW_STOP) ?: "", any(), eq("HIGH") ?: "HIGH"
        )
    }

    @Test
    fun `reconcile 会话过期下发 STOP 并清理会话`() {
        svc.startLiveView("u1", "d1")
        expireSession()
        svc.reconcileLiveSessions()
        assertFalse(liveSessions().containsKey("d1"), "过期会话必须清理，否则每 3s 重复下发 STOP")
        verify(commands).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.LIVE_VIEW_STOP) ?: "", any(), eq("HIGH") ?: "HIGH"
        )
    }

    @Test
    fun `reconcile 未在推流时重发 LIVE_VIEW_START`() {
        svc.startLiveView("u1", "d1")
        `when`(hub.isPublishing("d1")).thenReturn(false)
        svc.reconcileLiveSessions()
        // start 一次 + 重发一次
        verify(commands, times(2)).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.LIVE_VIEW_START) ?: "", any(), eq("HIGH") ?: "HIGH"
        )
    }

    @Test
    fun `reconcile 僵尸连接（有 publisher 但最近帧陈旧）仍判为中断并重发`() {
        svc.startLiveView("u1", "d1")
        `when`(hub.isPublishing("d1")).thenReturn(true)
        `when`(hub.lastFrameAt("d1")).thenReturn(0L)   // now - 0 远超 15s
        svc.reconcileLiveSessions()
        verify(commands, times(2)).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.LIVE_VIEW_START) ?: "", any(), eq("HIGH") ?: "HIGH"
        )
    }

    @Test
    fun `reconcile 正常推流且有新鲜帧时不重发`() {
        svc.startLiveView("u1", "d1")
        `when`(hub.isPublishing("d1")).thenReturn(true)
        `when`(hub.lastFrameAt("d1")).thenReturn(System.currentTimeMillis())
        svc.reconcileLiveSessions()
        verify(commands, times(1)).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.LIVE_VIEW_START) ?: "", any(), eq("HIGH") ?: "HIGH"
        )
    }

    @Test
    fun `reconcile 冷却窗内不重复重发`() {
        svc.startLiveView("u1", "d1")
        `when`(hub.isPublishing("d1")).thenReturn(false)
        svc.reconcileLiveSessions()
        svc.reconcileLiveSessions()
        // 8s 冷却：第二次扫描不得再发
        verify(commands, times(2)).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.LIVE_VIEW_START) ?: "", any(), eq("HIGH") ?: "HIGH"
        )
    }

    @Test
    fun `reconcile 空会话表直接返回不查 hub`() {
        svc.reconcileLiveSessions()
        verify(hub, never()).isPublishing(anyString())
        verify(commands, never()).issueCommand(anyString(), anyString(), any(), anyString())
    }

    // ---------- 孩子端上传：跨设备劫持防护 ----------

    @Test
    fun `finishMediaTask 上报设备与任务归属不符时静默丢弃且不落库`() {
        val task = MediaTask(id = "t1", deviceId = "d1", kind = "RECORD", status = "PENDING")
        `when`(mediaTasks.findById("t1")).thenReturn(Optional.of(task))
        svc.finishMediaTask("t1", "d2", "http://evil/x.mp4", "audio/mp4", 10L, 3)
        assertEquals("PENDING", task.status)
        assertEquals(null, task.url)
        verify(mediaTasks, never()).save(any(MediaTask::class.java))
    }

    @Test
    fun `finishMediaTask 归属相符时回填全部字段并置 READY`() {
        val task = MediaTask(id = "t1", deviceId = "d1", kind = "RECORD", status = "PENDING")
        `when`(mediaTasks.findById("t1")).thenReturn(Optional.of(task))
        svc.finishMediaTask("t1", "d1", "http://f/x.mp4", "audio/mp4", 10L, 3)
        assertEquals("READY", task.status)
        assertEquals("http://f/x.mp4", task.url)
        assertEquals("audio/mp4", task.mimeType)
        assertEquals(10L, task.size)
        assertEquals(3, task.durationSeconds)
        assertNotNull(task.finishedAt)
        verify(mediaTasks).save(any(MediaTask::class.java))
    }

    @Test
    fun `finishMediaTask 任务不存在不报错也不落库`() {
        svc.finishMediaTask("missing", "d1", "http://f/x", "video/mp4", 1L, null)
        verify(mediaTasks, never()).save(any(MediaTask::class.java))
    }

    // ---------- 停止采集 / 设置 / 回执 ----------

    @Test
    fun `stopScreenRecord 真下发 STOP_SCREEN_RECORD 指令`() {
        `when`(mediaTasks.findById("t1"))
            .thenReturn(Optional.of(MediaTask(id = "t1", deviceId = "d1", kind = "SCREEN_RECORD", status = "PENDING")))
        svc.stopScreenRecord("u1", "t1")
        verify(commands).issueCommand(
            eq("d1") ?: "d1", eq(CommandType.STOP_SCREEN_RECORD) ?: "", any(), eq("HIGH") ?: "HIGH"
        )
    }

    @Test
    fun `stopScreenRecord 任务不存在抛 PARAM_ERROR 且不发指令`() {
        val ex = assertThrows(BizException::class.java) { svc.stopScreenRecord("u1", "missing") }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
        verify(commands, never()).issueCommand(anyString(), anyString(), any(), anyString())
    }

    @Test
    fun `stopByKind 无进行中任务抛 PARAM_ERROR`() {
        val ex = assertThrows(BizException::class.java) { svc.stopByKind("u1", "d1", "RECORD") }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun `stopByKind 一次性任务没有停止语义故不下发指令`() {
        `when`(mediaTasks.findByDeviceIdAndKindOrderByStartedAtDesc(
            "d1", "PHOTO", PageRequest.of(0, 1)
        )).thenReturn(listOf(MediaTask(id = "t1", deviceId = "d1", kind = "PHOTO")))
        svc.stopByKind("u1", "d1", "PHOTO")
        verify(commands, never()).issueCommand(anyString(), anyString(), any(), anyString())
    }

    @Test
    fun `getMediaTask 任务不存在抛 PARAM_ERROR`() {
        val ex = assertThrows(BizException::class.java) { svc.getMediaTask("u1", "missing") }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun `updateScreenSettings 写库后必须 rebuildPolicy 否则端上收不到新间隔`() {
        val req = ScreenMonitorSettingsDto("d1", 30, true, "FHD_1080P", false, false)
        val res = svc.updateScreenSettings("u1", "d1", req)
        assertEquals(30, res.autoRefreshSeconds)
        assertEquals(true, res.highDefinition)
        assertEquals("FHD_1080P", res.recordResolution)
        verify(settings).save(any(DeviceSetting::class.java))
        verify(policyExt).rebuildPolicy(eq("d1") ?: "d1")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `storeScreenshot 推送 screenshot_ready 给归属家长`() {
        svc.storeScreenshot("s1", "d1", "http://f/x.png", 720, 1280, 12345L)
        val cap = ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, Any?>>
        verify(push).pushToUser(eq("u1") ?: "u1", cap.capture() ?: emptyMap())
        assertEquals("screenshot.ready", cap.value["type"])
        assertEquals("d1", cap.value["deviceId"])
        assertEquals("http://f/x.png", cap.value["imageUrl"])
        assertEquals(12345L, cap.value["capturedAt"])
    }

    @Test
    fun `storeScreenshot 无归属家长时不推送`() {
        `when`(devices.findById("d1")).thenReturn(Optional.of(Device(id = "d1")))
        svc.storeScreenshot(null, "d1", "http://f/x.png", null, null, null)
        verify(push, never()).pushToUser(anyString(), any() ?: emptyMap())
    }
}
