package com.padguard.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 订阅权益闸门的纯逻辑测试。
 *
 * 闸门的语义核心：订阅失效时，策略包必须被完整改写为「放开包」——
 * 这直接对应 P0 缺陷"订阅到期后孩子端管控冻结在最后状态"：
 * 此前订阅只在绑定时校验一次，到期后锁屏/黑名单/限额永远保留，
 * 家长端接口又被订阅拦截器挡住，平板只能等运营人工解锁。
 */
class EntitlementGateTest {

    private fun normalPkg(): MutableMap<String, Any?> = mutableMapOf(
        "version" to 3, "deviceId" to "d1", "scene" to "FAMILY", "sceneMode" to "LEARNING",
        "peripheral" to mapOf(
            "wifi" to "ALLOW", "bluetooth" to "DISABLE", "camera" to "DISABLE", "usbFileTransfer" to "DISABLE"
        ),
        "systemLock" to mapOf("developerOptions" to "DISABLE", "usbDebug" to "DISABLE"),
        "app" to mapOf(
            "mode" to "BLACKLIST", "whitelist" to listOf<String>("com.a"),
            "blacklist" to listOf("com.b", "com.c"),
            "requirePasswordOnLaunch" to true,
            "installPolicy" to mapOf("requirePasswordOnInstall" to true, "requirePasswordOnUninstall" to true),
            "hiddenPackages" to listOf("com.x")
        ),
        "appLimit" to mapOf(
            "enabled" to true, "dailyTotalMinutes" to 120, "weekdayTotalMinutes" to 90,
            "weekendTotalMinutes" to 180, "restAfterMinutes" to 45, "restDurationMinutes" to 10,
            "rules" to listOf(mapOf("packageName" to "com.b", "dailyMinutes" to 30))
        ),
        "schedule" to mapOf("enabled" to true, "timezone" to "Asia/Shanghai", "rules" to listOf(mapOf("start" to "22:00"))),
        "web" to mapOf(
            "mode" to "BLACKLIST", "blacklist" to listOf("bad.com"), "browserDisabled" to true,
            "smartShutdownEnabled" to true, "safeSearch" to true, "blockedCategories" to listOf("GAMBLING")
        ),
        "monitoring" to mapOf(
            "heartbeatIntervalSec" to 30, "logUploadIntervalSec" to 300, "locationIntervalSec" to 600
        ),
        "eyeCare" to mapOf("enabled" to true, "continuousMinutes" to 40, "restMinutes" to 10, "forceLockDuringRest" to true),
        "eyeProtection" to mapOf("enabled" to true, "level" to 2),
        "kiosk" to mapOf("enabled" to true, "allowedPackages" to listOf("com.a")),
        "minorMode" to mapOf("enabled" to true, "ageBand" to "BAND_8_12", "curfewEnabled" to true),
        "security" to mapOf("antiUninstall" to true, "antiForceStop" to true, "antiClockTamper" to true, "blockRoot" to true),
        "guard" to mapOf("passwordHash" to "abc", "clear" to false)
    )

    @Test
    fun `entitlement truth table`() {
        assertTrue(EntitlementGate.isEntitled("TRIAL"))
        assertTrue(EntitlementGate.isEntitled("ACTIVE"))
        assertFalse(EntitlementGate.isEntitled("EXPIRED"))
        assertFalse(EntitlementGate.isEntitled("REVOKED"), "退款吊销必须视同失效，否则退款等于白嫖一整期")
        assertFalse(EntitlementGate.isEntitled(null))
        assertFalse(EntitlementGate.isEntitled(""))
    }

