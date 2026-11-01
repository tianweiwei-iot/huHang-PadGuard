package com.padguard.parent.wxapi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.padguard.parent.data.remote.WeChatPayLauncher
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 微信支付回调页（路径 [applicationId].wxapi.WXPayEntryActivity 是微信 SDK 的硬性约定，
 * 不可移动、不可改名）。
 *
 * 职责单一：把微信支付结果转投 [WeChatPayLauncher] 的结果流，供挂起中的 pay() 恢复，
 * 然后立即退场 —— 本页不渲染任何内容（透明主题），也不做任何业务判断：
 * 订阅开通与否由服务端支付回调决定，这里的结果只用于客户端 UI 状态收敛。
 *
 * 继承 [ComponentActivity]：@AndroidEntryPoint 的硬性要求；对微信 SDK 无影响
 * （handleIntent 只需要 IWXAPIEventHandler，不关心 Activity 具体类型）。
 */
@AndroidEntryPoint
class WXPayEntryActivity : ComponentActivity(), IWXAPIEventHandler {

    @Inject
    lateinit var payLauncher: WeChatPayLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        payLauncher.handlePayIntent(this, intent)
    }

    /** 从收银台返回时可能带新 intent（singleTop 复用），同样要交给 SDK 处理 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        payLauncher.handlePayIntent(this, intent)
    }

    override fun onReq(req: BaseReq?) = Unit

    override fun onResp(resp: BaseResp?) {
        payLauncher.onPayResp(resp?.errCode ?: -1)
        finish()
    }
}
