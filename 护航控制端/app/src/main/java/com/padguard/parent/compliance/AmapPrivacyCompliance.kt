package com.padguard.parent.compliance

import android.content.Context
import android.util.Log
import com.amap.api.maps.MapsInitializer

/**
 * 高德地图 SDK 隐私合规状态的**唯一同步出口**。
 *
 * ## 事故背景
 * 旧实现把 `updatePrivacyShow(true,true)` + `updatePrivacyAgree(true)` 无条件放在
 * `Application.onCreate`：用户还没看到协议、更没勾选，SDK 已被宣告"已取得同意"。
 * 这在 PIPL 第 14 条下属于**未取得有效同意即处理**，应用市场隐私检测会直接命中；
 * 同时它也让 `PrivacyConsentPrefs` 的显式勾选变成摆设——真正的同意记录在 SDK 侧早已是 true。
 *
 * ## 正确时序
 * - 启动时：`updatePrivacyShow(true, 是否已同意)` + `updatePrivacyAgree(是否已同意)`；
 * - 用户勾选同意：`agree()` 落盘后调用 [sync] 置 true；
 * - 用户取消勾选/不同意：`revoke()` 清除留痕后调用 [sync] 置 false。
 *
 * 高德要求这两个调用发生在**任何地图接口之前**；本对象在 Application 启动与同意状态变化时
 * 都会同步，MapView 只会在登录后的定位页创建（登录已被同意闸门拦截）。
 */
object AmapPrivacyCompliance {

    fun sync(context: Context, agreed: Boolean) {
        runCatching {
            MapsInitializer.updatePrivacyShow(context, true, agreed)
            MapsInitializer.updatePrivacyAgree(context, agreed)
        }.onFailure {
            Log.w("PadGuard/AmapConsent", "amap privacy sync failed (agreed=$agreed)", it)
        }
    }
}
