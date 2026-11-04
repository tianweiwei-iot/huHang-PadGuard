package com.padguard.parent.data.remote

import android.content.Context
import com.tencent.mm.opensdk.modelpay.PayReq
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 微信 APP 支付调起器（家长端）。
 *
 * 职责边界（安全语义）：
 * - 只负责"把服务端 payParams 原样交给微信 SDK 拉起收银台 + 感知用户操作结果"；
 * - **绝不以微信本地回调结果（errCode）开通订阅** —— 本地回调可被伪造、可因进程被杀丢失，
 *   真实开通只认服务端收到微信支付回调后的 markPaid；
 * - 支付结果以调用方轮询服务端订单状态为准（SubscriptionViewModel.confirmPay）。
 *
 * appId 来源：服务端下单响应 payParams.appid（服务端配置哪套商户，客户端就调哪套），
 * 客户端不硬编码，避免"换商户号要发版"。
 */
@Singleton
class WeChatPayLauncher @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 微信客户端是否安装（与 SDK 实例无关，纯包名探测，可在收银台前置判断） */
    fun weChatInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(WECHAT_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * 拉起微信支付并挂起等待用户操作结果。
     *
     * @param payParams 服务端 CreateOrderResponse.payParams（键为微信协议字段：
     *   appid / partnerid / prepayid / package / noncestr / timestamp / sign）
     * @return 用户侧操作结果：SUCCESS / CANCEL / FAILED（见 [PayResult]）。
     *   注意：FAILED 也可能是"进程切换导致回调丢失"，调用方必须再轮询服务端确认。
     */
    suspend fun pay(payParams: Map<String, Any?>): Int {
        val appId = payParams["appid"] as? String
            ?: return PayResult.FAILED
        val api = ensureApi(appId)

        val req = PayReq().apply {
            this.appId = appId
            partnerId = payParams["partnerid"] as? String
            prepayId = payParams["prepayid"] as? String
            packageValue = payParams["package"] as? String ?: "Sign=WXPay"
            nonceStr = payParams["noncestr"] as? String
            timeStamp = payParams["timestamp"] as? String
            sign = payParams["sign"] as? String
            // v3 商户签名，固定 RSA（微信 APP 支付二次签名规范）
            signType = "RSA"
        }
        // sendReq 返回 false = 调起失败（微信版本过低/参数异常），不必等待回调
        if (!api.sendReq(req)) return PayResult.FAILED
        return withTimeoutOrNull(RESULT_TIMEOUT_MS) { payResults.firstOrNull() }
            ?: PayResult.FAILED
    }

    /** WXPayEntryActivity.onResp 唯一入口：把 errCode 投递给等待中的 [pay] */
    fun onPayResp(errCode: Int) {
        payResults.tryEmit(errCode)
    }

    /**
     * WXPayEntryActivity 专用：用"最近一次调起所用的真实 AppID"创建 IWXAPI 并处理回调 intent。
     * 微信回调路由只认包名（<applicationId>.wxapi.WXPayEntryActivity），
     * 但 IWXAPI 实例必须与调起时的 AppID 一致，否则 handleIntent 解析不出结果。
     * 从未调起过（lastAppId 为空）时返回 false —— 该 intent 不是我们发起的支付。
     */
    fun handlePayIntent(activity: android.app.Activity, intent: android.content.Intent): Boolean {
        val appId = lastAppId ?: return false
        return runCatching { ensureApi(appId).handleIntent(intent, activity as IWXAPIEventHandler) }
            .getOrDefault(false)
    }

    /**
     * 记下"有一次支付结果没能当场确认"。
     *
     * 触发场景：家长在微信收银台里停留时 App 进程被系统回收（低内存机型常态），
     * 从微信返回时 [WXPayEntryActivity] 是冷启动，内存里没有 lastAppId，
     * 无法把回调 intent 解析成 errCode。
     *
     * 这不代表支付失败 —— 钱可能已经扣了、服务端回调也可能已经开通订阅。
     * 所以只落一个标记，由订阅页读取后主动拉一次订单状态，避免"付了钱但界面说没付"。
     */
    fun markResultPending() {
        runCatching {
            prefs().edit().putBoolean(KEY_RESULT_PENDING, true).apply()
        }
    }

    /** 读取并清除"支付结果待确认"标记；为 true 时调用方应主动刷新一次订单状态。 */
    fun consumeResultPending(): Boolean = runCatching {
        val pending = prefs().getBoolean(KEY_RESULT_PENDING, false)
        if (pending) prefs().edit().putBoolean(KEY_RESULT_PENDING, false).apply()
        pending
    }.getOrDefault(false)

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var api: IWXAPI? = null

    @Volatile
    private var lastAppId: String? = null

    private val payResults = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 4)

    private fun ensureApi(appId: String): IWXAPI {
        val existing = api
        if (existing != null && lastAppId == appId) return existing
        // appId 变化（服务端切换商户号）必须重建 IWXAPI：复用旧 appId 的实例去调起新 appId
        // 的 PayReq，回调会因注册的 appId 不匹配而解析不出 errCode，支付必然 5 分钟超时。
        synchronized(this) {
            if (api != null && lastAppId == appId) return api!!
            val fresh = WXAPIFactory.createWXAPI(context.applicationContext, appId, true)
            fresh.registerApp(appId)
            api = fresh
            lastAppId = appId
            return fresh
        }
    }

    /** 用户操作结果码（透传微信 BaseResp.ErrCode 语义） */
    object PayResult {
        const val SUCCESS = 0
        const val FAILED = -1
        const val CANCEL = -2
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        /** 用户在微信收银台停留的最长等待；超时按 FAILED 处理（调用方仍会轮询服务端） */
        const val RESULT_TIMEOUT_MS = 5 * 60_000L

        const val PREFS_NAME = "wechat_pay_state"
        const val KEY_RESULT_PENDING = "result_pending"
    }
}
