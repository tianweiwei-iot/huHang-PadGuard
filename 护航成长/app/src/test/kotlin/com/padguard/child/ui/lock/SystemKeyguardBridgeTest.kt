package com.padguard.child.ui.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 [SystemKeyguardBridge.isSystemLockAllowed] 的纯函数决策：
 * 设备带安全凭据（PIN/图案/密码）时必须禁止压系统锁，否则解锁会陷入
 * 「应用撤不掉系统锁、回不去桌面」的永久死锁（非 DO 形态的平台硬边界）。
 */
class SystemKeyguardBridgeTest {

    @Test
    fun `secure keyguard must forbid pressing system lock`() {
        assertFalse(
            "设备带安全锁时禁止下压系统锁，规避解锁死锁",
            SystemKeyguardBridge.isSystemLockAllowed(keyguardSecure = true)
        )
    }

    @Test
    fun `non-secure keyguard allows pressing system lock`() {
        assertTrue(
            "滑动锁/无锁设备允许下压系统锁（撤锁无需凭据，无死锁风险）",
            SystemKeyguardBridge.isSystemLockAllowed(keyguardSecure = false)
        )
    }
}
