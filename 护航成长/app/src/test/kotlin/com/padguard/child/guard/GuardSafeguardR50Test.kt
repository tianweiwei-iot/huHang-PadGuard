package com.padguard.child.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R50 契约：三条保底（永不掉线 / 权限永不丢失 / 锁屏后无法退出）的**接线锁**。
 *
 * 本轮修的每一处都是"看起来在、实际不生效"的静默缺陷，真机才复现，
 * 因此全部用源码扫描定死形状，避免后续重构把守卫又"顺手清理"掉。
 */
class GuardSafeguardR50Test {

    // ================= 锁屏防逃逸 =================

    @Test
    fun `锁屏看门狗不得用本包在前台做放过条件`() {
        val a11y = readText(
            "src/main/kotlin/com/padguard/child/guard/OperationGuardAccessibilityService.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/guard/OperationGuardAccessibilityService.kt"
        )
        // 旧口径：isOwnPackage(fg) → 本包任一页面在前台就 continue。
        // 家长把本应用设为默认桌面后，孩子按 HOME 回到 KidHomeActivity（本包）同样命中，
        // 看门狗于是永不重拉锁屏 —— 这是"锁屏后无法退出"最直接的突破口。
        val code = a11y.lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
            .joinToString("\n")
        assertFalse(
            "锁屏看门狗不得再用「前台包名 == 本包」跳过重拉：儿童桌面同属本包，" +
                "孩子按 HOME 回桌面就会被当成「锁屏页还在」而永久放过",
            code.contains("isOwnPackage(fg)")
        )
        assertTrue(
            "看门狗必须改用「锁屏流程页是否可见」判定（LockScreenActivity.visible || " +
                "GuardUnlockActivity.visible）",
            code.contains("LockScreenActivity.visible || GuardUnlockActivity.visible")
        )
        // 放过条件必须在重拉之前：否则"可见也重拉"会变成反复拉起锁屏页的抖动源
        val idxGuard = code.indexOf("LockScreenActivity.visible || GuardUnlockActivity.visible")
        val idxShow = code.indexOf("LockScreenActivity.show(")
        assertTrue(
            "放过条件必须出现在重拉调用之前（先判可见再重拉）",
            idxGuard in 0 until idxShow
        )
    }

    @Test
    fun `密码输入页必须暴露前台可见标记 否则重拉会踢掉家长正在输的框`() {
        val activity = readText(
            "src/main/kotlin/com/padguard/child/guard/GuardUnlockActivity.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/guard/GuardUnlockActivity.kt"
        )
        assertTrue(
            "GuardUnlockActivity 必须暴露 companion visible（看门狗据此放过密码输入页）",
            activity.contains("var visible: Boolean = false")
        )
        assertTrue("onResume 必须置 visible = true", activity.contains("visible = true"))
        assertTrue("onPause 必须置 visible = false", activity.contains("visible = false"))
        // 该标记必须是 companion 级别的 static 状态：Activity 实例级字段看门狗读不到
        val companionIdx = activity.indexOf("companion object")
        val visibleIdx = activity.indexOf("var visible: Boolean = false")
        assertTrue(
            "visible 必须声明在 companion object 内（实例字段对看门狗不可见）",
            companionIdx in 0 until visibleIdx
        )
    }

    @Test
    fun `儿童桌面必须在锁定态自行重拉锁屏 不依赖无障碍存活`() {
        val home = readText(
            "src/main/kotlin/com/padguard/child/KidHomeActivity.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/KidHomeActivity.kt"
        )
        assertTrue(
            "KidHomeActivity 必须注入 LockController：看门狗依赖无障碍，而无障碍恰是最先被" +
                "清理工具停掉的一环，按 HOME 回桌面必须有第二道自保",
            home.contains("lockController: com.padguard.core.engine.lock.LockController") ||
                home.contains("lateinit var lockController")
        )
        assertTrue(
            "必须在 RESUMED 时读取 lockController.state 并在 locked 时重拉锁屏",
            home.contains("lockController.state.collect") && home.contains("st.locked")
        )
        assertTrue(
            "重拉必须复用 LockScreenActivity.show（与看门狗同一出口，含 FSI 兜底）",
            home.contains("LockScreenActivity.show(this@KidHomeActivity")
        )
        // 节流：锁屏页异常自退（onCreate 即 finish）时，无节流会变成拉起-退出-再拉起的死循环
        assertTrue(
            "必须保留重拉节流常量，防锁屏页异常自退导致热循环",
            home.contains("LOCK_REDISPLAY_MIN_INTERVAL_MS")
        )
    }

    // ================= 权限自愈 =================

    @Test
    fun `授权守护的单飞守卫不得用 return@withLock 早退`() {
        val keeper = readText(
            "src/main/kotlin/com/padguard/child/permission/AuthorizationKeeper.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/AuthorizationKeeper.kt"
        )
        val code = keeper.lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
            .joinToString("\n")
        // `return@withLock` 只退出 lambda：守卫跳过后函数照旧执行 doReconcile()，
        // 且 finally 会把并发那一方的 running 标记提前清掉 —— 守卫双向失效。
        assertFalse(
            "reconcile 的守卫不得用 return@withLock（退不出函数，两次 reconcile 会真的并行跑）",
            code.contains("return@withLock")
        )
        assertTrue(
            "必须先把「是否轮到我」求值成布尔，再在锁外 return",
            code.contains("if (!proceed) return")
        )
    }

