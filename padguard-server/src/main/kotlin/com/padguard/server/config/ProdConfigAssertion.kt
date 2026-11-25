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
 * 2. 演示账号开关 —— 只在 local 联调档位播种；非开发档位打开它是纯粹的误配置
 *    （会被静默忽略，运维却以为演示账号存在），因此同样直接中止。
 * 3. 短信免密登录 —— 未接短信服务商时它是一条免密入口。运行时 [com.padguard.server.service.AuthService]
 *    在非开发档位会硬拒，这里只 warn：配置项本身不再放大风险，不必为一个"反正不生效"的
 *    开关把整个服务卡在启动阶段。
 * 4. JWT 密钥 + DB 凭据来源（容器/云端档 fail-fast）——
 *    `home` 家庭单机档未注入 JWT 时首启随机生成写本地文件，同机才可读，是**合法形态**；
 *    但容器/云端档（空 profile 或 docker）必须显式注入同一把强密钥与强 DB 口令，
 *    否则：多实例各自生成 → token 互不认、重启全部掉线；DB 用默认弱口令 → 裸奔。
 *    这类档位缺凭据直接中止启动，不留"能起来但埋雷"的窗口。
 *
 * 与 server-guard.ps1"防呆前置"同一思路：把人肉检查变成机器门禁。
 * 职责边界：本类只做"启动期一次性体检"，不做任何业务判定；运行期的权限/数据判定
 * 一律在各自 Service 里（不要把这些闸门搬到运行期，也不要把运行期判定挪进来）。
 */
