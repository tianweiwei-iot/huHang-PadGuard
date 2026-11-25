package com.padguard.parent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 家长端隐私政策「显式同意」合规门禁。
 *
 * 背景：国内应用市场（华为/小米/OPPO/vivo/应用宝）对未成年人相关产品审核尤严，
 * 「登录即表示同意」属默示同意，会被判未取得有效同意而拒绝上架。
 * 修复后在登录页加入主动勾选框 + ViewModel 统一门槛 + 协议版本留痕。
 * 这类合规要求容易被"简化 UI"时无意回退，故用源码契约钉死整条链：
 *   Checkbox → agreeToPolicies → consentGate → 三个登录入口 → 持久化 prefs → SDK 披露。
 */
class PrivacyConsentContractTest {

    private fun read(relative: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir: File? = cwd
        repeat(6) {
            if (dir == null) return@repeat
            val candidate = File(dir!!, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir!!.parentFile
        }
        throw AssertionError("找不到源码文件：$relative；cwd=$cwd")
    }

    @Test
    fun `login screen uses explicit checkbox not implied consent`() {
        val ui = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/auth/LoginScreen.kt")
        assertTrue("登录页必须有 Checkbox", "Checkbox(" in ui)
        assertTrue("必须有主动勾选文案", "我已阅读并同意" in ui)
        // 只查实际 UI 文案，不查注释（注释里恰当地解释了为什么弃用默示同意）
        val codeOnly = ui.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse("禁止默示同意文案", "登录即表示同意" in codeOnly)
        assertTrue("协议正文可点击查看", "showAgreement = true" in ui)
        assertTrue("同意弹窗提供直接勾选入口", "onAgree = { viewModel.agreeToPolicies() }" in ui)
    }

    @Test
    fun `all login and register entries gated by consent`() {
        val vm = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/viewmodel/LoginViewModel.kt")
        assertTrue("必须有统一同意门槛", "private fun consentGate" in vm)
        for (entry in listOf("fun register()", "fun loginWithPassword()", "fun loginWithSms()")) {
            val idx = vm.indexOf(entry)
            assertTrue("缺少入口 $entry", idx >= 0)
            val body = vm.substring(idx, minOf(idx + 160, vm.length))
            assertTrue("$entry 必须先过 consentGate", "if (!consentGate(state)) return" in body)
        }
        // 发码同样会把手机号发给服务端，是第 4 个必须过闸门的入口（历史漏项）。
        val smsIdx = vm.indexOf("fun sendSmsCode")
        assertTrue("缺少入口 sendSmsCode", smsIdx >= 0)
        val smsBody = vm.substring(smsIdx, minOf(smsIdx + 260, vm.length))
        assertTrue(
            "sendSmsCode 必须先过 consentGate，否则未勾选也能把手机号发给服务端",
            "if (!consentGate(_uiState.value)) return" in smsBody
        )
        assertTrue("同意状态持久化", "consentPrefs.agree()" in vm)
    }

    @Test
    fun `logged in user with stale consent is forced back to login`() {
        val nav = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/PadGuardNavigation.kt")
        assertTrue("导航必须读取协议同意状态", "PrivacyConsentPrefs.isAgreed" in nav)
        assertTrue(
            "已登录但协议版本失效时必须落登录页，不能直接进主页",
            "user != null && consentAgreed" in nav && "user == null || !consentAgreed" in nav
        )
    }

    @Test
    fun `amap sdk only declares consent after the user actually agrees`() {
        val app = read("护航控制端/app/src/main/java/com/padguard/parent/PadGuardApplication.kt")
        val codeOnly = app.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse(
            "Application 不得无条件 updatePrivacyAgree(true)（未同意先处理）",
            "updatePrivacyAgree(this, true)" in codeOnly
        )
        assertTrue(
            "启动时按真实同意状态同步 SDK",
            "AmapPrivacyCompliance.sync(this, consentPrefs.isAgreed())" in app
        )
        val amap = read("护航控制端/app/src/main/java/com/padguard/parent/compliance/AmapPrivacyCompliance.kt")
        assertTrue("SDK 同意状态必须由入参决定", "updatePrivacyAgree(context, agreed)" in amap)
        val prefs = read("护航控制端/app/src/main/java/com/padguard/parent/di/PrivacyConsentPrefs.kt")
        assertTrue("同意时置 true", "sync(application, agreed = true)" in prefs)
        assertTrue("撤回时置 false", "sync(application, agreed = false)" in prefs)
    }

    @Test
    fun `consent prefs stores version and timestamp only`() {
        val prefs = read("护航控制端/app/src/main/java/com/padguard/parent/di/PrivacyConsentPrefs.kt")
        assertTrue("记录隐私政策版本", "KEY_POLICY_VERSION" in prefs)
        assertTrue("记录用户协议版本", "KEY_TERMS_VERSION" in prefs)
        assertTrue("记录同意时间", "KEY_AGREED_AT" in prefs)
        assertTrue("版本升级需重新同意", "CURRENT_POLICY_VERSION" in prefs)
        assertTrue("协议版本独立比对", "CURRENT_TERMS_VERSION" in prefs)
        // 合规留痕不得夹带任何个人信息
        assertFalse("同意存储不得包含手机号", "phone" in prefs.lowercase())
        assertFalse("同意存储不得包含密码", "password" in prefs.lowercase())
    }

    @Test
    fun `privacy policy discloses third party sdk`() {
        val ui = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/auth/LoginScreen.kt")
        assertTrue("必须披露高德 SDK", "高德地图 SDK" in ui)
        assertTrue("必须披露微信支付 SDK", "微信开放平台 SDK" in ui)
        assertTrue("必须声明无广告统计类 SDK", "未集成广告、统计" in ui)
        assertTrue("必须提供自助数据权利入口说明", "数据权利" in ui)
    }
}
