package com.padguard.child.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UninstallEntryDetector] 的单元测试。
 *
 * 对应 2026-11-02 事故：孩子在**系统桌面长按图标 → 卸载**，这条路径的事件包名是桌面启动器，
 * 旧闸门只认设置页与安装器包名，于是直接放行。本测试同时锁定"必须拦住桌面浮层"
 * 与"绝不能把桌面主页本身当成卸载入口"（后者会导致一按 HOME 就弹密码框、设备不可用）。
 */
class UninstallEntryDetectorTest {

    private val launcher = "com.huawei.android.launcher"
    private val homeClass = "com.huawei.android.launcher.Launcher"

    @Test
    fun `桌面主页本身不拦`() {
        assertFalse(
            "桌面主页必须放行，否则设备一按 HOME 就被弹密码框",
            UninstallEntryDetector.isUninstallSurface(launcher, homeClass, launcher, homeClass)
        )
    }

    @Test
    fun `桌面弹出的长按菜单要拦（本次事故入口）`() {
        assertTrue(
            "长按图标弹出的菜单是桌面包的另一个窗口，必须纳入卸载入口判定",
            UninstallEntryDetector.isUninstallSurface(
                launcher,
                "com.huawei.android.launcher.dragndrop.DragLayer",
                launcher,
                homeClass
            )
        )
    }

    @Test
    fun `类名是全限定与相对名混用时仍认作同一主页`() {
        assertFalse(
            "无障碍事件里的 className 可能是相对名，简单名一致即应视为主页",
            UninstallEntryDetector.isUninstallSurface(
                launcher, ".Launcher", launcher, "com.huawei.android.launcher.Launcher"
            )
        )
    }

    @Test
    fun `系统卸载界面无论哪个包都要拦`() {
        assertTrue(
            "UninstallerActivity 属于卸载确认页，任何包名都应命中",
            UninstallEntryDetector.isUninstallSurface(
                "com.android.packageinstaller",
                "com.android.packageinstaller.UninstallerActivity",
                launcher,
                homeClass
            )
        )
        assertTrue(
            "厂商自定义的卸载进度页同样要拦",
            UninstallEntryDetector.isUninstallSurface(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.UninstallAppProgress",
                launcher,
                homeClass
            )
        )
    }

    @Test
    fun `桌面解析不到时只按类名判，绝不误拦整屏桌面`() {
        assertFalse(
            "拿不到主页类名时无法区分主页与浮层，必须放行，避免把桌面拦死",
            UninstallEntryDetector.isUninstallSurface(launcher, "android.widget.FrameLayout", null, null)
        )
        assertTrue(
            "但明确的卸载界面类名仍然可判",
            UninstallEntryDetector.isUninstallSurface(launcher, "xxx.UninstallDialog", null, null)
        )
    }

    @Test
    fun `非桌面的第三方应用窗口不因包名不同而被误判`() {
        assertFalse(
            "普通应用切窗口不在本判定范围内",
            UninstallEntryDetector.isUninstallSurface(
                "com.tencent.mm", "com.tencent.mm.ui.LauncherUI", launcher, homeClass
            )
        )
    }

    @Test
    fun `空包名或空类名一律放行，避免事件残缺导致误拦`() {
        assertFalse(UninstallEntryDetector.isUninstallSurface("", "x.UninstallerActivity", launcher, homeClass))
        assertFalse(UninstallEntryDetector.isUninstallSurface(launcher, "", launcher, homeClass))
    }
}