@Component
class ProdConfigAssertion(
    private val env: Environment
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(ProdConfigAssertion::class.java)

    override fun run(args: ApplicationArguments?) {
        val profiles = env.activeProfiles.toList()
        val isDevLike = profiles.any { it in DEV_PROFILES }
        // 启动即把"当前到底是哪档"打到日志首行：排查"为什么支付回调被放行了""为什么演示账号
        // 登不上"时，第一件事就是确认档位；而 activeProfiles 为空（容器生产形态）在日志里
        // 完全看不见，很容易被误读成"没配 profile 就是开发环境"。这里显式写出来。
        log.info(
            "启动自检：activeProfiles={} devLike={}（空档位=生产形态）。开发档位={}",
            if (profiles.isEmpty()) "<none>" else profiles.joinToString(","),
            isDevLike,
            DEV_PROFILES.joinToString("/")
        )

        val mockPay = env.getProperty("padguard.billing.mock-pay-enabled", Boolean::class.java, false)
        if (mockPay && !isDevLike) {
            throw IllegalStateException(
                "启动中止：padguard.billing.mock-pay-enabled=true 且当前非开发 profile（$profiles）。" +
                    "该开关会放行所有支付回调（白嫖订阅），正式部署严禁开启。"
            )
        }

        // 2026-11-18：此前只靠 @Profile("local","dev") 装配 DevChildSimulator，
        // 配置本身在生产档被误写成 true 时没有任何启动反馈（Bean 不存在 → 静默忽略）。
        // 模拟端会自动 ACK 全部指令，一旦有人误把它带进生产，管控指令会被"假成功"标记，
        // 比支付开关更隐蔽，因此与其他开发开关同级 fail-fast。
        val childSimulator = env.getProperty("padguard.dev.child-simulator", Boolean::class.java, false)
        if (childSimulator && !isDevLike) {
            throw IllegalStateException(
                "启动中止：padguard.dev.child-simulator=true 且当前非开发 profile（$profiles）。" +
                    "模拟孩子端会自动 ACK 指令，造成管控假成功，正式部署严禁开启。"
            )
        }

        val demoAccounts = env.getProperty("padguard.demo-accounts-enabled", Boolean::class.java, false)
        if (demoAccounts && !isDevLike) {
            // DataInitializer / AdminDataInitializer 只在 `local` 档位播种演示账号，非开发档位
            // 会静默跳过。静默跳过本身是安全的，但"配了却没生效"最容易演变成事故：
            // 运维以为有演示账号、拿它去演示/交付，实际库里没有，或者反过来有人以为安全
            // 而把整份 local 配置搬到了生产。所以这里直接拒绝启动。
            throw IllegalStateException(
                "启动中止：padguard.demo-accounts-enabled=true 且当前非开发 profile（$profiles）。" +
                    "演示/测试账号只在 local 档位播种，正式部署严禁开启（等同于把后门账号打进安装包）。"
            )
        }

        if (!isDevLike) {
            val smsEnabled = env.getProperty("padguard.auth.sms-login-enabled", Boolean::class.java, false)
            if (smsEnabled) {
                log.warn(
                    "padguard.auth.sms-login-enabled=true 但当前非开发 profile（{}）。" +
                        "短信登录未接入服务商、验证码不校验，运行期会硬拒该入口（返回 UNAUTHORIZED）。" +
                        "该配置项不会放大风险，但请清理配置以免误导。",
                    profiles
                )
            }
        }

        val jwtSecret = env.getProperty("padguard.jwt.secret")?.trim().orEmpty()

        // 容器/云端档：activeProfiles 为空（docker-compose 生产形态）或显式 docker 档。
        // 与 home 单机档的分界必须清晰——home 的 JWT 随机写本地文件、H2 sa/空口令是合法形态，
        // 不能一刀切 fail-fast，否则家庭发布包直接起不来。
        val isContainerProd = !isDevLike && !profiles.contains(SINGLE_NODE_RELEASE_PROFILE)
        if (isContainerProd) {
            if (jwtSecret.isBlank()) {
                throw IllegalStateException(
                    "启动中止：容器/云端生产档（profiles=$profiles）未注入 padguard.jwt.secret。" +
                        "多实例各自随机生成会导致 token 互不认、重启全部掉线；" +
                        "请用 PADGUARD_JWT_SECRET 注入同一把强密钥。"
                )
            }
            val dbUser = env.getProperty("spring.datasource.username")?.trim().orEmpty()
            val dbPassword = env.getProperty("spring.datasource.password")?.trim().orEmpty()
            if (dbUser == WEAK_DB_CREDENTIAL || dbPassword == WEAK_DB_CREDENTIAL || dbPassword.isBlank()) {
                throw IllegalStateException(
                    "启动中止：容器/云端生产档（profiles=$profiles）数据库仍是默认弱口令 " +
                        "($dbUser/$dbPassword)。裸机部署请用 PADGUARD_DB_USERNAME / PADGUARD_DB_PASSWORD 注入；" +
                        "docker-compose 部署请在 .env 设置 PG_USER / PG_PASSWORD（不可沿用模板默认的 padguard 用户名）。"
                )
            }
        } else if (jwtSecret.isBlank() && !isDevLike) {
            // home 单机发布档：随机生成可接受，但仍显式提示，便于排查"多开实例 token 互不认"。
            log.warn(
                "home 单机档未注入 padguard.jwt.secret，将首启随机生成写本地文件。" +
                    "单机合法；若改为多实例部署，必须用环境变量统一注入同一把强密钥。"
            )
        }
    }

    companion object {
        /**
         * 视为「开发/联调」的 profile 集合；空 profile（生产容器形态）不在其中。
         *
         * **`home` 刻意不在此列**：它是发布包（家庭单机）的正式档位，必须享受全部生产闸门。
         * 新增档位前先想清楚"它是不是会被打进安装包" —— 会，就不要加进来。
         */
        val DEV_PROFILES = setOf("local", "dev")

        /** 家庭/私域单机发布档：JWT 随机生成、H2 sa 空口令是合法形态，不强制注入凭据。 */
        const val SINGLE_NODE_RELEASE_PROFILE = "home"

        /** application.yml 中数据库凭据的占位默认值，容器档发现它即视为未改弱口令。 */
        const val WEAK_DB_CREDENTIAL = "padguard"
    }
}
