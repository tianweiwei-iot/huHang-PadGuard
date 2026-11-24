package com.padguard.server.security

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 限流阈值配置（P2-6：上线后按真实流量数据收紧，无需改代码）。
 *
 * 覆盖五类匿名可爆破入口（见 [RateLimit]）：
 *  - login          家长密码登录
 *  - register       家长注册（匿名写库，可批量建号/枚举手机号）
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
     * 家长注册：默认 3 次/分钟，只按 IP 计（账号维度对注册没有意义，见下）。
     *
     * 注册是唯一"匿名 + 写库"的入口，之前**完全没有频控**：一次脚本就能批量建号，
     * 每个号还白送一份试用订阅（billing.statusOf 会在注册时建台账），
     * 既是刷试用/灌库的入口，也是手机号枚举的放大器（"已注册"与"格式不对"两种回包即可判定）。
     *
     * 阈值取得比登录更紧（3 次）：正常家长一台手机一分钟内不会注册三次以上，
     * 而批量注册脚本会立刻撞墙。**不按账号维度计数**——注册的账号维度键是被注册的手机号，
     * 给新建号码单独开桶既没意义（每个号本来就只注册一次），又会放大计数桶用量。
     */
    var register: Int = 3

    /**
     * 短信登录（阶段 1 体检 F-02）：默认 5 次/分钟/维度。
     *
     * 该端点匿名可达，且当前未接短信服务商（非开发 profile 由 AuthService 硬拒），
     * 但仍是手机号探测与请求轰炸入口。与密码登录分桶，避免互相挤占额度。
     * 环境变量：`PADGUARD_RATELIMIT_LOGINSMS`。
     */
    var loginSms: Int = 5

    /**
     * 短信验证码发送（阶段 1 体检 F-02）：默认 3 次/分钟/维度。
     *
     * 接口当前是空实现；一旦接入服务商，它就是唯一「匿名即可触发外部计费调用」的入口，
     * 因此阈值取最紧。与 login_sms 分桶，防止打满一个入口把另一个也拖住。
     * 环境变量：`PADGUARD_RATELIMIT_SMSSEND`。
     */
    var smsSend: Int = 3

    /**
     * 支付 / 退款回调（匿名可达但必须验签）：默认 30 次/分钟/IP。
     *
     * 回调无权鉴权，每次请求最多读 1MB body；若攻击者伪造齐全的微信签名头且 serial
     * 命中已知平台证书，会一路走到 SHA256withRSA 验签（CPU 密集）。这里按 IP 限流，
     * 阻断把公开回调端点当作验签 CPU / 带宽放大器的用法。正常支付网关回调远低于此阈值。
     * 环境变量：`PADGUARD_RATELIMIT_BILLINGNOTIFY`。
     */
    var billingNotify: Int = 30

    /**
     * 限流的 IP 维度是否采信「受信任反向代理写入的客户端 IP」（默认 **false**）。
     *
     * **键名 `trustForwardedFor` 是历史命名，实现并不采信 `X-Forwarded-For`。**
     * 打开后走 `ClientIps.viaTrustedProxy()`，只采信 **`X-Real-IP`**：本项目 Nginx 用
     * `proxy_set_header X-Real-IP $remote_addr` **覆盖**该头，客户端自带的同名头会被丢弃，
     * 因此经代理后它等价于 TCP 对端地址；而 XFF 用的是 `$proxy_add_x_forwarded_for`
     * （追加），客户端伪造的第一段会被原样保留，刻意不用于限流。
     *
     * ## 为什么默认必须关
     * 代理头是**客户端可以任意伪造的请求头**。直连暴露时若采信它，
     * 攻击者每发一次请求换一个值就得到一个全新的 IP 计数桶 —— IP 维度频控形同虚设，
     * 只剩账号维度在拦。默认取 TCP 对端地址（`request.remoteAddr`），
     * 由内核从三次握手得来，无法伪造。
     *
     * ## 什么时候可以打开
     * 仅当服务端**确实部署在会覆盖 `X-Real-IP` 的受信任反向代理之后**
     * （如本次生产部署的 Nginx），且所有流量必过代理。
     * 直连暴露（家庭局域网部署的常见形态）**绝不能开**；只配了 XFF 没配 X-Real-IP 也不行，
     * 此时会回落到 TCP 对端地址，全员共用代理 IP 桶 → 误 429。
     *
     * 环境变量：`PADGUARD_RATELIMIT_TRUSTFORWARDEDFOR=true`
     */
    var trustForwardedFor: Boolean = false
}
