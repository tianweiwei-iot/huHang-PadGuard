package com.padguard.server.security

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.common.ClientIps
import com.padguard.server.common.ParentErr
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

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
 * 阈值：按场景差异化、可配置（[RateLimitProperties]），默认偏紧（登录 5 / 绑定 8 次每分钟），
 * 上线后按真实流量数据通过环境变量 `PADGUARD_RATELIMIT_*` 收紧或放宽，无需改代码。
 *
 * ## 为什么不用 Resilience4j 的 RateLimiterRegistry
 *
 * 早期实现是 `registry.rateLimiter("$scenario:ip:$ip", config)`：Registry 按 name 缓存限流器，
 * **且从不淘汰**。限流器名字里带 IP，于是每一个新出现的 IP 都会在内存里留下一份常驻对象 ——
 * 攻击者只要伪造/轮换源 IP 持续打登录接口，注册表就无上限增长，最终 OOM 拖垮整个服务端。
 * 一个"防爆破"的组件反而成了最容易被利用的 DoS 面，这是不能接受的。
 *
 * 现改为**自持有界固定窗口计数**：
 * - 每个维度只存 `(windowStart, count)` 两个 long/int，单条成本几十字节；
 * - 窗口过期即原地复位复用，不新增条目；
 * - 条目数达到 [MAX_KEYS] 时先清理过期桶，仍然超限则**直接拒绝**（fail closed）——
 *   宁可误伤一部分请求，也绝不让它把内存吃光。
 *
 * 超限抛 [BizException]，经 [com.padguard.server.common.GlobalExceptionHandler] 落 429。
 */
@Component
class RateLimit(private val props: RateLimitProperties) {

    private val log = LoggerFactory.getLogger(RateLimit::class.java)

    /** 单个计数桶：窗口起点 + 窗口内已用次数。用对象承载以便对其加锁做读-改-写。 */
    private class Bucket(@Volatile var windowStart: Long, @Volatile var count: Int)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** 按场景取阈值（未知场景回退保守默认，避免漏配导致无限制） */
    private fun limitOf(scenario: String): Int = when (scenario) {
        "login" -> props.login
        "admin_login" -> props.adminLogin
        "bind" -> props.bind
        "bind_by_account" -> props.bindByAccount
        else -> DEFAULT_LIMIT
    }

    /**
     * 限流检查；超限抛 TOO_MANY_REQUESTS。
     *
     * @param scenario 场景名（login / admin_login / bind / bind_by_account），隔离各入口计数
     * @param account  账号维度键（手机号 / 用户名 / 绑定码），空则只按 IP 计数
     */
    fun require(scenario: String, request: HttpServletRequest, account: String? = null, audience: Audience = Audience.PARENT) {
        // IP 维度默认取不可伪造的 TCP 对端地址；只有在受信任反向代理之后才采信转发头。
        // 采信时用 X-Real-IP（Nginx 覆盖写入）而不是 X-Forwarded-For（可被客户端伪造首段），
        // 否则生产环境"全站共用一个 IP 桶"（remoteAddr 恒为 nginx 容器地址）→ 全员误伤 429，
        // 而改用 XFF 又会退化成"换个头就绕过频控"。
        val ip = (if (props.trustForwardedFor) ClientIps.viaTrustedProxy(request) else ClientIps.peerOf(request))
            ?: "unknown"
        val limit = limitOf(scenario)
        val now = System.currentTimeMillis()
        check("$scenario:ip:$ip", limit, now, audience)
        if (!account.isNullOrBlank()) check("$scenario:acc:$account", limit, now, audience)
    }

    private fun check(key: String, limit: Int, now: Long, audience: Audience) {
        // 容量闸门：接近上限先做一次过期清理；清理后仍超限说明确实在被海量不同 key 冲击，
        // 此时拒绝请求（fail closed）而不是继续往 map 里塞，避免内存被撑爆。
        if (buckets.size >= MAX_KEYS) {
            evictExpired(now)
            if (buckets.size >= MAX_KEYS) {
                log.warn("rate limit bucket map saturated ({}), rejecting request fail-closed", buckets.size)
                throw tooManyRequests(audience)
            }
        }

        val bucket = buckets.computeIfAbsent(key) { Bucket(now, 0) }
        // 读-改-写必须原子：并发下 if(count>=limit) count++ 会同时放行超额请求。
        synchronized(bucket) {
            if (now - bucket.windowStart >= WINDOW_MS) {
                bucket.windowStart = now
                bucket.count = 0
            }
            if (bucket.count >= limit) throw tooManyRequests(audience)
            bucket.count++
        }
    }

    /** 清理已过窗口期的桶，释放内存。仅在容量告急时触发，避免常态遍历开销。 */
    private fun evictExpired(now: Long) {
        val it = buckets.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            // 这里弱一致即可：即使漏掉个别刚翻窗的桶，下一轮清理或原地复位会处理。
            if (now - e.value.windowStart >= WINDOW_MS) it.remove()
        }
    }

    private fun tooManyRequests(audience: Audience): BizException = BizException(
        if (audience == Audience.CHILD) ChildErr.TOO_MANY_REQUESTS else ParentErr.TOO_MANY_REQUESTS,
        "请求过于频繁，请一分钟后再试",
        audience
    )

    companion object {
        /** 未知场景的保守默认：10 次/分钟/维度，避免漏配导致入口无限制 */
        private const val DEFAULT_LIMIT = 10

        /** 计数窗口：1 分钟（与 Resilience4j 旧实现的 limitRefreshPeriod 保持一致） */
        private const val WINDOW_MS = 60_000L

        /**
         * 计数桶上限。按"IP 维度 + 账号维度"各一条估算，20k 足以覆盖正常部署下
         * 的活跃 IP/账号规模（本机单实例部署通常只有几十个 key），
         * 同时把恶意冲击下的内存封顶在几 MB 量级。
         */
        private const val MAX_KEYS = 20_000
    }
}
