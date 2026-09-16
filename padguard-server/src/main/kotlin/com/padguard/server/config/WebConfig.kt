package com.padguard.server.config

import com.padguard.server.security.ChildAuthInterceptor
import com.padguard.server.security.ParentAuthInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val parentAuthInterceptor: ParentAuthInterceptor,
    private val childAuthInterceptor: ChildAuthInterceptor
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        // 控制端（家长）：/v1/** 需 JWT，登录/发码/静态文件接口放行
        registry.addInterceptor(parentAuthInterceptor)
            .addPathPatterns("/v1/**")
            .excludePathPatterns(
                "/v1/auth/login/password",
                "/v1/auth/login/sms",
                "/v1/auth/sms/send",
                "/v1/files/**"
            )

        // 被管控端（孩子）：/api/v1/** 需 deviceToken，绑定/时间接口放行
        registry.addInterceptor(childAuthInterceptor)
            .addPathPatterns("/api/v1/**")
            .excludePathPatterns(
                "/api/v1/device/bind",
                "/api/v1/device/time"
            )
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        // 开发期允许本地前端/模拟器跨域；生产请收紧到具体域名
        registry.addMapping("/**")
            .allowedOriginPatterns("*")
            .allowedMethods("*")
            .allowedHeaders("*")
            .allowCredentials(true)
    }
}
