package com.padguard.parent

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 护航管控 - 家长控制端 Application 入口
 *
 * 基于 Hilt 依赖注入，初始化全局配置。
 * 参考架构：MDMesh (Kotlin Agent + Hilt DI)、Headwind MDM (模块化设计)
 */
@HiltAndroidApp
class PadGuardApplication : Application()
