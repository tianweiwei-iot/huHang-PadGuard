package com.padguard.child.hidden

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 契约锁：「家长密码正确 → 退出并卸载」必须真的能把管控系统卸掉。
 *
 * 2026-11-18 现场：家长输对密码后点「退出并卸载」，系统卸载页要么不出现、要么卡在确认页，
 * 管控系统卸不掉。根因是两层同时失效：
 * ① 旧 ExitController 只 `setUninstallBlocked(self,false)` + `removeActiveAdmin()`，
 *    对 **Device Owner** 设备无效（DO 只能 `clearDeviceOwnerApp`），且没清
 *    `DISALLOW_UNINSTALL_APPS` 等用户限制与 Kiosk 锁屏；
 * ② 悬浮卡密码验证只设了 `unlocked=true`，没开 `GuardPasswordManager.unlock()` 的
 *    设备操作窗口，`OperationGuardAccessibilityService` 把系统卸载确认页判为
 *    REASON_INSTALLER，直接 HOME 顶回并弹密码框 —— 表现为"密码对了却没反应"。
 *
 * 这组断言锁死修复形状，任何人回退到"只解封"或删掉解锁窗，测试立刻变红。
 */
class ExitUninstallContractTest {

    @Test
    fun `卸载前必须完整释放管控 而不是只解封再 removeActiveAdmin`() {
        val src = readText(
            "app/src/main/kotlin/com/padguard/child/hidden/ExitController.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/hidden/ExitController.kt"
        )
        assertTrue(
            "必须复用权威释放入口 releaseControl()（清 DO / 用户限制 / Kiosk）",
            src.contains("admin.releaseControl()")
        )
        assertFalse(
            "removeActiveAdmin 对 Device Owner 无效，禁止回退到旧调用（注释里复盘说明不算）",
            src.contains("dpm.removeActiveAdmin(")
        )
        assertTrue(
            "释放后必须立刻重探权限模式，防止缓存仍是 DO 时策略自检重压封锁",
            src.contains("admin.refreshControlMode()")
        )
        assertTrue("必须拉起系统卸载页", src.contains("Intent.ACTION_UNINSTALL_PACKAGE"))
    }

    @Test
    fun `释放流程必须清掉 DO 与全部卸载相关限制`() {
        val src = readText(
            "core/engine/src/main/kotlin/com/padguard/core/engine/admin/DeviceAdminBridge.kt",
            "护航成长/core/engine/src/main/kotlin/com/padguard/core/engine/admin/DeviceAdminBridge.kt"
        )
        assertTrue(
            "DO 只能用 clearDeviceOwnerApp 释放，否则系统层依旧禁止卸载自身",
            src.contains("dpm.clearDeviceOwnerApp(context.packageName)")
        )
        assertTrue("必须解除自身卸载封锁", src.contains("setUninstallBlocked(pkg, false)"))
        assertTrue(
            "必须清 DISALLOW_UNINSTALL_APPS，否则连 DPC 自己的卸载意图一并被拦",
            src.contains("UserManager.DISALLOW_UNINSTALL_APPS")
        )
        assertTrue(
            "必须清 DISALLOW_APPS_CONTROL（SelfProtectionModule 下的强停/清数据置灰）",
            src.contains("UserManager.DISALLOW_APPS_CONTROL")
        )
        assertTrue("relinquish 后必须刷新缓存的控制模式", src.contains("refreshControlMode()"))
    }

    @Test
    fun `密码验证通过后必须先开设备操作窗口再执行卸载`() {
        val src = readText(
            "app/src/main/kotlin/com/padguard/child/hidden/FloatingActionActivity.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/hidden/FloatingActionActivity.kt"
        )
        assertTrue("卸载分支必须开 10 分钟设备操作窗口", src.contains("guard.unlock()"))
        val unlockAt = src.indexOf("guard.unlock()")
        val exitAt = src.indexOf("onExit()")
        assertTrue("源码里必须存在 onExit() 调用", exitAt >= 0)
        assertTrue(
            "unlock() 必须先于卸载执行，否则卸载确认页会被无障碍闸门顶回",
            unlockAt >= 0 && unlockAt < exitAt
        )
    }

    @Test
    fun `卸载前必须收起锁屏 否则系统确认页点不到`() {
        val src = readText(
            "app/src/main/kotlin/com/padguard/child/hidden/ExitController.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/hidden/ExitController.kt"
        )
        assertTrue(src.contains("lockController.releaseAll()"))
        assertTrue(src.contains("lockScreenPresenter.dismiss()"))
    }

    private fun readText(vararg candidates: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isFile) return direct.readText().replace("\r\n", "\n")
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir, relative)
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到源码文件：${candidates.joinToString()}；cwd=$cwd")
    }
}
