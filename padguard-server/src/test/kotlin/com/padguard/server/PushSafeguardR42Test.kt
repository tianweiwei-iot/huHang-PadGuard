package com.padguard.server

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * R42 推送链路四处不变量：注册竞态、长连接失权、上线假通知、告警去重先写后建。
 *
 * 都是并发/异常路径，行为测试难以稳定复现；用源码契约把修复后的结构锁死，
 * 防止后续重构把「compute 原子注册」「提交后再推送」「建警成功后再去重」回退掉。
 */
class PushSafeguardR42Test {

    @Test
    fun `WS注册与注销必须在桶锁内原子完成 避免孤儿会话`() {
        val src = source("ws/WebSocketPushService.kt")
        val register = src.substringAfter("fun register(userId: String, session: WebSocketSession)")
            .substringBefore("fun unregister(")
        assertTrue("sessions.compute(userId)" in register, "register 必须用 sessions.compute 原子取集合并写入")
        val unregister = src.substringAfter("fun unregister(userId: String, session: WebSocketSession)")
            .substringBefore("fun revokeUser(")
        assertTrue("sessions.computeIfPresent(userId)" in unregister, "unregister 必须在桶锁内校验同一集合且已空")
        val pushCleanup = src.substringAfter("if (dead.isNotEmpty()) {")
            .substringBefore("private fun closeQuietly(")
        assertTrue(
            "sessions.remove(userId, set)" !in unregister && "sessions.remove(userId, set)" !in pushCleanup,
            "set 锁内不得再取桶锁（与 register 的锁序相反会死锁）"
        )
    }

    @Test
    fun `看屏长连接必须运行期复核归属与账号状态`() {
        val src = source("stream/StreamControllers.kt")
        val live = src.substringAfter("fun live(").substringBefore("fun status(")
        assertTrue("OWNERSHIP_RECHECK_MS" in live, "长连接必须有运行期复核")
        assertTrue("stillOwned == false" in live, "必须复核设备归属")
        assertTrue("accountUsable == false" in live, "必须复核家长账号可用")
    }

    @Test
    fun `上线WS推送必须放到事务提交之后`() {
        val src = source("service/DeviceService.kt")
        val body = src.substringAfter("private fun setOnline(deviceId: String, clientIp: String?)")
            .substringBefore("/**\n     * 记录孩子端的一次 HTTP 活动")
        val push = body.substringAfter("if (wasOffline)")
        assertTrue("afterCommit {" in push, "上线推送必须经 afterCommit，避免回滚后推送假上线")
        assertTrue(
            !push.substringBefore("afterCommit {").contains("pushToUser"),
            "推送前不得直接调用 pushToUser"
        )
    }

    @Test
    fun `脱管去重标记必须在告警建立成功之后写入`() {
        val src = source("service/OfflineMonitorService.kt")
        val block = src.substringAfter("findByOnlineStatusAndLastOnlineAtLessThan")
            .substringBefore("// ---------- T3")
        val createAt = block.indexOf("alertService.create(")
        val markAt = block.indexOf("alerted.add(dev.id)")
        assertTrue(createAt in 0..markAt, "去重写在建警之前，建警失败将永久丢告警")
    }

    private fun source(relative: String): String {
        val rel = "src/main/kotlin/com/padguard/server/$relative"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (dir == null) return@repeat
            val candidate = File(dir!!, rel)
            if (candidate.isFile) return candidate.readText()
            dir = dir!!.parentFile
        }
        throw AssertionError("找不到源码文件 $rel；cwd=${System.getProperty("user.dir")}")
    }
}
