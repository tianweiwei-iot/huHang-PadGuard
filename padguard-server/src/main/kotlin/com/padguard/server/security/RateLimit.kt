package com.padguard.server.security

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.common.ClientIps
import com.padguard.server.common.ParentErr
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 匿名可爆破入口的限流闸门（S14）。
 *
 * 覆盖四个入口：家长登录 `/v1/auth/login/password`、后台登录 `/v1/admin/login`、
 * 孩子端绑定 `/api/v1/device/bind`（绑定码可匿名穷举）与 `/api/v1/device/bind-by-account`
 * （家长密码可匿名穷举，与登录同型爆破面，一并收口）。
 *
 * 维度：**IP + 账号双计数**。只按 IP 限，同一 NAT（家庭路由器很常见）下一个孩子端
 * 重装重试几次就把全家 IP 打满；只按账号限，攻击者换 IP 绕过。两者独立计数互不影响。
 *
 * 实现：Resilience4j `RateLimiterRegistry` 编程式动态 key（官方对动态场景的标准用法，
 * 注解式 starter 只适合静态命名限流器）。阈值先宽（10 次/分钟），上线后按数据收紧。
 * 超限抛 [BizException]，经 [com.padguard.server.common.GlobalExceptionHandler] 落 429。
 */
@Component
class RateLimit {

    private val registry = RateLimiterRegistry.of(
        RateLimiterConfig.custom()
            .limitForPeriod(LIMIT_PER_MINUTE)
            .limitRefreshPeriod(Duration.ofMinutes(1))
            // 不排队：超限立即拒绝。登录/绑定场景排队重试只会让攻击者的请求堆积在服务端。
            .timeoutDuration(Duration.ZERO)
            .build()
    )

    /**
     * 限流检查；超限抛 TOO_MANY_REQUESTS。
     *
     * @param scenario 场景名（login / admin_login / bind / bind_by_account），隔离各入口计数
     * @param account  账号维度键（手机号 / 用户名 / 绑定码），空则只按 IP 计数
     */
    fun require(scenario: String, request: HttpServletRequest, account: String? = null, audience: Audience = Audience.PARENT) {
        val ip = ClientIps.of(request) ?: "unknown"
        check(registry.rateLimiter("$scenario:ip:$ip"), audience)
        if (!account.isNullOrBlank()) check(registry.rateLimiter("$scenario:acc:$account"), audience)
    }

    private fun check(limiter: RateLimiter, audience: Audience) {
        // acquirePermission 返回 boolean（false = 超限）；RequestNotPermitted 由装饰器层抛出，
        // 直接调用必须自己判返回值 —— timeoutDuration=0 时超限立即返回 false，不排队。
        val permitted = try {
            limiter.acquirePermission()
        } catch (e: RequestNotPermitted) {
            false
        }
        if (!permitted) {
            throw BizException(
                if (audience == Audience.CHILD) ChildErr.TOO_MANY_REQUESTS else ParentErr.TOO_MANY_REQUESTS,
                "请求过于频繁，请一分钟后再试",
                audience
            )
        }
    }

    companion object {
        /** 阈值先宽：10 次/分钟/维度（实施方案 S14：上线后按数据收紧） */
        private const val LIMIT_PER_MINUTE = 10
    }
}
