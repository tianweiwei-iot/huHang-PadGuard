package com.padguard.parent.wxapi

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
 *
 * ## 退出路径必须穷尽，不能只依赖 onResp
 * 本页是透明主题，没有内容也没有返回按钮。只要有一条路径没有 `finish()`，
 * 家长看到的就是"一整页空白、返回键还被拦住"，只能杀进程。
 * 三种情况都必须退场：
 * 1. 正常回调 → `onResp` 里 finish；
 * 2. 进程在微信收银台期间被杀 → 冷启动后内存里没有 appId，`handlePayIntent` 返回 false，
 *    **永远等不到 onResp**，必须在 onCreate 里主动 finish 并留下"待确认"标记；
 * 3. SDK 解析成功但迟迟不回调（个别机型/微信版本）→ 兜底延时 finish。
 */
@AndroidEntryPoint
class WXPayEntryActivity : ComponentActivity(), IWXAPIEventHandler {

    @Inject
    lateinit var payLauncher: WeChatPayLauncher

    private val safetyHandler = Handler(Looper.getMainLooper())
    private val safetyFinish = Runnable { if (!isFinishing) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!payLauncher.handlePayIntent(this, intent)) {
            // 冷启动无法解析回调：钱可能已经扣了，不能当成失败。
            // 只留一个标记，由订阅页读取后主动刷新订单状态。
            payLauncher.markResultPending()
            Toast.makeText(this, "支付结果待确认，请回到 App 查看订阅状态", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // 兜底：SDK 未回调时也不要把家长留在空白页上
        safetyHandler.postDelayed(safetyFinish, SAFETY_FINISH_MS)
    }

    /** 从收银台返回时可能带新 intent（singleTop 复用），同样要交给 SDK 处理 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        payLauncher.handlePayIntent(this, intent)
        safetyHandler.removeCallbacks(safetyFinish)
        safetyHandler.postDelayed(safetyFinish, SAFETY_FINISH_MS)
    }

    override fun onReq(req: BaseReq?) = Unit

    override fun onResp(resp: BaseResp?) {
        payLauncher.onPayResp(resp?.errCode ?: -1)
        finish()
    }

    override fun onDestroy() {
        safetyHandler.removeCallbacks(safetyFinish)
        super.onDestroy()
    }

    private companion object {
        /**
         * 兜底退场时间。
         *
         * 微信 SDK 收到回调后在主线程同步分发，正常是毫秒级；留 5 秒是为了覆盖
         * "handleIntent 返回 true 但尚未分发"的极短窗口，同时不会让家长久等。
         */
        const val SAFETY_FINISH_MS = 5_000L
    }
}
