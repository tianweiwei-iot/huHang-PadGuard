package com.padguard.core.data.di

import javax.inject.Qualifier

/** 凭据类偏好存储（绑定信息、令牌、签名密钥） */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthDataStore

/** 运行配置类偏好存储（心跳周期、传输模式等） */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ConfigDataStore
