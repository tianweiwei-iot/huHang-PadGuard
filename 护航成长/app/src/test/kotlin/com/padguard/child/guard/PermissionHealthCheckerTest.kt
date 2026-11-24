package com.padguard.child.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PermissionHealthChecker.needsA11yDroppedAlert] 纯判定逻辑的单元测试。
 *
 * 验证"静默自愈成功后不再误报无障碍告警"这一根因修复：
 * 旧实现中 checkAccessibility 在 DO 自愈成功后仍无条件上报告警，导致家长端反复出现
 * "无障碍服务已关闭"。本测试锁定该判定，防止回归。
 * 2026-11-17 起判定参数由 isDeviceOwner 泛化为 silentHealCapable
 * （DO **或**装机时 adb 授权 WRITE_SECURE_SETTINGS，见 A11yCapabilityHealer.canSilentHeal），
 * 新增非 DO + 已授权形态的用例（EMUI 一键清理自动恢复场景）。
 */
class PermissionHealthCheckerTest {

    @Test
    fun `DO 自愈成功则不再上报告警`() {
        assertFalse(
            "DO 下自愈后已恢复启用，应抑制告警",
            PermissionHealthChecker.needsA11yDroppedAlert(a11yEnabledAfterHeal = true, silentHealCapable = true)
        )
    }

    @Test
    fun `DO 自愈失败仍上报告警`() {
        assertTrue(
            "DO 下自愈未成功，应继续上报告警",
            PermissionHealthChecker.needsA11yDroppedAlert(a11yEnabledAfterHeal = false, silentHealCapable = true)
        )
    }

    @Test
    fun `非 DO 设备始终上报告警（平台限制，只能引导重授）`() {
        assertTrue(
            "非 DO 无静默自愈能力，应上报告警引导",
            PermissionHealthChecker.needsA11yDroppedAlert(a11yEnabledAfterHeal = false, silentHealCapable = false)
        )
    }

    @Test
    fun `非 DO 但授权 WRITE_SECURE_SETTINGS 自愈成功则不上报告警`() {
        assertFalse(
            "EMUI 一键清理停用无障碍后经静默通道自动恢复，家长无需到场，应抑制告警",
            PermissionHealthChecker.needsA11yDroppedAlert(a11yEnabledAfterHeal = true, silentHealCapable = true)
        )
    }

    @Test
    fun `非 DO 且未授权时即使下一拍读到已启用也不误报`() {
        // 未授权设备理论上不会"自愈成功"；但若家长手动重开恰好落在自检拍内，
        // a11yEnabledAfterHeal=true 而 silentHealCapable=false —— 旧口径会继续走
        // 告警分支（随后 computeRecovered 会发"恢复"事件关闭告警），语义保持不变。
        assertTrue(
            "未授权设备不享受自愈抑制口径（恢复由告警闭环处理，不在此抑制）",
            PermissionHealthChecker.needsA11yDroppedAlert(a11yEnabledAfterHeal = true, silentHealCapable = false)
        )
    }

    /**
     * [PermissionHealthChecker.computeResolveCategories] 纯判定逻辑的单元测试。
     *
     * 验证"恢复即关闭"闭环的核心安全约束：
     * - `GUARD_PERMISSION_DROPPED` 同覆盖无障碍与悬浮窗，仅当**两者都恢复**才关闭，
     *   避免把"悬浮窗仍缺失"的有效告警一并抹掉；
     * - 边沿触发（prev - now）：当前不缺失且上一轮也未缺失时，不产生任何关闭，避免每轮自检刷请求。
     */
    @Test
    fun `无障碍与悬浮窗均恢复且曾掉失则关闭两类告警`() {
        val set = PermissionHealthChecker.computeResolveCategories(
            a11yOk = true, overlayOk = true,
            prevDropped = setOf("a11y", "overlay"), nowDropped = emptySet()
        )
        assertEquals(setOf("ACCESSIBILITY_DISABLED", "GUARD_PERMISSION_DROPPED"), set)
    }

    @Test
    fun `无障碍恢复但悬浮窗仍缺失时不关闭 GUARD_PERMISSION_DROPPED`() {
        val set = PermissionHealthChecker.computeResolveCategories(
            a11yOk = true, overlayOk = false,
            prevDropped = setOf("a11y", "overlay"), nowDropped = setOf("overlay")
        )
        // 仅关无障碍独立类别；共享的 GUARD_PERMISSION_DROPPED 因悬浮窗仍缺失而保留
        assertEquals(setOf("ACCESSIBILITY_DISABLED"), set)
    }

