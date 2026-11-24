package com.padguard.child.permission

import com.padguard.core.data.model.policy.ManagementMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 硬 / 软管控并行、用户自选的契约回归（纯 JVM）。
 *
 * 锁死四件真机可感知的事：
 * 1. 存储值映射不认脏值（"hard" / "DEVICE_OWNER" 不能悄悄变成某一档）；
 * 2. 装机首启二选一必须排在 DO 预置之前，且不再有"按构建类型强制 DO"的旁路；
 * 3. 硬管控系统级增强必须同时满足"家长选了硬管控"与"设备确实是 DO"两道闸门；
 * 4. 切换模式只改一个键，解绑不清模式，两档配置互不清除。
 */
class ManagementModeContractTest {

    @Test
    fun `存储值映射只接受 HARD 和 SOFT 且脏值不误判`() {
        assertEquals(ManagementMode.HARD, ManagementMode.fromStorage("HARD"))
        assertEquals(ManagementMode.SOFT, ManagementMode.fromStorage("SOFT"))
        assertNull(ManagementMode.fromStorage(null))
        assertNull(ManagementMode.fromStorage("hard"))
        assertNull(ManagementMode.fromStorage(""))
        // 不能把设备权限等级当成用户选择
        assertNull(ManagementMode.fromStorage("DEVICE_OWNER"))
    }

    @Test
    fun `两档存储值互不重叠且标签稳定`() {
        assertEquals(2, ManagementMode.values().size)
        assertEquals("硬管控", ManagementMode.HARD.label)
        assertEquals("软管控", ManagementMode.SOFT.label)
        assertNotEquals(ManagementMode.HARD.storageValue, ManagementMode.SOFT.storageValue)
    }

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
        throw AssertionError("找不到源码文件：${candidates.joinToString()}；cwd=$cwd")
    }

    private fun child(rel: String) = readText(
        "app/src/main/kotlin/com/padguard/child/$rel",
        "src/main/kotlin/com/padguard/child/$rel",
        "护航成长/app/src/main/kotlin/com/padguard/child/$rel"
    )

    private fun repository() = readText(
        "core/data/src/main/kotlin/com/padguard/core/data/repository/AuthRepository.kt",
        "../core/data/src/main/kotlin/com/padguard/core/data/repository/AuthRepository.kt",
        "护航成长/core/data/src/main/kotlin/com/padguard/core/data/repository/AuthRepository.kt"
    )

    @Test
    fun `首启二选一必须排在 DO 预置之前且不再按构建类型强制`() {
        val main = child("MainActivity.kt")
        val choice = main.indexOf("managementState?.mode == null -> ManagementModeChoiceScreen()")
        val doGate = main.indexOf("hardSelected && !isDeviceOwner && doPromptAck != true -> DoProvisioningScreen()")
        assertTrue("缺少装机首启二选一分流", choice >= 0)
        assertTrue("DO 预置必须只在家长选了硬管控时出现", doGate >= 0)
        assertTrue("二选一必须排在 DO 预置之前", choice < doGate)
        assertFalse("不能再有按构建类型强制 DO 的旁路", main.contains("isRelease && !isDeviceOwner"))
    }

    @Test
    fun `硬管控闸门必须同时检查家长选择与设备 DO 身份`() {
        val enforcer = child("guard/DeviceOwnerEnforcer.kt")
        assertTrue(
            "闸门必须是「家长选硬管控 AND 设备是 DO」",
            enforcer.contains("val isDo = hardControlEnabled && admin.isDeviceOwner()")
        )
        assertTrue("必须订阅家长选择，切档即时生效", enforcer.contains("authRepository.managementMode"))
        assertTrue("只有 HARD 才启用硬管控增强", enforcer.contains("mode == ManagementMode.HARD"))
        assertTrue("切到软管控必须归还已回收权限", enforcer.contains("releasePermissions()"))
    }

    @Test
    fun `切换模式只改一个键且解绑不清除模式选择`() {
        val repo = repository()
        val setter = Regex("""suspend fun setManagementMode\([\s\S]*?\n    \}""").find(repo)?.value
            ?: throw AssertionError("找不到 setManagementMode")
        assertTrue("切换只应写 MANAGEMENT_MODE", setter.contains("Keys.MANAGEMENT_MODE"))
        assertFalse("切换不得清除另一档配置", setter.contains("remove("))
        assertFalse("切换不得整表清空", setter.contains("clear()"))

        val unbind = Regex("""suspend fun clearDeviceBinding\([\s\S]*?\n    \}""").find(repo)?.value
            ?: throw AssertionError("找不到 clearDeviceBinding")
        assertFalse("管控模式是设备级选择，解绑不应重置", unbind.contains("MANAGEMENT_MODE"))
    }

    @Test
    fun `切回硬管控必须撤销旧的 DO 跳过标记`() {
        // 旧 ack 是"这次不预置 DO 了"。家长后来在「我的」切回硬管控时若不撤销，
        // 入口分流会直接跳过预置页：选了硬管控却设备非 DO、增强又被双闸门拦住。
        val clear = "if (mode == ManagementMode.HARD) authRepository.clearDeviceOwnerPromptAck()"
        assertTrue(
            "首启二选一选硬管控时需清 ack",
            child("permission/ManagementModeChoiceViewModel.kt").contains(clear)
        )
        assertTrue(
            "「我的」页切硬管控时需清 ack",
            child("ui/profile/ProfileViewModel.kt").contains(clear)
        )
        assertTrue(
            "仓库需提供清 ack 方法",
            repository().contains("suspend fun clearDeviceOwnerPromptAck()")
        )
    }

    @Test
    fun `所有者权限回补必须受管控模式闸门约束`() {
        val main = child("MainActivity.kt")
        assertTrue(
            "PO 与 DO 一样具备所有者级权限，不能被判成永远未预置",
            main.contains("controlMode == ControlMode.PROFILE_OWNER")
        )
        val backfill = main.substringAfter("LaunchedEffect(controlMode, hardSelected)")
            .substringBefore("// 已登录且已绑定")
        assertTrue("硬管控分支应施加加固基线", backfill.contains("permissionGranter.grantAll()"))
        val softBranch = backfill.substringAfter("} else {", "")
        assertTrue(
            "软管控分支只补运行时权限，不得绕过家长选择施加固基线",
            softBranch.contains("permissionGranter.ensureGranted()") &&
                !softBranch.contains("grantAll()")
        )
    }

    @Test
    fun `DO 引导页降级入口落软管控而非旧的带风险继续`() {
        val vm = child("permission/DoProvisioningViewModel.kt")
        assertTrue(vm.contains("suspend fun fallbackToSoft()"))
        assertTrue("降级必须落软管控", vm.contains("setManagementMode(ManagementMode.SOFT)"))
        assertFalse("旧的强风险放行入口已被替换", vm.contains("suspend fun ackSkip()"))

        val screen = child("permission/DoProvisioningScreen.kt")
        assertTrue(screen.contains("vm.fallbackToSoft()"))
    }
}
