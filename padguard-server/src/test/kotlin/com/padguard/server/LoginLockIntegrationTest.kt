package com.padguard.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「登录失败计数落库」的集成测试。
 *
 * ## 为什么必须是 HTTP + 真库
 * 改造前计数放在 `ConcurrentHashMap` 里，有两个**单测完全看不出来**的短板：
 * ① 重启即清零 —— 等一次发布就能白拿一轮重试额度；
 * ② 多实例各算各的 —— 5 次阈值在 3 个实例负载均衡下实际接近 15 次。
 *
 * 这两点都无法在"直接调 Service 方法"的测试里暴露（Service 每行都正常返回）。
 * 只有从 HTTP 打进去、再**直接读数据库**确认计数真的落在 `users` 行上，
 * 才能证明"进程内存里没有第二个真相"。
 *
 * ## 关于限流
 * 登录入口还有一层 IP+账号限流（默认 5 次/分钟），与账号锁定同样落 429。
 * 两者混在一起会让断言失去区分度（"被限流"和"被锁定"都是 429），
 * 所以这里把 `padguard.ratelimit.login` 调到 1000，让被测的只剩锁定这一层。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:loginlock;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=login-lock-test-secret-key-32-bytes-min-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/loginlock-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/loginlock-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080",
    // 把限流抬到不可能触发的水平，避免"被限流"冒充"被锁定"
    "padguard.ratelimit.login=1000",
    // 本类每个用例都要先注册家长账号，而 MockMvc 全部请求共用同一个对端 IP，
    // 注册限流（默认 3 次/窗口）会在第 4 个用例起直接 429 —— 那是限流的正常行为，
    // 不是本类要验的东西。注册限流本身由 RateLimit 的单测覆盖。
    "padguard.ratelimit.register=1000",
    "padguard.auth.max-fail-attempts=5",
    "padguard.auth.lock-minutes=15"
])
class LoginLockIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var userRepository: UserRepository

    companion object {
        private const val PASSWORD = "Test@12345"
        private const val WRONG = "WrongPass999"

        /** JUnit 5 每个测试方法新建实例，计数器必须静态，否则用例之间抢同一手机号 */
        private val phoneSeq = AtomicInteger(5000)
    }

    private fun newPhone(): String = "138" + (60000000 + phoneSeq.incrementAndGet())

    private fun register(phone: String) {
        val body = objectMapper.writeValueAsString(mapOf("phone" to phone, "password" to PASSWORD))
        mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .accept(MediaType.APPLICATION_JSON)
        ).andExpect(status().isOk)
    }

    /** 返回 HTTP 状态码，便于断言"这次是 401 还是 429" */
    private fun attempt(phone: String, password: String): Int {
        val body = objectMapper.writeValueAsString(mapOf("phone" to phone, "password" to password))
        return mockMvc.perform(
            MockMvcRequestBuilders.post("/v1/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .accept(MediaType.APPLICATION_JSON)
        ).andReturn().response.status
    }

    @Test
    fun `连续失败达阈值后即使密码正确也被拒绝`() {
        val phone = newPhone()
        register(phone)

        repeat(5) { i ->
            assertEquals(401, attempt(phone, WRONG), "第 ${i + 1} 次错误密码应为 401")
        }

        // 关键断言：不是"错误密码被拒"，而是"**正确**密码也被拒"。
        // 只断言错误密码返回 401 的话，锁定逻辑整段删掉测试依然通过。
        assertEquals(429, attempt(phone, PASSWORD), "达阈值后正确密码也必须被拒，说明账号已锁定")

        val u = userRepository.findByPhone(phone)
        assertNotNull(u)
        // 锁定时计数清零、只保留截止时间：解锁后重新计数，而不是"再错一次立刻又锁"
        assertTrue((u!!.loginLockedUntil ?: 0L) > System.currentTimeMillis(), "锁定截止时间必须落在未来")
        assertEquals(0, u.loginFailCount ?: -1, "触发锁定时计数应清零")
    }

    @Test
    fun `失败计数落在数据库而非进程内存`() {
        val phone = newPhone()
        register(phone)

        repeat(3) { attempt(phone, WRONG) }

        // 直接读库：这行如果还是 0，说明计数仍活在某个 Map 里，
        // 重启清零 / 多实例各算各的这两个问题一个都没解决。
        val u = userRepository.findByPhone(phone)
        assertEquals(3, u!!.loginFailCount ?: -1, "失败次数必须持久化到 users.login_fail_count")
        assertNull(u.loginLockedUntil, "未达阈值不应写入锁定时间")
    }

    @Test
    fun `成功登录清零计数，不会把之前的错误累加到下一次`() {
        val phone = newPhone()
        register(phone)

        repeat(4) { attempt(phone, WRONG) }
        assertEquals(200, attempt(phone, PASSWORD), "未达阈值时正确密码应能登录")

        val after = userRepository.findByPhone(phone)!!
        assertEquals(0, after.loginFailCount ?: -1, "登录成功必须清零")
        assertNull(after.loginLockedUntil)

        // 清零若失效，这里第 1 次就会 429（4+1 达阈值）。家长偶尔手抖输错又改对，
        // 不该在几分钟后突然被锁 —— 这是清零唯一的用户可见意义。
        repeat(4) { attempt(phone, WRONG) }
        assertEquals(200, attempt(phone, PASSWORD), "成功后重新计数，连续 4 次错误仍应可登录")
    }

    @Test
    fun `锁定到期后自动放行，无需人工干预`() {
        val phone = newPhone()
        register(phone)
        repeat(5) { attempt(phone, WRONG) }
        assertEquals(429, attempt(phone, PASSWORD))

        // 不 sleep 15 分钟：直接把截止时间改到过去，等价于"时间已经走过锁定期"。
        val u = userRepository.findByPhone(phone)!!
        u.loginLockedUntil = System.currentTimeMillis() - 1_000
        userRepository.save(u)

        assertEquals(200, attempt(phone, PASSWORD), "锁定过期后必须能正常登录")
    }

    @Test
    fun `存量账号计数为 NULL 时仍能正常计次并锁定`() {
        val phone = newPhone()
        register(phone)

        // 模拟存量行：login_fail_count 是后加列，老数据被 ddl-auto 补成 NULL。
        // SQL 里 `NULL + 1` 仍是 NULL —— 若 UPDATE 不写 coalesce，
        // 计数永远停在 NULL、阈值永不触发，**而且只有有历史数据的库才会这样**，
        // 本地新建库永远复现不出来。
        val u = userRepository.findByPhone(phone)!!
        u.loginFailCount = null
        userRepository.saveAndFlush(u)

        repeat(5) { attempt(phone, WRONG) }
        assertEquals(429, attempt(phone, PASSWORD), "NULL 起始值不得让计数永远停在 0、锁定永不触发")
    }

    @Test
    fun `并发错误密码不会因为读改写竞争而少计`() {
        val phone = newPhone()
        register(phone)

        // 12 个并发请求都打同一个账号。若计数是「读出来 +1 再写回」，
        // 并发下大量更新互相覆盖，最终计数会远小于 12 甚至停在 1，
        // 阈值永远达不到 —— 这正是把自增放进单条 UPDATE 的原因。
        val pool = Executors.newFixedThreadPool(12)
        val codes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        repeat(12) {
            pool.submit { codes.add(attempt(phone, WRONG)) }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发请求应在 60s 内完成")

        val u = userRepository.findByPhone(phone)!!
        val locked = (u.loginLockedUntil ?: 0L) > 0
        assertTrue(
            locked,
            "12 次并发错误必须触发锁定；若未锁定说明存在丢失更新（当前计数=${u.loginFailCount}）"
        )
        assertEquals(429, attempt(phone, PASSWORD), "锁定后正确密码也应被拒")
    }
}
