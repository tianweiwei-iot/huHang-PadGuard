package com.padguard.server.config

import com.padguard.server.security.AdminAuthInterceptor
import com.padguard.server.security.ChildAuthInterceptor
import com.padguard.server.security.FileAccessInterceptor
import com.padguard.server.security.ParentAuthInterceptor
import com.padguard.server.security.SubscriptionInterceptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val parentAuthInterceptor: ParentAuthInterceptor,
    private val childAuthInterceptor: ChildAuthInterceptor,
    private val fileAccessInterceptor: FileAccessInterceptor,
    private val subscriptionInterceptor: SubscriptionInterceptor,
    private val adminAuthInterceptor: AdminAuthInterceptor,
    /**
     * 管控后台（padguard-admin）静态资源位置，逗号分隔。
     * 默认同时挂载「打成 jar 的 classpath 副本」与「就地运行时的同级目录」，
     * 使 `http://<服务器>:8090/admin/` 与接口**同源**，彻底规避 file:// 的跨域限制。
     */
    @Value("\${padguard.admin.static-locations:classpath:/static/admin/,file:./padguard-admin/,file:../padguard-admin/}")
    private val adminStaticLocations: String
) : WebMvcConfigurer {

    /** 管控后台静态资源：/admin/index.html、/admin/assets/... —— 与 /v1/admin 接口同源，无跨域 */
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val locations = adminStaticLocations.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toTypedArray()
        registry.addResourceHandler("/admin/**")
            .addResourceLocations(*locations)
            // 运营后台含登录态逻辑，禁用缓存，避免升级后仍加载旧页面
            .setCacheControl(CacheControl.noCache())
    }

    /** /admin 与 /admin/ 统一落到 index.html */
    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addRedirectViewController("/admin", "/admin/index.html")
        registry.addRedirectViewController("/admin/", "/admin/index.html")
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        // 控制端（家长）：/v1/** 需 JWT，登录/发码/刷新令牌/静态文件接口放行
        registry.addInterceptor(parentAuthInterceptor)
            .addPathPatterns("/v1/**")
            .excludePathPatterns(
                "/v1/auth/login/password",
                "/v1/auth/login/sms",
                "/v1/auth/sms/send",
                // 刷新令牌必须放行：客户端此时手里的 accessToken 往往已经过期，
                // 拦截器会先一步抛 UNAUTHORIZED，刷新就永远不可能成功 ——
                // 表现为登录满 2 小时后全端接口统一报「token 不合法」。
                // 刷新请求的合法性由 AuthService.refresh 校验 Refresh-Token 本身。
                "/v1/auth/refresh",
                // 注册必须放行：注册是「无 token 也能调」的公开入口，否则新用户永远注册不了。
                // 登录/注册是仅有的两个公开端点，其余 /v1/auth/**（如 logout）仍要求已登录。
                "/v1/auth/register",
                "/v1/files/**",
                // 公开服务器信息端点：客户端绑定引导/信息展示需匿名读取
                "/v1/server/info",
                // WebSocket 握手端点：token 走 query 参数（?token=），由
                // DeviceWebSocketHandler 自行 jwt 校验，不走 Authorization 头。
                // 若不过滤，握手会被本拦截器判为"未授权"而失败。
                // /v1/ws 是兼容旧版家长端 APK 的别名（详见 WebSocketConfig）。
                "/v1/ws",
                // 后台 admin 端点走独立鉴权，不混用家长 JWT
                "/v1/admin/**"
            )

        // 订阅闸门：必须在鉴权之后（上面先注册），否则取不到 userId。
        // 排除 auth 与 billing —— 不付费也得能登录、能付费，否则是死结。
        // 后台 admin 端点同样排除：管理员操作不应被"家长订阅是否到期"所阻断。
        registry.addInterceptor(subscriptionInterceptor)
            .addPathPatterns("/v1/**")
            .excludePathPatterns(
                "/v1/auth/**",
                "/v1/billing/**",
                "/v1/files/**",
                "/v1/server/info",
                // WebSocket 握手端点：鉴权由 DeviceWebSocketHandler 自理，
                // 不应被"家长订阅是否到期"的闸门阻断（同 ParentAuth 处的放行理由）
                "/v1/ws",
                "/v1/admin/**"
            )

        // 管控后台（管理员）：/v1/admin/** 需管理员 JWT（scope=admin），登录接口放行
        registry.addInterceptor(adminAuthInterceptor)
            .addPathPatterns("/v1/admin/**")
            .excludePathPatterns(
                "/v1/admin/login"
            )

        // 文件下载：软鉴权（认得出调用者就记身份，认不出也不拦）。
        // 必须排在最后，否则对同一路径的拦截顺序会让人误判"谁先生效"。
        registry.addInterceptor(fileAccessInterceptor)
            .addPathPatterns("/v1/files/**")

        // 被管控端（孩子）：/api/v1/** 需 deviceToken，绑定/时间接口放行
        registry.addInterceptor(childAuthInterceptor)
            .addPathPatterns("/api/v1/**")
            .excludePathPatterns(
                "/api/v1/device/bind",
                "/api/v1/device/bind-by-account",
                "/api/v1/device/time"
            )
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        // 家长/孩子端均为原生 App（OkHttp 发 Authorization: Bearer，不依赖 Cookie），
        // 管控后台 SPA 与本服务同源，因此**无需携带凭据**。
        // 显式关闭 allowCredentials，消除「通配源 + 凭据」这一被浏览器禁止且危险的跨域组合。
        registry.addMapping("/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
            .allowedHeaders("Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With")
            .allowCredentials(false)
    }
}
