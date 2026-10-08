package com.padguard.parent

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 护航管控 - 家长控制端 Application 入口
 *
 * 基于 Hilt 依赖注入，初始化全局配置。
 * 参考架构：MDMesh (Kotlin Agent + Hilt DI)、Headwind MDM (模块化设计)
 *
 * 实时推送客户端（UsagePushClient）由首页 HomeViewModel 在注入后调用 start() 启动，
 * 按登录态/服务器地址自动管理 WS 连接，无需在 Application 里拿 Hilt 入口。
 */
@HiltAndroidApp
class PadGuardApplication : Application()