    @Test
    fun `ensureEnabled 必须无条件补写无障碍总开关`() {
        val healer = readText(
            "src/main/kotlin/com/padguard/child/guard/AccessibilityCapabilityHealer.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/guard/AccessibilityCapabilityHealer.kt"
        )
        val body = healer.substringAfter("private fun ensureEnabledLocked() {")
            .substringBefore("suspend fun rebindIfDetached")

        // 定位 `if (next.size != entries.size) {` 块的结束行
        val lines = body.lines()
        val ifIdx = lines.indexOfFirst { it.contains("if (next.size != entries.size)") }
        assertTrue("ensureEnabledLocked 结构变化，测试需同步：找不到 size 判定", ifIdx >= 0)
        var endIdx = ifIdx + 1
        while (endIdx < lines.size && !lines[endIdx].trim().startsWith("}")) endIdx++
        val switchIdx = lines.indexOfFirst { it.contains("ensureMasterSwitch()") }
        assertTrue("ensureEnabledLocked 里必须调用 ensureMasterSwitch()", switchIdx >= 0)

        assertTrue(
            "ensureMasterSwitch() 必须在 size 判定**之外**：EMUI 一键清理的落点之一是" +
                "「名单原样保留、只把 ACCESSIBILITY_ENABLED 置 0」，名单未变就跳过会让服务永不启动，" +
                "而检测侧读名单仍认为已开启，形成怎么修都不动的静默锁死",
            switchIdx > endIdx
        )
        // rebind 恢复写回后也必须补总开关
        val rebind = healer.substringAfter("suspend fun rebindIfDetached")
        val restoredIdx = rebind.indexOf("val restored = withEntry(nowEntries, flat)")
        val rebindSwitchIdx = rebind.indexOf("ensureMasterSwitch()")
        assertTrue(
            "rebindIfDetached 在恢复写回后也必须补写总开关（否则「名单正常 + 总开关 0」上无限空转）",
            restoredIdx >= 0 && rebindSwitchIdx in 0..rebind.length && rebindSwitchIdx > restoredIdx
        )
    }

    @Test
    fun `静默自愈成功后必须关闭 TamperDetector 上报的无障碍告警`() {
        val checker = readText(
            "src/main/kotlin/com/padguard/child/guard/PermissionHealthChecker.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/guard/PermissionHealthChecker.kt"
        )
        assertTrue(
            "checkAccessibility 必须回传 selfHealed（否则恢复边沿永远不出现）",
            checker.contains("internal data class A11yHealth") && checker.contains("selfHealed")
        )
        assertTrue(
            "自愈成功分支必须标记 selfHealed = a11yNow",
            checker.contains("A11yHealth(true, selfHealed = a11yNow)")
        )
        assertTrue(
            "computeRecovered 必须接受 selfHealed 参数",
            checker.contains("a11ySelfHealed: Boolean = false")
        )
        assertTrue(
            "本轮静默自愈必须显式补关 ACCESSIBILITY_DISABLED：" +
                "TamperDetector 按「开关被关掉」即时上报，它的恢复只能由这里关闭；" +
                "静默自愈是同一轮内从关到开，prev 永远看不到 a11y → 家长端红点永挂",
            checker.contains("recovered += \"ACCESSIBILITY_DISABLED\"")
        )
        assertTrue(
            "两处调用（check 与 recheckSpecialCapabilities）都必须传 selfHealed",
            Regex("computeRecovered\\(a11yOk, overlayOk, a11y\\.selfHealed\\)")
                .findAll(checker).count() == 2
        )
    }

    @Test
    fun `拉回授权页必须在启动成功后才落冷却戳`() {
        val keeper = readText(
            "src/main/kotlin/com/padguard/child/permission/AuthorizationKeeper.kt",
            "护航成长/app/src/main/kotlin/com/padguard/child/permission/AuthorizationKeeper.kt"
        )
        val idxSuccess = keeper.indexOf(".onSuccess {")
        val idxPut = keeper.indexOf("putLong(KEY_LAST_BRING_BACK")
        assertTrue(
            "bringBackToConsent 必须存在 onSuccess 分支",
            idxSuccess >= 0
        )
        assertTrue(
            "冷却戳必须在 startActivity 成功之后（onSuccess 内）才写：" +
                "先落戳会把一次失败\"记成已尝试\"，整个冷窗内不再补拉，" +
                "家长看到的就是「权限页点了没反应」",
            idxPut > idxSuccess
        )
        // 反向：runCatching 之前不许再出现无条件的 putLong
        val idxStart = keeper.indexOf("context.startActivity(")
        assertFalse(
            "startActivity 之前不得无条件落戳",
            idxPut in 0 until idxStart
        )
    }

    private fun readText(vararg candidates: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        for (relative in candidates) {
            val direct = File(relative)
            if (direct.isFile) return direct.readText().replace("\r\n", "\n")
            var dir: File? = cwd
            repeat(6) {
                if (dir == null) return@repeat
                val candidate = File(dir, relative)
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
                dir = dir!!.parentFile
            }
        }
        throw AssertionError("找不到源码文件：${candidates.joinToString()}；cwd=$cwd")
    }
}
