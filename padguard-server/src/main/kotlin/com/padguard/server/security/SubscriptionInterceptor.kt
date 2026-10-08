package com.padguard.server.security

import com.padguard.server.service.BillingService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/*
 * 订阅闸门：家长端（控制端）所有业务接口都必须校验订阅有效。
 *
 * 为什么做成拦截器而不是在每个 Service 里各判一次：
 * 分散判断迟早会漏 —— 只要有一个接口忘了校验，它就成为永久免费后门，
 * 而且这种漏洞不会报错，只能靠对账发现。统一拦截只有一处，不可能漏。
 *
 * 排除名单是生死线：auth（登录）与 billing（查套餐、下单、续费）前缀必须放行。
 * 若把它们也拦了，未付费用户既登不进去也付不了款，系统就变成彻底打不开的死结 ——
 * 这类"付费墙把自己也挡住"的事故在订阅制产品里很常见。
 *
 * 注意本拦截器只作用于家长端 v1 前缀；孩子端的上报通道刻意不拦：
 * 订阅过期后孩子端仍能上报数据，家长至少还能看到"孩子在不在、用了多久"。
 * 彻底切断上报会让家长以为数据丢了，是订阅过期时最容易引发投诉的行为。
 * （控制类能力由家长端触发，家长端被拦即自动失效。）
 *
 * 注：这里用块注释而非 KDoc，因为正文中需要出现带星号的路径通配写法。
 */
@Component
class SubscriptionInterceptor(
    private val billingService: BillingService
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        // 取不到身份说明还没通过鉴权（或已放行），交给 ParentAuthInterceptor 处理
        val userId = request.getAttribute(USER_ID_ATTR) as? String
        if (userId.isNullOrBlank()) return true

        // 不可用会抛 BizException，由 GlobalExceptionHandler 统一转成 ApiResponse
        billingService.ensureActive(userId)
        return true
    }

    companion object {
        /** 与 ParentAuthInterceptor 注入请求属性时使用的 key 保持一致 */
        const val USER_ID_ATTR = "userId"
    }
}