    @Test
    fun `运行期权限恢复则关闭 PERMISSION_REVOKED`() {
        val set = PermissionHealthChecker.computeResolveCategories(
            a11yOk = false, overlayOk = false,
            prevDropped = setOf("runtime"), nowDropped = emptySet()
        )
        assertEquals(setOf("PERMISSION_REVOKED"), set)
    }

    /**
     * usageStats 等"其余特殊权限"恢复、且当前没有任何特殊权限仍掉失时，
     * 必须同时关闭共享的 GUARD_PERMISSION_DROPPED **和** TamperDetector 那条独立的
     * USAGE_ACCESS_LOST。后者曾完全漏关：家长重开权限后，服务端仍永久挂着
     * 「使用情况权限被关闭」，每次再掉失又叠一条（R31 修复）。
     */
    @Test
    fun `usageStats恢复同时关闭共享告警与独立的USAGE_ACCESS_LOST`() {
        val set = PermissionHealthChecker.computeResolveCategories(
            a11yOk = true, overlayOk = true,
            prevDropped = setOf("usageStats", "batteryOptimization"), nowDropped = emptySet()
        )
        assertEquals(setOf("GUARD_PERMISSION_DROPPED", "USAGE_ACCESS_LOST"), set)
    }

    @Test
    fun `仅其它特殊权限恢复不误关USAGE_ACCESS_LOST`() {
        val set = PermissionHealthChecker.computeResolveCategories(
            a11yOk = true, overlayOk = true,
            prevDropped = setOf("batteryOptimization"), nowDropped = emptySet()
        )
        assertEquals(setOf("GUARD_PERMISSION_DROPPED"), set)
    }

    @Test
    fun `usageStats持续掉失时不产生USAGE_ACCESS_LOST关闭请求`() {
        val set = PermissionHealthChecker.computeResolveCategories(
            a11yOk = true, overlayOk = true,
            prevDropped = setOf("usageStats"), nowDropped = setOf("usageStats")
        )
        assertTrue("权限仍缺失时不能把有效告警关掉", set.isEmpty())
    }

    /**
     * 新增：即便 a11y 已恢复，只要 usageStats 仍掉失（仍在 nowDropped），
     * GUARD_PERMISSION_DROPPED 不得被误关 —— 否则会留下"权限掉了却没告警"的空洞。
     */
    @Test
    fun `usageStats仍掉失时即使a11y恢复也不关闭GUARD_PERMISSION_DROPPED`() {
        val set = PermissionHealthChecker.computeResolveCategories(
            a11yOk = true, overlayOk = true,
            prevDropped = setOf("a11y", "usageStats"), nowDropped = setOf("usageStats")
        )
        assertEquals(setOf("ACCESSIBILITY_DISABLED"), set)
    }

    @Test
    fun `无恢复则空集（边沿触发，不掉失则不关闭）`() {
        val set = PermissionHealthChecker.computeResolveCategories(
            a11yOk = true, overlayOk = true,
            prevDropped = emptySet(), nowDropped = emptySet()
        )
        assertTrue("能力持续正常时不应产生任何关闭请求", set.isEmpty())
    }

    /**
     * AUTH-04：非 DO 设备运行时危险权限被回收时新补的专属告警，其类别必须能被
     * 「恢复即关闭」闭环精确关闭。历史上曾因上报 GUARD_PERMISSION_DROPPED、
     * 恢复时却关 PERMISSION_REVOKED，导致家长端永久挂着一条消除不掉的告警。
     */
    @Test
    fun `运行期权限告警类型与恢复关闭类别严格一致`() {
        val raised = PermissionHealthChecker.runtimeRevocationType().name
        val closed = PermissionHealthChecker.computeResolveCategories(
            a11yOk = false, overlayOk = false,
            prevDropped = setOf("runtime"), nowDropped = emptySet()
        )
        assertEquals(
            "上报的告警类别必须能被恢复闭环关闭，否则家长端会留下永远消不掉的告警",
            setOf(raised), closed
        )
    }

