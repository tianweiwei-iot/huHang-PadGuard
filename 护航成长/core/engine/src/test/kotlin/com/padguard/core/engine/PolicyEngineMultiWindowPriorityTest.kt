package com.padguard.core.engine

import com.padguard.core.data.model.policy.AppListMode
import com.padguard.core.data.model.policy.AppPolicy
import com.padguard.core.engine.enforcer.KioskRuntime
import com.padguard.core.engine.enforcer.LimitReason
import com.padguard.core.engine.enforcer.LimitVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * R-P2-10：分屏（多窗口）时"该拦谁"的纯逻辑单测。
 *
 * 锁住三件事：
 * 1. 分屏里另一半屏幕上的黑名单/超额应用会被选中拦截（旧实现只认最后被点过的那个）；
 * 2. 家长临时放行的应用不会被这条路径推翻；
 * 3. 都不该拦时返回 null，让调用方回落到"最近 resumed 的那个"，不改变原有体验。
 */
class PolicyEngineMultiWindowPriorityTest {

    private val noKiosk = KioskRuntime()

    private fun allowed(pkg: String) = LimitVerdict.Allowed

    private fun app(blacklist: List<String> = emptyList(), mode: AppListMode = AppListMode.BLACKLIST) =
        AppPolicy(mode = mode, blacklist = blacklist)

    @Test
    fun `分屏里另一半屏幕上的黑名单应用被选中`() = runBlocking<Unit> {
        val picked = PolicyEngine.decideMultiWindowPriority(
            candidates = listOf("com.calculator", "com.game"),
            app = app(blacklist = listOf("com.game")),
            kiosk = noKiosk,
            tempUnlocked = { false },
            limitVerdict = { allowed(it) }
        )
        assertEquals("com.game", picked)
    }

    @Test
    fun `超出单应用限额的那个被选中`() = runBlocking<Unit> {
        val picked = PolicyEngine.decideMultiWindowPriority(
            candidates = listOf("com.calculator", "com.video"),
            app = app(),
            kiosk = noKiosk,
            tempUnlocked = { false },
            limitVerdict = { pkg ->
                if (pkg == "com.video") {
                    LimitVerdict.Blocked(pkg, LimitReason.APP_DAILY, usedMinutes = 30, quotaMinutes = 30)
                } else {
                    allowed(pkg)
                }
            }
        )
        assertEquals("com.video", picked)
    }

    @Test
    fun `日总时长判定不参与多窗口选择`() = runBlocking<Unit> {
        // DAILY_TOTAL 由服务端权威会话统一驱动，本地不重复判；
        // 若这里也当命中，会在家长刚续时后多拦一次。
        val picked = PolicyEngine.decideMultiWindowPriority(
            candidates = listOf("com.a", "com.b"),
            app = app(),
            kiosk = noKiosk,
            tempUnlocked = { false },
            limitVerdict = { pkg ->
                LimitVerdict.Blocked(pkg, LimitReason.DAILY_TOTAL, usedMinutes = 120, quotaMinutes = 120)
            }
        )
        assertNull(picked)
    }

    @Test
    fun `家长临时放行的黑名单应用不被选中`() = runBlocking<Unit> {
        val picked = PolicyEngine.decideMultiWindowPriority(
            candidates = listOf("com.game"),
            app = app(blacklist = listOf("com.game")),
            kiosk = noKiosk,
            tempUnlocked = { it == "com.game" },
            limitVerdict = { allowed(it) }
        )
        assertNull(picked)
    }

    @Test
    fun `都不该拦时返回 null（回落最近 resumed）`() = runBlocking<Unit> {
        val picked = PolicyEngine.decideMultiWindowPriority(
            candidates = listOf("com.a", "com.b"),
            app = app(),
            kiosk = noKiosk,
            tempUnlocked = { false },
            limitVerdict = { allowed(it) }
        )
        assertNull(picked)
    }

    @Test
    fun `Kiosk 降级白名单外的应用被选中`() = runBlocking<Unit> {
        val kiosk = KioskRuntime(
            enabled = true,
            degraded = true,
            userAppCount = 1,
            allowedPackages = setOf("com.allowed", "com.android.launcher")
        )
        val picked = PolicyEngine.decideMultiWindowPriority(
            candidates = listOf("com.allowed", "com.game"),
            app = app(),
            kiosk = kiosk,
            tempUnlocked = { false },
            limitVerdict = { allowed(it) }
        )
        assertEquals("com.game", picked)
    }
}
