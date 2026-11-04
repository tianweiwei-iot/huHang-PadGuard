package com.padguard.server.security

/**
 * 微信退款的最小能力面。
 *
 * ## 为什么单独抽一个接口
 * [AdminBillingService.refund] 的**顺序**是钱相关的最关键约束：
 * 必须先让微信受理，才允许把本地订单标记为 REFUNDED；顺序反了就是
 * "系统说已退款、钱实际没退"，只能人工补。
 *
 * 而 `WeChatPayClient` 是 final 类 + 需要真实商户配置（私钥/证书/APIv3 密钥），
 * 用真实实例根本走不到"微信返回失败"那条分支 —— 也就是说，
 * 如果不抽这个接口，这条最该被测试锁死的约束**永远不会有断言**，
 * 日后有人图省事把 `markRefundedIfPaid` 挪到 HTTP 调用之前，没有任何测试会红。
 *
 * 接口只包含退款流程用到的两个方法，实现仍由 [WeChatPayClient] 提供。
 */
interface WeChatRefundClient {

    /** 是否具备真实调用的条件（商户号/私钥/证书齐全）。未配置时调用方不应发起退款。 */
    fun isConfigured(): Boolean

    /**
     * 申请退款。
     *
     * @return 受理成功返回 `mapOf("refundId" to ..., "status" to ...)`；
     *   未配置 / 网络失败 / 微信拒绝一律返回 null（调用方据此判定"未受理"，不得改动订单状态）。
     */
    fun createRefund(
        orderNo: String,
        outRefundNo: String,
        refundFen: Int,
        totalFen: Int,
        reason: String
    ): Map<String, String>?
}
