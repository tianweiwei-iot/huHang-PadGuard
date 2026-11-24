package com.padguard.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「家长自助修改登录密码」的集成测试。
 *
 * ## 为什么必须有这条链路
 * 账号的初始密码、以及运营在管理后台点「重置密码」生成的，都是**服务端随机强口令**；
 * 口令文件与后台弹窗都写着"请登录后尽快修改"，但改造前服务端根本没有修改接口 ——
 * 家长只能一直对着截图里的随机串输入，忘记一次就要再找一次客服。
 * 这是"提示承诺了能力、系统却没有该能力"的典型缺口，纯单元测试看不出来
 * （每个 Service 方法都正常返回，缺的是"根本没有这个方法"）。
 *
 * ## 为什么必须验证"旧令牌失效 + 新令牌可用"这一对
 * 改密码的动机几乎总是"别人的手机还登着我的号"。若只改 hash 不升 token_version，
 * 已签发的 access token 还能用满 120 分钟、refresh token 能续 30 天，改密码等于没改。
 * 反过来，升了 version 却不返回新令牌，会把操作者自己踢下线 ——
 * 用户看到"改密码成功后立刻被登出"，会以为把账号改坏了。
 *
 * 两条都必须同时成立，所以断言成对出现。
 *
 * ## 踩过的坑：MockMvc 的 `response.contentAsString` 默认按 ISO-8859-1 解码
 * 服务端返回的 JSON 没有显式 charset 时，用它取中文 message 会得到「å¯ç è³å° 6 ä½」这样的乱码，
 * 断言就变成"逻辑是对的、测试是红的"。统一改用 `getContentAsString(StandardCharsets.UTF_8)`。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "padguard.ratelimit.register=200",
    "spring.datasource.url=jdbc:h2:mem:changepwd;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=change-password-test-secret-key-32-bytes",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/changepwd-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/changepwd-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080"
])
class ChangePasswordIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private data class Session(val phone: String, val access: String, val refresh: String)

    companion object {
        /** 静态计数器：JUnit 每次用例新建测试类实例，实例字段会让多个用例抢同一个手机号 */
        private val phoneSeq = AtomicInteger(2000)
        private const val OLD_PASSWORD = "Test@12345"
        private const val NEW_PASSWORD = "N3wPass@2026"
    }

    private fun registerParent(password: String = OLD_PASSWORD): Session {
        val phone = "138" + (60000000 + phoneSeq.incrementAndGet())
        val body = objectMapper.writeValueAsString(mapOf("phone" to phone, "password" to password))
        val res = mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk).andReturn()
        val json = objectMapper.readTree(res.response.getContentAsString(StandardCharsets.UTF_8))
        return Session(phone, json.at("/data/token").asText(), json.at("/data/refreshToken").asText())
    }

    private fun changePassword(
        access: String?,
        oldPassword: String,
        newPassword: String
    ) = mockMvc.perform(
        MockMvcRequestBuilders.post("/v1/auth/password")
            .apply { if (access != null) header("Authorization", "Bearer $access") }
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                mapOf("oldPassword" to oldPassword, "newPassword" to newPassword)
            ))
            .accept(MediaType.APPLICATION_JSON)
    )

    private fun login(phone: String, password: String) = mockMvc.perform(
        MockMvcRequestBuilders.post("/v1/auth/login/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("phone" to phone, "password" to password)))
            .accept(MediaType.APPLICATION_JSON)
    )

    @Test
    fun `改密码成功后旧令牌立即失效，返回的新令牌可用，新密码能登录`() {
        val s = registerParent()

        // 基线：改之前旧令牌是好的（否则下面的 401 证明不了任何事）
        mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/devices")
                .header("Authorization", "Bearer ${s.access}")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)

        val res = changePassword(s.access, OLD_PASSWORD, NEW_PASSWORD)
            .andExpect(status().isOk).andReturn()
        val json = objectMapper.readTree(res.response.getContentAsString(StandardCharsets.UTF_8))
        val newAccess = json.at("/data/token").asText()
        val newRefresh = json.at("/data/refreshToken").asText()
        assertTrue(newAccess.isNotBlank(), "必须返回新 access 令牌，否则当前设备会被自己踢下线")
        assertTrue(newRefresh.isNotBlank(), "必须返回新 refresh 令牌，否则两小时后照样被踢")

        // 旧令牌：必须立刻失效（"别人的手机还登着我的号"是改密码最常见的动机）
        mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/devices")
                .header("Authorization", "Bearer ${s.access}")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isUnauthorized)

        // 旧 refresh：同样不能继续换令牌
        mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/refresh")
                .header("Refresh-Token", s.refresh)
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isUnauthorized)

        // 新令牌：当前设备应当无缝继续使用
        mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/devices")
                .header("Authorization", "Bearer $newAccess")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)

        // 新密码可登录、旧密码不可登录
        login(s.phone, NEW_PASSWORD).andExpect(status().isOk)
        login(s.phone, OLD_PASSWORD).andExpect(status().isUnauthorized)
    }

    @Test
    fun `原密码不正确时拒绝，且不影响现有会话`() {
        val s = registerParent()

        val res = changePassword(s.access, "definitely-wrong", NEW_PASSWORD)
            .andExpect(status().isUnauthorized).andReturn()
        val message = objectMapper.readTree(res.response.getContentAsString(StandardCharsets.UTF_8)).at("/message").asText()
        assertEquals("原密码不正确", message)

        // 失败的改密尝试不能把当前会话踢掉：用户在弹窗里打错一次旧密码很常见，
        // 若顺带失效令牌，他会在"只是输错一个字"之后被迫重新登录。
        mockMvc.perform(
            MockMvcRequestBuilders.get("/v1/devices")
                .header("Authorization", "Bearer ${s.access}")
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)

        // 旧密码仍是唯一有效口令
        login(s.phone, OLD_PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `新密码与原密码相同时拒绝`() {
        val s = registerParent()
        val res = changePassword(s.access, OLD_PASSWORD, OLD_PASSWORD)
            .andExpect(status().isBadRequest).andReturn()
        val message = objectMapper.readTree(res.response.getContentAsString(StandardCharsets.UTF_8)).at("/message").asText()
        assertEquals("新密码不能与原密码相同", message)
    }

    @Test
    fun `新密码过短时拒绝`() {
        val s = registerParent()
        val res = changePassword(s.access, OLD_PASSWORD, "123")
            .andExpect(status().isBadRequest).andReturn()
        val message = objectMapper.readTree(res.response.getContentAsString(StandardCharsets.UTF_8)).at("/message").asText()
        assertEquals("密码至少 6 位", message)
    }

    @Test
    fun `未登录时不允许改密码`() {
        // 不带令牌：拦截器就该挡下。若这里被放行，等于任何人只要知道手机号就能改密码。
        changePassword(null, OLD_PASSWORD, NEW_PASSWORD).andExpect(status().isUnauthorized)
    }

    @Test
    fun `旧密码连续输错不会把账号锁死，正确密码仍可改`() {
        val s = registerParent()

        // 模拟家长在弹窗里反复打错旧密码
        repeat(3) { changePassword(s.access, "wrong-$it", NEW_PASSWORD).andExpect(status().isUnauthorized) }

        // 然后用正确的旧密码仍然必须能改成功：
        // 改密码接口不参与登录失败计数，"输错几次就锁号"在这里是负体验而非安全收益
        // （能调这个接口的前提是已持有有效令牌）。
        changePassword(s.access, OLD_PASSWORD, NEW_PASSWORD).andExpect(status().isOk)
        login(s.phone, NEW_PASSWORD).andExpect(status().isOk)
    }
}
