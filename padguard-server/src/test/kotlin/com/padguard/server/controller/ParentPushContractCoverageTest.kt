package com.padguard.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 「服务端 WS 推送 ↔ 家长端消费」的**跨端契约**守门测试（R32）。
 *
 * ## 为什么需要
 * 两端分属独立 Gradle 工程，`pushToUser(mapOf("type" to ...))` 与
 * `ParentPushClient.handleText` 的 `when (env.type)` 只是两个字符串集合，编译器管不着。
 * 服务端推了、家长端没接，界面就停在旧状态，直到该页面自己的轮询把数据捞回来；
 * 而有些页面（设备详情页）根本没有轮询 —— 这是一个已经实际发生过的用户可见缺陷：
 * `device.profile.updated` 从服务端推出来后被静默丢弃，孩子改了昵称，
 * 首页 ≤10s 变新、详情页还停在进页面那一刻，同 App 内两处显示相反。
 *
 * 本测试从两侧源码各提取一次 type 集合：
 * - 家长端消费的 type 必须与 [EXPECTED_CONSUMED] 完全一致（加/删分支都要人工确认）；
 * - 每个消费的 type 必须能在服务端源码里找到推送字面量（防死代码）；
 * - 服务端新增 `"type" to "xxx"` 却没有对应消费分支时直接红，
 *   除非它属于 [POLL_BACKED]（已核实有独立 HTTP 轮询兜底，不需要 WS 快路径）。
 *
 * 这样「加一个推送却忘了接」不再等到用户看见旧状态才被发现。
 */
class ParentPushContractCoverageTest {

    private val parentPush = readText(
        "../护航控制端/app/src/main/java/com/padguard/parent/data/remote/ParentPushClient.kt",
        "护航控制端/app/src/main/java/com/padguard/parent/data/remote/ParentPushClient.kt"
    )
    private val detailViewModel = readText(
        "../护航控制端/app/src/main/java/com/padguard/parent/presentation/viewmodel/DeviceDetailViewModel.kt",
        "护航控制端/app/src/main/java/com/padguard/parent/presentation/viewmodel/DeviceDetailViewModel.kt"
    )
    private val riskAlertViewModel = readText(
        "../护航控制端/app/src/main/java/com/padguard/parent/presentation/viewmodel/RiskAlertCenterViewModel.kt",
        "护航控制端/app/src/main/java/com/padguard/parent/presentation/viewmodel/RiskAlertCenterViewModel.kt"
    )
    private val serverSource: String = findDir(
        "src/main/kotlin",
        "padguard-server/src/main/kotlin"
    ).walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .joinToString("\n") { it.readText() }

    @Test
    fun `家长端消费的推送类型与契约集合严格一致`() {
        val consumed = parentConsumedTypes()
        assertTrue(consumed.isNotEmpty(), "家长端 type 解析失败，源码结构已变")
        assertEquals(
            EXPECTED_CONSUMED, consumed,
            "WS 消费契约变了：新增 ${consumed - EXPECTED_CONSUMED}、缺失 ${EXPECTED_CONSUMED - consumed}。" +
                "新增分支必须同步确认服务端确实在推，删除分支必须确认该页面有轮询兜底。"
        )
    }

    @Test
    fun `每个消费的类型在服务端确有推送 防止家长端死分支`() {
        for (type in EXPECTED_CONSUMED) {
            assertTrue(
                serverSource.contains("\"$type\""),
                "家长端消费 $type，但服务端 main 源码里找不到该字符串字面量 —— " +
                    "要么推送被删了（死分支），要么 type 改了名（家长端永远收不到）"
            )
        }
    }

    @Test
    fun `服务端新增推送必须有消费分支 除非明确登记了轮询兜底`() {
        val pushed = serverPushedLiterals()
        val consumed = parentConsumedTypes()
        val unhandled = pushed - POLL_BACKED - consumed
        assertTrue(
            unhandled.isEmpty(),
            "服务端推送了 $unhandled，家长端既不消费、也没登记轮询兜底 —— " +
                "对应页面会停在旧状态。要么在 handleText 加分支，要么在 POLL_BACKED 里登记并写明兜底证据。"
        )
    }

    @Test
    fun `设备资料更新推送必须接到详情页 且不再依赖轮询兜底`() {
        assertTrue(
            "device.profile.updated" in parentConsumedTypes(),
            "device.profile.updated 曾长期无人消费：孩子改昵称后设备详情页仍是旧快照"
        )
        assertFalse(
            "device.profile.updated" in POLL_BACKED,
            "详情页没有轮询兜底，不能把这个 type 登记进「允许不消费」白名单"
        )
        assertTrue(
            parentPush.contains("ParentPushMessage.DeviceProfileUpdated(deviceId)"),
            "推送分支没有发出 DeviceProfileUpdated 消息"
        )
        assertTrue(
            detailViewModel.contains("ParentPushMessage.DeviceProfileUpdated"),
            "DeviceDetailViewModel 没有消费 DeviceProfileUpdated，消息仍会落空"
        )
        assertTrue(
            detailViewModel.contains("loadDeviceDetail(silent = true)"),
            "详情页收到资料更新后没有静默重拉，快照不会刷新"
        )
    }

