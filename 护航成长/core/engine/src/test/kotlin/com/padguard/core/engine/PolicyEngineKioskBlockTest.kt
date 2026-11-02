package com.padguard.core.engine

import com.padguard.core.engine.enforcer.KioskRuntime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PolicyEngine.decideKioskBlock] 的纯逻辑单测。
 *
 * 这段判定的意义：非 DO 机型上 Kiosk 既没有 LockTask 也没有常驻桌面，
 * 白名单等于一纸空文。加上应用层兜底后，"纯净桌面"才真的能拦住别的应用。
 * 但拦错了会直接把设备变成砖，因此三个前置条件必须逐条锁死：
 * 未开启 / 非降级态（DO 已真锁）/ 白名单为空 —— 任一成立都不拦。
 */
class PolicyEngineKioskBlockTest {

    private fun runtime(
        enabled: Boolean = true,
        degraded: Boolean = true,
        userAppCount: Int = 2,
        allowed: Set<String> = setOf("com.padguard.child", "com.android.systemui", "com.kid.app")
    ) = KioskRuntime(
        enabled = enabled,
        degraded = degraded,
        allowedPackages = allowed,
        userAppCount = userAppCount
    )

    @Test
    fun `降级态下白名单外的应用被拦`() {
        assertTrue(PolicyEngine.decideKioskBlock("com.other.game", runtime(), tempUnlocked = false))
    }

    @Test
    fun `白名单内的应用放行`() {
        assertFalse(PolicyEngine.decideKioskBlock("com.kid.app", runtime(), tempUnlocked = false))
    }

    @Test
    fun `Kiosk 未开启时一律不拦`() {
        assertFalse(
            PolicyEngine.decideKioskBlock("com.other.game", runtime(enabled = false), tempUnlocked = false)
        )
    }

    @Test
    fun `非降级态（DO 真锁定）不在应用层重复拦截`() {
        assertFalse(
            PolicyEngine.decideKioskBlock("com.other.game", runtime(degraded = false), tempUnlocked = false)
        )
    }

    @Test
    fun `白名单没配应用时不拦——否则设备只剩桌面，等于变砖`() {
        assertFalse(
            PolicyEngine.decideKioskBlock(
                "com.other.game",
                runtime(userAppCount = 0, allowed = setOf("com.padguard.child", "com.android.systemui")),
                tempUnlocked = false
            )
        )
    }

    @Test
    fun `家长临时放行的应用不拦`() {
        assertFalse(PolicyEngine.decideKioskBlock("com.other.game", runtime(), tempUnlocked = true))
    }
}
