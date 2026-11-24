package com.padguard.core.engine.guard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import com.padguard.core.common.Logger
import com.padguard.core.data.model.RiskLevel
import com.padguard.core.data.model.RiskType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 代理 / VPN 暴露检测（P0-3）。
 *
 * 导入自定义 CA 的主动中间人（Fiddler / Charles）常配合代理或 VPN 解密观测 HTTPS / MQTT 流量。
 * 本端已用证书固定阻断解密，但代理 / VPN 本身是"孩子在尝试绕过观测"的强信号，
 * 命中即上报 [RiskType.PROXY_DETECTED] HIGH，让家长立刻可见并提示检查网络环境。
 * 不主动阻断连接（避免误伤正常企业 / VPN 网络）。
 */
@Singleton
class EgressExposureDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val selfVpn: SelfVpnProbe = NoopSelfVpnProbe
) {
    fun detect(): List<TamperFinding> {
        val detail = mutableMapOf<String, String>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()

        // 1) 系统全局 HTTP 代理（设置 → WLAN → 高级代理，或 adb 写入）
        runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.HTTP_PROXY).orEmpty()
        }.getOrDefault("").let { if (it.isNotBlank()) detail["global_http_proxy"] = it }

        // 2) 当前网络是否走 VPN 传输
        // 注意：本应用自身的 Web 过滤隧道就是 VpnService，运行时活跃网络必然带 TRANSPORT_VPN。
        // 若不做区分，隧道运行期会持续误报 PROXY_DETECTED（狼来了），反而淹没真实"孩子自建 VPN 绕过"信号。
        // 因此只在「VPN 在跑且不是我们自己的隧道」时才视为暴露 —— 自家隧道被系统撤销的瞬间，
        // selfVpn.isOwnVpnActive() 会先归 false，此时若有别的 VPN 顶上即正确命中。
        val ownVpnUp = selfVpn.isOwnVpnActive()
        runCatching {
            val net = cm.activeNetwork
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }.getOrDefault(false).let { vpnActive ->
            if (vpnActive && !ownVpnUp) detail["vpn"] = "active"
        }

        // 3) 当前 Wi-Fi 是否设了代理（API 31+ 的 LinkProperties.httpProxy）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val net = cm.activeNetwork ?: return@runCatching false
                cm.getLinkProperties(net)?.httpProxy != null
            }.getOrDefault(false).let { if (it) detail["wifi_proxy"] = "set" }
        }

        if (detail.isEmpty()) return emptyList()
        Logger.w(TAG) { "egress exposure detected: $detail" }
        return listOf(
            TamperFinding(
                type = RiskType.PROXY_DETECTED,
                level = RiskLevel.HIGH,
                detail = detail
            )
        )
    }

    companion object {
        private const val TAG = "EgressExposureDetector"
    }
}
