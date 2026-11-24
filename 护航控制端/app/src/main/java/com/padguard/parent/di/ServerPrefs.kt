package com.padguard.parent.di

import android.app.Application
import android.content.SharedPreferences
import com.padguard.parent.BuildConfig
import com.padguard.parent.data.remote.EndpointSafety
import com.padguard.parent.data.remote.EndpointSafety.Verdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 家长端服务器地址的运行时配置。
 *
 * 为什么做成可变的运行时配置而不是写死 BuildConfig：
 * 实测中手机连不上电脑通常是「路由器把无线设备隔离」导致，与 App 无关；
 * 用 USB 数据线直连电脑后，电脑会拿到一个全新的 IP（如 192.168.42.1），
 * 写死的地址就失效了。把地址开放成手动填写，换任何网络都不用重打包。
 *
 * 默认值取 [BuildConfig.BASE_URL]（由构建期注入，仓库内无真实地址），
 * 用户首次填写后落 SharedPreferences，重启后依然生效。
 * 配合 [com.padguard.parent.data.remote.HostSelectionInterceptor] 实现「改完立即生效、无需重启 App」。
 *
 * ## 安全边界
 *
 * 所有写入（含存量数据读取）都过 [EndpointSafety]：公网地址必须使用 HTTPS，
 * 只有回环/私网才允许明文 HTTP。这样 Manifest 里为内网部署保留的
 * `usesCleartextTraffic="true"` 不会被误用于公网链路。
 */
@Singleton
class ServerPrefs @Inject constructor(
    private val application: Application
) {
    private val prefs: SharedPreferences =
        application.getSharedPreferences("padguard_parent_server", android.content.Context.MODE_PRIVATE)

    private val _baseUrl = MutableStateFlow(read())
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private fun read(): String {
        val saved = prefs.getString(KEY_BASE_URL, null)
        if (saved.isNullOrBlank()) return safeDefault()
        return migrate(saved) ?: safeDefault()
    }

    /**
     * 存量迁移：旧版本已把「明文公网地址」（历史构建脚本里的 8.148.68.183）持久化到本机。
     * 若直接沿用，会被网络层的 [EndpointSafety] 拦下，用户表现为「升级后突然全部连不上」
     * 且在没有网络时无从判断原因。这里优先尝试把 http 升级为 https —— 服务端已部署 TLS
     * 时零感知恢复；失败才回落到构建期默认地址，并把修正结果写回避免每次冷启动重复迁移。
     *
     * 注意：https 升级成功**不代表服务端一定启了 TLS**，此时仍会连接失败并给出可读报错，
     * 由用户在「高级设置」改成实际地址。这比静默放行明文公网更符合上线要求。
     */
    private fun migrate(saved: String): String? {
        when (val verdict = EndpointSafety.check(saved, BuildConfig.ALLOW_CLEARTEXT_PUBLIC)) {
            is Verdict.Allowed -> return verdict.normalizedUrl
            is Verdict.Rejected -> {
                val raw = saved.trim()
                if (raw.startsWith("http://", ignoreCase = true)) {
                    // startsWith 是 ignoreCase 的，但 removePrefix 不是：
                    // 输入 HTTP://... 时前缀删不掉，会拼出 https://HTTP://... 的畸形地址，
                    // 被判非法后直接丢弃用户配置回落出厂默认。用分隔符切分才对大小写免疫。
                    val upgraded = "https://" + raw.substringAfter("://")
                    val retry = EndpointSafety.check(upgraded, BuildConfig.ALLOW_CLEARTEXT_PUBLIC)
                    if (retry is Verdict.Allowed) {
                        prefs.edit().putString(KEY_BASE_URL, retry.normalizedUrl).commit()
                        return retry.normalizedUrl
                    }
                }
                prefs.edit().remove(KEY_BASE_URL).commit()
                return null
            }
        }
    }

    private fun safeDefault(): String {
        val v = EndpointSafety.check(BuildConfig.BASE_URL, BuildConfig.ALLOW_CLEARTEXT_PUBLIC)
        // 默认地址本身不合法时保持原值：网络层会给出明确拒绝原因，而不是静默换到另一个地址。
        return (v as? Verdict.Allowed)?.normalizedUrl ?: BuildConfig.BASE_URL
    }

    fun getBaseUrl(): String = _baseUrl.value

    /** @return false 表示地址被 [EndpointSafety] 拒绝，原值保持不变。 */
    fun setBaseUrl(url: String): Boolean {
        val verdict = EndpointSafety.check(url, BuildConfig.ALLOW_CLEARTEXT_PUBLIC)
        if (verdict !is Verdict.Allowed) return false
        val normalized = verdict.normalizedUrl
        prefs.edit().putString(KEY_BASE_URL, normalized).commit()
        _baseUrl.value = normalized
        return true
    }

    /**
     * 恢复出厂默认服务器地址：用户填错导致彻底连不上时的兜底回退。
     *
     * 手动填写的地址一旦写错（把端口填进地址栏、IP 少一位等），App 会彻底连不上，
     * 而"连不上"时又没有任何界面能引导修正——所以必须给用户一个不依赖网络的回退路径。
     */
    fun resetToDefault() {
        prefs.edit().remove(KEY_BASE_URL).commit()
        _baseUrl.value = safeDefault()
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
    }
}
