package com.padguard.core.engine.enforcer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.padguard.core.common.Logger
import com.padguard.core.data.model.policy.KeywordLevel
import com.padguard.core.data.model.policy.WebFilterMode
import com.padguard.core.data.model.policy.WebPolicy
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上网内容管控。
 *
 * ## 先说清楚能力边界（这一条比实现更重要）
 * 在**不 root、不装证书**的前提下，Android 上没有任何办法看到 HTTPS 的完整 URL。
 * 市面上被管控端的三条技术路线各有硬伤：
 *
 * | 路线 | 可见信息 | 硬伤 |
 * |---|---|---|
 * | VpnService 本地拦截（推荐） | DNS 域名、TLS SNI 域名 | 看不到路径与关键词；用户可关 VPN（DO 下可用 `DISALLOW_CONFIG_VPN` + alwaysOn 封住） |
 * | 无障碍服务读地址栏（本类采用） | 浏览器地址栏文本、页面标题 | 依赖各浏览器控件 id，换版本会失效；App 内嵌 WebView 读不到 |
 * | 私有 DNS / 代理 | 域名 | 需要服务端基础设施，且 App 内直连 IP 可绕过 |
 *
 * P0 阶段的取舍：**域名级过滤 + 浏览器收口**。
 * - 域名匹配（[match]）由无障碍服务/VpnService 调用，两条通道共用同一套规则，避免两份实现打架；
 * - 浏览器收口（[apply]）通过挂起非许可浏览器，把上网入口压缩到可控范围 ——
 *   这是**真正见效**的一步，因为哪怕关键词过滤失效，孩子也只能用被允许的浏览器。
 *
 * 关键词级过滤在本阶段只做**标题/URL 文本匹配并记录**，不宣称能拦截页面正文 ——
 * 宣称做不到的能力，等于给家长一个假的安全感，这比不做更糟。
 */