    @Test
    fun `告警去抖 窗口内不重报 跨过窗口才重报`() {
        // 单调钟域：入参是 elapsedRealtime 读数，不再是墙钟。
        val now = 36_000_000L // 设备开机 10 小时
        val interval = 6 * 60 * 60 * 1000L
        assertFalse(
            "刚报过不应立刻再报",
            PermissionHealthChecker.shouldRaiseAlert(now + 1_000L, now, interval)
        )
        assertFalse(
            "窗口边界前一毫秒仍不报",
            PermissionHealthChecker.shouldRaiseAlert(now + interval - 1L, now, interval)
        )
        assertTrue(
            "跨过去抖窗口应允许重报",
            PermissionHealthChecker.shouldRaiseAlert(now + interval, now, interval)
        )
        assertTrue(
            "从未上报过应允许首次上报",
            PermissionHealthChecker.shouldRaiseAlert(now, 0L, interval)
        )
    }

    @Test
    fun `告警去抖 高水位 脏值与旧域时间戳按过期处理`() {
        val now = 36_000_000L
        val interval = 6 * 60 * 60 * 1000L
        // 旧墙钟时间戳（升级前落盘，量级远大于 elapsedRealtime）→ 必须放行，
        // 否则一次时钟域切换会把高危告警静音到 elapsedRealtime 追上墙钟量级（数月）。
        assertTrue(
            "升级前的旧墙钟时间戳不得静音告警",
            PermissionHealthChecker.shouldRaiseAlert(now, 1_700_000_000_000L, interval)
        )
        // 设备重启后 elapsedRealtime 归零，旧的单调读数大于 now → 同样放行。
        assertTrue(
            "重启后残留的旧读数不得静音告警",
            PermissionHealthChecker.shouldRaiseAlert(now, now + 5_000L, interval)
        )
    }

    /**
     * `LOCK_SCREEN_UNPROTECTED` 的防误报总闸。
     *
     * 这条告警的意义是揭穿"家长以为锁了、其实没锁"。但它的可信度完全取决于
     * **不该报的时候绝不报** —— 一条会误报的告警会被家长训练成"无视"，
     * 真出事那天也就没人看了。两个必须挡住的场景：
     */
    @Test
    fun `息屏时锁屏页不可见属于设计行为 不上报`() {
        assertFalse(
            "息屏不拉锁屏页是为了不点亮屏幕，此时不可见是预期结果，不能报成'锁屏失效'",
            PermissionHealthChecker.shouldReportLockUnprotected(
                screenOn = false,
                coverageConfirmed = false
            )
        )
    }

    @Test
    fun `亮屏且已确认覆盖时不上报`() {
        assertFalse(
            "锁屏页已占满前台，属于正常锁定，不能报",
            PermissionHealthChecker.shouldReportLockUnprotected(
                screenOn = true,
                coverageConfirmed = true
            )
        )
    }

    @Test
    fun `亮屏且未确认覆盖时必须上报`() {
        assertTrue(
            "家长已下发锁屏、屏幕亮着、锁屏页却不在前台 —— 孩子照旧能用，必须让家长知道",
            PermissionHealthChecker.shouldReportLockUnprotected(
                screenOn = true,
                coverageConfirmed = false
            )
        )
    }

    /**
     * 「无障碍未启用」容忍窗口：挡的是国产 ROM 切权限强制重启时的瞬时设置串清空。
     * 这里一拍误判会立刻用全屏 intent 把「操作闸门已失效」警告页顶到前台，
     * 是设备端「自己弹危险警告 / 反复提示」的直接来源，必须锁死。
     */
    @Test
    fun `无障碍未启用 观察窗内不告警 持续超过窗口才确认`() {
        val now = 36_000_000L
        val window = 90 * 1000L
        assertFalse(
            "从未记录过起点（0）应重新起窗，不能第一拍就告警",
            PermissionHealthChecker.a11yDisabledConfirmed(now, 0L, window)
        )
        assertFalse(
            "旧墙钟值 / 重启后 elapsedRealtime 归零造成的脏值必须重新起窗",
            PermissionHealthChecker.a11yDisabledConfirmed(now, now + 5_000L, window)
        )
        assertFalse(
            "窗口边界前一毫秒仍在观察期",
            PermissionHealthChecker.a11yDisabledConfirmed(now, now - window + 1L, window)
        )
        assertTrue(
            "恰好跨过窗口应确认持续未启用",
            PermissionHealthChecker.a11yDisabledConfirmed(now, now - window, window)
        )
        assertTrue(
            "远超窗口应确认持续未启用",
            PermissionHealthChecker.a11yDisabledConfirmed(now, now - window - 1L, window)
        )
    }

