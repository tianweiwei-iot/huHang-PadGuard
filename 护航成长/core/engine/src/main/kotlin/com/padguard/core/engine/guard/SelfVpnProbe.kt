package com.padguard.core.engine.guard

/**
 * 让 [EgressExposureDetector] 能区分"自家 Web 过滤隧道"与"孩子的绕过 VPN"。
 *
 * ## 为什么需要
 * 本应用的 Web 过滤是 DNS-only 的 VpnService，运行时活跃网络必然带 `TRANSPORT_VPN`。
 * 若 [EgressExposureDetector] 见 VPN 就报 [com.padguard.core.data.model.RiskType.PROXY_DETECTED]，
 * 隧道运行期会**持续误报**，既刷屏又淹没真实"孩子自建 VPN 绕过"信号（狼来了）。
 *
 * 实现由 app 模块提供（持有 [com.padguard.child.guard.WebGuardVpnService] 的进程内存活标记），
 * 接口定义在此以免 core:engine 反向依赖 app。
 */
interface SelfVpnProbe {
    /** 当前活跃 VPN 是否为本应用自身的 Web 过滤隧道 */
    fun isOwnVpnActive(): Boolean
}

/** 默认实现：无自家隧道概念（纯测试 / 未接入时兜底）。 */
object NoopSelfVpnProbe : SelfVpnProbe {
    override fun isOwnVpnActive(): Boolean = false
}
