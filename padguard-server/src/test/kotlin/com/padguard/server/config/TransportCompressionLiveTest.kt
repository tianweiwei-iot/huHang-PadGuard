package com.padguard.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * 压缩链路的**真实端口**验证（MockMvc 验不了）。
 *
 * ## 为什么必须起真容器
 * `server.compression` 是 Tomcat **连接器**级别的实现：MockMvc 走的是
 * `MockHttpServletResponse`，压根不经过 `Http11Processor`，所以无论配置对错，
 * MockMvc 里都永远看不到 `Content-Encoding: gzip` —— 用 MockMvc 断言压缩等于自欺。
 * 这里用 `RANDOM_PORT` 起真实 Tomcat，再用手工拼 `Accept-Encoding` 的
 * `HttpURLConnection` 取**原始字节**（不能用 RestTemplate：它的转换器会顺手解压，
 * 于是"压没压"又看不出来了）。
 *
 * 取后台的 `api.js` 作样本：31 KB、`text/javascript`，正是家长/管理员打开控制台时
 * 首屏要拉的那一批文件；比 `/health`（89 B，靠 chunked 才被压）这种边界样本稳。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:transportlive;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=integration-test-secret-key-32-bytes-minimum-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/transport-live-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/transport-live-parent-pwd.txt"
])
class TransportCompressionLiveTest {

    @LocalServerPort private var port: Int = 0

    @Test
    fun `large text asset is gzip encoded on the wire`() {
        val res = get("/admin/assets/js/api.js", gzip = true)
        assertEquals(200, res.status, "样本资源应由 ResourceHandler 正常返回")
        assertEquals("gzip", res.contentEncoding, "配置未生效：响应仍是明文传输")
        assertTrue(res.bodySize < 20_000, "压缩后仍 ${res.bodySize} B，与未压缩的 31,749 B 无差别")
        // gzip 魔数：能确认拿到的是压缩流，而不是"响应头声称 gzip、实体却是明文"。
        assertEquals(0x1f, res.firstByte, "实体首字节不是 gzip 魔数")
    }

    @Test
    fun `same asset is sent uncompressed when client does not accept gzip`() {
        // 老客户端 / 自行拼请求的探测器不带 `Accept-Encoding`，必须原样返回，
        // 否则它们会把 gzip 流当文本读——变成乱码 JSON。
        val res = get("/admin/assets/js/api.js", gzip = false)
        assertEquals(200, res.status)
        assertEquals(null, res.contentEncoding, "客户端没声明 gzip，响应不应压缩")
        assertTrue(res.bodySize > 20_000, "未请求压缩时体积应接近原始大小，实际 ${res.bodySize} B")
    }

    @Test
    fun `json responses are compressed even when the body is tiny`() {
        // Tomcat 只在**提交时已知 Content-Length** 的情况下才比较 `min-response-size`；
        // Jackson 流式写出的响应往往长度未知（chunked），于是 89 B 的 /health 也会被压成 105 B。
        // 这 16 B 的逆差换来的是所有大 JSON 60% 以上的节省，权衡后有意保留 —— 但必须有断言盯着，
        // 否则哪天 `application/json` 被误删出白名单，压缩会静默退化到"只有静态资源生效"。
        val res = get("/health", gzip = true)
        assertEquals(200, res.status)
        assertEquals("gzip", res.contentEncoding, "application/json 未生效压缩（白名单漏了它？）")
        assertEquals(0x1f, res.firstByte, "实体首字节不是 gzip 魔数")
    }

    private data class Res(
        val status: Int,
        val contentEncoding: String?,
        val bodySize: Int,
        val firstByte: Int
    )

    private fun get(path: String, gzip: Boolean): Res {
        val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        if (gzip) conn.setRequestProperty("Accept-Encoding", "gzip")
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val buffer = ByteArrayOutputStream()
            stream?.use { it.copyTo(buffer) }
            val bytes = buffer.toByteArray()
            return Res(status, conn.getHeaderField("Content-Encoding"), bytes.size, bytes.firstOrNull()?.toInt() ?: -1)
        } finally {
            conn.disconnect()
        }
    }
}
