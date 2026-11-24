package com.padguard.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「退出登录即吊销」的集成测试。
 *
 * ## 为什么必须是一条 HTTP 级测试
 * 改造前 `POST /v1/auth/logout` 是 `ApiResponse.ok(null)` —— 一个空实现。
 * 客户端删掉本地令牌就算登出，服务端手里的 access token 还能用满 120 分钟，
 * refresh token 更能续期 30 天。任何令牌泄漏（截图、代理日志、二手平板）都等于永久有效。
 *
 * 这类缺陷在单元测试里"看不出来"：Service 层每一行都正常返回，
 * 问题出在"根本没有代码去做吊销"这件事本身。只有从 HTTP 打进去、
 * 再拿同一枚令牌打第二次，才能证明吊销真的生效。
 *
 * 覆盖三条都必须成立的路径：
 * 1. 登出后，旧 access token 调受保护接口 → 401（拦截器版本比对）；
 * 2. 登出后，旧 refresh token 换新令牌 → 401（续期侧版本比对）——
 *    只做第 1 条不够：客户端凭 refresh 随时能换出一对全新令牌，等于没登出；
 * 3. 登出后重新登录能拿到可用令牌 —— 版本号只能拒绝旧令牌，不能把账号锁死。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    // 集成测试会在几秒内为每个用例开一个家长账号；真实注册频控（默认 6 次/分钟/IP）
    // 是给生产拦批量建号的，不该干预用例本身——频控行为由 RateLimitTest 单测覆盖。
    "padguard.ratelimit.register=200",
    "spring.datasource.url=jdbc:h2:mem:tokenrevoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=token-revocation-test-secret-key-32-bytes-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/tokrevoke-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/tokrevoke-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080"
])
class TokenRevocationIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private data class Session(val phone: String, val access: String, val refresh: String)

    companion object {
        /**
         * 手机号计数器必须是**静态**的：JUnit 5 每个测试方法都会新建测试类实例，
         * 用实例字段会让三个用例都从 1000 开始、抢同一个号码，
         * 后两个直接撞上「手机号已注册」—— 看起来像被测代码坏了，其实是测试自己写错。
         * 同一 Spring 上下文共享同一个内存库，故计数也只能共享。
         */
        private val phoneSeq = AtomicInteger(1000)
    }

    private fun registerParent(): Session {
        val phone = "139" + (70000000 + phoneSeq.incrementAndGet())
        val body = objectMapper.writeValueAsString(mapOf("phone" to phone, "password" to "Test@12345"))
        val res = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        val json = objectMapper.readTree(res.response.contentAsString)
        val access = json.at("/data/token").asText()
        val refresh = json.at("/data/refreshToken").asText()
        assertTrue(access.isNotBlank() && refresh.isNotBlank(), "注册应返回 access/refresh 令牌")
        return Session(phone, access, refresh)
    }

    private fun logout(access: String) {
        mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/logout")
                .header("Authorization", "Bearer $access")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)
    }

    @Test
    fun `logout revokes the access token that performed it`() {
        val s = registerParent()

        // 登出前：令牌可用（顺带证明基线不是"所有令牌都 401"）
        mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/devices")
                .header("Authorization", "Bearer ${s.access}")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)

        logout(s.access)

        // 登出后：同一枚令牌必须立刻失效，而不是"再撑 120 分钟"
        mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/devices")
                .header("Authorization", "Bearer ${s.access}")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `refresh token issued before logout cannot mint new tokens`() {
        val s = registerParent()
        logout(s.access)

        mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/refresh")
                .header("Refresh-Token", s.refresh)
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `login again after logout yields a usable token`() {
        val s = registerParent()
        logout(s.access)

        // 版本号只应拒绝"旧令牌"，不能把账号本身锁死；
        // 若登录时忘了带上最新 token_version，这里会拿到 ver=0 的令牌并被立刻判为过期，
        // 表现就是"登出一次之后再也进不去"。
        val body = objectMapper.writeValueAsString(mapOf("phone" to s.phone, "password" to "Test@12345"))
        val res = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        val newToken = objectMapper.readTree(res.response.contentAsString).at("/data/token").asText()
        assertTrue(newToken.isNotBlank(), "重新登录应返回新令牌")

        mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/devices")
                .header("Authorization", "Bearer $newToken")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)
    }
}