    /**
     * 悬浮窗观察窗与无障碍共用 [PermissionHealthChecker.disabledConfirmed] 单份实现。
     * 若两条自检各自演化出不同的脏值/边界口径，ROM 抖动会一边被挡住、一边漏出去，
     * 家长端表现为「告警反复出现又自己消失」。
     */
    @Test
    fun `悬浮窗与无障碍共用同一观察窗判定`() {
        val now = 36_000_000L
        val window = 90 * 1000L
        val cases = listOf(
            0L,                  // 从未记录
            now + 5_000L,        // 重启后残留的旧单调读数
            now - window + 1L,   // 窗口边界前一毫秒
            now - window,        // 恰好跨过窗口
            now - window - 1L    // 远超窗口
        )
        for (since in cases) {
            assertEquals(
                "since=$since 两条自检必须同判定",
                PermissionHealthChecker.disabledConfirmed(now, since, window),
                PermissionHealthChecker.a11yDisabledConfirmed(now, since, window)
            )
        }
        // 同时锁死通用判定自身语义，避免"两个函数都恒 false"的假一致。
        assertFalse(PermissionHealthChecker.disabledConfirmed(now, 0L, window))
        assertFalse(PermissionHealthChecker.disabledConfirmed(now, now + 5_000L, window))
        assertFalse(PermissionHealthChecker.disabledConfirmed(now, now - window + 1L, window))
        assertTrue(PermissionHealthChecker.disabledConfirmed(now, now - window, window))
        assertTrue(PermissionHealthChecker.disabledConfirmed(now, now - window - 1L, window))
    }

    /**
     * 「开着却没连上」容忍窗的 DO / 非 DO 分档（2026-11-16 EMUI 一键清理事故复盘）。
     *
     * 非 DO 没有任何自愈手段：系统若会重绑，早在进程被拉回的秒级就完成了；
     * 进程活着、服务却迟迟不连 = 永远不会自愈，多等的每一分钟都是闸门裸奔。
     * 若这组断言红了（非 DO 被改回 10 分钟），意味着 EMUI 清理事故的静默失效回归。
     */
    @Test
    fun `detached容忍窗 DO十分钟 非DO九十秒`() {
        assertEquals(
            "DO 有强制重绑与 10 分钟限频，维持长窗防误报",
            10 * 60 * 1000L,
            PermissionHealthChecker.detachedToleranceMs(isDeviceOwner = true)
        )
        assertEquals(
            "非 DO 无自愈手段，必须与其它观察窗同口径 90 秒",
            90 * 1000L,
            PermissionHealthChecker.detachedToleranceMs(isDeviceOwner = false)
        )
    }

    /**
     * 「a11y 观察期」三路合成判定：任一信号为真即切 2 分钟快检，全假才回常规节奏。
     *
     * 三个信号各自的来源（见 [PermissionHealthChecker.a11yObservationActive] 文档）：
     * - suspect：最近一轮 check 确认未健康（进程重启后靠陈旧心跳撑着也算）；
     * - a11yDisabledSince / detachedSince：持久化观察窗起算过且未清除（进程重启后仍生效）。
     */
    @Test
    fun `a11y观察态 三路信号任一为真即快检`() {
        assertTrue(
            "疑似态（仅靠陈旧心跳撑着）必须触发快检",
            PermissionHealthChecker.a11yObservationActive(suspect = true, a11yDisabledSince = 0L, detachedSince = 0L)
        )
        assertTrue(
            "「未启用」观察窗计时中必须触发快检",
            PermissionHealthChecker.a11yObservationActive(suspect = false, a11yDisabledSince = 36_000_000L, detachedSince = 0L)
        )
        assertTrue(
            "「没连上」观察窗计时中必须触发快检",
            PermissionHealthChecker.a11yObservationActive(suspect = false, a11yDisabledSince = 0L, detachedSince = 36_000_000L)
        )
        assertFalse(
            "健康且无计时中的观察窗应回 5 分钟常规节奏",
            PermissionHealthChecker.a11yObservationActive(suspect = false, a11yDisabledSince = 0L, detachedSince = 0L)
        )
    }
}
