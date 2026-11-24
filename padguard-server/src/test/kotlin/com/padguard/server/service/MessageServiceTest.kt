package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.Device
import com.padguard.server.domain.PublishedMessage
import com.padguard.server.dto.CommandDto
import com.padguard.server.dto.MessagePublishRequest
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.PublishedMessageRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable

/**
 * [MessageService] 单测：家长→设备实时信息发布。信任关键不变量：
 * ① 归属校验 [DeviceOwnershipGuard.requireOwned] 是唯一闸门，跨家长越权必须被拒；
 * ② 空内容/霸屏+0秒/时长越界属于「入口即拒绝」，优于下发后把孩子端锁死或家长端误报成功；
 * ③ 下行指令 payload 必须严格命中 [CommandContract]（CONTENT/DURATION_SEC/BLOCKING/...），
 *    否则孩子端判 BAD_PAYLOAD 静默丢弃；④ listMessages 的 limit 必须钳制在 [1,100]。
 * 不加载 Spring 上下文：直接构造 + mock 依赖。
 */
class MessageServiceTest {

    private data class Fx(
        val svc: MessageService,
        val guard: DeviceOwnershipGuard,
        val command: CommandService,
        val repo: PublishedMessageRepository
    )

    private fun fx(): Fx {
        val guard = mock(DeviceOwnershipGuard::class.java)
        val command = mock(CommandService::class.java)
        val repo = mock(PublishedMessageRepository::class.java)
        val deviceRepo = mock(DeviceRepository::class.java)
        val svc = MessageService(deviceRepo, guard, command, repo)
        `when`(guard.requireOwned(any() ?: "u1", any() ?: "d1")).thenReturn(mock(Device::class.java))
        `when`(
            command.issueCommand(
                any() ?: "d1", any() ?: "", any() ?: emptyMap<String, Any?>(), any() ?: "NORMAL"
            )
        ).thenReturn(mock(CommandDto::class.java))
        `when`(repo.save(any(PublishedMessage::class.java) ?: PublishedMessage())).thenAnswer { it.arguments[0] }
        `when`(repo.findByDeviceIdOrderByPublishedAtDesc(eq("d1") ?: "d1", any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(emptyList())
        return Fx(svc, guard, command, repo)
    }

    @Test
    fun emptyContentNoMediaThrows() {
        val f = fx()
        val ex = assertThrows(BizException::class.java) {
            f.svc.publish("u1", "d1", MessagePublishRequest(text = "   ", mediaUrl = null, mediaName = null))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
        verify(f.command, never()).issueCommand(any() ?: "d1", any() ?: "", any() ?: emptyMap<String, Any?>(), any() ?: "NORMAL")
        verify(f.repo, never()).save(any(PublishedMessage::class.java) ?: PublishedMessage())
    }

    @Test
    fun fullScreenWithZeroDurationThrows() {
        val f = fx()
        val ex = assertThrows(BizException::class.java) {
            f.svc.publish("u1", "d1", MessagePublishRequest(text = "hi", fullScreen = true, displaySeconds = 0))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
        verify(f.command, never()).issueCommand(any() ?: "d1", any() ?: "", any() ?: emptyMap<String, Any?>(), any() ?: "NORMAL")
    }

    @Test
    fun durationOutOfRangeThrows() {
        val f = fx()
        assertEquals(
            ParentErr.PARAM_ERROR,
            assertThrows(BizException::class.java) {
                f.svc.publish("u1", "d1", MessagePublishRequest(text = "hi", displaySeconds = 3601))
            }.code
        )
        assertEquals(
            ParentErr.PARAM_ERROR,
            assertThrows(BizException::class.java) {
                f.svc.publish("u1", "d1", MessagePublishRequest(text = "hi", displaySeconds = -1))
            }.code
        )
    }

    @Test
    fun successIssuesShowMessageWithContractPayload() {
        val f = fx()
        val dto = f.svc.publish(
            "u1", "d1",
            MessagePublishRequest(text = "hello", contentType = "TEXT", displaySeconds = 15, fullScreen = true)
        )
        assertEquals("hello", dto.summary)
        val captor = ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, Any?>>
        verify(f.command).issueCommand(
            eq("d1") ?: "d1",
            eq(CommandType.SHOW_MESSAGE) ?: CommandType.SHOW_MESSAGE,
            captor.capture(),
            eq("NORMAL") ?: "NORMAL"
        )
        val payload = captor.value as Map<String, Any?>
        assertEquals("hello", payload[CommandKey.CONTENT])
        assertEquals(15, payload[CommandKey.DURATION_SEC])
        assertEquals(true, payload[CommandKey.BLOCKING])
        assertEquals("TEXT", payload[CommandKey.CONTENT_TYPE])
        assertEquals("", payload[CommandKey.MEDIA_URL])
        assertEquals("", payload[CommandKey.MEDIA_NAME])
        verify(f.repo).save(any(PublishedMessage::class.java) ?: PublishedMessage())
    }

    @Test
    fun mediaOnlyFallsBackToMediaName() {
        val f = fx()
        val dto = f.svc.publish(
            "u1", "d1",
            MessagePublishRequest(text = null, mediaUrl = "http://x/a.png", mediaName = "photo.png", displaySeconds = 5)
        )
        assertEquals("photo.png", dto.summary)
        val captor = ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, Any?>>
        verify(f.command).issueCommand(
            eq("d1") ?: "d1",
            eq(CommandType.SHOW_MESSAGE) ?: CommandType.SHOW_MESSAGE,
            captor.capture(),
            eq("NORMAL") ?: "NORMAL"
        )
        val payload = captor.value as Map<String, Any?>
        assertEquals("photo.png", payload[CommandKey.CONTENT])
        assertEquals("http://x/a.png", payload[CommandKey.MEDIA_URL])
        assertEquals("photo.png", payload[CommandKey.MEDIA_NAME])
    }

    @Test
    fun notOwnedPropagatesAndDoesNotIssue() {
        val f = fx()
        `when`(f.guard.requireOwned(any() ?: "u1", any() ?: "d1"))
            .thenThrow(BizException(ParentErr.DEVICE_NOT_OWNED, "not owned", Audience.PARENT))
        val ex = assertThrows(BizException::class.java) {
            f.svc.publish("u1", "d1", MessagePublishRequest(text = "x"))
        }
        assertEquals(ParentErr.DEVICE_NOT_OWNED, ex.code)
        verify(f.command, never()).issueCommand(any() ?: "d1", any() ?: "", any() ?: emptyMap<String, Any?>(), any() ?: "NORMAL")
        verify(f.repo, never()).save(any(PublishedMessage::class.java) ?: PublishedMessage())
    }

    @Test
    fun listMessagesClampsLimitTo100() {
        val f = fx()
        val msg = PublishedMessage(
            id = "m1", deviceId = "d1", contentType = "TEXT", text = "t",
            displaySeconds = 10, fullScreen = false, publishedAt = 123
        )
        `when`(f.repo.findByDeviceIdOrderByPublishedAtDesc(eq("d1") ?: "d1", any(Pageable::class.java) ?: PageRequest.of(0, 1)))
            .thenReturn(listOf(msg))
        val result = f.svc.listMessages("u1", "d1", 1_000_000)
        assertEquals(1, result.size)
        val captor = ArgumentCaptor.forClass(Pageable::class.java)
        verify(f.repo).findByDeviceIdOrderByPublishedAtDesc(eq("d1") ?: "d1", captor.capture() ?: PageRequest.of(0, 1))
        assertEquals(100, captor.value.pageSize)

        f.svc.listMessages("u1", "d1", 0)
        verify(f.repo, times(2)).findByDeviceIdOrderByPublishedAtDesc(eq("d1") ?: "d1", captor.capture() ?: PageRequest.of(0, 1))
        assertEquals(1, captor.value.pageSize)
        verify(f.guard, times(2)).requireOwned(eq("u1") ?: "u1", eq("d1") ?: "d1")
    }
}
