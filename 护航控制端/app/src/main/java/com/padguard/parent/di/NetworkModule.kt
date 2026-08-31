package com.padguard.parent.di

import com.padguard.data.api.AlertApi
import com.padguard.data.api.AuthApi
import com.padguard.data.api.DeviceApi
import com.padguard.data.api.MonitorApi
import com.padguard.data.api.PolicyApi
import com.padguard.data.api.StatisticsApi
import com.padguard.parent.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 网络模块 - 提供 Retrofit API 实例
 *
 * 参考架构：
 * - MDMesh: 使用 Retrofit + Moshi + OkHttp 标准组合
 * - Headwind MDM: 统一 Token 拦截器 + 日志拦截器
 *
 * 注意：此模块位于 app 模块内，因为 API 接口定义 (com.padguard.data.api.*)
 * 同样定义在 app 模块中（core:network 不能反向依赖 app）。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .addInterceptor { chain ->
                val original = chain.request()
                // 自动附加通用请求头（Token 实际应从 TokenManager 获取，此处预留）
                val request = original.newBuilder()
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method(original.method, original.body)
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit {
        // 实际部署时替换为真实 API 地址（当前由 BuildConfig 注入）
        val baseUrl = BuildConfig.BASE_URL
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi =
        retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideDeviceApi(retrofit: Retrofit): DeviceApi =
        retrofit.create(DeviceApi::class.java)

    @Provides
    @Singleton
    fun provideMonitorApi(retrofit: Retrofit): MonitorApi =
        retrofit.create(MonitorApi::class.java)

    @Provides
    @Singleton
    fun providePolicyApi(retrofit: Retrofit): PolicyApi =
        retrofit.create(PolicyApi::class.java)

    @Provides
    @Singleton
    fun provideStatisticsApi(retrofit: Retrofit): StatisticsApi =
        retrofit.create(StatisticsApi::class.java)

    @Provides
    @Singleton
    fun provideAlertApi(retrofit: Retrofit): AlertApi =
        retrofit.create(AlertApi::class.java)
}
