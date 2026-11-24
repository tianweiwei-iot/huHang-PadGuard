package com.padguard.child.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R36 状态一致性契约回归（纯 JVM 源码扫描）。
 *
 * 两处真机可感知缺陷：
 * 1. **入口分流首帧抢跑**：`MainActivity` 的 8 条 DataStore 流里只有 3 条用 `initial=null`
 *    并进了 Splash gate，其余 4 条用 `initial=false`。真实值到达前的那一帧会走
 *    「DO 预置页 / 隐藏态 finish() / 协议弹窗」这类不可撤销的分支 —— 家长点补授权后
 *    Activity 已被 finish，授权页一闪就没。
 * 2. **单向历史账本用 apply() 异步落盘**：「曾授予」「设备管理员曾激活」「运行时权限曾授予」
 *    这类账本无删除入口、只在首次观察到时写一次，进程随即被杀丢一次就永远补不回来，
 *    日后权限被撤销再也不会把家长拉回授权页。下降沿基线、日切快照同理。
 *
 * 本测试不触碰 Android 运行时，只锁源码接线。
 */
class PersistenceHardeningR36Test {

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

    // ==================== ① 入口分流：八条流全部等齐 ====================

    @Test
    fun `入口分流的八条 DataStore 流首帧都必须是未知态 null`() {
        val main = child("MainActivity.kt")
        val flows = Regex("""val (\w+) by authRepository\.\w+\.collectAsState\(initial = (\w+)\)""")
            .findAll(main)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertEquals("入口分流应恰好有 8 条来自 AuthRepository 的 DataStore 流", 8, flows.size)
        flows.forEach { (name, initial) ->
            assertEquals(
                "$name 用 initial=false 会在真实值到达前抢跑并作出不可撤销的决策",
                "null", initial
            )
        }
        assertEquals(
            setOf(
                "consentDone", "loggedIn", "isBound", "agreed",
                "daPromptAck", "reauthRequired", "doPromptAck", "managementState"
            ),
            flows.map { it.first }.toSet()
        )
    }

    @Test
    fun `Splash gate 必须覆盖全部八条流`() {
        val main = child("MainActivity.kt")
        val gate = Regex("""consentDone == null[\s\S]*?-> Splash\(\)""").find(main)?.value
            ?: throw AssertionError("找不到 Splash 启动页 gate")
        listOf(
            "consentDone", "loggedIn", "isBound", "agreed",
            "daPromptAck", "reauthRequired", "doPromptAck", "managementState"
        ).forEach {
            assertTrue("Splash gate 漏了 $it，首帧会抢跑", gate.contains("$it == null"))
        }
    }

    @Test
    fun `可空布尔不得再用一元取反把未知当成假`() {
        val main = child("MainActivity.kt")
        assertFalse("!doPromptAck 会把 DataStore 首帧未知态误判为未确认", main.contains("!doPromptAck"))
        assertFalse("!daPromptAck 会把 DataStore 首帧未知态误判为未确认", main.contains("!daPromptAck"))
        assertTrue("改用可空安全的 != true", main.contains("doPromptAck != true"))
        assertTrue("改用可空安全的 != true", main.contains("daPromptAck != true"))
    }

    // ==================== ② 关键状态：commit 而非 apply ====================

    /** 某个写入表达式所在行必须是同步提交，且不得出现同表达式的 apply 版本。 */
    private fun assertSynchronousCommit(rel: String, writeExpr: String, expectedCount: Int = 1) {
        val src = child(rel)
        val commitCount = src.lineSequence().count { it.contains(writeExpr) && it.contains(".commit()") }
        assertEquals("$rel 的 `$writeExpr` 必须以 commit 同步落盘", expectedCount, commitCount)
        val asyncCount = src.lineSequence().count { it.contains(writeExpr) && it.contains(".apply()") }
        assertEquals("$rel 的 `$writeExpr` 不能再用 apply 异步落盘", 0, asyncCount)
    }

    @Test
    fun `曾授予账本与下降沿基线一律同步落盘`() {
        assertSynchronousCommit("permission/AuthorizationLedger.kt", "putBoolean(prefixed(key), true)")
        assertSynchronousCommit("guard/PermissionHealthChecker.kt", "putBoolean(everKey, true)", 2)
        assertSynchronousCommit("permission/AuthorizationKeeper.kt", "putStringSet(KEY_RUNTIME_EVER_GRANTED, runtimeEver)")
        assertSynchronousCommit("permission/AuthorizationKeeper.kt", "putBoolean(KEY_DA_EVER_ACTIVE, true)")
        assertSynchronousCommit("guard/UninstallDefenseModule.kt", "putString(KEY_LAST_MODE, mode.name)")
    }

    @Test
    fun `控制模式基线只在变化时写 不产生每拍同步 IO`() {
        val shield = child("guard/UninstallDefenseModule.kt")
        assertTrue(
            "commit 是同步写盘，必须先用值比较挡住每 tick 的无变化写入",
            shield.contains("if (prefs.getString(KEY_LAST_MODE, null) != mode.name)")
        )
    }

    @Test
    fun `每日计时快照与离线起表同为同步提交`() {
        val usage = child("usagesession/UsageSessionManager.kt")
        assertTrue("离线起表快照必须 commit", usage.contains("putBoolean(KEY_PENDING_START, true)") &&
            usage.contains(".commit()"))
        // 日切 8 键块与 reset 的 clear 都必须同步，避免半套 dayKey 或残留旧家庭进度。
        val dayRollover = Regex("""putString\(KEY_DAY, todayKey\)[\s\S]{0,900}?\.commit\(\)""")
            .find(usage)?.value
        assertTrue("日切重置快照必须同步提交", dayRollover != null && dayRollover.contains("KEY_PENDING_START, false"))
        assertTrue("解绑清库必须同步提交", usage.contains("sessionPrefs.edit().clear().commit()"))
        assertFalse("解绑清库不得再异步", usage.contains("sessionPrefs.edit().clear().apply()"))
    }

    @Test
    fun `时间偏移初值同步落盘且保留零值语义`() {
        val time = child("time/TimeOffsetPrefs.kt")
        assertTrue("冷启动初值必须 commit", time.contains("putLong(KEY_OFFSET, offsetMs).commit()"))
        assertFalse("冷启动初值不得再 apply", time.contains("putLong(KEY_OFFSET, offsetMs).apply()"))
        // 0 在 TimeProvider.seedOffset 里是「无可用样本」，不是要把偏移清零；
        // 覆盖成 0 反而会丢掉磁盘上已有的初值。
        assertTrue("必须保留 offsetMs==0 的提前返回", time.contains("if (offsetMs == 0L) return"))
    }
}
