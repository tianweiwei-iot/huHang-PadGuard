package com.padguard.child.guard

import com.padguard.core.data.model.policy.AppListMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppGuardDecisions] 纯函数决策层的单元测试。
 *
 * 这是"应用管控"判定口径的**单一真相**，必须可脱离设备直接验证：
 * 桌面可视网关（[com.padguard.child.KidHomeViewModel]）、无障碍软拦截
 * （[OperationGuardAccessibilityService]）都收敛到这里的同一份逻辑，
 * 任何一处漂移都会制造"桌面显示但点开被拦""一边免密一边要密"的回归。
 */
class AppGuardDecisionsTest {

    private fun snapshot(
        mode: AppListMode = AppListMode.BLACKLIST,
        whitelist: Set<String> = emptySet(),
        blacklist: Set<String> = emptySet(),
        systemAppRules: Map<String, String> = emptyMap(),
        requirePasswordOnLaunch: Boolean = false,
        requirePasswordOnInstall: Boolean = true,
        requirePasswordOnUninstall: Boolean = true,
        isDeviceOwner: Boolean = false
    ) = AppGuardDecisions.Snapshot(
        mode = mode,
        whitelist = whitelist,
        blacklist = blacklist,
        systemAppRules = systemAppRules,
        requirePasswordOnLaunch = requirePasswordOnLaunch,
        requirePasswordOnInstall = requirePasswordOnInstall,
        requirePasswordOnUninstall = requirePasswordOnUninstall,
        isDeviceOwner = isDeviceOwner
    )

    private fun decisions(
        snapshot: AppGuardDecisions.Snapshot,
        isSystemCritical: (String) -> Boolean = { false },
        isHidden: (String) -> Boolean = { false }
    ) = AppGuardDecisions(snapshot, isSystemCritical, isHidden)

    // ==================== isAuthorized ====================

    @Test
    fun `whitelist mode authorizes only whitelisted packages`() {
        val d = decisions(snapshot(mode = AppListMode.WHITELIST, whitelist = setOf("com.a")))
        assertTrue(d.isAuthorized("com.a"))
        assertFalse(d.isAuthorized("com.b"))
    }

    @Test
    fun `blacklist mode authorizes only non-blacklisted packages`() {
        val d = decisions(snapshot(mode = AppListMode.BLACKLIST, blacklist = setOf("com.b")))
        assertTrue(d.isAuthorized("com.a"))
        assertFalse(d.isAuthorized("com.b"))
    }

    // ==================== isHiddenControlled ====================

    @Test
    fun `hidden app is controlled unless it is system critical`() {
        val d = decisions(snapshot(), isHidden = { it == "com.b" })
        assertTrue(d.isHiddenControlled("com.b"))
        assertFalse(d.isHiddenControlled("com.a"))
    }

    @Test
    fun `system critical package is never hidden-controlled (anti-brick)`() {
        val d = decisions(snapshot(), isSystemCritical = { it == "com.sys" }, isHidden = { true })
        assertFalse(d.isHiddenControlled("com.sys"))
    }

    // ==================== isControlled ====================

    @Test
    fun `authorized app is never controlled even if hidden`() {
        // 授权优先于取消授权：避免"已授权应用被锁死"的现场故障。
        val d = decisions(
            snapshot(mode = AppListMode.WHITELIST, whitelist = setOf("com.a")),
            isHidden = { true }
        )
        assertFalse(d.isControlled("com.a"))
    }

    @Test
    fun `hidden but unauthorized app is controlled`() {
        val d = decisions(snapshot(blacklist = setOf("com.b")), isHidden = { it == "com.b" })
        assertTrue(d.isControlled("com.b"))
    }

    @Test
    fun `systemAppRules BLOCK overrides list`() {
        val d = decisions(
            snapshot(blacklist = setOf("com.b"), systemAppRules = mapOf("com.b" to "BLOCK"))
        )
        assertTrue(d.isControlled("com.b"))
    }

    @Test
    fun `systemAppRules ALLOW overrides blacklist`() {
        val d = decisions(
            snapshot(blacklist = setOf("com.b"), systemAppRules = mapOf("com.b" to "ALLOW"))
        )
        assertFalse(d.isControlled("com.b"))
    }

