package com.padguard.child.guard

import com.padguard.core.engine.guard.SelfVpnProbe
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SelfVpnProbe] 的 app 侧实现：本应用的 Web 过滤隧道（VpnService）是否在跑。
 *
 * 读到的是 [WebGuardVpnService.running] 进程内标记 —— 隧道被系统撤销/自身异常退出时
 * 会先复位，故此探针能正确区分"自家隧道"与"孩子自建的绕过 VPN"。
 */
@Singleton
class WebGuardSelfVpnProbe @Inject constructor() : SelfVpnProbe {
    override fun isOwnVpnActive(): Boolean = WebGuardVpnService.running
}