    @Test
    fun `告警推送必须接到风险告警VM 秒级刷新而非只靠轮询`() {
        assertTrue(
            setOf("alert.new", "alert.resolved").all { it in parentConsumedTypes() },
            "alert.new/alert.resolved 曾长期无人消费：告警产生与自愈关闭两个方向都要等 15s 轮询，" +
                "正是「已修好却持续报警」的最后一公里断裂点"
        )
        assertFalse(
            setOf("alert.new", "alert.resolved").any { it in POLL_BACKED },
            "alerts 已接 WS 快路径，不能继续留在「允许不消费」白名单（留着等于允许分支被误删）"
        )
        assertTrue(
            parentPush.contains("ParentPushMessage.AlertsChanged"),
            "推送分支没有发出 AlertsChanged 信号"
        )
        assertTrue(
            riskAlertViewModel.contains("ParentPushMessage.AlertsChanged"),
            "RiskAlertCenterViewModel 没有消费 AlertsChanged，告警推送仍会落空、红点只能等轮询"
        )
        assertTrue(
            riskAlertViewModel.contains("refresh()"),
            "风险告警VM 收到推送后没有触发全量刷新"
        )
    }

    @Test
    fun `轮询兜底白名单必须逐项有据 不得随手扩容`() {
        assertEquals(
            setOf("command.result", "screenshot.ready"),
            POLL_BACKED,
            "白名单只能在核实「该 type 有独立 HTTP 轮询通道且不接 WS」后修改：" +
                "alert.new/alert.resolved 已改接 RiskAlertCenterViewModel 快路径，移出白名单；" +
                "command.result → DeviceDetailViewModel 800ms 查 getCommand；" +
                "screenshot.ready → ScreenMonitorViewModel 2s×20 查截图列表"
        )
        val pushed = serverPushedLiterals()
        for (type in POLL_BACKED) {
            assertTrue(type in pushed, "白名单里的 $type 服务端已不推送，属于陈旧登记，请清理")
        }
    }

    // ==================== 源码解析 ====================

    /** 从 handleText 的 when 分支提取 type：只看带 `->` 的分支头，忽略注释与分支体。 */
    private fun parentConsumedTypes(): Set<String> {
        val body = parentPush
            .substringAfter("private fun handleText(text: String)", "")
            .substringBefore("}.onFailure")
        val out = mutableSetOf<String>()
        for (line in body.lines()) {
            val t = line.trim()
            if (!t.contains("->")) continue
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue
            val head = t.substringBefore("->")
            TYPE_LITERAL.findAll(head).forEach { out += it.groupValues[1] }
        }
        return out
    }

    /** 服务端 `"type" to "xxx"` 的字面量推送；常量/形参形式由「消费侧反查字面量」那条断言覆盖。 */
    private fun serverPushedLiterals(): Set<String> =
        Regex("\"type\"\\s+to\\s+\"([a-z][a-z0-9]*(?:\\.[a-z0-9_]+)+)\"")
            .findAll(serverSource).map { it.groupValues[1] }.toSet()

    /** 单测 cwd 可能是模块目录或仓库根，逐级向上找，避免 IDE / 命令行差异造成假红。 */
    private fun readText(vararg candidates: String): String = findFile(*candidates).readText()

    private fun findFile(vararg candidates: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isFile) return direct
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir!!, relative)
                if (candidate.isFile) return candidate
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到源码文件：${candidates.joinToString()}；cwd=$cwd")
    }

    private fun findDir(vararg candidates: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isDirectory) return direct
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir!!, relative)
                if (candidate.isDirectory) return candidate
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到源码目录：${candidates.joinToString()}；cwd=$cwd")
    }

    private companion object {
        val TYPE_LITERAL = Regex("\"([a-z][a-z0-9]*(?:\\.[a-z0-9_]+)+)\"")

        /** 家长端应当消费的全部 WS type（改动需连同服务端推送一起评审）。 */
        val EXPECTED_CONSUMED = setOf(
            "usage.today",
            "device.enrolled",
            "usage.delay.requested",
            "unlock.requested",
            "usage.delay.updated",
            "usage.start.requested",
            "usage.start.confirmed",
            "device.online",
            "device.offline",
            "device.profile.updated",
            "alert.new",
            "alert.resolved"
        )

        /** 已核实有独立 HTTP 轮询兜底、家长端不消费 WS 也不影响正确性的类型。 */
        val POLL_BACKED = setOf(
            "command.result",
            "screenshot.ready"
        )
    }
}
