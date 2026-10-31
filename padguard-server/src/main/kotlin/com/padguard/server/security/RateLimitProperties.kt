package com.padguard.server.security

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 限流阈值配置（P2-6：上线后按真实流量数据收紧，无需改代码）。
 *
 * 覆盖四类匿名可爆破入口（见 [RateLimit]）：
 *  - login          家长密码登录
 *  - adminLogin     后台登录
 *  - bind           孩子端绑定（绑定码可匿名穷举）
 *  - bindByAccount  按账号绑定（家长密码可匿名穷举）
 *
 * 维度均为 **IP + 账号双计数**（见 [RateLimit.require]）。阈值先按"家庭 NAT 下正常用户
 * 几乎不会触发、但能挡住自动化爆破"的原则取偏紧默认值；生产可通过环境变量
 * `PADGUARD_RATELIMIT_LOGIN` 等覆盖（Spring relaxed binding：下划线/小写均可）。
 */
@Component
@ConfigurationProperties("padguard.ratelimit")
class RateLimitProperties {
    /** 家长登录：默认 5 次/分钟/维度（正常家长连续输错 5 次才会被拦，爆破则迅速耗尽） */
    var login: Int = 5

    /** 后台登录：默认 5 次/分钟/维度 */
    var adminLogin: Int = 5

    /** 设备绑定（绑定码）：默认 8 次/分钟/维度 */
    var bind: Int = 8

    /** 按账号绑定（家长密码）：默认 8 次/分钟/维度 */
    var bindByAccount: Int = 8

    /**
     * 限流的 IP 维度是否采信 `X-Forwarded-For` 头（默认 **false**）。
     *
     * ## 为什么默认必须关
     * `X-Forwarded-For` 是**客户端可以任意伪造的请求头**。限流的 IP 维度若直接采信它，
     * 攻击者每发一次请求换一个 XFF 值，就得到一个全新的 IP 计数桶 ——
     * IP 维度频控**形同虚设**，只剩账号维度在拦。而账号维度又只对"已存在的账号"计数
     * （避免被用来恶意锁定受害者），于是爆破面被完全打开。
     *
     * 默认改取 TCP 对端地址（`request.remoteAddr`），它由内核从三次握手得来，无法伪造。
     *
     * ## 什么时候可以打开
     * 仅当服务端**确实部署在会重写该头的受信任反向代理之后**（如本次生产部署的 Nginx，
     * 它用 `$proxy_add_x_forwarded_for` 追加真实客户端 IP）。此时所有流量必过代理，
     * 客户端自带的 XFF 会被代理覆盖/追加，采信才是安全的。
     * 直连暴露（家庭局域网部署的常见形态）**绝不能开**。
     *
     * 环境变量：`PADGUARD_RATELIMIT_TRUSTFORWARDEDFOR=true`
     */
    var trustForwardedFor: Boolean = false
}
