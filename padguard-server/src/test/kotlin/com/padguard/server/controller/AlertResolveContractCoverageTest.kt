package com.padguard.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 「告警自愈关闭」的**跨端契约**守门测试（R31）。
 *
 * ## 为什么必须有这份测试
 * 孩子端自愈成功后调 `POST /api/v1/device/alerts/resolve` 关闭家长端 ACTIVE 告警，
 * 但两端口径分处两个独立 Gradle 工程：
 * - 服务端 `ChildControllers.RESOLVABLE_ALERT_CATEGORIES` 是字符串白名单；
 * - 孩子端 `PermissionHealthChecker` 产出 `RiskType.XXX.name` 与 `set += "XXX"`。
 * 编译器无法互相约束。**已实际发生的事故**：孩子端 `clearLockScreenUnprotected`
 * 早就传 `LOCK_SCREEN_UNPROTECTED`，服务端白名单却漏了它 → 400；
 * 而 `ApiCaller` 把 400 转成 `ApiResult.BizError` 返回（不抛异常），
 * 端上 `runCatching{}.onFailure{}` 完全静默 —— 锁屏早已恢复，家长端
 * 「锁屏未能生效」告警却永久挂着，且没有任何一条日志能指出原因。
 *
 * 本测试从**两侧源码**各提取一次类别集合做双向比对：任一端单边改动都会当场红，
 * 而不是变成一条静默失效的闭环。
 *
 * ## 维护方式
 * 与 `CommandContractCoverageTest` 同为跨端契约，但这里改用源码扫描（而非手工抄写），
 * 因为两侧类别都在持续增长，抄写必然漂移。
 */
class AlertResolveContractCoverageTest {

    private val serverController = readText(
        "src/main/kotlin/com/padguard/server/controller/ChildControllers.kt",
        "padguard-server/src/main/kotlin/com/padguard/server/controller/ChildControllers.kt"
    )
    private val childChecker = readText(
        "../护航成长/app/src/main/kotlin/com/padguard/child/guard/PermissionHealthChecker.kt",
        "护航成长/app/src/main/kotlin/com/padguard/child/guard/PermissionHealthChecker.kt"
    )

    @Test
    fun `服务端可恢复白名单与孩子端产出类别严格双向对齐`() {
        val whitelist = categoriesInWhitelist()
        val produced = categoriesProducedByChild()

        assertTrue(whitelist.isNotEmpty(), "白名单解析失败，源码结构已变")
        assertTrue(produced.isNotEmpty(), "孩子端产出类别解析失败，源码结构已变")

        assertEquals(
            produced, whitelist,
            "跨端告警恢复契约漂移：孩子端会请求关闭 ${produced - whitelist}（将被服务端 400 拒绝），" +
                "白名单里另有 ${whitelist - produced} 从未被孩子端产出。" +
                "两端必须同时改，单边修改就是「修好了还在报警」事故。"
        )
    }

    @Test
    fun `白名单只收状态型 不含事件型与需人工介入型`() {
        val forbidden = setOf(
            "UNINSTALL_ATTEMPT", "FORCE_STOP_ATTEMPT", "CLEAR_DATA_ATTEMPT",
            "CLOCK_TAMPERING", "ROOT_DETECTED", "BLACKLIST_APP_LAUNCH",
            "BLOCKED_URL_ACCESS", "TIME_LIMIT_EXCEEDED", "APP_INSTALLED",
            "APP_UNINSTALLED", "INVALID_SIGNATURE", "POLICY_APPLY_FAILED"
        )
        val overlap = forbidden.intersect(categoriesInWhitelist())
        assertTrue(
            overlap.isEmpty(),
            "设备端不应有权批量关闭事件型/需人工介入的告警：$overlap"
        )
    }