    @Test
    fun `mode based control resolves outside-whitelist and inside-blacklist`() {
        val wl = decisions(snapshot(mode = AppListMode.WHITELIST, whitelist = setOf("com.a")))
        assertTrue(wl.isControlled("com.b")) // 白名单外
        assertFalse(wl.isControlled("com.a"))

        val bl = decisions(snapshot(mode = AppListMode.BLACKLIST, blacklist = setOf("com.b")))
        assertTrue(bl.isControlled("com.b"))
        assertFalse(bl.isControlled("com.a"))
    }

    // ==================== needsLaunchGuard ====================

    @Test
    fun `system critical never needs guard`() {
        val d = decisions(snapshot(requirePasswordOnLaunch = true), isSystemCritical = { it == "com.sys" })
        assertFalse(d.needsLaunchGuard("com.sys", appAccessGranted = false, unlocked = false))
    }

    @Test
    fun `app access granted or unlocked bypasses guard`() {
        val d = decisions(snapshot(requirePasswordOnLaunch = true, blacklist = setOf("com.b")))
        assertFalse(d.needsLaunchGuard("com.b", appAccessGranted = true, unlocked = false))
        assertFalse(d.needsLaunchGuard("com.b", appAccessGranted = false, unlocked = true))
    }

    @Test
    fun `hidden app needs guard regardless of launch-block switch`() {
        // 关键回归点：取消授权的应用在任何情况下都走门禁，
        // 不因"打开应用也要密码"关闭而漏放，也不因未设家长密码而漏放。
        val off = decisions(snapshot(requirePasswordOnLaunch = false), isHidden = { it == "com.b" })
        assertTrue(off.needsLaunchGuard("com.b", appAccessGranted = false, unlocked = false))

        val on = decisions(snapshot(requirePasswordOnLaunch = true), isHidden = { it == "com.b" })
        assertTrue(on.needsLaunchGuard("com.b", appAccessGranted = false, unlocked = false))
    }

    @Test
    fun `controlled app needs guard only when launch-block switch is on`() {
        // 门禁决策本身与"家长密码是否设置"无关：密码只是门禁浮层内的一步，
        // 未设密码时门禁浮层改为提示"请家长设置密码"，不应静默放行。
        val off = decisions(snapshot(requirePasswordOnLaunch = false, blacklist = setOf("com.b")))
        assertFalse(off.needsLaunchGuard("com.b", appAccessGranted = false, unlocked = false))

        val on = decisions(snapshot(requirePasswordOnLaunch = true, blacklist = setOf("com.b")))
        assertTrue(on.needsLaunchGuard("com.b", appAccessGranted = false, unlocked = false))
    }

    @Test
    fun `DO authorized app never needs guard even when launch-block is on`() {
        // DO 设备由系统隐藏/挂起负责，已授权应用运行自由，闸门仅作用于受控应用（保留原口径）。
        val d = decisions(
            snapshot(
                mode = AppListMode.WHITELIST,
                whitelist = setOf("com.a"),
                requirePasswordOnLaunch = true,
                isDeviceOwner = true
            )
        )
        assertFalse(d.needsLaunchGuard("com.a", appAccessGranted = false, unlocked = false))
    }

    @Test
    fun `non-DO authorized app needs guard when launch-block is on (R-P2-19)`() {
        // 非 DO 全量启动闸门：白名单内已授权应用（非系统）也要密码，杜绝"装完即玩"。
        val d = decisions(
            snapshot(
                mode = AppListMode.WHITELIST,
                whitelist = setOf("com.a"),
                requirePasswordOnLaunch = true,
                isDeviceOwner = false
            )
        )
        assertTrue(d.needsLaunchGuard("com.a", appAccessGranted = false, unlocked = false))
    }

    @Test
    fun `non-DO new app under default blacklist needs guard when launch-block is on (R-P2-19)`() {
        // 默认黑名单模式下，未列入黑名单的新装应用"已授权"，非 DO 全量闸门仍拦它（治 APP-04）。
        val d = decisions(
            snapshot(
                mode = AppListMode.BLACKLIST,
                blacklist = setOf("com.b"),
                requirePasswordOnLaunch = true,
                isDeviceOwner = false
            )
        )
        assertTrue(d.needsLaunchGuard("com.newgame", appAccessGranted = false, unlocked = false))
        // 名单内受控应用同样拦。
        assertTrue(d.needsLaunchGuard("com.b", appAccessGranted = false, unlocked = false))
    }

