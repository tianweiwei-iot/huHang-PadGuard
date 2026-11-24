package com.padguard.child.usagesession

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「开始计时」确认浮层的并发不变量契约（纯 JVM 源码扫描，不触碰 WindowManager）。
 *
 * 锁死 2026-11-12 修复：旧实现在**调用线程**做 `if (view != null) return`，
 * 却把 View 的构造与挂载放进 `mainHandler.post{}`。状态流 collect、
 * 10s 心跳、家长端指令三条路径会在同一帧内连续/并发调用 show()，
 * 两次检查都看到 view==null → 排进两次挂载 → 屏幕叠出两层浮层，
 * view 只指向最后一层，hide() 移不走另一层（点不掉的僵尸浮层）。
 *
 * 现在每次调用 post 一份自带不可变意图快照、由主线程串行 reconcile 收敛。
 * 用源码扫描而非 instrumentation 测试：本仓无 Robolectric / WindowManager 测试夹具，
 * 而这类"把 add/remove 收口到一处"的结构性不变量，源码契约比行为测试更抗重构。
 */
class UsageStartOverlayContractTest {

    @Test
    fun `show与hide只登记意图 真正的挂载收口到主线程reconcile`() {
        val source = readText()
        assertTrue("缺少主线程串行收敛入口", source.contains("private fun reconcile(intent: Intent)"))
        assertTrue("收敛没有通过 mainHandler.post 串行化", source.contains("mainHandler.post { reconcile(intent) }"))
        assertTrue("缺少不可变意图快照", source.contains("private data class Intent("))
        assertTrue("意图快照没有按调用自带", source.contains("val intent = Intent("))

        // 挂载只能出现在收敛方法里：show() 体内若直接 addView，判空检查与赋值
        // 又会跨线程排队，重复弹层的 bug 立刻回归。
        val reconcileBody = source
            .substringAfter("private fun reconcile(intent: Intent)")
            .substringBefore("private fun removeViewIfPresent()")
        assertTrue("reconcile 内没有真正挂载", reconcileBody.contains("wm.addView("))
        val showBody = source
            .substringAfter("fun show(")
            .substringBefore("/** 移除计时开始确认浮层")
        assertFalse("show 内不该直接操作 WindowManager", showBody.contains("wm.addView("))
        assertFalse("show 内不该直接置 view（必须在主线程收敛内）", showBody.contains("view = v"))
    }

    @Test
    fun `家长催办意图不被心跳降级 且抑制窗口只约束日切自动提示`() {
        val source = readText()
        // 家长催办必须绕过「暂不开始」抑制窗口：判断里要带 fromParent 例外。
        assertTrue(
            "抑制窗口没有排除家长催办（催办会被心跳/暂不开始吞掉）",
            source.contains("if (!intent.fromParent && declinedUntilElapsed != 0L")
        )
        assertTrue("家长催办文案没有按快照渲染", source.contains("buildView(intent.fromParent)"))
    }

    @Test
    fun `挂载失败必须回滚状态 否则浮层永远不再出现`() {
        val source = readText()
        val failureBlock = source.substringAfter(".onFailure {").substringBefore("private fun removeViewIfPresent()")
        assertTrue("挂载失败没有回滚 view", failureBlock.contains("view = null"))
        assertTrue("挂载失败没有回滚 showingFlow", failureBlock.contains("_showing.value = false"))
        assertTrue("挂载失败没有回滚确认回调", failureBlock.contains("onConfirm = null"))
    }

    @Test
    fun `移除同样只走收敛 且权限被撤销时也要收掉已展示的浮层`() {
        val source = readText()
        val hideBody = source.substringAfter("fun hide()").substringBefore("private fun reconcile(intent: Intent)")
        assertFalse("hide 内不该直接 removeView", hideBody.contains("wm.removeView("))
        assertTrue("hide 没有发布「不展示」意图", hideBody.contains("visible = false"))

        val canDrawGuard = source.substringAfter("if (!canDraw)").substringBefore("if (view != null) return")
        assertTrue("缺悬浮窗权限时没有收掉孤儿浮层", canDrawGuard.contains("removeViewIfPresent()"))
    }

    private fun readText(): String {
        val relative = "护航成长/app/src/main/kotlin/com/padguard/child/usagesession/UsageStartOverlay.kt"
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val direct = File(relative)
        if (direct.isFile) return direct.readText()
        var dir: File? = cwd
        repeat(6) {
            if (dir == null) return@repeat
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir!!.parentFile
        }
        // 模块目录下运行 Gradle 时，源文件相对模块根
        val local = File("src/main/kotlin/com/padguard/child/usagesession/UsageStartOverlay.kt")
        if (local.isFile) return local.readText()
        throw AssertionError("找不到 UsageStartOverlay.kt；cwd=$cwd")
    }
}
