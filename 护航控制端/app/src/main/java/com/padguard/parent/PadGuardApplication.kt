package com.padguard.parent

import android.app.Application
import com.padguard.parent.compliance.AmapPrivacyCompliance
import com.padguard.parent.di.PrivacyConsentPrefs
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 护航管控 - 家长控制端 Application 入口
 *
 * 基于 Hilt 依赖注入，初始化全局配置。
 *
 * 实时推送客户端（ParentPushClient）由首页 HomeViewModel 在注入后调用 start() 启动，
 * 按登录态/服务器地址自动管理 WS 连接，无需在 Application 里拿 Hilt 入口。
 */
@HiltAndroidApp
class PadGuardApplication : Application() {

    @Inject
    lateinit var consentPrefs: PrivacyConsentPrefs

    override fun onCreate() {
        super.onCreate()
        // 先挂前台可见性监听：下面的轮询循环据此在后台暂停（见 AppVisibility）
        com.padguard.parent.util.AppVisibility.attach(this)
        // 高德 SDK 的隐私状态必须与用户真实同意状态一致：
        // 未同意时绝不能 updatePrivacyAgree(true)（旧实现即因此构成"未同意先处理"）。
        AmapPrivacyCompliance.sync(this, consentPrefs.isAgreed())
    }
}
