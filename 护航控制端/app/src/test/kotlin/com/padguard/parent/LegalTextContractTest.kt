package com.padguard.parent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 法务文本契约：把「法律文本必须与实际处理活动一致」钉死在代码门禁里。
 *
 * 背景（2026-11-25 法务复核）：v1.0《隐私政策》声称"开发方不运营任何服务器、不接触任何设备数据、
 * 处理者是家长本人"，但项目实际运营官方托管服务并存在可全量读取家长/儿童数据的管理后台，
 * 构成 PIPL 第 17 条项下的不实告知。此类问题单看代码测试发现不了，必须跨文本锁定。
 */
class LegalTextContractTest {

    private fun read(relative: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir: File? = cwd
        repeat(6) {
            if (dir == null) return@repeat
            val candidate = File(dir!!, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir!!.parentFile
        }
        throw AssertionError("找不到法律文本：$relative；cwd=$cwd")
    }

    @Test
    fun `privacy policy must not deny operating servers or contacting data`() {
        val policy = read("隐私政策.md")
        for (falseClaim in listOf(
            "不运营任何服务器",
            "不接收、不存储、不接触",
            "个人信息处理者是家长本人，而非本应用开发方",
            "当前版本在家庭局域网内以明文 HTTP 传输"
        )) {
            assertFalse("隐私政策不得再声称与事实不符的：$falseClaim", falseClaim in policy)
        }
        assertTrue("必须如实披露官方托管形态", "官方托管服务" in policy)
        assertTrue("必须如实披露运营方接触数据的场景与限制", "角色权限控制与操作审计" in policy)
        assertTrue("必须区分两种形态下的处理者", "家长（部署者）是处理者" in policy)
    }

    @Test
    fun `privacy policy states real retention periods and rights paths`() {
        val policy = read("隐私政策.md")
        assertTrue("用量日志保留期必须写明", "30 天" in policy)
        assertTrue("位置与媒体保留期必须写明", "90 天" in policy)
        assertTrue("注销必须如实说明匿名化而非全部物理删除", "匿名化" in policy)
        assertTrue("导出权必须指向已实现的 App 功能",
            "导出个人数据文件（JSON）" in policy)
        assertTrue("撤回同意必须指向已实现的 App 功能",
            "撤回同意" in policy && "数据权利与合规" in policy)
        assertTrue("必须提供可联系邮箱", "support@padguard.com" in policy)
        assertTrue("必须给出响应时限", "15 个工作日" in policy)
    }

    @Test
    fun `independent user agreement exists with mandatory consumer clauses`() {
        val terms = read("用户协议.md")
        for (clause in listOf(
            "年满 18 周岁", "不自动续费", "退款规则", "争议解决", "support@padguard.com"
        )) {
            assertTrue("用户协议缺少必备条款：$clause", clause in terms)
        }
        assertTrue("必须披露软管控能力边界", "不承诺绝对防绕过" in terms)
    }

    @Test
    fun `parent in-app agreement mirrors the real privacy policy`() {
        val ui = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/auth/LoginScreen.kt")
        assertTrue("弹窗必须披露官方托管处理者身份", "官方托管服务由我们运营" in ui)
        assertTrue("弹窗必须给出客服邮箱", "support@padguard.com" in ui)
        assertTrue("弹窗必须披露自建 HTTP 风险而非笼统安全话术", "被嗅探风险" in ui)
        assertTrue("弹窗不得再用'遵循相关安全规范'掩盖事实", "遵循相关安全规范" !in ui)
        assertTrue("用户协议要点必须含用户资格", "年满 18 周岁" in ui)
        assertTrue("用户协议要点必须说明非自动续费", "不自动续费" in ui)
        assertTrue("SDK 披露不得丢失", "高德地图 SDK" in ui && "微信开放平台 SDK" in ui)
    }

    @Test
    fun `billing UI and payment manual match the implemented refund flow`() {
        val ui = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/profile/SubscriptionScreen.kt")
        assertFalse("订阅页不得再说支付渠道接入中", "支付渠道正在接入中" in ui)
        assertTrue("订阅页必须说明非自动续费", "不自动续费" in ui)
        assertTrue("订阅页必须给出未到账的客服路径", "support@padguard.com" in ui)

        val manual = read("微信支付接入操作手册.md")
        assertFalse("手册不得再说无退款 API", "无退款 API 实现" in manual)
        assertTrue("手册必须记载已实现的退款链路", "AdminBillingService.refund" in manual)
    }

    @Test
    fun `disclaimer clauses survive PRC contract and consumer law review`() {
        val terms = read("用户协议.md")
        assertFalse("不得保留英美法式'按现状'免责", "按现状提供服务" in terms)
        assertTrue("必须写清按约定履行", "按照本协议约定" in terms)
        assertTrue(
            "必须写明民法典506条法定边界（故意/重大过失、人身损害不得免责）",
            "因故意或重大过失" in terms && "人身损害" in terms
        )
        assertTrue("第三方原因不得概括免责", "第三方原因不免责" in terms)
        assertTrue("必须载明定位非紧急救援依据", "110 / 120 / 119" in terms)
        assertTrue("必须提示采集涉及在场第三人隐私", "在场第三人" in terms)
        assertTrue("必须提示数据不永久保存", "我们不承诺数据永久保存" in terms)
        assertTrue("必须写明服务整体终止的导出窗口", "服务整体终止" in terms)
        for (clause in listOf("通知与送达", "完整协议与优先顺序", "可分性", "权利保留", "转让与承继")) {
            assertTrue("缺少合同健壮性条款：$clause", clause in terms)
        }
    }

    @Test
    fun `in-app legal text is complete versioned and decidable`() {
        val ui = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/auth/LoginScreen.kt")
        assertTrue("必须提供用户协议全文入口", "查看《用户协议》完整全文" in ui)
        assertTrue("必须提供隐私政策全文入口", "查看《隐私政策》完整全文" in ui)
        assertTrue("必须展示两份文本的版本号", "v1.1" in ui && "v2.0" in ui)
        assertTrue("必须展示生效日期", "2026-11-25 起生效" in ui)
        assertTrue("必须提供不同意退路", "不同意" in ui)
        assertFalse("不得再声称全文在'随包'却不可达", "完整条款见随包" in ui)
        assertTrue("要点必须含保存期限", "30 天" in ui && "90 天" in ui)
        assertTrue("要点必须提示单独同意入口", "不满 14 周岁" in ui)

        val termsAsset = read("护航控制端/app/src/main/assets/legal/terms-of-service.md")
        val policyAsset = read("护航控制端/app/src/main/assets/legal/privacy-policy.md")
        assertTrue("随包《用户协议》必须与仓库原文一致", termsAsset.trim() == read("用户协议.md").trim())
        assertTrue("随包《隐私政策》必须与仓库原文一致", policyAsset.trim() == read("隐私政策.md").trim())
    }

    @Test
    fun `consent state tracks legal version and supports real withdrawal`() {
        val prefs = read("护航控制端/app/src/main/java/com/padguard/parent/di/PrivacyConsentPrefs.kt")
        assertTrue("同意版本必须能区分政策v2.0", "CURRENT_POLICY_VERSION = \"2.0.0\"" in prefs)
        assertTrue("必须提供真实撤回实现", "fun revoke()" in prefs)

        val vm = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/viewmodel/LoginViewModel.kt")
        assertTrue("ViewModel 必须提供撤回入口", "fun declinePolicies()" in vm)

        val ui = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/auth/LoginScreen.kt")
        assertTrue("取消勾选必须真实撤回而非只改UI", "else viewModel.declinePolicies()" in ui)
    }

    @Test
    fun `permission UI must only expose permissions the child app declares`() {
        val ui = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/device/DeviceAdminSheets.kt")
        assertTrue("电话权限开关必须移除", "READ_PHONE_STATE" !in ui)
        assertTrue("通讯录权限开关必须移除", "READ_CONTACTS" !in ui)
        assertTrue("存储权限必须标注系统版本限制", "仅 Android 12 及以下" in ui)

        val manifest = read("护航成长/app/src/main/AndroidManifest.xml")
        assertTrue(
            "孩子端不得声明电话权限",
            "android:name=\"android.permission.READ_PHONE_STATE\"" !in manifest
        )
        assertTrue(
            "孩子端不得声明通讯录权限",
            "android:name=\"android.permission.READ_CONTACTS\"" !in manifest
        )
    }

    @Test
    fun `deletion and correction wording match implemented rights`() {
        val screen = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/profile/DataRightsScreen.kt")
        assertFalse("不得再宣称账号被不可逆清除", "账号将被不可逆清除" in screen)
        assertTrue("注销文案必须说明匿名化", "账号匿名化" in screen)
        assertTrue("注销文案必须说明订单与审计保留", "支付订单与审计记录依法保留" in screen)
        assertFalse("UI 文案不得残留 Markdown 星号", "**不是实名年龄核验**" in screen)

        val policy = read("隐私政策.md")
        assertTrue("更正路径必须指向真实可达的客服渠道", "邮件 support@padguard.com" in policy)
        val deviceScreen = read("护航控制端/app/src/main/java/com/padguard/parent/presentation/ui/device/DeviceManageScreen.kt")
        assertTrue("重命名设备入口必须真实存在", "renameDevice" in deviceScreen)
    }
}
