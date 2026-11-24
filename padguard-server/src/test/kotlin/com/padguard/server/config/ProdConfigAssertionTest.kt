package com.padguard.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.mock.env.MockEnvironment
import java.io.File

/**
 * U-01 回归门禁：**发布包绝不能跑在开发档位上**。
 *
 * 背景（真实事故级缺陷）：`start-server.bat` 为了拿到「H2 文件库 + 关 MQTT」这两个便利，
 * 激活的是 `local` 档位。而 `local` 在 [ProdConfigAssertion.DEV_PROFILES] 里，于是
 * 下面四条生产红线在**真实安装包**里同时失效：
 *   1. `padguard.billing.mock-pay-enabled=true` 不再中止启动 → 放行所有支付回调；
 *   2. 后台「系统设置」允许把 `mockPay` 打开（`isDevProfile` 为真）→ 同样白嫖会员；
 *   3. 短信免密登录闸门按"开发环境"放行；
 *   4. 演示账号播种条件退化为"开关打开即播种"。
 *
 * 修复是把发布档位换成 `home`（与 local 只差档位名，同样 H2/8090/关 MQTT）。
 * 本测试同时守住"组件行为"和"脚本真值"两处——只测组件是测不出事故的，
 * 因为组件一直是对的，错的是没人给它正确的 profile。
 */
class ProdConfigAssertionTest {

    private fun env(vararg profiles: String, props: Map<String, String> = emptyMap()): MockEnvironment =
        MockEnvironment().apply {
            setActiveProfiles(*profiles)
            props.forEach { (k, v) -> setProperty(k, v) }
        }

    // ------------------------- 档位分类 -------------------------

    @Test
    fun `home is a release profile, never a dev profile`() {
        // 这条断言就是整个修复的支点：home 一旦被加进 DEV_PROFILES，
        // 安装包立刻退回"开发环境"，四条闸门再次失效，而且没有任何运行期症状。
        assertFalse("home" in ProdConfigAssertion.DEV_PROFILES)
        assertEquals(setOf("local", "dev"), ProdConfigAssertion.DEV_PROFILES, "开发档位集合被改动，需同步评审发布包影响")
    }

    // ------------------------- mockPay 红线 -------------------------

    @Test
    fun `home profile aborts startup when mock pay is enabled`() {
        val e = env("home", props = mapOf("padguard.billing.mock-pay-enabled" to "true"))
        val ex = assertThrows<IllegalStateException> { ProdConfigAssertion(e).run(null) }
        assertTrue(ex.message!!.contains("mock-pay-enabled"), "报错必须点明是哪个配置项，否则运维无从下手")
    }

    @Test
    fun `no active profile is treated as production and aborts on mock pay`() {
        // 容器生产形态：activeProfiles 为空。曾把"空 profile"当成开发环境放行过。
        val e = env(props = mapOf("padguard.billing.mock-pay-enabled" to "true"))
        assertThrows<IllegalStateException> { ProdConfigAssertion(e).run(null) }
    }

    @Test
    fun `local profile still allows mock pay for联调`() {
        val e = env("local", props = mapOf("padguard.billing.mock-pay-enabled" to "true"))
        assertDoesNotThrow { ProdConfigAssertion(e).run(null) }
    }

    @Test
    fun `home profile aborts startup when child simulator is enabled`() {
        val e = env("home", props = mapOf("padguard.dev.child-simulator" to "true"))
        val ex = assertThrows<IllegalStateException> { ProdConfigAssertion(e).run(null) }
        assertTrue(ex.message!!.contains("child-simulator"), "报错必须点明配置项")
    }

    @Test
    fun `local profile still allows child simulator`() {
        val e = env("local", props = mapOf("padguard.dev.child-simulator" to "true"))
        assertDoesNotThrow { ProdConfigAssertion(e).run(null) }
    }

    // ------------------------- 演示账号红线 -------------------------

    @Test
    fun `home profile aborts startup when demo accounts are enabled`() {
        // 非开发档位里 DataInitializer 会静默跳过播种 —— "配了却没生效"比报错更危险，
        // 运维会以为演示账号存在，或反过来以为这份配置是安全的。
        val e = env("home", props = mapOf("padguard.demo-accounts-enabled" to "true"))
        val ex = assertThrows<IllegalStateException> { ProdConfigAssertion(e).run(null) }
        assertTrue(ex.message!!.contains("demo-accounts-enabled"))
    }

    @Test
    fun `local profile allows demo accounts`() {
        val e = env("local", props = mapOf("padguard.demo-accounts-enabled" to "true"))
        assertDoesNotThrow { ProdConfigAssertion(e).run(null) }
    }

    // ------------------------- 容器档凭据 fail-fast -------------------------

