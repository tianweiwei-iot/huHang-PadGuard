package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.Command
import com.padguard.server.domain.Screenshot
import com.padguard.server.repository.CommandRepository
import com.padguard.server.repository.ScreenshotRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * 第 7 批红护栏：过期清扫**联动收敛截图**时的终态覆盖。
 *
 * `ExpiredCommandSweeper` 第 60~66 行早已用 `markExpiredIfPending` 条件 UPDATE 修掉主指令的
 * 同型缺陷（类注释 F01 写得很清楚：「两次查询之间若回执已置 SUCCESS，旧快照会覆盖」），
 * 但 `convergeRelatedRecords` 里对截图仍是「findFirst → 判 PENDING → 改内存快照 → save 整行」。
 *
 * 真实后果比指令更严重：孩子端上传成功会把截图置 READY 并填 `fileUrl`；清扫器慢一步用
 * 旧快照整行写回，会把状态改回 EXPIRED 且把 `fileUrl` 抹成 null —— **图片文件还在磁盘上，
 * 记录却永远指向不到了**，家长端表现为「截图拍了、提示成功、点开却说已过期」。
 */
@DisplayName("过期清扫收敛截图 —— 终态不可被旧快照覆盖")
class ScreenshotConvergenceCasTest {

    private val commands = Mockito.mock(CommandRepository::class.java)
    private val screenshots = Mockito.mock(ScreenshotRepository::class.java)
    private val redelivery = Mockito.mock(CommandRedeliveryService::class.java)
    private val sweeper = ExpiredCommandSweeper(commands, screenshots, ObjectMapper(), redelivery)

    private fun screenshotCommand() = Command(
        id = "c1", deviceId = "dev-1", msgId = "m1", type = "SCREENSHOT", status = "PENDING"
    ).apply { payloadJson = "{\"shotId\":\"shot-1\"}" }

    @Test
    @DisplayName("查询返回旧 PENDING 快照后上传成功，权威行不得被覆盖回 EXPIRED")
    fun `READY screenshot must not be overwritten by a stale sweep snapshot`() {
        val cmd = screenshotCommand()
        Mockito.`when`(commands.findExpiredPending("dev-1", "PENDING", 1000L)).thenReturn(listOf(cmd))
        Mockito.`when`(redelivery.isEligible(cmd, 1000L)).thenReturn(false)
        Mockito.`when`(commands.markExpiredIfPending("c1", "PENDING", "EXPIRED")).thenReturn(1)

        // 前置条件：孩子端已上传成功，权威行是 READY 且 fileUrl 已写好。
        // 模拟时序 = 清扫器先发起查询（拿到旧 PENDING 快照），上传随后落库。
        // 刻意不把「改 READY」挂在某个 mock 的回调里 —— 那样护栏会依赖旧实现恰好调用
        // findFirst...，修法一旦绕开这次查询，钩子不触发，假绿/假红都说不清。
        val authoritative = Screenshot(
            id = "s1", deviceId = "dev-1", shotId = "shot-1",
            status = "READY", fileUrl = "/v1/files/shot-1.jpg"
        )
        // 旧实现路径：findFirst 返回一份**独立**的 PENDING 旧快照
        Mockito.`when`(screenshots.findFirstByDeviceIdAndShotId("dev-1", "shot-1"))
            .thenReturn(Screenshot(id = "s1", deviceId = "dev-1", shotId = "shot-1", status = "PENDING"))
        // 旧实现路径：JpaRepository.save 对脱管实体是整行 merge，旧快照覆盖刚写好的列
        Mockito.`when`(screenshots.save(Mockito.any(Screenshot::class.java))).thenAnswer { inv ->
            val stale = inv.getArgument<Screenshot>(0)
            authoritative.status = stale.status
            authoritative.fileUrl = stale.fileUrl
            stale
        }
        // 新实现路径：条件 UPDATE 发现权威行已 READY，影响 0 行
        Mockito.`when`(screenshots.markExpiredIfPending("dev-1", "shot-1", "PENDING", "EXPIRED"))
            .thenReturn(0)

        sweeper.sweep("dev-1", 1000L)

        assertEquals(
            "READY", authoritative.status,
            "迟到的清扫把已就绪截图覆盖回 EXPIRED，家长端会看到「截图成功却已过期」"
        )
        assertEquals(
            "/v1/files/shot-1.jpg", authoritative.fileUrl,
            "整行 merge 把 fileUrl 抹掉了：文件还在磁盘，记录却再也指不到它"
        )
    }

    @Test
    @DisplayName("仍处于 PENDING 的截图必须能被条件 UPDATE 正常收敛（修法不能把功能改没）")
    fun `a genuinely pending screenshot is still expired`() {
        val cmd = screenshotCommand()
        Mockito.`when`(commands.findExpiredPending("dev-1", "PENDING", 2000L)).thenReturn(listOf(cmd))
        Mockito.`when`(redelivery.isEligible(cmd, 2000L)).thenReturn(false)
        Mockito.`when`(commands.markExpiredIfPending("c1", "PENDING", "EXPIRED")).thenReturn(1)
        Mockito.`when`(screenshots.markExpiredIfPending("dev-1", "shot-1", "PENDING", "EXPIRED"))
            .thenReturn(1)

        sweeper.sweep("dev-1", 2000L)

        Mockito.verify(screenshots).markExpiredIfPending("dev-1", "shot-1", "PENDING", "EXPIRED")
        Mockito.verify(screenshots, Mockito.never()).save(Mockito.any(Screenshot::class.java))
    }
}
