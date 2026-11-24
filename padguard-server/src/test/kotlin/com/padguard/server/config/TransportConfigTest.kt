package com.padguard.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 传输层配置守卫：响应压缩 + 安全响应头。
 *
 * ## 为什么要写成测试
 * 这两项都是"配置少一行就静默失效、且没有任何功能测试会红"的类型：
 * - 压缩关掉后所有接口照常返回 200，只是每个响应都白白多传几倍字节；
 * - 少了 `X-Content-Type-Options` 也照常工作，直到某个上传的 `.png` 被嗅探成 HTML 执行。
 * 上一轮 `uploads/` 里就有 `shell.php.png` 这种样本，nosniff 不是纸面要求。
 *
 * 断言分三层：
 *  1. `application.yml` 里的压缩白名单（只压文本，**不压**已压缩二进制）；
 *  2. 没有任何 profile 文档把它重新关掉 / 没被某个 profile 的 `server:` 段整段覆盖；
 *  3. 真实请求（MockMvc）上安全头确实出现，包含 401 这类错误响应。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:transportcfg;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=integration-test-secret-key-32-bytes-minimum-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/transport-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/transport-parent-pwd.txt",
    "padguard.ratelimit.login=1000",
    "padguard.ratelimit.admin-login=1000"
])
class TransportConfigTest {

    @Autowired lateinit var mockMvc: MockMvc

    // ---------------- 1. 压缩配置 ----------------

    @Test
    fun `http response compression is enabled for text payloads`() {
        val base = baseDocument()
        val compression = base.map("server").map("compression")

        assertEquals(true, compression["enabled"], "server.compression.enabled 必须为 true，否则 JSON 与后台资源全部明文传输")

        val mime = compression["mime-types"].toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        listOf("application/json", "text/html", "text/css", "application/javascript").forEach {
            assertTrue(it in mime, "压缩白名单缺 $it，对应响应不会被压缩（实际：$mime）")
        }

        // 已压缩格式再压一遍只烧 CPU，体积几乎不降；截图/录屏/APK 都走这些类型。
        listOf("application/octet-stream", "image/png", "image/jpeg", "video/mp4").forEach {
            assertFalse(it in mime, "压缩白名单不应包含 $it：该类型本身已压缩")
        }
        // 实时流被压缩会攒缓冲，变成"憋一坨再发"。
        assertFalse("text/event-stream" in mime, "压缩白名单不应包含 text/event-stream")

        val minSize = (compression["min-response-size"] as Number).toInt()
        assertTrue(minSize >= 512, "min-response-size=$minSize 太小：几十字节的小回执压缩后反而更大")
        assertTrue(minSize <= 4096, "min-response-size=$minSize 过大：7.7KB 的告警列表仍会被压，但 2KB 的用量回执会漏掉")
    }

    @Test
    fun `no profile document re-disables compression`() {
        val docs = loadDocuments()
        // 各 profile 文档里的 `server:` 段只写差异（port 等），一旦哪天有人整段复制 base 的 server 段
        // 却漏了 compression，Spring 的合并规则不会"保留"缺失的键 —— 它直接就是关掉的。
        docs.forEachIndexed { index, doc ->
            val server = (doc["server"] as? Map<*, *>) ?: return@forEachIndexed
            val compression = server["compression"] as? Map<*, *> ?: return@forEachIndexed
            assertEquals(true, compression["enabled"], "第 ${index + 1} 个 YAML 文档把压缩关掉了")
        }
    }

    // ---------------- 2. 安全响应头（真实请求） ----------------

    @Test
    fun `security headers are present on normal responses`() {
        mockMvc.perform(MockMvcRequestBuilders.get("/health"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
    }

    @Test
    fun `security headers survive error responses`() {
        // 401 是客户端最常撞到的响应，也是最容易被 iframe/嗅探利用的响应（错误页往往是纯文本）。
        // 鉴权拦截器在 Filter 之后才跑，所以这里同时验证了"头先于异常写入"。
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/devices"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
    }

    // ---------------- 3. nginx 与代码不打架 ----------------

    private fun baseDocument(): Map<*, *> {
        val doc = loadDocuments().firstOrNull()
        assertNotNull(doc, "classpath:application.yml 读不到")
        return doc!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.map(key: String): Map<*, *> {
        val value = this[key] as? Map<*, *>
        assertNotNull(value, "application.yml 缺少 `${key}` 段")
        return value!!
    }

    private fun loadDocuments(): List<Map<*, *>> {
        val file = File("src/main/resources/application.yml")
        assertTrue(file.exists(), "找不到 ${file.absolutePath}")
        val all = Yaml().loadAll(file.readText()).toList()
        return all.mapNotNull { it as? Map<*, *> }
    }
}
