package com.padguard.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R29 孩子端 UI 状态层 6 处结构性不变量的源码契约（纯 JVM 源码扫描）。
 *
 * 这 6 处都是"状态口径 / 落盘方式 / 协程归属"类缺陷，没有 Robolectric / Compose 测试夹具，
 * 源码契约比行为测试更抗重构。定义见 2026-11-15 工作日志 R29。
 */
class UiStateHardeningR29Test {

    @Test
    fun `授权断点进度必须同步落盘 否则进程被杀又从头重来`() {
        val source = consent()
        val save = source.substringAfter("private fun saveProgress()")
            .substringBefore("private fun clearProgress()")
        assertTrue("saveProgress 没有同步 commit()", save.contains("putStringSet(KEY_PROGRESS_SET, set).commit()"))
        assertFalse("saveProgress 回退成了异步 apply()", save.contains("putStringSet(KEY_PROGRESS_SET, set).apply()"))

        val clear = source.substringAfter("private fun clearProgress()")
            .substringBefore("// ==================== 清单状态")
        assertTrue("clearProgress 没有同步 commit()", clear.contains("clear().commit()"))
        assertFalse("clearProgress 回退成了异步 apply()", clear.contains("clear().apply()"))
        assertTrue("缺 ApplySharedPref 抑制说明", source.contains("@SuppressLint(\"ApplySharedPref\")"))
    }

    @Test
    fun `卡住项轮询不得抢着推进别人的跳转`() {
        val source = consent()
        assertTrue("缺唯一轮询 Job 字段", source.contains("private var stuckPollJob: Job? = null"))
        assertTrue("启动新轮询前没有取消旧的", source.contains("stuckPollJob?.cancel()"))
        assertTrue("轮询没有挂到唯一 Job 上", source.contains("stuckPollJob = viewModelScope.launch"))
        assertTrue(
            "轮询每拍没有校验自己仍是当前等待项",
            source.contains("if (_currentKey.value != key || _phase.value != Phase.WAITING_PARENT) return@launch")
        )
        val advanceHead = source.substringAfter("private fun advance(from: String)")
            .substringBefore("consentReturn.stop()")
        assertTrue("advance 开头没有作废旧轮询", advanceHead.contains("stuckPollJob?.cancel()"))
    }

    @Test
    fun `家长在场标记必须随状态实时更新 且覆盖登录与绑定页`() {
        val source = mainActivity()
        assertTrue("仍只写首帧，缺 LaunchedEffect(parentUiVisible)", source.contains("LaunchedEffect(parentUiVisible)"))
        val expr = source.substringAfter("val parentUiVisible =")
            .substringBefore("LaunchedEffect(parentUiVisible)")
        assertTrue("判据漏了登录页", expr.contains("loggedIn == false"))
        assertTrue("判据漏了绑定页", expr.contains("isBound == false"))
        // 不能只查 "DisposableEffect(Unit)"：说明性注释里也会出现该词，会误伤。
        // 真正要锁死的是「写入动作不再留在常量 key 的 effect 体内」。
        assertFalse(
            "实时写入不该再挂在常量 key 的 DisposableEffect 里",
            expr.contains("ConsoleState.setManagementVisible(parentUiVisible)")
        )
        val dispose = source.substringAfter("DisposableEffect(Unit) {").substringBefore("}")
        assertTrue("离场时没有复位标记", dispose.contains("onDispose"))
    }

    @Test
    fun `应用管理页首帧不得伪装成已加载完成并误报错误`() {
        val source = appInventory()
        assertTrue("没有用 null 区分『未完成』与『合法空名单』", source.contains("StateFlow<List<AppInventoryItem>?>"))
        assertTrue("apps 初值不是 null", source.contains("SharingStarted.Eagerly, null"))
        assertTrue("loaded 没有绑定采集完成", source.contains("loaded = items != null"))
        assertTrue("首帧仍会把 null 当成失败上报", source.contains("snapshot?.isEmpty() == true"))
        assertFalse("仍存在无条件 loaded = true", source.contains("loaded = true"))
    }

    @Test
    fun `持久化账号回填不得覆盖家长刚输入的内容`() {
        val source = login()
        assertTrue("缺用户已编辑标记", source.contains("private var userEdited = false"))
        assertEquals("账号输入与勾选两处都要置位", 2, countOccurrences(source, "userEdited = true"))
        val initGuard = source
            .substringAfter("val remembered = authRepository.rememberedCredentials.first()")
            .substringBefore("_phone.value = phone")
        assertTrue("回填前没有检查用户是否已编辑", initGuard.contains("if (userEdited) return@launch"))
    }

    @Test
    fun `电量与存储必须走RealtimeState单点定义`() {
        val source = profile()
        val rt = source.substringAfter("internal data class RealtimeState(")
            .substringBefore("fun applyTo(")
        assertTrue("RealtimeState 漏了 storage", rt.contains("val storage: String"))
        assertTrue("RealtimeState 漏了 battery", rt.contains("val battery: String"))
        val apply = source.substringAfter("fun applyTo(state: ProfileUiState)")
            .substringBefore("/** 设备所有者")
        assertTrue("applyTo 没有覆盖 storage", apply.contains("storage = storage"))
        assertTrue("applyTo 没有覆盖 battery", apply.contains("battery = battery"))
        // 定义 1 次 + readRealtimeState 1 次；combine 里若再手写一份就是 3 次（旧实现的分叉源头）。
        assertEquals("readStorage 仍有多余的手写调用点", 2, countOccurrences(source, "readStorage()"))
        assertEquals("readBattery 仍有多余的手写调用点", 2, countOccurrences(source, "readBattery()"))
    }

    private fun consent() = readText(
        "src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/consent/OneTimeConsentViewModel.kt"
    )

    private fun mainActivity() = readText(
        "src/main/kotlin/com/padguard/child/MainActivity.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/MainActivity.kt"
    )

    private fun appInventory() = readText(
        "src/main/kotlin/com/padguard/child/ui/apps/AppInventoryViewModel.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/ui/apps/AppInventoryViewModel.kt"
    )

    private fun login() = readText(
        "src/main/kotlin/com/padguard/child/login/ChildLoginViewModel.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/login/ChildLoginViewModel.kt"
    )

    private fun profile() = readText(
        "src/main/kotlin/com/padguard/child/ui/profile/ProfileViewModel.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/ui/profile/ProfileViewModel.kt"
    )

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) return count
            count++
            from = i + needle.length
        }
    }

    /** Gradle 单测 cwd 可能是模块目录或仓库根，逐级向上找，避免 IDE / 命令行差异造成假红。 */
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
}
