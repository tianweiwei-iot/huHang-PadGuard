package com.padguard.child.agreement

import com.padguard.core.data.repository.guardActive
import com.padguard.core.data.repository.shouldReleaseControl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 法务 v2.0「协议明示同意」硬闸门契约（2026-11-25）。
 *
 * 背景：隐私政策承诺"监护人明示同意后方可启用守护能力"，但GuardService 只认 isBound，
 * 采集链路在协议弹窗出现之前就已运行；孩子端内置协议文本还声称"开发方不接触任何数据"，
 * 与官方托管及管理后台的客服/运维访问能力直接冲突。
 *
 * 这些分支无法在纯 JVM 单测里起 ForegroundService / DataStore，
 * 因此用「纯函数语义 + 源码接线扫描」双层锁定，防止注释写对、实现回退。
 */
class AgreementHardGateContractTest {

    // ==================== 闸门语义 ====================

    @Test
    fun `仅已绑定且已明示同意时才允许开启守护`() {
        assertTrue("正常装机：已绑定 + 已同意", guardActive(true, true))
        assertFalse("只绑定未同意：不得采集", guardActive(true, false))
        assertFalse("同意过但未绑定：不得采集", guardActive(false, true))
        assertFalse("两者都无", guardActive(false, false))
    }

    // ==================== 服务侧接线 ====================

    @Test
    fun `守卫总闸必须同时观察绑定与协议同意两条流`() {
        val section = guardServiceSection()
        assertTrue(
            "observeBinding 必须观察 isAgreementAccepted，" +
                "否则协议升版/撤回后守卫仍按旧同意态采集",
            section.contains("authRepository.isAgreementAccepted.distinctUntilChanged()")
        )
        assertTrue(
            "必须走 guardActive 纯闸门，禁止在调用点各写一份 bound && agreed",
            section.contains("guardActive(bound, agreed)")
        )
    }

    @Test
    fun `闸门关闭必须真停采集而不是只清标志位`() {
        val section = guardServiceSection()
        assertTrue("未同意必须断开 MQTT 上行", section.contains("remote.stop()"))
        assertTrue("未同意必须停周期定位", section.contains("locationAutoReporter.stop()"))
        assertTrue(
            "transportStarted 必须复位，否则重新同意后 startTransport 会被幂等标志挡住、永久不上线",
            section.contains("transportStarted.compareAndSet(true, false)")
        )
    }

    @Test
    fun `重新同意后必须补启定位与策略不能只靠 bootstrap 一次性启动`() {
        val section = guardServiceSection()
        assertTrue("开闸后必须补启定位采集", section.contains("locationAutoReporter.start()"))
        assertTrue(
            "开闸后必须补落地本地策略，否则升版重新同意后设备在线但管控为空",
            section.contains("policyOnGateOpen")
        )
    }

    @Test
    fun `启动期的缓存策略与定位也必须过协议闸门`() {
        val src = read("app/src/main/kotlin/com/padguard/child/service/GuardService.kt")
        val bootstrap = src.substringAfter("private fun bootstrap(reason: String)")
            .substringBefore("observeEffects()")
        val policy = bootstrap.substringAfter("runSafely(\"applyCachedPolicy\")")
        assertTrue(
            "启动落地缓存策略前必须检查协议同意，否则开机首帧就会先管控后同意",
            policy.contains("isAgreementAccepted.first()")
        )
        val location = bootstrap.substringAfter("runSafely(\"startLocationReporter\")")
        assertTrue(
            "周期定位启动前必须检查协议同意",
            location.contains("isAgreementAccepted.first()")
        )
    }

    @Test
    fun `协议未同意但已绑定时不得释放设备管理员身份`() {
        assertTrue("真解绑才释放 DO/DA", shouldReleaseControl(false))
        assertFalse(
            "协议未同意不是解绑：释放 DO 通常不可逆，会把硬管控设备永久降级",
            shouldReleaseControl(true)
        )
        val section = guardServiceSection()
        assertTrue(
            "服务必须使用 shouldReleaseControl 判据，禁止直接 if (!bound) 之外再叠加协议条件",
            section.contains("if (shouldReleaseControl(bound))")
        )
        assertTrue(
            "闸门关闭时必须恢复管理台入口，否则协议升版后家长没有重新确认的入口",
            section.contains("revealOnGateClosed")
        )
    }

    // ==================== 法律文本 ====================

    @Test
    fun `协议版本升到v2且不回潮到虚假主体陈述`() {
        val auth = read("core/data/src/main/kotlin/com/padguard/core/data/repository/AuthRepository.kt")
        assertTrue(
            "升版才会让存量设备重新征求同意；版本不变则老签署记录一直放行",
            auth.contains("CURRENT_AGREEMENT_VERSION = \"2.0.0\"")
        )
        val dialog = read("app/src/main/kotlin/com/padguard/child/agreement/AgreementDialog.kt")
        listOf(
            "开发方无法接触任何数据",
            "个人信息处理者是家长本人",
            "数据不出家长自有设备",
            "开发方不运营任何服务器"
        ).forEach { stale ->
            assertFalse("协议文本不得回潮到 v1.0 的虚假陈述：$stale", stale in dialog)
        }
        assertTrue("必须如实披露官方托管下的处理者身份", "我们是个人信息处理者" in dialog)
        assertTrue("必须给出客服邮箱", "support@padguard.com" in dialog)
        assertTrue("必须写明保存期限", "30 天" in dialog)
    }

    @Test
    fun `首次装机必须展示协议全文并主动勾选`() {
        val screen = read("app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentScreen.kt")
        assertTrue("必须展示授权协议全文", "AGREEMENT_TEXT" in screen)
        assertTrue("必须展示隐私政策全文", "PRIVACY_POLICY_TEXT" in screen)
        assertTrue("必须有显式勾选框", "Checkbox(" in screen)
        assertTrue("未勾选时同意按钮必须不可点", "agreementChecked" in screen)
    }

    // ==================== helpers ====================

    private fun guardServiceSection(): String {
        val src = read("app/src/main/kotlin/com/padguard/child/service/GuardService.kt")
        return src.substringAfter("private fun observeBinding()")
            .substringBefore("private suspend fun startTransport()")
    }

    private fun read(relative: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            relative,
            relative.removePrefix("app/"),
            "护航成长/$relative"
        )
        for (candidate in candidates) {
            val direct = File(candidate)
            if (direct.isFile) return direct.readText()
            var dir: File? = cwd
            repeat(8) {
                val current = dir ?: return@repeat
                val file = File(current, candidate)
                if (file.isFile) return file.readText()
                dir = current.parentFile
            }
        }
        throw AssertionError("找不到源码：$relative；cwd=$cwd")
    }
}
