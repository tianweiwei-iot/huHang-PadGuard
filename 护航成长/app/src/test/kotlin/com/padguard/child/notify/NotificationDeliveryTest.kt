package com.padguard.child.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 通知「是否真送达」判定的纯逻辑 + 三处接线契约（R34）。
 *
 * 用户可感知现象：家长把通知开关关掉再打开后，常驻通知不回来、操作闸门失效的
 * 本地引导被静音半小时、看屏授权通知一直不出现。根因不是「权限没恢复」，而是
 * 权限被关期间 `NotificationManager.notify()` **静默丢弃且不抛异常**，
 * 于是所有 `runCatching { notify(...) }.onSuccess { 记账 }` 都记成了假账。
 *
 * 本测试同时锁死：纯判定的四条分支、三处 notify 前必须先问 canDeliver、
 * 常驻通知不可投递时必须作废去重基线（否则恢复后正文没变就永不重发）。
 */
class NotificationDeliveryTest {

    // ==================== 纯判定 ====================

    @Test
    fun `通知服务不可用不可投递`() {
        assertFalse(NotificationDelivery.decide(hasManager = false, appNotificationsEnabled = true, channelImportance = null))
    }

    @Test
    fun `应用通知总开关关闭不可投递`() {
        assertFalse(NotificationDelivery.decide(hasManager = true, appNotificationsEnabled = false, channelImportance = null))
        assertFalse("总开关关了，就算渠道是高优先级也送不到", NotificationDelivery.decide(true, false, 4))
    }

    @Test
    fun `渠道被用户调成不显示不可投递`() {
        // 0 = NotificationManager.IMPORTANCE_NONE
        assertFalse(NotificationDelivery.decide(true, true, 0))
    }

    @Test
    fun `渠道尚未创建不算被关闭`() {
        assertTrue(
            "首次装机时渠道还不存在，误判成不可投递会让引导通知永远发不出去",
            NotificationDelivery.decide(true, true, null)
        )
    }

    @Test
    fun `正常渠道均可投递`() {
        listOf(1, 2, 3, 4, 5).forEach { importance ->
            assertTrue("importance=$importance 应当可投递", NotificationDelivery.decide(true, true, importance))
        }
    }

    // ==================== 三处接线契约 ====================

    @Test
    fun `常驻通知不可投递时必须作废去重基线`() {
        val body = functionBody(
            readSource("service/GuardService.kt"),
            "private fun updateNotification()"
        )
        assertTrue(
            "updateNotification 必须先问 canDeliver，否则 notify 静默丢弃仍会写 lastNotificationText",
            body.contains("NotificationDelivery.canDeliver(this, CHANNEL_ID)")
        )
        assertTrue(
            "不可投递时必须把 lastNotificationText 置 null，权限恢复后才能强制重投常驻通知",
            body.contains("lastNotificationText = null")
        )
        assertTrue(
            "canDeliver 判定必须早于「已投递」记账",
            body.indexOf("canDeliver") < body.indexOf("lastNotificationText = text")
        )
    }

    @Test
    fun `操作闸门全屏引导不可投递时不得假装成功`() {
        val body = functionBody(
            readSource("guard/A11yRebindNotifier.kt"),
            "fun post(): Boolean"
        )
        assertTrue(
            "A11yRebindNotifier.post 必须先问 canDeliver，否则 isSuccess 会置活跃态并让上层落 30 分钟节流戳",
            body.contains("NotificationDelivery.canDeliver(context, CHANNEL_ID)")
        )
        assertTrue(
            "canDeliver 判定必须早于 notify",
            body.indexOf("canDeliver") < body.indexOf("mgr.notify(")
        )
        assertTrue("不可投递必须 return false", body.contains("return false"))
    }

