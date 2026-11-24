package com.padguard.child.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「权限判定单一事实源」的横切契约回归（纯 JVM + 源码扫描）。
 *
 * ## 为什么需要跨文件契约而不是普通单测
 * 「使用情况访问」这一项曾经在 app 层有 **4 份**判定：
 * [SpecialPermission.USAGE_STATS]（Q+ 用 `unsafeCheckOpNoThrow` + `applicationInfo.uid`）、
 * `ProfileViewModel.isUsageStatsGranted()`、`GuardInfraProbeImpl.isUsageAccessGranted()`
 * （后两者用已废弃的 `checkOpNoThrow` + `Process.myUid()`）、
 * `ForegroundAppMonitor.hasPermission()`（Q 分叉正确但仍自写一份）。
 * 单看每个文件都没错，合起来却让「我的」页同屏出现
 * 「授权总览：全部已开启」与「安全状态：未授权 · 无法拦截」，
 * 并让 TamperDetector 上报一条恢复不了的假 `USAGE_ACCESS_LOST` 告警。
 * 这类缺陷只有横切比对才能发现，也只有源码扫描能防止它长回来。
 *
 * 另锁死 5 分钟快检与 [SpecialPermission] 目录的全量对齐：
 * 新增一项权限若忘了同步自检集合，本测试会直接红。
 */
class PermissionSingleSourceContractTest {

    // ==================== ① 使用情况访问：只允许一份系统查询 ====================

    @Test
    fun `app 层除目录外不得再手写 OPSTR_GET_USAGE_STATS 查询`() {
        val dir = sourceDir()
        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "SpecialPermission.kt" }
            .filter { it.readText().contains("OPSTR_GET_USAGE_STATS") }
            .map { it.name }
            .toList()
        assertTrue(
            "发现绕过单一事实源的使用情况访问探测：$offenders。" +
                "同一权限出现两套 AppOps 口径会让首页自相矛盾、家长端收到恢复不了的假告警；" +
                "请统一改为 SpecialPermission.USAGE_STATS.isGranted(context)",
            offenders.isEmpty()
        )
    }

    @Test
    fun `首页与篡改探针与前台探测一律委托单一事实源`() {
        val expected = "SpecialPermission.USAGE_STATS.isGranted(context)"
        listOf(
            "ui/profile/ProfileViewModel.kt",
            "guard/GuardInfraProbeImpl.kt",
            "monitor/ForegroundAppMonitor.kt"
        ).forEach { relative ->
            val source = readText(relative)
            assertTrue(
                "$relative 没有委托 [SpecialPermission.USAGE_STATS.isGranted]，" +
                    "会重新引入第二套权限口径",
                source.contains(expected)
            )
        }
    }

    @Test
    fun `电池优化白名单也不留第二份手写查询`() {
        val profile = readText("ui/profile/ProfileViewModel.kt")
        assertTrue(
            "电池优化白名单应委托 SpecialPermission.BATTERY_OPTIMIZATION，" +
                "保留手写查询意味着以后改一处必漏另一处",
            profile.contains("SpecialPermission.BATTERY_OPTIMIZATION.isGranted(context)")
        )
        val offenders = sourceDir().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "SpecialPermission.kt" }
            .filter { it.readText().contains("isIgnoringBatteryOptimizations(") }
            .map { it.name }
            .toList()
        assertTrue("发现绕过单一事实源的电池优化查询：$offenders", offenders.isEmpty())
    }

    // ==================== ② 5 分钟快检与目录全量对齐 ====================

    @Test
    fun `可检测且参与自愈的授权项全部纳入 5 分钟快检或有独立自检`() {
        val checker = readText("guard/PermissionHealthChecker.kt")
        val block = checker
            .substringAfter("private val SPECIAL_PERMS_TO_MONITOR = listOf(", "")
            .substringBefore(")")
        assertTrue("找不到 SPECIAL_PERMS_TO_MONITOR 定义，源码结构已变", block.isNotBlank())

        val monitored = SpecialPermission.values()
            .filter { block.contains("SpecialPermission.${it.name}") }
            .toSet()

        // 这两项有自己独立的 checkXxx（含 a11y 三态与 overlay 观察窗），
        // 重复进 MONITOR 会触发双重告警。
        val independent = setOf(SpecialPermission.OVERLAY, SpecialPermission.ACCESSIBILITY)
        val expected = SpecialPermission.values()
            .filter { it.detectable && it.requiredForReauth }
            .toSet() - independent

        assertEquals(
            "快检集合与授权目录不对齐：$expected 中缺 ${expected - monitored}，" +
                "多出 ${monitored - expected}。目录新增权限必须同步 checkOtherSpecialCapabilities，" +
                "否则掉失检测从 5 分钟退化到 30 分钟且恢复闭环缺项",
            expected,
            monitored
        )
        // INSTALL_PACKAGES 曾是本集合唯一漏掉的一项（非 DO 设备远程安装静默失效）。
        assertTrue(SpecialPermission.INSTALL_PACKAGES in monitored)
        // OEM_BACKGROUND 无查询接口，混进来会每轮误报。
        assertFalse(SpecialPermission.OEM_BACKGROUND in monitored)
    }

    @Test
    fun `独立自检存在且掉失键由快检集合自动派生`() {
        val checker = readText("guard/PermissionHealthChecker.kt")
        assertTrue(
            "OVERLAY 的独立自检消失后不能从 MONITOR 移除它",
            checker.contains("private suspend fun checkOverlay()")
        )
        assertTrue(
            "ACCESSIBILITY 的独立自检消失后不能从 MONITOR 移除它",
            checker.contains("private suspend fun checkAccessibility()")
        )
        assertTrue(
            "SPECIAL_DROPPED_KEYS 必须由 MONITOR 派生，否则新增权限的告警恢复闭环会缺项",
            checker.contains("SPECIAL_PERMS_TO_MONITOR.map { it.key }")
        )
        assertTrue(
            "快检循环必须用 appliesTo 三闸门过滤，否则 DO 下 INSTALL_PACKAGES 会误报",
            checker.contains("if (!perm.applies(context, hasOwner)) continue")
        )
    }

    @Test
    fun `EMUI 10 非 DO 纳入安装未知应用 DO 下自动剔除不会误报`() {
        val perm = SpecialPermission.INSTALL_PACKAGES
        assertTrue(
            "目标机型（EMUI 10 / API 29 / 非 DO）适用本项，漏检会让远程安装静默失效",
            perm.appliesTo(29, RomSettings.Vendor.HUAWEI, false)
        )
        assertFalse(
            "DO/PO 下本项不适用（走 PackageInstaller 静默安装），纳入快检必须被三闸门剔除",
            perm.appliesTo(29, RomSettings.Vendor.HUAWEI, true)
        )
    }

    // ==================== 工具 ====================

    /** 单测 cwd 可能是模块目录或仓库根，逐级向上找，避免 IDE / 命令行差异造成假红。 */
    private fun sourceDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            "src/main/kotlin/com/padguard/child",
            "护航成长/app/src/main/kotlin/com/padguard/child"
        )
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isDirectory) return direct
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir!!, relative)
                if (candidate.isDirectory) return candidate
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到孩子端源码目录：${candidates.joinToString()}；cwd=$cwd")
    }

    private fun readText(relativeUnderChild: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            "src/main/kotlin/com/padguard/child/$relativeUnderChild",
            "护航成长/app/src/main/kotlin/com/padguard/child/$relativeUnderChild"
        )
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isFile) return direct.readText()
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir!!, relative)
                if (candidate.isFile) return candidate.readText()
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到源码文件：$relativeUnderChild；cwd=$cwd")
    }
}
