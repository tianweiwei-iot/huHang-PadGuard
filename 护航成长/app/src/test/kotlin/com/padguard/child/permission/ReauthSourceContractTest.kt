package com.padguard.child.permission

import com.padguard.core.data.repository.ReauthSource
import com.padguard.core.data.repository.reauthRequiredOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R36：「需要重新授权」由单一 bool 改为**按来源记账**后的契约回归（纯 JVM）。
 *
 * 事故背景：旧 `AuthRepository.clearReauthRequired()` 是全局清，`AuthorizationKeeper`
 * 每 30 分钟只要自己那一套权限全齐就无条件调用它，于是把
 * 「通知代收授权掉失」「卸载防护为 0」「家长刚点一键补齐」等与 keeper 维度无关的
 * 引导请求一起吞掉 —— 真机表现为「权限页点了一键补齐没反应」「权限掉了却不被拉回」。
 *
 * 本测试锁死三条纪律：
 * 1. 谁置位谁复位，任一自检**不得**清掉别人的来源；
 * 2. 只有引导流程真正走完才允许整表收口，且必须一并复位旧版遗留 bool；
 * 3. 旧设备升级后遗留的单一 bool 仍被兼容，不会丢掉既有引导请求。
 */
class ReauthSourceContractTest {

    // ==================== ① 读判定纯函数 ====================

    @Test
    fun `无来源且旧标记为假不进入引导`() {
        assertFalse(reauthRequiredOf(emptySet(), false))
    }

    @Test
    fun `旧版遗留 bool 为真仍进入引导`() {
        assertTrue("老设备升级后不能丢掉已经置位的引导请求", reauthRequiredOf(emptySet(), true))
    }

    @Test
    fun `任一来源存在即进入引导`() {
        assertTrue(reauthRequiredOf(setOf(ReauthSource.KEEPER), false))
        assertTrue(reauthRequiredOf(setOf("permHealth:usageStats"), false))
    }

    // ==================== ② 多来源状态机（模拟 DataStore 的集合语义） ====================

    private fun mark(state: Set<String>, source: String): Set<String> = state + source
    private fun unmark(state: Set<String>, source: String): Set<String> = state - source
    private fun replaceNs(state: Set<String>, prefix: String, keys: Set<String>): Set<String> =
        state.filterNot { it.startsWith(prefix) }.toSet() + keys.map { prefix + it }

    @Test
    fun `keeper 全齐只清自己 不吞权限快检的来源`() {
        var state = mark(emptySet(), ReauthSource.KEEPER)
        state = mark(state, ReauthSource.PERM_HEALTH_PREFIX + "usageStats")
        state = unmark(state, ReauthSource.KEEPER) // keeper 判定 stillMissing 为空
        assertTrue("权限快检仍在报警，引导请求不能被 keeper 抹掉", reauthRequiredOf(state, false))
        assertEquals(setOf("permHealth:usageStats"), state)
    }

    @Test
    fun `keeper 全齐不吞家长一键补齐`() {
        var state = mark(emptySet(), ReauthSource.MANUAL_REVIEW)
        state = mark(state, ReauthSource.KEEPER)
        state = unmark(state, ReauthSource.KEEPER)
        assertEquals(setOf(ReauthSource.MANUAL_REVIEW), state)
        assertTrue(reauthRequiredOf(state, false))
    }

    @Test
    fun `keeper 全齐不吞卸载防护`() {
        var state = mark(emptySet(), ReauthSource.UNINSTALL_SHIELD)
        state = unmark(state, ReauthSource.KEEPER)
        assertEquals(setOf(ReauthSource.UNINSTALL_SHIELD), state)
        assertTrue(reauthRequiredOf(state, false))
    }

    @Test
    fun `权限恢复后按命名空间对账 不误伤其它来源`() {
        var state = mark(emptySet(), ReauthSource.MANUAL_REVIEW)
        state = mark(state, "permHealth:usageStats")
        state = mark(state, "permHealth:exactAlarm")
        state = replaceNs(state, ReauthSource.PERM_HEALTH_PREFIX, setOf("exactAlarm"))
        assertEquals(
            "恢复的 usageStats 应移除、仍掉失的 exactAlarm 应保留、家长手动补齐的请求必须原样保留",
            setOf(ReauthSource.MANUAL_REVIEW, "permHealth:exactAlarm"),
            state
        )
    }

    @Test
    fun `命名空间全部恢复后不残留僵尸来源`() {
        var state = mark(emptySet(), "permHealth:usageStats")
        state = replaceNs(state, ReauthSource.PERM_HEALTH_PREFIX, emptySet())
        assertFalse(reauthRequiredOf(state, false))
    }

    @Test
    fun `引导流程走完清掉全部来源与旧标记`() {
        var state = mark(mark(mark(emptySet(), ReauthSource.KEEPER), ReauthSource.MANUAL_REVIEW),
            ReauthSource.UNINSTALL_SHIELD)
        state = emptySet()
        // clearAllReauthRequired 同时把旧 bool 写成 false；两者都清才不会重启后又弹回。
        assertFalse(reauthRequiredOf(state, false))
    }