    @Test
    fun `release turns off all enforcement while keeping package structure`() {
        val pkg = normalPkg()
        EntitlementGate.applyRelease(pkg)

        // 应用管控：名单清空、密码闸门关、取消授权集合清空
        @Suppress("UNCHECKED_CAST")
        val app = pkg["app"] as Map<String, Any?>
        assertEquals("BLACKLIST", app["mode"])
        assertEquals(emptyList<String>(), app["blacklist"])
        assertEquals(emptyList<String>(), app["whitelist"])
        assertEquals(false, app["requirePasswordOnLaunch"])
        assertEquals(emptyList<String>(), app["hiddenPackages"])
        @Suppress("UNCHECKED_CAST")
        val install = app["installPolicy"] as Map<String, Any?>
        assertEquals(false, install["requirePasswordOnInstall"])
        assertEquals(false, install["requirePasswordOnUninstall"])

        // 限时：总开关关、单应用规则清空，但额度数值必须保留
        // （写 0 会被孩子端旧版本解析成"整天禁用"，放开包反而把平板锁死）
        @Suppress("UNCHECKED_CAST")
        val limit = pkg["appLimit"] as Map<String, Any?>
        assertEquals(false, limit["enabled"])
        assertEquals(emptyList<Any>(), limit["rules"])
        assertEquals(90, limit["weekdayTotalMinutes"])
        assertEquals(180, limit["weekendTotalMinutes"])

        // 时段与内容管控
        @Suppress("UNCHECKED_CAST")
        val schedule = pkg["schedule"] as Map<String, Any?>
        assertEquals(false, schedule["enabled"])
        assertEquals(emptyList<Any>(), schedule["rules"])
        @Suppress("UNCHECKED_CAST")
        val web = pkg["web"] as Map<String, Any?>
        assertEquals(false, web["browserDisabled"])
        assertEquals(false, web["smartShutdownEnabled"])
        assertEquals(emptyList<Any>(), web["blockedCategories"])

        // 系统锁全放开（救援通道必须能装 App / 修设备）
        @Suppress("UNCHECKED_CAST")
        val lock = pkg["systemLock"] as Map<String, Any?>
        assertEquals("ALLOW", lock["developerOptions"])
        assertEquals("ALLOW", lock["usbDebug"])
        assertEquals("ALLOW", lock["unknownSourceInstall"])

        // 监控：付费采集停止，但心跳间隔必须保留（放开包本身靠它送达）
        @Suppress("UNCHECKED_CAST")
        val mon = pkg["monitoring"] as Map<String, Any?>
        assertEquals(0, mon["locationIntervalSec"])
        assertEquals(30, mon["heartbeatIntervalSec"])
        assertEquals(false, mon["collectAppUsage"])

        // 护眼/滤镜/未成年人/篏亭/安全防护全关；minorMode 必须关，
        // 否则孩子端分龄派生会把 appLimit/eyeCare/schedule 的限制又加回来
        assertEquals(false, (pkg["eyeCare"] as Map<*, *>)["enabled"])
        assertEquals(false, (pkg["eyeProtection"] as Map<*, *>)["enabled"])
        assertEquals(false, (pkg["kiosk"] as Map<*, *>)["enabled"])
        assertEquals(false, (pkg["minorMode"] as Map<*, *>)["enabled"])
        assertEquals(false, (pkg["minorMode"] as Map<*, *>)["curfewEnabled"])
        assertEquals(false, (pkg["security"] as Map<*, *>)["antiUninstall"])

        // 无关段保持原样
        assertEquals("abc", (pkg["guard"] as Map<*, *>)["passwordHash"])
        assertEquals("LEARNING", pkg["sceneMode"])
    }

    @Test
    fun `release on minimal package does not throw`() {
        // 空包/缺段也要能就地补齐 —— ensureRebuilt 对"库存包残缺"的设备同样会走到这里
        val pkg = mutableMapOf<String, Any?>("version" to 1, "deviceId" to "d2")
        EntitlementGate.applyRelease(pkg)
        assertEquals(false, (pkg["appLimit"] as Map<*, *>)["enabled"])
        assertEquals("ALLOW", (pkg["systemLock"] as Map<*, *>)["developerOptions"])
        assertEquals(0, (pkg["monitoring"] as Map<*, *>)["locationIntervalSec"])
    }
}
