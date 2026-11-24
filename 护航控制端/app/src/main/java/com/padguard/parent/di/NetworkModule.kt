package com.padguard.parent.di

import com.padguard.parent.data.api.BillingApi
import android.app.Application
import android.util.Log
import com.padguard.parent.BuildConfig
import com.padguard.parent.data.api.AlertApi
import com.padguard.parent.data.api.AppManageApi
import com.padguard.parent.data.api.AuthApi
import com.padguard.parent.data.api.ComplianceApi
import com.padguard.parent.data.api.DeviceApi
import com.padguard.parent.data.api.FileApi
import com.padguard.parent.data.api.LocationApi
import com.padguard.parent.data.api.MessageApi
import com.padguard.parent.data.api.MonitorApi
import com.padguard.parent.data.api.PolicyApi
import com.padguard.parent.data.api.StatisticsApi
import com.padguard.parent.data.auth.TokenManager
import com.padguard.parent.data.remote.AuthInterceptor
import com.padguard.parent.data.remote.CertPinTrustManager
import com.padguard.parent.data.remote.HostSelectionInterceptor
import com.padguard.parent.data.remote.TokenAuthenticator
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.io.InterruptedIOException
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
 * - 其余 10 个 API 来自带鉴权 Retrofit（Device / Monitor / Message / Location / Policy /
 *   Statistics / Alert / AppManage / File / Billing）。
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

    /**
     * Moshi 默认无法为 kotlin.Unit 建立适配器，而本工程大量接口声明为
     * `Response<ApiResponse<Unit>>`（logout / 远程安装卸载 / 应用挂起 / 时长限额 …）。
     *
     * 缺少适配器时，Retrofit 在**创建 converter 阶段**即抛
     * `IllegalArgumentException: Unable to create converter for kotlin.Unit`，
     * 请求根本发不出去 —— 典型表现是"点了按钮立刻报错，但服务端日志一切正常"。
     *
     * 这里注册一个空适配器兜底：读取并丢弃响应值即可。
     * 注册后既不影响 data 为 null 的场景，也让所有 ApiResponse<Unit> 接口恢复可用。
     */
    private val unitJsonAdapter = object : com.squareup.moshi.JsonAdapter<Unit>() {
        override fun fromJson(reader: com.squareup.moshi.JsonReader): Unit {
            reader.skipValue()
            return Unit
        }

        override fun toJson(writer: com.squareup.moshi.JsonWriter, value: Unit?) {
            writer.nullValue()
        }
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(Unit::class.java, unitJsonAdapter)
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideServerPrefs(application: Application): ServerPrefs = ServerPrefs(application)

    @Provides
    @Singleton
    fun provideHostSelectionInterceptor(serverPrefs: ServerPrefs): HostSelectionInterceptor =
        HostSelectionInterceptor(serverPrefs)

    /**
     * 网络层自动重试（仅 GET / 仅 IOException）。
     *
     * 移动网络抖动下，连接失败、DNS 解析超时、读超时（SocketTimeout）频发；OkHttp 默认只重试
     * 「连接级失败」，对单路由的**读超时不重试**——于是家长端在弱网下一次超时就把整个请求判失败、
     * 界面直接弹「网络异常」。这里在拦截器层对 **GET（HTTP 语义即幂等）** 做至多 2 次退避重试，
     * 吸收瞬时抖动，提升各列表 / 详情页的稳定加载。
     *
     * 安全约束（绝不破坏写操作，R19c 已确认盲目重试会双发指令）：
     * - 只重试 `method == "GET"`：POST/PUT/DELETE 一律直接抛出，绝不重试，避免锁屏 / 解锁 / 下发指令
     *   被重复发送。
     * - 只重试 `IOException`（连接 / 超时 / 被重置）：HTTP 4xx/5xx 由上层 `exec/execRaw` 按业务码处理，不重试。
     * - 协程取消（Canceled）不重试：检测到 `call.isCanceled()` 或 `InterruptedIOException("Canceled")`
     *   立即原样上抛，确保用户退出页面 / 轮询取消时不会把取消误判为「失败」或「成功」。
     */
    private val networkRetryInterceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.method != "GET") {
            return@Interceptor chain.proceed(request)
        }
        val maxRetries = 2
        var attempt = 0
        var lastIo: IOException? = null
        while (attempt <= maxRetries) {
            if (chain.call().isCanceled()) {
                throw lastIo ?: IOException("call canceled")
            }
            try {
                return@Interceptor chain.proceed(request)
            } catch (e: IOException) {
                // 协程取消在 OkHttp 侧表现为 InterruptedIOException("Canceled")，必须原样上抛，
                // 否则会吞掉取消、把本该静默中止的请求当成可重试的网络故障反复重试。
                if (e is InterruptedIOException && e.message == "Canceled") throw e
                lastIo = e
                attempt++
                if (attempt > maxRetries) break
                if (BuildConfig.DEBUG) {
                    Log.d("NetworkModule", "GET ${request.url} transient failure (attempt $attempt/$maxRetries), retrying…")
                }
                try {
                    Thread.sleep(300L * attempt) // 300ms、600ms 退避，运行在 IO 调度线程，短暂阻塞可接受
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastIo ?: IOException("GET retry exhausted: ${request.url}")
    }

    /** 公共 OkHttp 模板：动态 Host 拦截器 + 超时 + 日志 + 证书固定（P0-1 对称加固）。鉴权/刷新客户端均在此基础上扩展。 */
    private fun baseHttpClient(hostInterceptor: HostSelectionInterceptor, application: Application): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(hostInterceptor)
            .addInterceptor(networkRetryInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // 日志级别**必须**降级为 BASIC（且仅 debug 开启）：
            // BODY 会把整个响应体读进内存再格式化输出。实时看屏 `/v1/stream/{deviceId}/live`
            // 是**不会结束的无限流**，BODY 日志会一直缓冲它 —— 疯狂刷日志、吃掉大量内存，
            // 是看屏卡顿/内存上涨的隐藏元凶。BASIC 只打印请求行与响应行，无此问题。
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BASIC
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
                }
            )
            // 同主机并发上限：OkHttp 默认 5。首页轮询（3 个接口）与设备详情页刷新（3 个接口）
            // 同时发生就会超过 5，多出来的请求只能排队，家长端观感是"点哪里都转圈"。
            // 本项目是**单主机**场景（服务端就是家长自己的电脑），放宽不会压垮服务端。
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
            // 证书固定（P0-1 对称加固）：系统 CA 校验先过，再做 TOFU / 静态 pin。
            .apply {
                val pins = CertPinTrustManager.parsePins(BuildConfig.CERT_PINS)
                if (pins.isNotEmpty()) {
                    // 生产静态 pin：装配失败必须 fail-closed，绝不可静默退回系统 CA
                    // （否则具备自定义 CA 的主动 MITM 可解密全链路明文行为画像）。
                    val (sf, tm) = CertPinTrustManager.forOkHttp(application, pins)
                    sslSocketFactory(sf, tm)
                } else {
                    // 无静态 pin（TOFU / LAN 私有化部署）：照常装配 TOFU 信任管理器；
                    // 仅当 TOFU 管理器装配本身异常时，才接受退回系统 CA（基线安全，不阻断网络层）。
                    runCatching {
                        val (sf, tm) = CertPinTrustManager.forOkHttp(application, pins)
                        sslSocketFactory(sf, tm)
                    }.onFailure {
                        Log.w("NetworkModule", "TOFU cert pin setup failed, fallback to system trust: ${it.message}")
                    }
                }
            }
            .build()

    /** 无鉴权 Retrofit：供 AuthApi（登录/短信/刷新）使用。@Named 区分，避免与带鉴权 Retrofit 绑定冲突/成环 */
    @Provides
    @Singleton
    @Named("auth")
    fun provideAuthRetrofit(moshi: Moshi, hostInterceptor: HostSelectionInterceptor, application: Application): Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(baseHttpClient(hostInterceptor, application))
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
        hostInterceptor: HostSelectionInterceptor,
        application: Application
    ): OkHttpClient = baseHttpClient(hostInterceptor, application).newBuilder()
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

    @Provides
    @Singleton
    fun provideFileApi(retrofit: Retrofit): FileApi =
        retrofit.create(FileApi::class.java)

    @Provides
    @Singleton
    fun provideBillingApi(retrofit: Retrofit): BillingApi =
        retrofit.create(BillingApi::class.java)

    @Provides
    @Singleton
    fun provideComplianceApi(retrofit: Retrofit): ComplianceApi =
        retrofit.create(ComplianceApi::class.java)

    /**
     * 实时推送专用 WS 客户端（不挂鉴权拦截器：令牌走 `?token=<JWT>` 查询参数；
     * 长连接 read 超时必须为 0，否则 OkHttp 会在空闲时被关掉）。
     */
    @Provides
    @Singleton
    @Named("ws")
    fun provideWebSocketClient(application: Application): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // WS 心跳（必须）：Wi-Fi↔蜂窝切换 / NAT 表项超时 / 路由器重启会造成 TCP 半开连接，
        // 此时 read 超时为 0 的连接既不会读出错也不会回调 onFailure —— 推送通道静默死亡，
        // 秒级上下线/实时看板/审批弹窗全部退化为轮询直至重启 App。pingInterval 让 OkHttp
        // 主动发 ping、超时未收到 pong 即判定连接死亡并触发重连，服务端 Spring WS 自动回 pong。
        .pingInterval(30, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
        // 证书固定（P0-1 对称加固）：WSS 链路同样锁死对端证书，阻断主动 MITM 解密推送内容。
        .apply {
            val pins = CertPinTrustManager.parsePins(BuildConfig.CERT_PINS)
            if (pins.isNotEmpty()) {
                // 生产静态 pin：装配失败必须 fail-closed，绝不可静默退回系统 CA
                // （推送内容含秒级上下线/实时看板/审批，明文泄露危害同 HTTPS 链路）。
                val (sf, tm) = CertPinTrustManager.forOkHttp(application, pins)
                sslSocketFactory(sf, tm)
            } else {
                // 无静态 pin（TOFU / LAN 私有化部署）：照常装配 TOFU 信任管理器；
                // 仅当 TOFU 管理器装配本身异常时，才接受退回系统 CA（基线安全，不阻断网络层）。
                runCatching {
                    val (sf, tm) = CertPinTrustManager.forOkHttp(application, pins)
                    sslSocketFactory(sf, tm)
                }.onFailure {
                    Log.w("NetworkModule", "ws TOFU cert pin setup failed, fallback to system trust: ${it.message}")
                }
            }
        }
        .build()
}
