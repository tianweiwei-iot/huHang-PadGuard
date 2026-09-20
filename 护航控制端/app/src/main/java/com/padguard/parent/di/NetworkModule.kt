package com.padguard.parent.di

import android.app.Application
import com.padguard.data.api.AlertApi
import com.padguard.data.api.AppManageApi
import com.padguard.data.api.AuthApi
import com.padguard.data.api.DeviceApi
import com.padguard.data.api.LocationApi
import com.padguard.data.api.MessageApi
import com.padguard.data.api.MonitorApi
import com.padguard.data.api.PolicyApi
import com.padguard.data.api.StatisticsApi
import com.padguard.parent.data.auth.TokenManager
import com.padguard.parent.data.remote.AuthInterceptor
import com.padguard.parent.data.remote.HostSelectionInterceptor
import com.padguard.parent.data.remote.TokenAuthenticator
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * 网络模块 - 提供 Retrofit API 实例与 OkHttp 客户端。
 *
 * 设计要点（主流稳妥方案）：
 * - 两套客户端：baseOkHttpClient（无鉴权，用于登录/短信/刷新，以及 Authenticator 刷新调用）；
 *   authenticatedOkHttpClient（自动附加 Bearer Token，401 时自动刷新）。
 * - AuthApi 来自 base Retrofit，避免刷新时与带鉴权客户端形成循环依赖。
 * - 其余 7 个 API 来自带鉴权 Retrofit。
 *
 * 服务器地址采用「占位 baseUrl + [HostSelectionInterceptor] 动态改写」方案：
 * 构建期 baseUrl 固定为占位符，真实地址在请求时由 [ServerPrefs] 注入，
 * 因此用户在登录页改完地址后无需重启即可生效。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** 占位 baseUrl：真实 scheme/host/port 由 HostSelectionInterceptor 在请求时改写 */
    private const val PLACEHOLDER_BASE_URL = "http://placeholder.padguard/placeholder/"

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideServerPrefs(application: Application): ServerPrefs = ServerPrefs(application)

    @Provides
    @Singleton
    fun provideHostSelectionInterceptor(serverPrefs: ServerPrefs): HostSelectionInterceptor =
        HostSelectionInterceptor(serverPrefs)

    /** 公共 OkHttp 模板：动态 Host 拦截器 + 超时 + 日志。鉴权/刷新客户端均在此基础上扩展。 */
    private fun baseHttpClient(hostInterceptor: HostSelectionInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(hostInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
            )
            .build()

    /** 无鉴权 Retrofit：供 AuthApi（登录/短信/刷新）使用。@Named 区分，避免与带鉴权 Retrofit 绑定冲突/成环 */
    @Provides
    @Singleton
    @Named("auth")
    fun provideAuthRetrofit(moshi: Moshi, hostInterceptor: HostSelectionInterceptor): Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(baseHttpClient(hostInterceptor))
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    /** 登录 / 刷新 / 短信接口，无需 Bearer 令牌 */
    @Provides
    @Singleton
    fun provideAuthApi(@Named("auth") authRetrofit: Retrofit): AuthApi =
        authRetrofit.create(AuthApi::class.java)

    /** 带鉴权 Retrofit：自动附加 Token，401 时自动刷新 */
    @Provides
    @Singleton
    fun provideAuthenticatedClient(
        tokenManager: TokenManager,
        authApi: AuthApi,
        hostInterceptor: HostSelectionInterceptor
    ): OkHttpClient = baseHttpClient(hostInterceptor).newBuilder()
        .addInterceptor(AuthInterceptor(tokenManager))
        .authenticator(TokenAuthenticator(tokenManager, authApi))
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        authenticatedClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(authenticatedClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

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
    fun provideMessageApi(retrofit: Retrofit): MessageApi =
        retrofit.create(MessageApi::class.java)

    @Provides
    @Singleton
    fun provideLocationApi(retrofit: Retrofit): LocationApi =
        retrofit.create(LocationApi::class.java)

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

    @Provides
    @Singleton
    fun provideAppManageApi(retrofit: Retrofit): AppManageApi =
        retrofit.create(AppManageApi::class.java)
}
