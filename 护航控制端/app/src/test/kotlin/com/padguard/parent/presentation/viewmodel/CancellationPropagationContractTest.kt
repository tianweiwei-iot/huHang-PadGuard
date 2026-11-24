package com.padguard.parent.presentation.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 取消传播契约：家长端 ViewModel 用 `viewModelScope.launch` 承载所有审批动作，
 * 用户返回/切账号/页面销毁都会取消这些协程。仓库层（ApiDataSource.exec / execRaw）
 * 刻意把 CancellationException 原样抛出，上层若用 `catch (Throwable)` 一兜，
 * 取消就会被当成"操作失败"：错误态被写到即将销毁的 ViewModel 上、工单被误记进
 * 失败冷却窗（一分钟内不再提醒）、甚至误取消风险告警通知。
 *
 * 本项目测试基建只有 JUnit4（无 coroutines-test / mockk），故沿用既有的源码扫描型
 * 契约风格（见 EffectiveDailyLimitTest / SessionSwitchResetR44Test）：
 * 扫描 viewmodel 目录下每一个 Throwable catch，强制其紧邻位置显式放行取消。
 */
class CancellationPropagationContractTest {

    private fun viewModelSources(): List<Pair<String, String>> {
        // 由 classpath 回溯到仓库根（与 DeviceDetailFingerprintTest 同款定位）
        var dir = File("app/src/main/java/com/padguard/parent/presentation/viewmodel")
        if (!dir.isDirectory) dir = File("../app/src/main/java/com/padguard/parent/presentation/viewmodel")
        if (!dir.isDirectory) dir = File("../../app/src/main/java/com/padguard/parent/presentation/viewmodel")
        assertTrue("找不到 viewmodel 源码目录: ${dir.absolutePath}", dir.isDirectory)
        return dir.listFiles { f -> f.isFile && f.name.endsWith("ViewModel.kt") }
            .orEmpty()
            .map { it.name to it.readText() }
    }

    @Test
    fun `所有 ViewModel 的 Throwable catch 都必须放行 CancellationException`() {
        val sources = viewModelSources()
        assertTrue("未扫描到任何 ViewModel，测试定位失效", sources.isNotEmpty())
        val offenders = mutableListOf<String>()
        var catchCount = 0
        sources.forEach { (name, src) ->
            Regex("""catch\s*\(\s*\w+\s*:\s*Throwable\s*\)\s*\{([\s\S]{0,200})""")
                .findAll(src).forEach { m ->
                    catchCount++
                    if (!m.groupValues[1].contains("CancellationException")) {
                        offenders += "$name: ${m.value.take(60)}"
                    }
                }
        }
        // 5 处审批类 catch + 1 处 DataRightsViewModel 合规写操作 + 1 处监护人单独同意提交（均放行取消）。
        // 钉死精确数量是为了防止有人删掉 catch 让扫描"假绿"，新增合法 catch 时同步更新此值。
        assertTrue("应恰好覆盖已知的 7 处 catch，实际 $catchCount", catchCount == 7)
        assertTrue(
            "以下 catch 吞掉了取消，会把退出页面当成操作失败:\n${offenders.joinToString("\n")}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `每个含 Throwable catch 的 ViewModel 必须显式 import CancellationException`() {
        viewModelSources().forEach { (name, src) ->
            if (src.contains("catch (") && src.contains(": Throwable")) {
                assertTrue(
                    "$name 重抛了取消却没有 import，编译期会失败",
                    src.contains("import kotlinx.coroutines.CancellationException")
                )
            }
        }
    }

    @Test
    fun `不得用 runCatching 重新包回已修复的审批 action`() {
        // 历史事故：runCatching { block() } 里 block 返回 Result<Unit>，
        // 会变成 Result<Result<Unit>>，网络失败被当成功静默吞掉。
        val fixed = listOf(
            "UnlockRequestCenterViewModel.kt",
            "UsageDelayCenterViewModel.kt",
            "TabletUsageSettingsViewModel.kt"
        )
        viewModelSources().filter { it.first in fixed }.forEach { (name, src) ->
            // 排除注释行：源码里本来就写着"不能用 runCatching { block() }"的反例说明。
            val code = src.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            assertFalse(
                "$name 不得再用 runCatching 包裹 suspend block（Result 嵌套会吞掉失败）",
                code.contains("runCatching { block() }")
            )
        }
    }
}
