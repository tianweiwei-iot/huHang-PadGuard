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
}
