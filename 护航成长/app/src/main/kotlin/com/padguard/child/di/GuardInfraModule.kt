package com.padguard.child.di

import com.padguard.child.guard.GuardInfraProbeImpl
import com.padguard.child.guard.WebGuardSelfVpnProbe
import com.padguard.core.data.repository.RealtimeAlertPublisher
import com.padguard.core.engine.guard.GuardInfraProbe
import com.padguard.core.engine.guard.SelfVpnProbe
import com.padguard.core.transport.mqtt.MqttRealtimeAlertPublisher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 守护基建探针的绑定（S12）。
 *
 * 独立于 [AppModule]：@Binds 需要 abstract class，而 AppModule 是
 * object + @Provides 的写法，两者不能混在同一个 Module 里 ——
 * 拆开是 Dagger 的标准做法，不是多余文件。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GuardInfraModule {

    @Binds
    @Singleton
    abstract fun bindGuardInfraProbe(impl: GuardInfraProbeImpl): GuardInfraProbe

    /** B：HIGH 告警即时单发通道（MQTT QoS1）的注入绑定 */
    @Binds
    @Singleton
    abstract fun bindRealtimeAlertPublisher(impl: MqttRealtimeAlertPublisher): RealtimeAlertPublisher

    /** A1：让代理检测能区分自家 Web 过滤隧道与孩子的绕过 VPN */
    @Binds
    @Singleton
    abstract fun bindSelfVpnProbe(impl: WebGuardSelfVpnProbe): SelfVpnProbe
}
