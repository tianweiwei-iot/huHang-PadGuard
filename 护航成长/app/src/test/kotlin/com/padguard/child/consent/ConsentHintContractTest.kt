package com.padguard.child.consent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 授权过程提示语义的契约回归（纯 JVM 源码扫描）。
 *
 * 真机反馈"授权过程中一直报错"的根因不是哪一步真的失败，而是早期实现把
 * 「已跳过某项」「正在确认开关」「未检测到状态变化」这些**正常路径**文案
 * 一律塞进 message 并用 errorContainer 红底常驻渲染。本测试锁死三层语义：
 *
 * 1. 中性告知与真错误分流，红底只留给需要家长处置的错误；
 * 2. 异常文案必须说清卡在哪一步、能做什么，而不是"出现意外错误"；
 * 3. VPN 步骤的返回信号去抖与查询异常降级不能被回退。
 */
class ConsentHintContractTest {

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

    private fun vm() = readText(
        "app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
        "src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt"
    )

    private fun screen() = readText(
        "app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentScreen.kt",
        "src/main/kotlin/com/padguard/child/consent/OneTimeConsentScreen.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentScreen.kt"
    )

    @Test
    fun `中性告知与真错误必须分成两个独立状态`() {
        val source = vm()
        assertTrue("缺少中性告知状态", source.contains("private val _notice = MutableStateFlow<String?>(null)"))
        assertTrue("中性告知必须对外可观察", source.contains("val notice: StateFlow<String?> = _notice.asStateFlow()"))
        assertTrue("真错误仍保留独立状态", source.contains("private val _message = MutableStateFlow<String?>(null)"))

        // 这些是正常路径：按意愿跳过隐私权限、ROM 状态回写延迟、机型没有设置入口。
        // 任何一条回到 _message（红底）都会重新制造"一直报错"的观感。
        listOf(
            "已跳过：",
            "已跳过「上网防护」",
            "正在确认「",
            "未检测到「",
            "本机型未开放「"
        ).forEach { text ->
            assertTrue("「$text」是中性告知，不得走红底错误态", source.contains("_notice.value = \"$text"))
        }
    }

    @Test
    fun `红底错误态只保留真正需要处置的失败`() {
        val source = vm()
        val launchFail = source.substringAfter("fun onLaunchFailed(key: String, reason: String)")
            .substringBefore("// launchManualStep")
        assertTrue("系统页打不开必须继续报错", launchFail.contains("_message.value = when (key) {"))
        assertTrue("设备管理员无入口必须继续报错", source.contains("_message.value = DA_NO_ENTRY_HINT"))
        // 核心回归：ActivityNotFoundException 的英文 message 曾被直接拼进红底卡片，
        // 家长看到"一段英文提示"，既不知道哪项没开成也不知道该怎么办。
        assertFalse(
            "系统异常原文不得拼进家长可见文案",
            launchFail.contains("（\$reason）") || launchFail.contains("「\$key」")
        )
        assertTrue(
            "异常原文只写日志",
            launchFail.contains("Logger.e(TAG) { \"launch failed for \$key: \$reason\" }")
        )
        assertTrue("要按步骤给中文指引", launchFail.contains("系统权限确认框没能打开"))
        assertTrue("未知步骤也要中文兜底", source.contains("?: \"该项授权\""))
    }

    @Test
    fun `结束时清空两类提示避免完成态仍挂着报错卡`() {
        val source = vm()
        val finish = source.substringAfter("private fun finish()").substringBefore("runCatching { GuardService")
        assertTrue("收尾必须清 message", finish.contains("_message.value = null"))
        assertTrue("收尾必须清 notice", finish.contains("_notice.value = null"))
    }

    @Test
    fun `推进异常文案必须指出步骤与退路`() {
        val source = vm()
        assertFalse(
            "不能再出现无信息量的旧文案",
            source.contains("_message.value = \"授权流程出现意外错误")
        )
        assertTrue("必须带出卡住的步骤名", source.contains("labelOfStep(_currentKey.value)"))
        assertTrue("必须给出重试退路", source.contains("点主按钮重试这一项"))
        assertTrue("必须给出跳过退路", source.contains("暂时跳过"))
        assertTrue("异常仍要写日志供排障", source.contains("Logger.e(TAG, e)"))
    }

    @Test
    fun `界面必须分别渲染中性卡与错误卡`() {
        val source = screen()
        assertTrue("界面需收集 notice", source.contains("val notice by viewModel.notice.collectAsStateWithLifecycle()"))
        assertTrue("中性卡用信息色", source.contains("containerColor = MaterialTheme.colorScheme.secondaryContainer"))
        assertTrue("错误卡仍用错误色", source.contains("containerColor = MaterialTheme.colorScheme.errorContainer"))
        assertTrue("中性卡不得误用错误色", source.contains("text = notice!!"))
    }

    @Test
    fun `VPN 跳转必须武装返回信号去抖`() {
        val source = vm()
        // advanceInternal 与 launchFor 是两条独立跳转路径，漏掉任一条都会在
        // 靠轮询接回前台的 ROM 上把 ActivityResult 回调吞掉、流水线卡死。
        val vpnInAdvance = source.substringAfter("// ③ 上网防护（VPN 授权）").substringBefore("// ④ 必须")
        assertTrue("主流程 VPN 跳转需设 returnExpected", vpnInAdvance.contains("returnExpected = true"))
        val vpnInLaunch = source.substringAfter("KEY_VPN -> {").substringBefore("else -> SpecialPermission")
        assertTrue("清单手动 VPN 跳转需设 returnExpected", vpnInLaunch.contains("returnExpected = true"))
    }

    @Test
    fun `VPN 查询异常必须如实跳过而非当成已授权`() {
        val source = vm()
        assertTrue("主流程需区分查询异常与已授权", source.contains("if (!vpnPrepared && vpnIntent == null"))
        assertTrue("手动路径需区分查询异常与已授权", source.contains("if (!prepared) {"))
        val count = Regex("skippedKeys \\+= KEY_VPN").findAll(source).count()
        assertTrue("两条异常路径都要记跳过", count >= 2)
    }

    @Test
    fun `VPN 确认页必须先校验可解析再跳转`() {
        val source = vm()
        assertTrue(
            "必须有 VPN Intent 的可解析校验",
            source.contains("private fun prepareVpnIntentOrNull()") &&
                source.contains("if (!resolvable(intent))")
        )
        val uses = Regex("prepareVpnIntentOrNull\\(\\)").findAll(source).count()
        assertTrue("主流程与清单手动跳转都要走校验后 Intent", uses >= 3)
        assertFalse(
            "不得再有未校验就硬 launch 的 VPN Intent",
            source.contains("val vpnIntent = if (vpnPrepared) null else webGuardVpn.prepareIntent()") ||
                source.contains("val intent = if (prepared) null else webGuardVpn.prepareIntent()")
        )
    }

    @Test
    fun `静默授权的失败结果不得被整包丢弃`() {
        val source = vm()
        assertTrue("必须消费 GrantReport", source.contains("onSuccess { report -> reportSilentGrantProblems(report) }"))
        val consumer = source.substringAfter("private fun reportSilentGrantProblems").substringBefore("private fun finish")
        assertTrue("授权失败要汇总", consumer.contains("report.failed"))
        assertTrue("加固失败要汇总", consumer.contains("report.hardening"))
        assertTrue("降级结果要让用户看见", consumer.contains("_notice.value = text"))
    }
}
