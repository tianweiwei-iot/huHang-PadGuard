package com.padguard.core.transport.di

import com.padguard.core.common.Logger
import com.padguard.core.transport.BuildConfig
import com.padguard.core.transport.RealRemoteDataSource
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.TransportSettings
import com.padguard.core.transport.http.AuthInterceptor
import com.padguard.core.transport.http.HostSelectionInterceptor
import com.padguard.core.transport.http.PadGuardApi
import com.padguard.core.transport.http.RetryInterceptor
import com.padguard.core.transport.mock.MockRemoteDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 传输层依赖装配。
 *
 * 唯一需要留意的判断在 [provideRemoteDataSource]：真实实现与 Mock 实现在这里二选一，
 * 上层（GuardService / 各 Repository）注入的都是 [RemoteDataSource] 接口，
 * 因此服务端就绪后切换只改这一个开关，业务代码零改动。
 */
@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    /**
     * 全局共享的 Json 实例。
     *
     * 三个配置都是刻意的，且都对应一个真实故障：
     * - `ignoreUnknownKeys = true`：服务端加字段不能让老终端直接解析崩溃 —— 否则一次
     *   平滑的服务端发版会把所有未升级的终端打成"策略拉取失败"，管控全面失效。
     * - `coerceInputValues = true`：字段为 null 时退回默认值，而不是抛异常。
     * - `encodeDefaults = true`：上行时把默认值也写出来，服务端不必区分"没传"和"传了默认值"。
     *
     * 注意没有开 `isLenient` —— 宽松解析会掩盖协议格式错误，
     * 而策略包是安全相关数据，宁可失败得明显，也不要静默按错误语义执行。
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        hostSelectionInterceptor: HostSelectionInterceptor,
        retryInterceptor: RetryInterceptor
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // 拦截器顺序有讲究：先改地址，再补鉴权头，最后才是重试包裹整条链路
            .addInterceptor(hostSelectionInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(retryInterceptor)
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            // 关掉 OkHttp 自带重试，避免与 RetryInterceptor 叠加成 3×2 次放大弱网压力
            .retryOnConnectionFailure(false)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor { message -> Logger.v("OkHttp") { message } }
                    .apply { level = HttpLoggingInterceptor.Level.BODY }
            )
        }
        return builder.build()
    }

    /**
     * Retrofit 的 baseUrl 是构建期常量，但被管控端要在运行时切接入点，
     * 因此这里填一个占位地址，真实地址由 [HostSelectionInterceptor] 在每次请求时重写。
     * 占位路径必须与 [HostSelectionInterceptor.PLACEHOLDER_PREFIX] 保持一致。
     */
    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl("http://localhost${HostSelectionInterceptor.PLACEHOLDER_PREFIX}/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun providePadGuardApi(retrofit: Retrofit): PadGuardApi = retrofit.create(PadGuardApi::class.java)

    /**
     * 真实实现与 Mock 实现的选择点。
     *
     * 用 [Provider] 惰性取值而不是直接注入两个实例：Mock 内部会起协程、真实实现会建 MQTT 连接，
     * 直接注入会让"没被选中"的那一方也完成初始化，出现两套心跳同时在跑的诡异现象。
     *
     * 判定用 `BuildConfig.USE_MOCK_SERVER` 而不是 [TransportSettings.useMock]：
     * 后者是运行时可变的 StateFlow，而 Hilt 的 @Singleton 只解析一次，
     * 用可变值会造成"页面上开关已切换、实际仍走旧实现"的不一致。
     * 运行时切 Mock 属于调试能力，由调试页重启守护服务来生效，语义更清晰。
     */
    @Provides
    @Singleton
    fun provideRemoteDataSource(
        realProvider: Provider<RealRemoteDataSource>,
        mockProvider: Provider<MockRemoteDataSource>
    ): RemoteDataSource = if (BuildConfig.USE_MOCK_SERVER) {
        Logger.w(TAG) { "==== MOCK TRANSPORT ENABLED (debug only) ====" }
        mockProvider.get()
    } else {
        Logger.i(TAG) { "real transport enabled, baseUrl=${BuildConfig.DEFAULT_BASE_URL}" }
        realProvider.get()
    }

    private const val TAG = "TransportModule"

    private const val CONNECT_TIMEOUT_SEC = 15L
    private const val READ_TIMEOUT_SEC = 30L

    /** 截图上传可能有几百 KB，写超时给宽一些 */
    private const val WRITE_TIMEOUT_SEC = 60L
}
