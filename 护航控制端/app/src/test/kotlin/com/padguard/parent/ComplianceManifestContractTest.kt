package com.padguard.parent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 应用市场审核硬项的**清单快照**：Manifest 一旦被人"顺手加个权限/导出组件"就红。
 *
 * 这些项单看代码测试发现不了，但审核工具会逐条扫：
 * - `allowBackup` 必须 false（JWT 与账号历史不能随云备份导出）；
 * - 权限集合必须与《隐私政策》一致（家长端不申请定位/通讯录/电话）；
 * - 导出组件只能有启动页与微信支付回调（后者是微信硬性要求）；
 * - 不得出现 debuggable。
 *
 * 不把 `usesCleartextTraffic=true` 判为违规：局域网私有化部署确需 http，
 * 三道防线（构建期/保存时/发请求前）已在隐私政策与代码注释中如实披露。
 */
class ComplianceManifestContractTest {

    private fun read(relative: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir: File? = cwd
        repeat(6) {
            if (dir == null) return@repeat
            val candidate = File(dir!!, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir!!.parentFile
        }
        throw AssertionError("找不到文件：$relative；cwd=$cwd")
    }

    private val manifest: String
        get() = read("护航控制端/app/src/main/AndroidManifest.xml")

    @Test
    fun `parent manifest keeps backup disabled and exposes only expected components`() {
        val text = manifest
        assertTrue("allowBackup 必须为 false", "android:allowBackup=\"false\"" in text)
        assertFalse("不得出现 android:debuggable", "android:debuggable" in text)
        assertEquals(
            "导出组件只允许启动页与微信支付回调（微信硬性要求）",
            2,
            Regex("""android:exported="true"""").findAll(text).count()
        )
        assertTrue("启动页必须存在", ".presentation.MainActivity" in text)
        assertTrue("微信回调页必须存在", ".wxapi.WXPayEntryActivity" in text)
    }

    @Test
    fun `parent permissions stay minimal`() {
        val declared = Regex("""uses-permission\s+android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "家长端权限集合被改动：新增权限必须同步用途说明、运行时申请与隐私政策权限表",
            setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.POST_NOTIFICATIONS"
            ),
            declared
        )
        for (forbidden in listOf(
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
            "READ_CONTACTS", "READ_PHONE_STATE", "READ_SMS"
        )) {
            assertFalse("家长端不得申请 $forbidden", declared.any { it.endsWith(forbidden) })
        }
    }

    @Test
    fun `privacy policy discloses every special capability the child app declares`() {
        val policy = read("隐私政策.md")
        assertTrue("政策版本必须已升到 v2.1", "**版本**：v2.1" in policy)
        for (capability in listOf(
            "通知使用权", "无障碍服务", "VPN（仅本地）", "安装未知应用",
            "精确闹钟", "开机自启", "全屏通知", "结束后台进程",
            "NFC", "WRITE_SECURE_SETTINGS"
        )) {
            assertTrue("隐私政策缺少特殊权限披露：$capability", capability in policy)
        }
        assertTrue("通知采集必须写明默认关闭", "通知标题（可选，默认关闭）" in policy)
        assertTrue("通知采集必须写明不读正文", "不读取、不上传通知正文" in policy)
        assertTrue("无障碍必须写明不读界面文本", "不读取界面文本" in policy)

        val rights = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/profile/DataRightsScreen.kt")
        assertTrue(
            "不满14周岁单独同意的文案版本必须与当前政策版本一致",
            "2026-11-25-v2.1" in rights
        )
        assertFalse("不得残留旧版本文案", "2026-11-23-v1" in rights)
    }
}
