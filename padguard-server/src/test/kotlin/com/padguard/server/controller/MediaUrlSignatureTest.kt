package com.padguard.server.controller

import com.padguard.server.domain.Device
import com.padguard.server.domain.UploadedFile
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UploadedFileRepository
import com.padguard.server.security.JwtTokenProvider
import com.padguard.server.security.MediaUrlSigner
import com.padguard.server.service.FileStorageService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

/**
 * 敏感媒体签名 URL 回归（第八轮评估第三批 P1-2）。
 *
 * 覆盖：
 * 1. 签发/验签闭环：sign 出的 URL 服务端验签通过；参数缺失/过期/篡改一律拒绝（fail-closed）；
 * 2. 有效签名 = 匿名访问凭证：无凭证请求带有效签名可下载；
 * 3. 匿名强制签名闸门（require-signature=true）：设备上行文件无签名 403；
 *    家长发布的共享素材不受闸门影响（孩子端图片库匿名加载依赖）；
 * 4. 签名不能替凭证提权：家长 A 即便拿到别人家文件的签名链接，带自己凭证访问仍 403。
 */
class MediaUrlSignatureTest {

    @TempDir
    lateinit var tempDir: Path

    private val uploadedRepo = Mockito.mock(UploadedFileRepository::class.java)
    private val deviceRepo = Mockito.mock(DeviceRepository::class.java)

    private val signer = MediaUrlSigner(
        jwt = JwtTokenProvider("unit-test-media-sign-secret-0123456789abcdef", 120, 30),
        urlTtlHours = 168
    )

    private fun newStorage() = FileStorageService(tempDir.toString(), "", uploadedRepo, signer)

    private fun newController(requireSignature: Boolean = false) = FilesController(
        fileStorageService = newStorage(),
        uploadedFileRepository = uploadedRepo,
        deviceRepository = deviceRepo,
        mediaUrlSigner = signer,
        requireSignature = requireSignature
    )

    private fun seedFile(id: String, contentType: String, ownerUserId: String?, deviceId: String?) {
        val stored = "${id}_f.bin"
        Files.write(tempDir.resolve(stored), "file-content".toByteArray())
        Mockito.`when`(uploadedRepo.findById(id)).thenReturn(
            Optional.of(UploadedFile(id = id, contentType = contentType, storedPath = stored, size = 12, createdAt = 0L, ownerUserId = ownerUserId, deviceId = deviceId))
        )
    }

    private fun anonymousRequest(vararg params: Pair<String, String>): MockHttpServletRequest =
        MockHttpServletRequest().apply { params.forEach { (k, v) -> setParameter(k, v) } }

    @Test
    fun `sign and verify roundtrip succeeds`() {
        val url = signer.sign("/v1/files/abc-123")
        assertTrue(url.startsWith("/v1/files/abc-123?exp="))
        val exp = url.substringAfter("exp=").substringBefore('&')
        val sig = url.substringAfter("sig=")
        assertTrue(signer.verify("abc-123", exp, sig))
    }

    @Test
    fun `expired signature is rejected`() {
        val exp = (System.currentTimeMillis() - 1000).toString()
        // 用 sign 生成合法签名后人为改成过期时间 → 必须拒（时间进了签名材料，不能事后改）
        val sig = signer.sign("/v1/files/x").substringAfter("sig=")
        assertNotEquals(true, signer.verify("x", exp, sig))
    }

    @Test
    fun `tampered signature is rejected`() {
        val url = signer.sign("/v1/files/tamper-1")
        val exp = url.substringAfter("exp=").substringBefore('&')
        val sig = url.substringAfter("sig=")
        val flipped = if (sig[0] == '0') "1${sig.substring(1)}" else "0${sig.substring(1)}"
        assertEquals(false, signer.verify("tamper-1", exp, flipped))
        // 签名不能跨文件重放
        assertEquals(false, signer.verify("other-file", exp, sig))
    }

    @Test
    fun `missing parameters are rejected fail-closed`() {
        val url = signer.sign("/v1/files/m-1")
        val exp = url.substringAfter("exp=").substringBefore('&')
        val sig = url.substringAfter("sig=")
        assertEquals(false, signer.verify("m-1", null, sig))
        assertEquals(false, signer.verify("m-1", exp, null))
        assertEquals(false, signer.verify("m-1", "not-a-number", sig))
    }

    @Test
    fun `signed url grants anonymous download`() {
        seedFile("s-ok", "image/png", ownerUserId = null, deviceId = "device-A")
        val signedUrl = newStorage().url("s-ok")
        val request = anonymousRequest(
            "exp" to signedUrl.substringAfter("exp=").substringBefore('&'),
            "sig" to signedUrl.substringAfter("sig=")
        )
        val resp = newController().get(request, "s-ok")
        assertEquals(200, resp.statusCode.value())
    }

    @Test
    fun `invalid signature is rejected fail-closed`() {
        seedFile("s-bad", "image/png", ownerUserId = null, deviceId = "device-A")
        val request = anonymousRequest("exp" to "${System.currentTimeMillis() + 60000}", "sig" to "deadbeef")
        assertEquals(403, newController().get(request, "s-bad").statusCode.value())
    }

    @Test
    fun `anonymous gate blocks unsigned device media when enabled`() {
        seedFile("gate-1", "image/png", ownerUserId = null, deviceId = "device-A")
        val request = anonymousRequest() // 无凭证、无签名
        assertEquals(403, newController(requireSignature = true).get(request, "gate-1").statusCode.value())
    }

    @Test
    fun `anonymous gate does not block parent-shared material`() {
        // 家长上传的共享素材（只有 ownerUserId）：孩子端图片库匿名加载依赖它，闸门不得拦截
        seedFile("gate-2", "image/png", ownerUserId = "parent-A", deviceId = null)
        val request = anonymousRequest()
        assertEquals(200, newController(requireSignature = true).get(request, "gate-2").statusCode.value())
    }

    @Test
    fun `signed url cannot override ownership check for credentialed viewer`() {
        // 家长 A 带自己的 JWT + 别人家文件的签名链接：仍按归属校验 403，签名不得提权
        seedFile("priv-1", "image/png", ownerUserId = null, deviceId = "device-B")
        Mockito.`when`(deviceRepo.findByUserId("parent-A")).thenReturn(emptyList())
        val signedUrl = newStorage().url("priv-1")
        val request = MockHttpServletRequest().apply {
            setAttribute("fileViewerUserId", "parent-A")
            setParameter("exp", signedUrl.substringAfter("exp=").substringBefore('&'))
            setParameter("sig", signedUrl.substringAfter("sig="))
        }
        assertEquals(403, newController().get(request, "priv-1").statusCode.value())
    }
}
