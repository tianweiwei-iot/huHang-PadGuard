package com.padguard.server.arch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 查询形状守卫：锁死 2026-11-20 修复的 6 处 N+1 / 口径不一致，防回潮。
 *
 * 这些改动都是"等价替换查询形状"——行为不变但性能/一致性差异巨大，
 * 常规行为测试无法感知，故沿用项目既有的源码扫描型契约风格（LayeringGuardTest 同款）。
 */
class QueryShapeGuardTest {

    private fun source(relative: String): String {
        val f = File("src/main/kotlin/com/padguard/server/$relative")
        assertTrue(f.isFile, "源文件未找到：${f.path}（测试工作目录应为 padguard-server/）")
        return f.readText()
    }

    /** 截取方法体（从 fun 名字到下一个同级 fun 之前），用于限定扫描范围。 */
    private fun methodBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue(start >= 0, "找不到方法：$signature")
        val next = src.indexOf("\n    fun ", start + signature.length)
        return if (next < 0) src.substring(start) else src.substring(start, next)
    }

    @Test
    fun `批量应用操作必须与台账一次预取关联而非循环内逐条查询`() {
        val src = source("service/AppManageService.kt")
        listOf(
            "fun setAppsHiddenBatch(",
            "fun setSuspendedBatch("
        ).forEach { sig ->
            val body = methodBody(src, sig)
            assertTrue(
                body.contains("findByDeviceId(deviceId).associateBy"),
                "$sig 必须先一次预取台账"
            )
            assertFalse(
                body.contains("findByDeviceIdAndPackageName"),
                "$sig 循环内不得再逐条 findByDeviceIdAndPackageName（N+1）"
            )
        }
    }

    @Test
    fun `升级检查必须一次预取已安装应用`() {
        val body = methodBody(source("service/DeviceMgmtService.kt"), "fun upgradeCheck(")
        assertTrue(body.contains("associateBy { it.packageName }"), "upgradeCheck 应按包名建索引")
        assertFalse(body.contains("findByDeviceIdAndPackageName"), "upgradeCheck 不得逐包查询（N+1）")
    }

    @Test
    fun `分组列表必须一次装载设备后归类`() {
        val body = methodBody(source("service/GroupService.kt"), "fun listGroups(")
        assertEquals(
            1,
            Regex("findByUserId\\(").findAll(body).count(),
            "listGroups 只应查一次设备，原实现在每个分组内重复全量装载"
        )
    }

    @Test
    fun `应用策略列表不得对同一张表查两次`() {
        val body = methodBody(source("service/PolicyExtensionService.kt"), "fun listAppPolicies(")
        assertEquals(
            1,
            Regex("appPolicyRepository\\.findByDeviceId\\(").findAll(body).count(),
            "listAppPolicies 应复用已取出的策略 Map"
        )
        assertTrue(body.contains("for (p in policies.values)"), "尾部残留策略应遍历已取的 Map")
    }

    @Test
    fun `WS 令牌版本复核口径必须与 HTTP 侧一致为不等于`() {
        val src = source("ws/WebSocketPushService.kt")
        val body = methodBody(src, "private fun accountStillCurrent(")
        assertTrue(
            body.contains("established.toLong() != currentVersion"),
            "HTTP 侧是 version != 即吊销；WS 用 < 会让回滚/降版后的超前令牌继续收推送"
        )
        assertFalse(body.contains("established.toLong() < currentVersion"), "不得回退到单向小于判定")
    }
}