@Singleton
class WebEnforcer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admin: DeviceAdminBridge
) {

    /** 上一轮被挂起的浏览器，策略变更时精准解挂 */
    private var lastSuspendedBrowsers: Set<String> = emptySet()

    /** 编译后的规则缓存，避免每次 URL 匹配都重新切分字符串 */
    @Volatile
    private var compiled: CompiledRules = CompiledRules()

    fun apply(policy: WebPolicy): EnforceReport {
        val report = EnforceReport()
        compiled = compile(policy)

        if (policy.mode == WebFilterMode.OFF && policy.allowBrowsers.isEmpty()) {
            report.note("web.filter=OFF")
            releaseAllBrowsers(report)
            return report
        }

        report.note("web.filter=${policy.mode}(黑${policy.blacklist.size}/白${policy.whitelist.size})")

        // 浏览器收口
        if (policy.allowBrowsers.isEmpty()) {
            report.markUnsupported(
                "web.browserLimit",
                "未指定允许的浏览器，域名过滤仅依赖无障碍服务，可被非常规浏览器绕过"
            )
        } else {
            restrictBrowsers(policy, report)
        }

        // 明确声明"做不到"的部分，让管控端能如实展示给家长
        if (policy.keywords.custom.isNotEmpty() || policy.keywords.level != KeywordLevel.MIDDLE) {
            report.note("web.keywords=${policy.keywords.level}(${compiled.keywords.size}项,仅匹配URL与标题)")
        }
        if (policy.blockPopup) {
            report.markUnsupported("web.blockPopup", "弹窗拦截需浏览器自身支持，终端侧无法干预")
        }
        if (policy.blockDownload) {
            val result = admin.setUserRestriction(
                android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                true
            )
            // 只能阻止"下载后安装"，无法阻止下载文件本身
            report.record("web.blockDownload(仅阻止安装)", result)
        }
        return report
    }

    /**
     * 判定一个 URL 是否放行。由无障碍服务或 VpnService 调用。
     *
     * @param url 完整 URL 或裸域名
     * @param pageTitle 页面标题（无障碍可拿到，用于关键词匹配）；VpnService 通道传空
     */
    fun match(url: String, pageTitle: String = ""): WebVerdict {
        val rules = compiled
        if (rules.mode == WebFilterMode.OFF) return WebVerdict.Allowed

        val host = extractHost(url) ?: return WebVerdict.Allowed
        val lowerUrl = url.lowercase()

        when (rules.mode) {
            WebFilterMode.WHITELIST -> {
                val hit = rules.whitelist.any { matchesDomain(host, it) }
                if (!hit) {
                    return WebVerdict.Blocked(host, BlockReason.NOT_IN_WHITELIST)
                }
            }

            WebFilterMode.BLACKLIST -> {
                val hit = rules.blacklist.firstOrNull { matchesDomain(host, it) }
                if (hit != null) {
                    return WebVerdict.Blocked(host, BlockReason.IN_BLACKLIST, hit)
                }
            }

            WebFilterMode.OFF -> return WebVerdict.Allowed
        }

        // 关键词：URL 与标题都查。命中即拦截，但只对显式配置生效，避免误杀正常学习内容
        val keyword = rules.keywords.firstOrNull { lowerUrl.contains(it) || pageTitle.lowercase().contains(it) }
        if (keyword != null) {
            return WebVerdict.Blocked(host, BlockReason.KEYWORD, keyword)
        }

        return WebVerdict.Allowed
    }

    /** 该浏览器是否在允许清单内（无障碍服务据此决定是否需要读地址栏） */
    fun isMonitoredBrowser(packageName: String): Boolean =
        compiled.allowBrowsers.isEmpty() || packageName in compiled.allowBrowsers

    private fun restrictBrowsers(policy: WebPolicy, report: EnforceReport) {
        if (!admin.can(Capability.SUSPEND_PACKAGES)) {
            report.markUnsupported("web.browserLimit", "需要 Device Owner / Profile Owner")
            return
        }
        val allowed = policy.allowBrowsers.toSet()
        val installed = installedBrowsers()
        val toSuspend = (installed - allowed).toList()
        val toRelease = (lastSuspendedBrowsers - toSuspend.toSet()).toList()

        if (toRelease.isNotEmpty()) {
            admin.setPackagesSuspended(toRelease, false)
            report.note("web.browserRelease=${toRelease.size}")
        }
        if (toSuspend.isNotEmpty()) {
            val result = admin.setPackagesSuspended(toSuspend, true)
            lastSuspendedBrowsers = result.succeeded.toSet()
            report.note("web.browserSuspend=${result.succeeded.size}")
            if (result.failed.isNotEmpty()) {
                report.markUnsupported("web.browserSuspend", "系统拒绝挂起: ${result.failed.take(5)}")
            }
        } else {
            lastSuspendedBrowsers = emptySet()
        }
    }

    private fun releaseAllBrowsers(report: EnforceReport) {
        if (lastSuspendedBrowsers.isEmpty()) return
        admin.setPackagesSuspended(lastSuspendedBrowsers.toList(), false)
        report.note("web.browserRelease=${lastSuspendedBrowsers.size}")
        lastSuspendedBrowsers = emptySet()
    }

    /**
     * 枚举系统里能处理 http(s) 的应用。
     *
     * 注意 targetSdk 30+ 起需要在 manifest 声明 `<queries>` 才能看到这些应用，
     * 否则返回空列表 —— 这个坑会让浏览器收口静默失效。
     */
    private fun installedBrowsers(): Set<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
        }.getOrNull().orEmpty()
        val browsers = resolved.mapNotNull { it.activityInfo?.packageName }
            .filter { it != context.packageName }
            .toSet()
        if (browsers.isEmpty()) {
            Logger.w(TAG) { "no browser resolved — check <queries> declaration in manifest" }
        }
        return browsers
    }

    private fun compile(policy: WebPolicy) = CompiledRules(
        mode = policy.mode,
        blacklist = policy.blacklist.map { normalizePattern(it) }.filter { it.isNotBlank() },
        whitelist = policy.whitelist.map { normalizePattern(it) }.filter { it.isNotBlank() },
        keywords = (builtinKeywords(policy.keywords.level) + policy.keywords.custom)
            .map { it.lowercase().trim() }
            .filter { it.isNotBlank() }
            .toSet(),
        allowBrowsers = policy.allowBrowsers.toSet()
    )

    /** 去掉协议、路径与前导 `*.`，统一成可比较的域名后缀 */
    private fun normalizePattern(raw: String): String = raw.trim().lowercase()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
        .removePrefix("*.")

    private fun extractHost(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val host = runCatching { Uri.parse(trimmed).host }.getOrNull()
            ?: trimmed.removePrefix("http://").removePrefix("https://").substringBefore('/')
        return host.lowercase().takeIf { it.isNotBlank() }?.removePrefix("www.")
    }

    /**
     * 域名匹配：`example.com` 同时匹配 `example.com` 与 `sub.example.com`。
     *
     * 这里刻意**不做**子串匹配 —— `evil-example.com` 不应命中 `example.com` 规则，
     * 而简单的 `contains` 会误判，进而在白名单模式下放行钓鱼域名。
     */
    private fun matchesDomain(host: String, pattern: String): Boolean =
        host == pattern || host.endsWith(".$pattern")

    /**
     * 内置关键词。
     *
     * 仅覆盖最基础的类别词，真实词库应由服务端下发并可热更新 ——
     * 把词库编进 APK 意味着每次调整都要发版，运营上不可接受。
     */
    private fun builtinKeywords(level: KeywordLevel): List<String> = when (level) {
        KeywordLevel.LOW -> listOf("赌博", "博彩", "色情")
        KeywordLevel.MIDDLE -> listOf("赌博", "博彩", "色情", "成人", "彩票", "私服", "外挂")
        KeywordLevel.HIGH -> listOf(
            "赌博", "博彩", "色情", "成人", "彩票", "私服", "外挂",
            "交友", "直播打赏", "贷款", "整容", "减肥药"
        )
    }

    private data class CompiledRules(
        val mode: WebFilterMode = WebFilterMode.OFF,
        val blacklist: List<String> = emptyList(),
        val whitelist: List<String> = emptyList(),
        val keywords: Set<String> = emptySet(),
        val allowBrowsers: Set<String> = emptySet()
    )

    companion object {
        private const val TAG = "WebEnforcer"
    }
}

sealed interface WebVerdict {
    data object Allowed : WebVerdict

    data class Blocked(
        val host: String,
        val reason: BlockReason,
        val matched: String = ""
    ) : WebVerdict {
        fun message(): String = when (reason) {
            BlockReason.NOT_IN_WHITELIST -> "该网站不在允许访问的名单内"
            BlockReason.IN_BLACKLIST -> "该网站已被家长/学校限制访问"
            BlockReason.KEYWORD -> "页面内容包含受限关键词"
        }
    }
}

enum class BlockReason { NOT_IN_WHITELIST, IN_BLACKLIST, KEYWORD }