    @Test
    fun `non-DO launch-block off still lets new app launch freely`() {
        // 家长显式关闭闸门 → 非 DO 回落名单口径，新应用（已授权）免密（不强制、可回退）。
        val d = decisions(
            snapshot(
                mode = AppListMode.BLACKLIST,
                requirePasswordOnLaunch = false,
                isDeviceOwner = false
            )
        )
        assertFalse(d.needsLaunchGuard("com.newgame", appAccessGranted = false, unlocked = false))
    }

    // ==================== systemAppRules 优先于名单（新增缺陷 O） ====================

    @Test
    fun `explicit BLOCK controls an app that is NOT in the blacklist`() {
        // 回归主线：旧实现 isAuthorized 只看名单，黑名单模式下"不在黑名单"即授权，
        // isControlled 第一行直接 return false，显式 BLOCK 规则永远到不了 → 家长配了也白配。
        val d = decisions(snapshot(systemAppRules = mapOf("com.sys" to "BLOCK")))
        assertFalse(d.isAuthorized("com.sys"))
        assertTrue(d.isControlled("com.sys"))
        assertFalse(d.isLaunchableByChild("com.sys"))
    }

    @Test
    fun `explicit BLOCK still needs launch guard on DO devices`() {
        // DO 分支只对"受控应用"施加闸门；若 BLOCK 没被算进受控，DO 上就能免密打开。
        // 开关关闭时 needsLaunchGuard 本就一律放行（DO 靠系统隐藏/挂起负责），故这里显式开启。
        val d = decisions(
            snapshot(
                systemAppRules = mapOf("com.sys" to "BLOCK"),
                isDeviceOwner = true,
                requirePasswordOnLaunch = true
            )
        )
        assertTrue(d.needsLaunchGuard("com.sys", appAccessGranted = false, unlocked = false))
    }

    @Test
    fun `explicit ALLOW overrides blacklist membership on every entry point`() {
        val d = decisions(snapshot(blacklist = setOf("com.b"), systemAppRules = mapOf("com.b" to "ALLOW")))
        assertTrue(d.isAuthorized("com.b"))
        assertFalse(d.isControlled("com.b"))
        assertTrue(d.isLaunchableByChild("com.b"))
    }

    @Test
    fun `systemAppRules values are case insensitive`() {
        val d = decisions(snapshot(systemAppRules = mapOf("com.a" to "block", "com.b" to "allow")))
        assertTrue(d.isControlled("com.a"))
        assertFalse(d.isLaunchableByChild("com.a"))
        assertFalse(d.isControlled("com.b"))
    }

    @Test
    fun `whitelist mode keeps strict list semantics and ignores system rules`() {
        // 与 PolicyEngine.decideBlacklistBlock 在白名单模式不做兜底保持同一取舍：
        // 白名单 = 只允许名单内，不能用一条 ALLOW 规则把名单外的包放进来。
        val d = decisions(
            snapshot(
                mode = AppListMode.WHITELIST,
                whitelist = setOf("com.a"),
                systemAppRules = mapOf("com.b" to "ALLOW")
            )
        )
        assertFalse(d.isAuthorized("com.b"))
        assertFalse(d.isLaunchableByChild("com.b"))
        assertTrue(d.isLaunchableByChild("com.a"))
    }

    // ==================== isLaunchableByChild ====================

    @Test
    fun `system critical is always launchable (anti-brick)`() {
        val d = decisions(snapshot(), isSystemCritical = { it == "com.sys" })
        assertTrue(d.isLaunchableByChild("com.sys"))
    }

    @Test
    fun `hidden app is never launchable by child`() {
        // 即使它同时在白名单（矛盾组合），取消授权优先：桌面直接不显示，从源头杜绝触达与绕过。
        val d = decisions(
            snapshot(mode = AppListMode.WHITELIST, whitelist = setOf("com.b")),
            isHidden = { it == "com.b" }
        )
        assertFalse(d.isLaunchableByChild("com.b"))
    }

    @Test
    fun `authorized app launchable and unauthorized not launchable`() {
        val wl = decisions(snapshot(mode = AppListMode.WHITELIST, whitelist = setOf("com.a")))
        assertTrue(wl.isLaunchableByChild("com.a"))
        assertFalse(wl.isLaunchableByChild("com.b"))

        val bl = decisions(snapshot(mode = AppListMode.BLACKLIST, blacklist = setOf("com.b")))
        assertFalse(bl.isLaunchableByChild("com.b"))
        assertTrue(bl.isLaunchableByChild("com.a"))
    }
}
