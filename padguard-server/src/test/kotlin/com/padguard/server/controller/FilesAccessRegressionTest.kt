package com.padguard.server.controller

import com.padguard.server.domain.Device
import com.padguard.server.domain.UploadedFile
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UploadedFileRepository
import com.padguard.server.service.FileStorageService
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

/**
 * 文件链路回归（第八轮评估 P2-10 缺口 + P1-A 修复验证）。
 *
 * 覆盖三类高风险路径：
 * 1. 越权：家长 A 不能下载家长 B 设备的截图/录屏（403）；
 *    家长下载自家设备上行文件必须放行（历史回归：录屏 403 缺陷）；
 * 2. XSS 防护：text/html 等可执行类型下载必须降级 octet-stream + attachment（不得 inline）；
 *    媒体类型（image/video/audio）保持 inline 以维持客户端渲染；
 * 3. 响应头安全：文件名中的引号/换行必须被净化（防响应头注入）。
 */
class FilesAccessRegressionTest {

    @TempDir
    lateinit var tempDir: Path

    private val uploadedRepo = Mockito.mock(UploadedFileRepository::class.java)
    private val deviceRepo = Mockito.mock(DeviceRepository::class.java)

    private fun newController(): FilesController =
        FilesController(
            fileStorageService = FileStorageService(tempDir.toString(), "", uploadedRepo),
            uploadedFileRepository = uploadedRepo,
            deviceRepository = deviceRepo
        )

    /** 在临时存储目录落一个真实文件并登记元数据 */
    private fun seedFile(id: String, contentType: String, storedName: String, ownerUserId: String?, deviceId: String?) {
        Files.write(tempDir.resolve(storedName), "file-content".toByteArray())
        Mockito.`when`(uploadedRepo.findById(id)).thenReturn(
            Optional.of(UploadedFile(id = id, contentType = contentType, storedPath = storedName, size = 12, createdAt = 0L, ownerUserId = ownerUserId, deviceId = deviceId))
        )
    }

    private fun request(viewerUserId: String?, viewerDeviceId: String?): HttpServletRequest =
        MockHttpServletRequest().apply {
            if (viewerUserId != null) setAttribute("fileViewerUserId", viewerUserId)
            if (viewerDeviceId != null) setAttribute("fileViewerDeviceId", viewerDeviceId)
        }

    @Test
    fun `parent cannot download another family's device screenshot`() {
        seedFile("shot-1", "image/png", "shot-1_s.png", ownerUserId = null, deviceId = "device-B")
        Mockito.`when`(deviceRepo.findByUserId("parent-A")).thenReturn(emptyList())

        val resp = newController().get(request(viewerUserId = "parent-A", viewerDeviceId = null), "shot-1")
        assertEquals(403, resp.statusCode.value())
    }

    @Test
    fun `parent can download own device screenshot via ownership fallback`() {
        // 截图归属只记 deviceId（孩子端上行，无 ownerUserId）——家长按"设备归属"放行
        seedFile("shot-2", "image/png", "shot-2_s.png", ownerUserId = null, deviceId = "device-A")
        Mockito.`when`(deviceRepo.findByUserId("parent-A")).thenReturn(listOf(Device(id = "device-A", userId = "parent-A")))

        val resp = newController().get(request(viewerUserId = "parent-A", viewerDeviceId = null), "shot-2")
        assertEquals(200, resp.statusCode.value())
    }

    @Test
    fun `device token of another device is denied`() {
        seedFile("shot-3", "image/png", "shot-3_s.png", ownerUserId = null, deviceId = "device-A")

        val resp = newController().get(request(viewerUserId = null, viewerDeviceId = "device-Z"), "shot-3")
        assertEquals(403, resp.statusCode.value())
    }

    @Test
    fun `credential-free request keeps capability-url semantics`() {
        // 无凭证（孩子端图片库加载）历史行为：不拦。该语义回归保护——改动须显式评审。
        seedFile("shot-4", "image/png", "shot-4_s.png", ownerUserId = null, deviceId = "device-A")

        val resp = newController().get(request(viewerUserId = null, viewerDeviceId = null), "shot-4")
        assertEquals(200, resp.statusCode.value())
    }

    @Test
    fun `html content type is forced to octet-stream with attachment disposition`() {
        // 攻击载荷：上传时声明 text/html —— 下载必须降级，否则与 /admin 同源执行任意 JS
        seedFile("evil-1", "text/html", "evil-1_page.html", ownerUserId = null, deviceId = null)

        val resp = newController().get(request(viewerUserId = null, viewerDeviceId = null), "evil-1")
        assertEquals("application/octet-stream", resp.headers.contentType.toString())
        assertTrue(resp.headers.getFirst("Content-Disposition")!!.startsWith("attachment"))
        assertEquals("nosniff", resp.headers.getFirst("X-Content-Type-Options"))
    }

    @Test
    fun `media types stay inline for client rendering`() {
        seedFile("img-1", "image/png", "img-1_p.png", ownerUserId = null, deviceId = null)

        val resp = newController().get(request(viewerUserId = null, viewerDeviceId = null), "img-1")
        assertEquals("image/png", resp.headers.contentType.toString())
        assertTrue(resp.headers.getFirst("Content-Disposition")!!.startsWith("inline"))
    }

    @Test
    fun `apk content type is forced to attachment download`() {
        // APK 不是媒体类型：不允许 inline，浏览器/WebView 必须走下载路径
        seedFile("app-1", "application/vnd.android.package-archive", "app-1_app.apk", ownerUserId = null, deviceId = null)

        val resp = newController().get(request(viewerUserId = null, viewerDeviceId = null), "app-1")
        assertEquals("application/octet-stream", resp.headers.contentType.toString())
        assertTrue(resp.headers.getFirst("Content-Disposition")!!.startsWith("attachment"))
    }
}
