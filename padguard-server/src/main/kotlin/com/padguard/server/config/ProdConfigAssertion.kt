package com.padguard.server.config

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * 生产配置断言：把"配置错误只能靠运行时撞上才知道"前移到启动期 fail-fast。
 *
 * 当前断言项：
 * 1. mock-pay 开关 —— 该开关打开时 PaymentVerifier 放行**所有**支付回调（等价于
 *    免费开通任意订阅）。local/dev 联调用它没问题，但正式部署若误开，比不开鉴权更糟。
 * 2. JWT 密钥来源 —— 未显式注入时首启随机生成写本地文件，家庭/私域部署可接受
 *    （同机才可读）；云端多实例/容器部署密钥会随实例漂移，必须环境变量统一注入，
 *    这里给出显式提醒（不阻断，家庭单机是合法形态）。
 *
 * 与 server-guard.ps1"防呆前置"同一思路：把人肉检查变成机器门禁。
 */
@Component
class ProdConfigAssertion(
    private val env: Environment
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(ProdConfigAssertion::class.java)

    override fun run(args: ApplicationArguments?) {
        val profiles = env.activeProfiles.toList()
        val isDevLike = profiles.any { it in DEV_PROFILES }

        val mockPay = env.getProperty("padguard.billing.mock-pay-enabled", Boolean::class.java, false)
        if (mockPay && !isDevLike) {
            throw IllegalStateException(
                "启动中止：padguard.billing.mock-pay-enabled=true 且当前非开发 profile（$profiles）。" +
                    "该开关会放行所有支付回调（白嫖订阅），正式部署严禁开启。"
            )
        }

        val jwtSecret = env.getProperty("padguard.jwt.secret")?.trim().orEmpty()
        if (jwtSecret.isBlank()) {
            log.warn(
                "padguard.jwt.secret 未显式注入，将首启随机生成写本地文件。" +
                    "私域单机部署可接受；云端/多实例部署必须用环境变量统一注入同一把强密钥，" +
                    "否则各实例 token 互不认、重启后全部掉线。当前 profiles={}", profiles
            )
        }
    }

    private companion object {
        val DEV_PROFILES = setOf("local", "dev")
    }
}
