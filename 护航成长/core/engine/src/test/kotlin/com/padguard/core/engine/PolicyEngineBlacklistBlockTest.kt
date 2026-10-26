package com.padguard.core.engine

import com.padguard.core.data.model.policy.AppListMode
import com.padguard.core.data.model.policy.AppPolicy
import com.padguard.core.data.model.policy.BlacklistAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PolicyEngine.decideBlacklistBlock] 的纯逻辑单测（E4 修复点：blacklistAction 终于生效）。
 *
 * 该函数把"黑名单命中判定 + 处置方式（BLOCK_DIALOG / SILENT_CLOSE）"与平台副作用解耦，
 * 因此可在 JVM 直接验证，不依赖 Android 运行时。
 */
class PolicyEngineBlacklistBlockTest {

    @Test
    fun `黑名单命中 + BLOCK_DIALOG 返回包名且 silentClose=false`() {
        val app = AppPolicy(
            mode = AppListMode.BLACKLIST,
            blacklist = listOf("com.banned"),
            blacklistAction = BlacklistAction.BLOCK_DIALOG
        )
        val (pkg, silent) = PolicyEngine.decideBlacklistBlock("com.banned", app, tempUnlocked = false)
        assertEquals("com.banned", pkg)
        assertEquals(false, silent)
    }

    @Test
    fun `黑名单命中 + SILENT_CLOSE 返回包名且 silentClose=true`() {
        val app = AppPolicy(
            mode = AppListMode.BLACKLIST,
            blacklist = listOf("com.banned"),
            blacklistAction = BlacklistAction.SILENT_CLOSE
        )
        val (pkg, silent) = PolicyEngine.decideBlacklistBlock("com.banned", app, tempUnlocked = false)
        assertEquals("com.banned", pkg)
        assertEquals(true, silent)
    }

    @Test
    fun `未命中黑名单的应用返回 null（不拦截）`() {
        val app = AppPolicy(mode = AppListMode.BLACKLIST, blacklist = listOf("com.banned"))
        val (pkg, _) = PolicyEngine.decideBlacklistBlock("com.ok", app, tempUnlocked = false)
        assertNull(pkg)
    }

    @Test
    fun `家长临时放行的黑名单应用返回 null（不拦截）`() {
        val app = AppPolicy(mode = AppListMode.BLACKLIST, blacklist = listOf("com.banned"))
        val (pkg, _) = PolicyEngine.decideBlacklistBlock("com.banned", app, tempUnlocked = true)
        assertNull(pkg)
    }

    @Test
    fun `systemAppRules 显式 BLOCK 被纳入拦截`() {
        val app = AppPolicy(
            mode = AppListMode.BLACKLIST,
            systemAppRules = mapOf("com.system.suspect" to "BLOCK")
        )
        val (pkg, _) = PolicyEngine.decideBlacklistBlock("com.system.suspect", app, tempUnlocked = false)
        assertEquals("com.system.suspect", pkg)
    }

    @Test
    fun `systemAppRules 显式 ALLOW 覆盖黑名单`() {
        val app = AppPolicy(
            mode = AppListMode.BLACKLIST,
            blacklist = listOf("com.gray"),
            systemAppRules = mapOf("com.gray" to "ALLOW")
        )
        val (pkg, _) = PolicyEngine.decideBlacklistBlock("com.gray", app, tempUnlocked = false)
        assertNull(pkg)
    }

    @Test
    fun `白名单模式下黑名单字段被忽略，兜底不拦（避免把桌面与输入法拦死）`() {
        val app = AppPolicy(
            mode = AppListMode.WHITELIST,
            whitelist = listOf("com.allowed"),
            blacklist = listOf("com.banned")
        )
        val (pkg, _) = PolicyEngine.decideBlacklistBlock("com.banned", app, tempUnlocked = false)
        assertNull(pkg)
    }
}
