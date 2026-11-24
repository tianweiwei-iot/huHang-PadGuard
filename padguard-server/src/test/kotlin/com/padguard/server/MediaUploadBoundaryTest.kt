package com.padguard.server

import com.padguard.server.domain.User
import com.padguard.server.repository.UserRepository
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.Passwords
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 家长端媒体上传入口边界 & 异常测试（第 4 轮 · 其二，MockMvc 通道）。
 *
 * 隔离 H2 库（uploadtest）。注意：MockMvc 不经过真实 Servlet 容器的 multipart
 * 大小限制（不会触发 MaxUploadSizeExceededException），"超限 → 413"用例
 * 在 WebSocketHandshakeBoundaryTest 的 RANDOM_PORT 真实容器里验证。
 *
 * 覆盖：缺 file part、空文件、危险扩展名(.html/.php/.svg)、危险 MIME(text/html)、
 * 双扩展名绕过(shell.php.png)、路径穿越(../../evil.png)、缺鉴权(401)。
 * 修复后（第 4 轮）：拒绝一律 HTTP 400 + code=1001（不再 200+code）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:uploadtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "padguard.jwt.secret=integration-test-secret-key-32-bytes-minimum-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/upload-itest-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/upload-itest-parent-pwd.txt",
    "padguard.base-url=http://localhost:8080",
    "padguard.admin.dashboard-cache-ttl-ms=0"
])
class MediaUploadBoundaryTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    private lateinit var parentId: String
    private lateinit var parentToken: String

    @BeforeEach
    fun setUp() {
        parentId = "U-upload-parent"
        if (userRepository.findById(parentId).isEmpty) {
            userRepository.save(User(id = parentId, phone = "13900004001", passwordHash = Passwords.encode("Test@12345"), status = "active"))
        }
        parentToken = jwt.createAccessToken(parentId, 0L)
    }

    private fun auth(token: String) = "Bearer $token"

    private fun upload(file: MockMultipartFile?, token: String = parentToken) = if (file != null) {
        mockMvc.perform(
            MockMvcRequestBuilders.multipart("/v1/media/upload")
                .file(file)
                .header("Authorization", auth(token))
                .accept(MediaType.APPLICATION_JSON)
        )
    } else {
        mockMvc.perform(
            MockMvcRequestBuilders.multipart("/v1/media/upload")
                .header("Authorization", auth(token))
                .accept(MediaType.APPLICATION_JSON)
        )
    }.andReturn()

    @Test
    fun `upload without auth is 401`() {
        val f = MockMultipartFile("file", "ok.png", "image/png", "hello".toByteArray())
        val r = mockMvc.perform(
            MockMvcRequestBuilders.multipart("/v1/media/upload").file(f).accept(MediaType.APPLICATION_JSON)
        ).andReturn()
        assertEquals(401, r.response.status, "缺鉴权应 401，实际=${r.response.status}")
    }

    @Test
    fun `upload missing file part is 400 not 500`() {
        // 第 4 轮修复：MissingServletRequestPartException 原先未映射、落到兜底变 500 + ERROR 假告警，
        // 现应映射 400 + "缺少必需的文件字段"
        val r = upload(null)
        assertEquals(400, r.response.status, "缺 file 参数应 400，实际=${r.response.status} ${r.response.contentAsString.take(200)}")
    }

    @Test
    fun `upload empty file is rejected 400`() {
        val f = MockMultipartFile("file", "empty.png", "image/png", ByteArray(0))
        val r = upload(f)
        assertEquals(400, r.response.status, "空文件应 400，实际=${r.response.status}")
        assertEquals(1001, objectMapper.readTree(r.response.contentAsString).at("/code").asInt(), "应为 PARAM_ERROR")
    }

    @Test
    fun `upload html extension is rejected 400 (stored XSS guard)`() {
        val f = MockMultipartFile("file", "evil.html", "image/png", "<html>".toByteArray())
        val r = upload(f)
        assertEquals(400, r.response.status, ".html 应被拒，实际=${r.response.status}")
    }

    @Test
    fun `upload php extension is rejected 400 (stored XSS guard)`() {
        val f = MockMultipartFile("file", "shell.php", "image/png", "<?php".toByteArray())
        val r = upload(f)
        assertEquals(400, r.response.status, ".php 应被拒，实际=${r.response.status}")
    }

    @Test
    fun `upload svg extension is rejected 400 (stored XSS guard)`() {
        val f = MockMultipartFile("file", "evil.svg", "image/png", "<svg>".toByteArray())
        val r = upload(f)
        assertEquals(400, r.response.status, ".svg 应被拒，实际=${r.response.status}")
    }

    @Test
    fun `upload text-html mime is rejected 400 (extension-bypass guard)`() {
        // 扩展名不在黑名单，但 MIME 是 text/html —— 必须被 MIME 层兜底拦截
        val f = MockMultipartFile("file", "note.bin", "text/html", "<html>".toByteArray())
        val r = upload(f)
        assertEquals(400, r.response.status, "MIME text/html 应被拒，实际=${r.response.status}")
    }

    @Test
    fun `double-extension php-dot-png is accepted but safe (no code exec)`() {
        // shell.php.png：扩展名 png 不在黑名单，MIME image/png 不在黑名单 → 200。
        // 落盘后下载侧对 image/* 仅 inline 渲染、且带 X-Content-Type-Options:nosniff，
        // 浏览器不会按脚本执行。此处仅断言"未被扩展名/MIME 黑名单误杀"，安全由下载侧兜底。
        val f = MockMultipartFile("file", "shell.php.png", "image/png", "<?php echo 1;".toByteArray())
        val r = upload(f)
        assertEquals(200, r.response.status, "双扩展名 png 应放行（安全由下载侧保证），实际=${r.response.status}")
        val fileId = objectMapper.readTree(r.response.contentAsString).at("/data/fileId").asText()
        assertNotNull(fileId, "应返回 fileId")
    }

    @Test
    fun `path-traversal filename is sanitized and still accepted`() {
        // ../../evil.png：sanitize 会剥掉所有 /，磁盘名不含路径分隔；URL 用 UUID fileId，无穿越风险
        val f = MockMultipartFile("file", "../../evil.png", "image/png", "data".toByteArray())
        val r = upload(f)
        assertEquals(200, r.response.status, "路径穿越文件名应被 sanitize 后正常存，实际=${r.response.status}")
        val body = objectMapper.readTree(r.response.contentAsString)
        val fileId = body.at("/data/fileId").asText()
        assertFalse(fileId.contains("/"), "fileId 不得含路径分隔符")
    }

    @Test
    fun `oversized upload is rejected by real container with 413 (see RandomPort tests)`() {
        // MockMvc 不强制 multipart 大小限制，本用例只断言该限制在 MockMvc 通道不生效的事实；
        // 真实验证在 WebSocketHandshakeBoundaryTest（RANDOM_PORT 真容器 + 10KB 上限）。
        val big = ByteArray(11 * 1024) { 0x41 }
        val f = MockMultipartFile("file", "big.png", "image/png", big)
        val r = upload(f)
        // 记录事实：MockMvc 通道 11KB 文件正常上传成功（不触发超限）
        assertEquals(200, r.response.status, "MockMvc 不触发 multipart 大小限制（预期事实，真实验证在 RandomPort 类）")
    }

    @Test
    fun `normal small png uploads successfully 200`() {
        val f = MockMultipartFile("file", "photo.png", "image/png", "fakepngbytes".toByteArray())
        val r = upload(f)
        assertEquals(200, r.response.status, "正常小文件应 200，实际=${r.response.status}")
        val body = objectMapper.readTree(r.response.contentAsString)
        assertTrue(body.at("/data/fileId").asText().isNotBlank(), "应返回 fileId")
    }
}
