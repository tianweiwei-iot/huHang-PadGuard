package com.padguard.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R27 四处结构性不变量的源码契约（纯 JVM 源码扫描，不触碰 Android 运行时）。
 *
 * 这四处都是"接线/落盘口径"类缺陷，用 instrumentation 测试既拿不到 PMS 故障注入、
 * 也拿不到 system_server 的 IntentSender 回填；源码契约比行为测试更抗重构。
 * 定义见 2026-11-15 工作日志 R27。
 */
class R27HardeningContractTest {

    @Test
    fun `安装与卸载的statusReceiver必须可变 否则EXTRA_STATUS回填丢失`() {
        val source = installer()
        assertTrue("缺少 statusReceiverFlags 单一出口", source.contains("private fun statusReceiverFlags(): Int"))
        assertTrue("statusReceiver 没有显式 FLAG_MUTABLE", source.contains("PendingIntent.FLAG_MUTABLE"))
        assertTrue(
            "FLAG_MUTABLE 缺 SDK>=S 守卫（低版本无此常量语义）",
            source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S")
        )
        val body = source.substringAfter("private fun statusReceiverFlags(): Int")
            .substringBefore("/** 目标包是否已安装")
        assertFalse("可变出口内不得再出现 FLAG_IMMUTABLE", body.contains("FLAG_IMMUTABLE"))

        // 安装 / 卸载两条通道都必须走同一出口；漏一处就会半边静默失效。
        assertTrue(
            "安装会话的 statusReceiver 没走可变出口",
            source.contains("PendingIntent.getBroadcast(context, sessionId, intent, statusReceiverFlags())")
        )
        assertTrue(
            "卸载的 statusReceiver 没走可变出口",
            source.contains("context, packageName.hashCode(), intent, statusReceiverFlags()")
        )
        assertFalse(
            "仍有遗留的 FLAG_IMMUTABLE flags 构造",
            source.contains("Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE")
        )
    }

    @Test
    fun `台账采集不完整时整轮作废 残缺清单不得进入差分与上传`() {
        val source = collector()
        assertTrue("queryIntentActivities 失败没有标记不完整", source.contains("queryFailed = true"))
        assertTrue("没有统计取详情失败数", source.contains("infoFailures++"))
        assertTrue("没有统计启动器候选数", source.contains("launcherCandidates++"))
        assertTrue(
            "collect 没有调用完整性判定",
            source.contains("if (inventoryIsIncomplete(queryFailed, launcherCandidates, infoFailures))")
        )
        val block = source
            .substringAfter("if (inventoryIsIncomplete(queryFailed, launcherCandidates, infoFailures))")
            .substringBefore("Logger.d(TAG)")
        assertTrue("判定不完整时没有返回空列表", block.contains("return@withContext emptyList()"))

        // 空列表是刻意复用同步侧既有兜底：不差分、不推进基线、不上传、快速重试。
        // 用宽松串断言，避免并行会话对 GuardService 的无关编辑造成假红。
        assertTrue("同步侧缺少空台账跳过整轮的兜底", guardService().contains("apps.isEmpty()"))
    }

    @Test
    fun `台账基线必须同步落盘`() {
        val source = tracker()
        val body = source.substringAfter("private fun writeBaseline(names: Set<String>)")
            .substringBefore("private companion object")
        assertTrue("基线写入没有同步 commit()", body.contains("putString(KEY_BASELINE, json).commit()"))
        assertFalse("基线写入回退成了异步 apply()", body.contains("putString(KEY_BASELINE, json).apply()"))
        assertTrue("缺 ApplySharedPref 抑制与意图说明", source.contains("@SuppressLint(\"ApplySharedPref\")"))
    }

    @Test
    fun `绑定码消费成功后不得被Effect重放二次提交`() {
        val source = bindViewModel()
        val guard = "if (bindSucceeded || _state.value.step == BindStep.Submitting) return"
        assertEquals("submit / submitByAccount 必须都有防重守卫", 2, countOccurrences(source, guard))
        assertEquals("两条成功分支都必须标记已消费", 2, countOccurrences(source, "bindSucceeded = true"))
        assertTrue("缺已消费事实的持久字段", source.contains("private var bindSucceeded = false"))

        val welcomeBody = source.substringAfter("fun backToWelcome()").substringBefore("/**")
        assertTrue("回到欢迎页没有复位守卫（无法开始新一轮绑定）", welcomeBody.contains("bindSucceeded = false"))

        // 只判 Submitting 挡不住：第二次提交发生在第一次已完成、停在 DeviceAdmin 引导页之后。
        assertTrue("成功分支未再区分 DeviceAdmin 停留态", source.contains("if (needsDeviceAdmin()) BindStep.DeviceAdmin"))
    }

    private fun installer() = readText(
        "src/main/kotlin/com/padguard/child/maintain/AppInstaller.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/maintain/AppInstaller.kt"
    )

    private fun collector() = readText(
        "src/main/kotlin/com/padguard/child/monitor/AppInventoryCollector.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/monitor/AppInventoryCollector.kt"
    )

    private fun tracker() = readText(
        "src/main/kotlin/com/padguard/child/monitor/AppInventoryDeltaTracker.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/monitor/AppInventoryDeltaTracker.kt"
    )

    private fun bindViewModel() = readText(
        "src/main/kotlin/com/padguard/child/bind/BindViewModel.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/bind/BindViewModel.kt"
    )

    private fun guardService() = readText(
        "src/main/kotlin/com/padguard/child/service/GuardService.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/service/GuardService.kt"
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
