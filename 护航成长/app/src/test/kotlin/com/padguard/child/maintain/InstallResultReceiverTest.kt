package com.padguard.child.maintain

import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InstallResultReceiver 回归测试（R15 · M1 修复）。
 *
 * 钉住的核心语义：安装/卸载结果回调里，只有「终态失败」才允许 abandonSession 清理残留会话；
 * STATUS_PENDING_USER_ACTION（系统等用户确认授权/未知来源弹窗，属**可恢复**安装）绝不能 abandon，
 * 否则会把本可完成的自更新/远程安装变成硬失败。此前 `if (status != SUCCESS) abandon` 会误杀。
 *
 * 决策逻辑抽成纯函数 [InstallResultReceiver.shouldAbandonSession]，故本测试零 Android 依赖、
 * 不需要 mock PackageManager（纯 JVM 单测里该类构造函数抛 Stub!，无法直接 mock）。
 */
class InstallResultReceiverTest {

    private val receiver = InstallResultReceiver()

    @Test
    fun `PENDING_USER_ACTION must NOT abandon (recoverable install protected)`() {
        assertFalse(
            receiver.shouldAbandonSession(PackageInstaller.STATUS_PENDING_USER_ACTION)
        )
    }

    @Test
    fun `STATUS_SUCCESS must NOT abandon`() {
        assertFalse(receiver.shouldAbandonSession(PackageInstaller.STATUS_SUCCESS))
    }

    @Test
    fun `terminal STATUS_FAILURE abandons`() {
        assertTrue(receiver.shouldAbandonSession(PackageInstaller.STATUS_FAILURE))
    }

    @Test
    fun `STATUS_FAILURE_ABORTED abandons`() {
        assertTrue(receiver.shouldAbandonSession(PackageInstaller.STATUS_FAILURE_ABORTED))
    }

    @Test
    fun `STATUS_FAILURE_BLOCKED abandons`() {
        assertTrue(receiver.shouldAbandonSession(PackageInstaller.STATUS_FAILURE_BLOCKED))
    }

    @Test
    fun `STATUS_FAILURE_INVALID abandons`() {
        assertTrue(receiver.shouldAbandonSession(PackageInstaller.STATUS_FAILURE_INVALID))
    }

    @Test
    fun `STATUS_FAILURE_CONFLICT abandons`() {
        assertTrue(receiver.shouldAbandonSession(PackageInstaller.STATUS_FAILURE_CONFLICT))
    }

    @Test
    fun `STATUS_FAILURE_STORAGE abandons`() {
        assertTrue(receiver.shouldAbandonSession(PackageInstaller.STATUS_FAILURE_STORAGE))
    }

    @Test
    fun `STATUS_FAILURE_INCOMPATIBLE abandons`() {
        assertTrue(receiver.shouldAbandonSession(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE))
    }
}
