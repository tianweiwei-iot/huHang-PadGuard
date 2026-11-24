package com.padguard.child.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「我的」页设备实时状态的刷新契约（JVM 单测，不需要 Android 上下文 / Hilt）。
 *
 * 背景：[ProfileViewModel.state] 由 DataStore / 策略流的 combine 驱动，而设备管理员、
 * 无障碍、使用情况访问等读数**不是任何 Flow 的发射源**，只能靠 ON_RESUME 的 refresh()
 * 重新采集。旧实现在 combine 与 refresh 里各手写一份字段清单，refresh 漏了
 * `guardInfraWarning` / `guardInfraDetached` 与 DO 能力矩阵 —— 家长在系统设置里
 * 重新开启无障碍后返回，授权总览已刷新成「全部已开启」，顶部横幅却仍挂着
 * 「操作密码闸门未完全生效」，同屏自相矛盾且要等某个流恰好 emit 才纠正。
 *
 * 现在实时字段由 [RealtimeState] 单点定义、[RealtimeState.applyTo] 统一覆盖，
 * 两条路径不可能再分叉。本测试锁死字段集合，防止以后新增实时字段时又漏一项。
 */
class ProfileRealtimeStateTest {

    @Test
    fun `ON_RESUME 刷新必须撤掉闸门失效横幅，不能只刷权限总览`() {
        // 进系统设置前：无障碍关着、两条横幅挂着、总览有缺口
        val stale = ProfileUiState(
            loaded = true,
            guardInfraWarning = true,
            guardInfraDetached = true,
            authorizationSummary = AuthorizationSummary(
                total = 7,
                missingLabels = listOf("无障碍服务"),
                noStateApiCount = 1
            )
        )
        // 从设置页回来：无障碍已重开、权限已补齐（RealtimeState 默认值 = 全部就绪）
        val fresh = RealtimeState().applyTo(stale)
        assertFalse("无障碍重开后，『操作密码闸门未完全生效』横幅必须消失", fresh.guardInfraWarning)
        assertFalse("服务重连成功后，『操作闸门正在重新连接』横幅必须消失", fresh.guardInfraDetached)
        assertEquals("授权总览也要同步刷新", 0, fresh.authorizationSummary.missingCount)
    }

    @Test
    fun `applyTo 覆盖全部实时字段且不误伤非实时字段`() {
        val caps = listOf(CapabilityStatus("防卸载自身", true, "需 DO"))
        val summary = AuthorizationSummary(
            total = 9,
            missingLabels = listOf("使用情况访问"),
            noStateApiCount = 2
        )
        val rt = RealtimeState(
            adminState = AdminState.OWNER,
            deviceAdminActive = true,
            isDeviceOwner = true,
            usageStatsGranted = true,
            batteryOptimizationWhitelisted = true,
            webGuardActive = true,
            authorizationSummary = summary,
            canHideApps = true,
            canSuspendApps = true,
            ownerCapabilities = caps,
            guardInfraWarning = true,
            guardInfraDetached = false,
            storage = "32.1 GB / 64 GB",
            battery = "68%"
        )
        val base = ProfileUiState(
            loaded = true,
            studentName = "小明",
            deviceName = "学习平板",
            appListCount = 12
        )
        val out = rt.applyTo(base)

        // 14 个实时字段逐项锁死（R29 补入 storage / battery）
        assertEquals(AdminState.OWNER, out.adminState)
        assertTrue(out.deviceAdminActive)
        assertTrue(out.isDeviceOwner)
        assertTrue(out.usageStatsGranted)
        assertTrue(out.batteryOptimizationWhitelisted)
        assertTrue(out.webGuardActive)
        assertEquals(summary, out.authorizationSummary)
        assertTrue(out.canHideApps)
        assertTrue(out.canSuspendApps)
        assertEquals(caps, out.ownerCapabilities)
        assertTrue(out.guardInfraWarning)
        assertFalse(out.guardInfraDetached)
        assertEquals("32.1 GB / 64 GB", out.storage)
        assertEquals("68%", out.battery)

        // 非实时字段保持 combine 快照原值，不被覆盖成脏数据
        assertTrue(out.loaded)
        assertEquals("小明", out.studentName)
        assertEquals("学习平板", out.deviceName)
        assertEquals(12, out.appListCount)
    }

    @Test
    fun `未启用操作密码时两条闸门横幅都不该出现`() {
        // RealtimeState 默认 guardInfraWarning/Detached 均为 false：
        // 家长没设密码时即便无障碍没开，也不该弹"闸门失效"吓人。
        val base = ProfileUiState(guardInfraWarning = true, guardInfraDetached = true)
        val out = RealtimeState().applyTo(base)
        assertFalse(out.guardInfraWarning)
        assertFalse(out.guardInfraDetached)
    }

    @Test
    fun `ON_RESUME 刷新必须同时带走冻结的电量与存储`() {
        // 家长进「我的」页时电量 30%、存储 16 GB，去系统设置开完权限回来（ON_RESUME → refresh()）。
        // 旧实现把 storage/battery 只写在 combine 首帧，DataStore 流不发射就永远不重算，
        // 于是权限卡片刷新了、电量却冻结在进页面那一刻（同屏自相矛盾）。
        val stale = ProfileUiState(
            loaded = true,
            studentName = "小明",
            storage = "16.0 GB / 64 GB",
            battery = "30%",
            usageStatsGranted = false
        )
        val out = RealtimeState(
            batteryOptimizationWhitelisted = true,
            usageStatsGranted = true,
            storage = "18.2 GB / 64 GB",
            battery = "72%"
        ).applyTo(stale)

        assertEquals("18.2 GB / 64 GB", out.storage)
        assertEquals("72%", out.battery)
        assertTrue("权限状态也要在同一帧刷新", out.usageStatsGranted)
        assertEquals("非实时字段不受影响", "小明", out.studentName)
    }
}
