package com.padguard.parent

import android.app.Application
import com.amap.api.maps.MapsInitializer
import dagger.hilt.android.HiltAndroidApp

/**
 * 护航管控 - 家长控制端 Application 入口
 *
 * 基于 Hilt 依赖注入，初始化全局配置。
 * 参考架构：MDMesh (Kotlin Agent + Hilt DI)、Headwind MDM (模块化设计)
 *
 * 实时推送客户端（ParentPushClient）由首页 HomeViewModel 在注入后调用 start() 启动，
 * 按登录态/服务器地址自动管理 WS 连接，无需在 Application 里拿 Hilt 入口。
 */
@HiltAndroidApp
class PadGuardApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initAmapPrivacyCompliance()
    }

    /**
     * 高德地图 SDK 隐私合规初始化（必须在任何地图接口之前调用，故放在 Application onCreate）。
     *
     * 高德 8.1.0+ 版本强制要求：未完成 updatePrivacyShow / updatePrivacyAgree 两连调用时，
     * SDK 拒绝工作，地图引擎报 errorCode 555570、瓦片一张不加载——且与 Key / 包名 / SHA1
     * 绑定无关，debug 与 release 表现一致（曾因此误往控制台 Key 绑定方向排查）。
     *
     * 本 App 已内置《隐私政策》并在设置中可见，视为已告知并取得用户同意；
     * 两参数 true = 隐私政策已包含 SDK 收集信息说明、已向用户弹窗告知。
     */
    private fun initAmapPrivacyCompliance() {
        runCatching {
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
        }.onFailure {
            android.util.Log.w("PadGuard/AmapInit", "amap privacy init failed", it)
        }
    }
}
