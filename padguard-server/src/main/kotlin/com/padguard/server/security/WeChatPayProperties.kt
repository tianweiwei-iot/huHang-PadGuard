package com.padguard.server.security

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 微信支付 v3 配置。
 *
 * 全部为可选项；未配置（enabled=false 或关键字段为空）时，[WeChatPayVerifier] 与 [WeChatPayClient]
 * 一律 fail-closed：回调拒绝入账、下单直接报错，绝不走"能下单没人收款"的口子。
 *
 * 凭证获取：微信商户平台 →「账户中心 → API 安全」下载证书（apiclient_cert.pem / apiclient_key.pem），
 * 设置 32 字节 APIv3 密钥，并记下商户 API 证书序列号（serial_no）。
 *
 * 平台证书（微信用来给回调签名的公钥）推荐留空 platform-certs，由 [WeChatPayVerifier] 在启动时
 * 自动从 /v3/certificates 拉取并缓存（需商户私钥+序列号可用）；也可手动把 .pem 放到 platform-cert-dir。
 */
@Component
@ConfigurationProperties(prefix = "padguard.billing.wechat")
class WeChatPayProperties {
    /** 总开关：false 时即使填了其他字段也不启用真实微信支付 */
    var enabled: Boolean = false

    /** 微信开放平台/公众平台 AppID（移动应用） */
    var appId: String? = null

    /** 微信支付商户号 */
    var mchId: String? = null

    /** 商户 API 证书序列号（serial_no） */
    var serialNo: String? = null

    /**
     * APIv3 密钥（32 字节）。支持三种写法：
     * - 长度为 44 的 Base64（解码后恰 32 字节，推荐）；
     * - 长度为 64 的十六进制；
     * - 长度为 32 的明文字符串（按 ISO-8859-1 取字节）。
     */
    var apiV3Key: String? = null

    /** 商户 API 私钥（apiclient_key.pem 内容，PKCS#8，-----BEGIN PRIVATE KEY-----） */
    var privateKeyPem: String? = null

    /**
     * 商户 API 私钥文件路径（PKCS#8 .pem）。生产推荐用此方式挂载密钥文件，避免把私钥写进环境变量。
     * 与 [privateKeyPem] 二选一；两者都填时以 [privateKeyPem] 优先。
     */
    var privateKeyPath: String? = null

    /** 支付结果异步回调地址（需公网可达 HTTPS，且已在商户平台配置） */
    var notifyUrl: String? = null

    /** 微信支付 API 基地址（生产与沙箱一致，均为 api.mch.weixin.qq.com） */
    var baseUrl: String = "https://api.mch.weixin.qq.com"

    /** 手动放置微信平台证书（.pem）的目录；留空则依赖自动拉取 */
    var platformCertDir: String? = null

    /** 内联平台证书 PEM 列表（与 platformCertDir 二选一或并存） */
    var platformCerts: List<String>? = null

    /** 启动时自动拉取平台证书（需 enabled + 商户私钥 + 序列号齐全） */
    var certAutoRefresh: Boolean = true

    /** 回调验签所需最小配置：开关开启 + APIv3 密钥存在 */
    fun readyForVerify(): Boolean = enabled && !apiV3Key.isNullOrBlank()

    /** 解析商户私钥：优先 [privateKeyPem]，否则从 [privateKeyPath] 读文件 */
    fun resolvePrivateKey(): String? =
        privateKeyPem?.takeIf { it.isNotBlank() }
            ?: privateKeyPath?.takeIf { it.isNotBlank() }?.let { runCatching { java.io.File(it).readText() }.getOrNull() }

    /** 下单所需完整配置：在 readyForVerify 基础上还需 appId / mchId / serialNo / 私钥 */
    fun readyForPay(): Boolean =
        readyForVerify() && !appId.isNullOrBlank() && !mchId.isNullOrBlank()
            && !serialNo.isNullOrBlank() && !resolvePrivateKey().isNullOrBlank()
}
