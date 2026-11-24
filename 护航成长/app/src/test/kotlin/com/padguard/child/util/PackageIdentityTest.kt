package com.padguard.child.util

import com.padguard.child.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isOwnPackage] 的纯 JVM 单测。
 *
 * 锁两条红线：
 * 1. 严禁前缀绕过 —— 守护是单 APK、单包名，只允许精确相等，任何「前缀延伸」形态的
 *    第三方包名都必须判为非自身，否则会逃过应用锁 / 时段管控 / 防卸载；
 * 2. 自身包名必须随构建变体 —— debug 带 `applicationIdSuffix=".debug"`，写死 release
 *    包名会让自家授权页被当成第三方拦截（真机表现：实时看屏永远无画面）。
 */
class PackageIdentityTest {

    @Test
    fun `当前构建变体的 applicationId 判为自身`() {
        assertTrue(isOwnPackage(BuildConfig.APPLICATION_ID))
    }

    @Test
    fun `debug 变体包名判为自身_否则屏幕采集授权页被自家拦截`() {
        // 回归：写死 "com.padguard.child" 时此断言在 debug 变体必红。
        // 真机症状：CapturePermissionActivity 被 GLOBAL_ACTION_HOME 顶回桌面 →
        // MediaProjection 系统授权框活不过 0.2 秒 → 家长端实时看屏一帧都收不到。
        if (BuildConfig.APPLICATION_ID.endsWith(".debug")) {
            assertTrue(isOwnPackage("com.padguard.child.debug"))
        } else {
            assertTrue(isOwnPackage("com.padguard.child"))
        }
    }

    @Test
    fun `非当前变体的包不得借自身身份免拦截`() {
        val other = if (BuildConfig.APPLICATION_ID.endsWith(".debug")) {
            "com.padguard.child"
        } else {
            "com.padguard.child.debug"
        }
        assertFalse(isOwnPackage(other))
    }

    @Test
    fun `当前变体包名的前缀延伸仍判为非自身_安全红线不回退`() {
        val own = BuildConfig.APPLICATION_ID
        assertFalse(isOwnPackage("$own.evil"))
        assertFalse(isOwnPackage("${own}1"))
    }

    @Test
    fun `前缀延伸带点的恶意包名必须判为非自身_关闭越权缺口`() {
        // 历史实现用 startsWith("com.padguard.child")，此包会被误判为自身。
        assertFalse(isOwnPackage("com.padguard.child.evil"))
        assertFalse(isOwnPackage("com.padguard.child.fake.lockbypass"))
    }

    @Test
    fun `前缀延伸不带分隔点的恶意包名也必须判为非自身`() {
        // com.padguard.child1 同样满足裸 startsWith，仍属越权缺口。
        assertFalse(isOwnPackage("com.padguard.child1"))
        assertFalse(isOwnPackage("com.padguard.childx"))
    }

    @Test
    fun `同组织其它模块包名判为非自身`() {
        assertFalse(isOwnPackage("com.padguard.core.transport"))
        assertFalse(isOwnPackage("com.padguard.parent"))
    }

    @Test
    fun `普通三方应用判为非自身`() {
        assertFalse(isOwnPackage("com.tencent.mm"))
        assertFalse(isOwnPackage("com.android.settings"))
    }

    @Test
    fun `大小写与空串均判为非自身_精确相等不含糊`() {
        assertFalse(isOwnPackage("COM.PADGUARD.CHILD"))
        assertFalse(isOwnPackage("com.padguard.Child"))
        assertFalse(isOwnPackage(""))
        assertFalse(isOwnPackage("com.padguard.child."))
    }
}