    @Test
    fun `端点仍对每个类别做白名单校验且 deviceId 由鉴权注入`() {
        assertTrue(
            serverController.contains("if (category !in RESOLVABLE_ALERT_CATEGORIES)"),
            "resolveAlerts 必须逐项校验白名单，否则孩子端可越权关闭任意告警"
        )
        assertTrue(
            serverController.contains("@RequestAttribute(\"deviceId\") deviceId: String"),
            "deviceId 必须由鉴权拦截器注入，禁止报文伪造"
        )
        assertTrue(
            serverController.contains("if (req.categories.isEmpty())"),
            "空类别列表必须显式拒绝，避免端上把空操作误判为成功"
        )
    }

    @Test
    fun `孩子端必须检查 resolve 返回值 不许再把 400 静默吞掉`() {
        assertTrue(
            childChecker.contains("if (result !is ApiResult.Success)"),
            "ApiCaller 把 HTTP 400 转成 BizError 返回而非抛异常；" +
                "只写 runCatching{}.onFailure{} 会让白名单漂移无声无息"
        )
        assertTrue(
            childChecker.countOccurrences("if (result !is ApiResult.Success)") >= 2,
            "直接 resolve 与 computeResolveCategories 两条路径都要检查返回值"
        )
        assertTrue(
            childChecker.contains("remote.resolveActiveAlerts(recovered.toList())"),
            "恢复类别必须经 resolveIfNeeded 统一出口上报"
        )
    }

    @Test
    fun `LOCK_SCREEN_UNPROTECTED 与 USAGE_ACCESS_LOST 均在闭环内`() {
        val all = categoriesInWhitelist()
        assertTrue(
            "LOCK_SCREEN_UNPROTECTED" in all,
            "锁屏自愈闭环曾被白名单 400 静默拒绝（R31 P0），不得回退"
        )
        assertTrue(
            "USAGE_ACCESS_LOST" in all,
            "usageStats 恢复必须同时关闭 TamperDetector 那条独立的 USAGE_ACCESS_LOST"
        )
        assertTrue(
            childChecker.contains("if (\"usageStats\" in recoveredCaps) set += \"USAGE_ACCESS_LOST\""),
            "computeResolveCategories 必须含 usageStats 的边沿映射"
        )
    }

    // ==================== 工具 ====================

    /**
     * 服务端白名单：从 `RESOLVABLE_ALERT_CATEGORIES = setOf(...)` 块内逐行提取字符串常量。
     *
     * 刻意**不用** `substringBefore(")")`：注释里出现 `foo()` 之类的括号就会提前截断，
     * 让测试只看到旧的前几项（曾在本轮静态核对时实际踩中）。逐行解析并跳过注释行，
     * 遇到闭合括号行才停止。
     */
    private fun categoriesInWhitelist(): Set<String> {
        val lines = serverController
            .substringAfter("RESOLVABLE_ALERT_CATEGORIES = setOf(", "")
            .lines()
        val out = mutableSetOf<String>()
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith(")")) break
            if (t.startsWith("//") || t.startsWith("*")) continue
            Regex("\"([A-Z_]+)\"").find(t)?.let { out += it.groupValues[1] }
        }
        return out
    }

    /**
     * 孩子端实际会请求关闭的类别，两处来源：
     * - `computeResolveCategories` 里的 `set += "XXX"`（动态恢复类别）；
     * - 直接 `resolveActiveAlerts(listOf(RiskType.XXX.name))`（锁屏单独闭环）。
     */
    private fun categoriesProducedByChild(): Set<String> {
        val dynamic = Regex("""set \+= "([A-Z_]+)"""")
            .findAll(childChecker).map { it.groupValues[1] }
        val direct = Regex("""resolveActiveAlerts\(listOf\(RiskType\.([A-Z_]+)\.name\)\)""")
            .findAll(childChecker).map { it.groupValues[1] }
        return (dynamic + direct).toSet()
    }

    /** 单测 cwd 可能是模块目录或仓库根，逐级向上找，避免 IDE / 命令行差异造成假红。 */
    private fun readText(vararg candidates: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
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
        throw AssertionError(
            "找不到源码文件：${candidates.joinToString()}；cwd=$cwd。" +
                "若源码被移动，请同步修正本契约测试的路径。"
        )
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(needle.length, 1).count { it == needle }
}
