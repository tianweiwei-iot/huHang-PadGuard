package com.padguard.server.service

import com.padguard.server.domain.Device
import com.padguard.server.domain.UploadedFile
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.UploadedFileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional

/**
 * 家长发布素材（只有 ownerUserId、无 deviceId）的归属闸门行为锁。
 *
 * 旧实现 `viewerUserId == ownerUserId || viewerDeviceId != null` 把"任何有效设备凭证"
 * 都当成放行，注释声明的"家长 B 不能读 A 的素材"并不成立；开启签名闸门后这条分支
 * 还会绕过签名要求。这里用真实 FileAccessService + mock 仓储锁死收窄后的语义。
 */
class FileAccessServiceOwnershipTest {
    private val files = mock(UploadedFileRepository::class.java)
    private val devices = mock(DeviceRepository::class.java)
    private val svc = FileAccessService(files, devices)

    private fun file(id: String, owner: String?, device: String? = null): UploadedFile =
        UploadedFile(id = id, ownerUserId = owner, deviceId = device)

    @Test
    fun uploaderAndOwnChildDeviceAllowed_otherUserDeviceDenied() {
        `when`(files.findById("f1")).thenReturn(Optional.of(file("f1", "u1")))
        `when`(devices.findByUserId("u1")).thenReturn(listOf(Device().also { it.id = "d-mine" }))
        `when`(devices.findByUserId("u2")).thenReturn(listOf(Device().also { it.id = "d-theirs" }))

        assertEquals(
            FileAccessService.Verdict.ALLOW,
            svc.resolveOwnership("u1", null, "f1"),
            "上传者本人必须可读自己发布的素材"
        )
        assertEquals(
            FileAccessService.Verdict.ALLOW,
            svc.resolveOwnership(null, "d-mine", "f1"),
            "上传者名下的孩子设备必须能取到家长发布的素材"
        )
        assertEquals(
            FileAccessService.Verdict.DENY,
            svc.resolveOwnership("u2", "d-theirs", "f1"),
            "其他家长及其设备不得跨租户读取（原实现在这里错误放行）"
        )
        assertEquals(
            FileAccessService.Verdict.DENY,
            svc.resolveOwnership(null, "d-theirs", "f1"),
            "仅有他人设备凭证时也必须拒绝，否则签名闸门可被绕过"
        )
    }

    @Test
    fun anonymousCapabilityUrlStillSkipped() {
        `when`(files.findById("f2")).thenReturn(Optional.of(file("f2", "u1")))
        assertEquals(
            FileAccessService.Verdict.SKIP,
            svc.resolveOwnership(null, null, "f2"),
            "无凭证的能力 URL 语义必须保持不变"
        )
    }
}