    @Test
    fun `container profile aborts when jwt secret is missing`() {
        // 空 profile = docker-compose 生产形态。多实例各自随机生成 JWT → token 互不认、
        // 重启全部掉线，且故障表现为"设备集体离线"极难定位，必须在启动期挡住。
        val e = env(
            props = mapOf(
                "spring.datasource.username" to "padguard_user",
                "spring.datasource.password" to "strong-secret-32-chars-minimum"
            )
        )
        val ex = assertThrows<IllegalStateException> { ProdConfigAssertion(e).run(null) }
        assertTrue(ex.message!!.contains("jwt.secret"))
    }

    @Test
    fun `container profile aborts when db uses default weak credential`() {
        val weak = env(
            props = mapOf(
                "padguard.jwt.secret" to "x".repeat(48),
                "spring.datasource.username" to "padguard",
                "spring.datasource.password" to "padguard"
            )
        )
        val ex = assertThrows<IllegalStateException> { ProdConfigAssertion(weak).run(null) }
        assertTrue(ex.message!!.contains("弱口令"), "报错必须点明弱口令，否则运维不知道改哪个环境变量")

        val emptyPwd = env(
            props = mapOf(
                "padguard.jwt.secret" to "x".repeat(48),
                "spring.datasource.username" to "padguard_user",
                "spring.datasource.password" to ""
            )
        )
        assertThrows<IllegalStateException> { ProdConfigAssertion(emptyPwd).run(null) }
    }

    @Test
    fun `container profile starts with strong credentials injected`() {
        // 正例：凭据齐全时正常放行，确保 fail-fast 没有把生产档彻底卡死。
        val e = env(
            props = mapOf(
                "padguard.jwt.secret" to "x".repeat(48),
                "spring.datasource.username" to "padguard_user",
                "spring.datasource.password" to "strong-secret-32-chars-minimum"
            )
        )
        assertDoesNotThrow { ProdConfigAssertion(e).run(null) }
    }

    @Test
    fun `home single-node profile still allows generated jwt and blank h2 password`() {
        // home 是家庭单机发布档，H2 sa/空口令 + 随机 JWT 是合法形态，不得被容器档闸门误伤。
        assertDoesNotThrow { ProdConfigAssertion(env("home")).run(null) }
    }

    @Test
    fun `local dev profile still allows missing credentials`() {
        assertDoesNotThrow { ProdConfigAssertion(env("local")).run(null) }
    }

    // ------------------------- 只 warn 的项 -------------------------

    @Test
    fun `sms login enabled on home only warns because runtime gate is authoritative`() {
        // 硬拒在 AuthService.loginWithSms（运行期），这里不重复拦截：
        // 为一个"反正不生效"的开关把服务卡在启动阶段属于过度防御。
        val e = env(
            "home",
            props = mapOf(
                "padguard.auth.sms-login-enabled" to "true",
                "padguard.jwt.secret" to "x".repeat(32)
            )
        )
        assertDoesNotThrow { ProdConfigAssertion(e).run(null) }
    }

    // ------------------------- 脚本真值（事故现场）-------------------------

    @Test
    fun `release launcher must not activate a dev profile`() {
        val bat = File("start-server.bat")
        assumeTrue(bat.exists(), "仅在模块根目录（含 start-server.bat）运行本断言")
        val text = bat.readText(Charsets.UTF_8)
            .lines()
            // 跳过 REM 注释行：脚本里大段注释解释了"为什么不能改回 local"，不能算违规
            .filterNot { it.trimStart().startsWith("REM", ignoreCase = true) }
            .joinToString("\n")

        val active = Regex("""--spring\.profiles\.active=([A-Za-z0-9_,]+)""").findAll(text)
            .map { it.groupValues[1] }
            .toList()
        assertTrue(active.isNotEmpty(), "发布启动脚本必须显式指定档位（否则容器/本机会落到错误的默认档）")
        active.forEach { value ->
            value.split(",").forEach { p ->
                assertFalse(
                    p.trim() in ProdConfigAssertion.DEV_PROFILES,
                    "发布启动脚本激活了开发档位 '$p' —— 这是 U-01 事故的复现条件：mockPay/短信免密/演示账号三道闸门会全部失效"
                )
            }
        }
        assertTrue(
            active.any { it.split(",").map(String::trim).contains("home") },
            "发布启动脚本必须显式激活 home 档位"
        )
    }

    @Test
    fun `release launcher must not override the live screen async timeout`() {
        val bat = File("start-server.bat")
        assumeTrue(bat.exists(), "仅在模块根目录（含 start-server.bat）运行本断言")
        val text = bat.readText(Charsets.UTF_8)
            .lines()
            .filterNot { it.trimStart().startsWith("REM", ignoreCase = true) }
            .joinToString("\n")
        // 命令行参数优先级高于 yml，两处各写一个值必然漂移：
        // 脚本写 600000、运行实例手工起 1800000，谁用脚本重启看屏就被砍回 10 分钟。
        assertFalse(
            text.contains("spring.mvc.async.request-timeout"),
            "看屏流超时的唯一权威来源是 application.yml，脚本里不得再传命令行覆盖"
        )
    }
}
