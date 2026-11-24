package com.padguard.core.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R37：MQTT 连接生命周期两处修复的契约回归（纯 JVM 源码扫描）。
 *
 * [MqttTransport] 依赖 Android Context 与 Paho 异步客户端，JVM 单测无法直接构造，
 * 这里以源码扫描钉住两个修复点，防止后续改动悄悄回退：
 *
 * 1. **connect() 快速路径必须比对 broker**：broker 地址运行时会被服务端下发变更
 *    （`TransportSettings.mqttBroker`）。旧实现 `if (client?.isConnected == true) return true`
 *    在旧 broker 仍可达时直接短路，新地址进程重启前永远不生效——正是 `clientBroker`
 *    字段注释要防的场景，原先只是漏把快速路径纳入检查。
 *
 * 2. **awaitToken.onSuccess 禁止 `resume(token ?: return)`**：IMqttActionListener 契约
 *    允许 token 为 null。该写法在 token null 时既不 resume 也不 fail，continuation
 *    永久悬挂；而 connect/disconnect 持有 connectMutex，一次命中即所有后续 connect()
 *    永久卡死（设备表现为"MQTT 永远连不上，降级轮询"却无任何错误日志）。
 */
class MqttTransportContractTest {

    private fun source(): String = readText(
        "src/main/kotlin/com/padguard/core/transport/mqtt/MqttTransport.kt",
        "core/transport/src/main/kotlin/com/padguard/core/transport/mqtt/MqttTransport.kt"
    )

    @Test
    fun `connect 快速路径必须比对 clientBroker`() {
        val src = source()
        assertTrue(
            "connect() 的快速路径必须同时检查 isConnected 与 clientBroker == broker，" +
                "否则 broker 迁移后新地址永不生效（回退即事故）",
            Regex("client\\?\\.isConnected == true && clientBroker == broker").containsMatchIn(src)
        )
    }

    @Test
    fun `onSuccess 悬挂泄漏已根除`() {
        val src = source()
        assertFalse(
            "禁止 resume(token ?: return) 写法：token 为 null 时 continuation 永久悬挂，" +
                "且调用方持有 connectMutex，一次命中即所有 connect() 永久卡死",
            src.contains("resume(asyncActionToken ?: return)")
        )
        // 修复后的形态：awaitToken 返回 Unit，onSuccess 无条件 resume Unit
        assertTrue(
            "awaitToken 应为 Unit 返回且 onSuccess 无条件 resume(Unit)",
            src.contains("CancellableContinuation<Unit>") && src.contains("cont.resume(Unit)")
        )
    }

    @Test
    fun `connect 的取消不得计入连接失败而降级`() {
        val src = source()
        assertTrue(
            "connect() 必须放行 CancellationException：服务停止 / 换账号 / 重连的取消" +
                "若计入 consecutiveFailures，几次就会把通道永久降级到 POLLING",
            src.contains("catch (e: CancellationException)") &&
                Regex("catch \\(e: CancellationException\\) \\{[\\s\\S]{0,300}?throw e").containsMatchIn(src)
        )
        // 必须存在独立的取消分支，不能只在通用 catch 里判断（那仍会走 failure 计数）
        assertTrue(
            "CancellationException 分支必须位于通用 Exception 分支之前且直接上抛",
            src.indexOf("catch (e: CancellationException)") < src.indexOf("catch (e: Exception)")
        )
    }

    @Test
    fun `断线与重订阅失败必须降级轮询 否则双通道同时空窗`() {
        val src = source()
        val lost = src.substringAfter("override fun connectionLost(")
            .substringBefore("override fun messageArrived(")
        assertTrue(
            "connectionLost 必须切 POLLING：Paho 自动重连不可达时轮询循环原本不会启动，设备会永久离线",
            lost.contains("settings.setMode(TransportMode.POLLING)")
        )
        val reconnect = src.substringAfter("override fun connectComplete(")
            .substringBefore("override fun connectionLost(")
        assertTrue(
            "自动重连后重订阅失败必须降级 POLLING：否则表现为在线但收不到任何下行指令",
            Regex("onFailure[\\s\\S]{0,400}?setMode\\(TransportMode\\.POLLING\\)").containsMatchIn(reconnect)
        )
    }

    @Test
    fun `自动重连成功必须切回 MQTT 否则永久停轮询`() {
        val reconnect = source().substringAfter("override fun connectComplete(")
            .substringBefore("override fun connectionLost(")
        val successIdx = reconnect.indexOf("onSuccess {")
        val backIdx = reconnect.indexOf("setMode(TransportMode.MQTT)")
        val failureIdx = reconnect.indexOf("onFailure {")
        assertTrue("自动重连回调缺少 onSuccess 分支：切回 MQTT 的逻辑无处安放", successIdx >= 0)
        assertTrue(
            "订阅恢复后必须 setMode(MQTT)：轮询循环的 connect() 命中快速路径时不会再切，mode 会永久停在 POLLING",
            backIdx > successIdx && (failureIdx < 0 || backIdx < failureIdx)
        )
        assertTrue(
            "完成日志必须只在订阅真正恢复后打印，失败分支不能谎报 restored",
            reconnect.indexOf("subscriptions restored") > successIdx
        )
    }

    /** 与 app 模块 ReauthSourceContractTest 同款定位：先按相对路径，再向上回溯。 */
    private fun readText(vararg candidates: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isFile) return direct.readText()
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir, relative)
                if (candidate.isFile) return candidate.readText()
                dir = dir!!.parentFile
            }
        }
        throw AssertionError(
            "找不到源码文件：${candidates.joinToString()}；cwd=$cwd。" +
                "若源码被移动，请同步修正本契约测试的路径。"
        )
    }
}