    @Test
    fun `看屏授权引导不可投递时不得占用节流额度与投递`() {
        val source = readSource("capture/CaptureConsentPrompter.kt")
        assertTrue(
            "dispatch 在 claimPromptSlot 之前必须判断「后台 + 通知不可用」这一死路，否则白占 60 秒额度",
            source.indexOf("NotificationDelivery.canDeliver") < source.indexOf("if (!claimPromptSlot())")
        )
        val body = functionBody(source, "fun prompt(context: Context, captureIntent: Intent)")
        assertTrue(
            "prompt 内 notify 前必须先问 canDeliver",
            body.contains("NotificationDelivery.canDeliver(context, CHANNEL_ID)")
        )
        assertTrue(
            "canDeliver 判定必须早于 notify",
            body.indexOf("canDeliver") < body.indexOf("manager.notify(")
        )
    }

    @Test
    fun `授权返回引导不得因假成功停止补发`() {
        // ConsentReturnPresenter 用 `notified = postReturnToConsent(...)` 记账：
        // 通知被关时 isSuccess 为 true 会让整个等待窗口不再补发，家长卡在系统设置页。
        val body = functionBody(
            readSource("consent/ConsentReturnNotifier.kt"),
            "fun postReturnToConsent("
        )
        assertTrue(
            "postReturnToConsent 必须先问 canDeliver",
            body.contains("NotificationDelivery.canDeliver(context, CHANNEL_ID)")
        )
        assertTrue(
            "判定必须早于 notify",
            body.indexOf("canDeliver") < body.indexOf("mgr.notify(")
        )
    }

    @Test
    fun `锁屏与解锁过渡页不得把假成功当成系统代拉已发起`() {
        val source = readSource("ui/lock/LockScreenNotifier.kt")
        val occurrences = Regex("""NotificationDelivery\.canDeliver\(context, CHANNEL_ID\)""")
            .findAll(source).count()
        assertEquals(
            "postInternal 与 postUnlock 两处 notify 都必须先问 canDeliver",
            2,
            occurrences
        )
        val unlock = functionBody(source, "fun postUnlock(context: Context")
        assertTrue(
            "postUnlock 假成功会让 LockScreenActivity 跳过 startActivity 兜底",
            unlock.contains("NotificationDelivery.canDeliver(context, CHANNEL_ID)")
        )
        assertTrue(
            "判定必须早于 notify",
            unlock.indexOf("canDeliver") < unlock.indexOf("mgr.notify(")
        )
    }

    @Test
    fun `app 层每个通知投递点所在文件都必须接入统一判定`() {
        val dir = sourceDir()
        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains(".notify(") }
            .filter { !it.readText().contains("NotificationDelivery.canDeliver") }
            .map { it.name }
            .toList()
        assertTrue(
            "以下文件仍直接 notify 而未判定能否送达：$offenders。" +
                "通知总开关被关时 notify() 静默丢弃且不抛异常，会重新引入假成功记账",
            offenders.isEmpty()
        )
    }

    // ==================== 辅助 ====================

    /** 取函数体（到下一个顶格四空格的右花括号为止），避免被文件后半段同名字符串干扰。 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到函数 $signature", start >= 0)
        val end = source.indexOf("\n    }", start)
        assertTrue("函数 $signature 未闭合", end > start)
        return source.substring(start, end)
    }

    private fun sourceDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            "src/main/kotlin/com/padguard/child",
            "护航成长/app/src/main/kotlin/com/padguard/child"
        )
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isDirectory) return direct
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir!!, relative)
                if (candidate.isDirectory) return candidate
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到孩子端源码目录；cwd=$cwd")
    }

    private fun readSource(relativeUnderChild: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            "src/main/kotlin/com/padguard/child/$relativeUnderChild",
            "护航成长/app/src/main/kotlin/com/padguard/child/$relativeUnderChild"
        )
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isFile) return direct.readText()
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir!!, relative)
                if (candidate.isFile) return candidate.readText()
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到源码文件：$relativeUnderChild；cwd=$cwd")
    }
}