    // ==================== ③ 源码契约：接线不能退化 ====================

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

    private fun child(rel: String) = readText(
        "app/src/main/kotlin/com/padguard/child/$rel",
        "src/main/kotlin/com/padguard/child/$rel",
        "护航成长/app/src/main/kotlin/com/padguard/child/$rel"
    )

    private fun coreData(rel: String) = readText(
        "core/data/src/main/kotlin/com/padguard/core/data/repository/$rel",
        "../core/data/src/main/kotlin/com/padguard/core/data/repository/$rel",
        "护航成长/core/data/src/main/kotlin/com/padguard/core/data/repository/$rel"
    )

    @Test
    fun `生产代码不得再出现无参置位或无参清除`() {
        listOf(
            "permission/AuthorizationKeeper.kt",
            "guard/PermissionHealthChecker.kt",
            "guard/UninstallDefenseModule.kt",
            "permission/PermissionGuideViewModel.kt",
            "consent/OneTimeConsentViewModel.kt"
        ).forEach { rel ->
            val src = child(rel)
            assertFalse("$rel 仍在无参置位单一 bool", src.contains("setReauthRequired()"))
            assertFalse("$rel 仍在无参清除单一 bool", src.contains("clearReauthRequired()"))
        }
    }

    @Test
    fun `全量自检只清自己的来源且不得整表收口`() {
        val keeper = child("permission/AuthorizationKeeper.kt")
        assertTrue("keeper 全齐分支必须只清 KEEPER", keeper.contains("clearReauthRequired(ReauthSource.KEEPER)"))
        assertTrue("keeper 掉失分支必须按来源置位", keeper.contains("setReauthRequired(ReauthSource.KEEPER)"))
        assertFalse("keeper 无权清掉其它自检的请求", keeper.contains("clearAllReauthRequired"))
    }

    @Test
    fun `权限快检一轮批量对账 恢复即自动移除来源`() {
        val health = child("guard/PermissionHealthChecker.kt")
        assertTrue(
            "快检必须整轮对账命名空间，不能回到逐项置位",
            health.contains("authRepository.replaceReauthSources(ReauthSource.PERM_HEALTH_PREFIX, dropped)")
        )
        assertTrue(
            "通知代收授权掉失必须并入同一轮对账",
            health.contains("if (checkNotificationListener()) dropped += NOTIFICATION_LISTENER_KEY")
        )
        assertFalse("快检不得绕过命名空间直接置位", health.contains("setReauthRequired("))
        assertFalse("快检不得全局清除", health.contains("clearAllReauthRequired"))
    }

    @Test
    fun `卸载防护所有早退分支都要清自己的来源`() {
        val shield = child("guard/UninstallDefenseModule.kt")
        assertTrue("三层全空时必须按来源置位", shield.contains("setReauthRequired(ReauthSource.UNINSTALL_SHIELD)"))
        assertEquals(
            "needsParentAttention / 家长主动关密码 / 软闸门缺密码 三条恢复分支都必须清来源",
            3,
            Regex("clearReauthRequired\\(ReauthSource\\.UNINSTALL_SHIELD\\)").findAll(shield).count()
        )
    }

    @Test
    fun `只有引导流程走完才允许整表收口`() {
        val consent = child("consent/OneTimeConsentViewModel.kt")
        assertEquals("finish 与 finishAnyway 两处出口都要收口", 2,
            Regex("clearAllReauthRequired\\(\\)").findAll(consent).count())
        assertFalse("流水线不得按单一来源清除（会留下别人的请求）", consent.contains("clearReauthRequired("))

        val guide = child("permission/PermissionGuideViewModel.kt")
        assertTrue("一键补齐必须登记为家长手动来源",
            guide.contains("setReauthRequired(ReauthSource.MANUAL_REVIEW)"))
    }

    @Test
    fun `仓库实现必须兼容旧版 bool 且来源常量互不重复`() {
        val repo = coreData("AuthRepository.kt")
        assertTrue("必须读来源集合", repo.contains("Keys.REAUTH_SOURCES"))
        assertTrue("必须兼容旧版遗留 bool", repo.contains("reauthRequiredOf(") &&
            repo.contains("it[Keys.REAUTH_REQUIRED] == true"))
        assertTrue("引导收口必须把旧 bool 一并复位", repo.contains("prefs[Keys.REAUTH_REQUIRED] = false"))
        assertTrue("整表收口必须移除来源集合", repo.contains("prefs.remove(Keys.REAUTH_SOURCES)"))

        val distinct = setOf(
            ReauthSource.KEEPER, ReauthSource.UNINSTALL_SHIELD,
            ReauthSource.MANUAL_REVIEW, ReauthSource.PERM_HEALTH_PREFIX
        )
        assertEquals("来源常量不得撞名", 4, distinct.size)
        assertTrue("命名空间前缀必须以冒号结尾", ReauthSource.PERM_HEALTH_PREFIX.endsWith(':'))
        listOf(ReauthSource.KEEPER, ReauthSource.UNINSTALL_SHIELD, ReauthSource.MANUAL_REVIEW)
            .forEach { assertFalse("普通来源不得带命名空间前缀", it.contains(':')) }
    }
}
