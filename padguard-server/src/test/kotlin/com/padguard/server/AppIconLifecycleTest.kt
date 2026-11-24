package com.padguard.server

import com.padguard.server.domain.Device
import com.padguard.server.dto.AppInventoryItem
import com.padguard.server.dto.AppInventoryRequest
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.InstalledAppRepository
import com.padguard.server.repository.UploadedFileRepository
import com.padguard.server.service.AppManageService
import com.padguard.server.service.FileStorageService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.util.Base64

/**
 * 应用图标生命周期（U-11）。
 *
 * 孩子端每 30 分钟全量上报一次、每次上报都可能带一张新图标（ROM 换主题、应用升级、
 * 家长改深色模式都会让图标内容变化）。旧实现只在内容变化时**新增**文件，从不删旧的：
 * 一个文件一条 `uploaded_files` 记录，一台设备上百个应用长期累积。
 *
 * 这里钉住三条：
 * 1. 内容变了 → 生成新文件，**旧的连记录带盘一起删**（不是只删记录、也不是只删盘）；
 * 2. 旧地址立刻不可下载（否则家长端点开还是那张旧图，看起来像没更新）；
 * 3. 内容没变 → 不产生任何新文件（否则每次上报都白写一张盘）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
@TestPropertySource(properties = [
    "padguard.storage.dir=build/test-uploads",
    "spring.datasource.url=jdbc:h2:mem:iconlifecycle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.jpa.hibernate.ddl-auto=create",
    "spring.flyway.enabled=false",
    "padguard.jwt.secret=integration-test-secret-key-32-bytes-minimum-ok",
    "padguard.demo-accounts-enabled=false",
    "padguard.initial-password-file=target/itest-admin-pwd.txt",
    "padguard.parent-initial-password-file=target/itest-parent-pwd.txt"
])
class AppIconLifecycleTest {

    @Autowired lateinit var appManageService: AppManageService
    @Autowired lateinit var deviceRepository: DeviceRepository
    @Autowired lateinit var installedAppRepository: InstalledAppRepository
    @Autowired lateinit var uploadedFileRepository: UploadedFileRepository
    @Autowired lateinit var fileStorageService: FileStorageService

    private val deviceId = "D-icon-lifecycle"

    /** 够长且可区分的假 PNG 载荷（内容不同 → SHA-256 不同即可，不需要真是图片）。 */
    private fun payload(seed: Int): String {
        val bytes = ByteArray(64) { (it + seed).toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun report(iconBase64: String) {
        appManageService.syncInventory(
            deviceId,
            AppInventoryRequest(
                apps = listOf(
                    AppInventoryItem(packageName = "com.demo.app", appName = "演示应用", iconBase64 = iconBase64)
                )
            )
        )
    }

    private fun iconUrl(): String =
        installedAppRepository.findByDeviceIdAndPackageName(deviceId, "com.demo.app")!!.iconUrl!!

    private fun iconFileId(): String = fileStorageService.idFromUrl(iconUrl())!!

    private fun deviceFileCount(): Long =
        uploadedFileRepository.findByDeviceId(deviceId).size.toLong()

    @Test
    fun `图标换新时旧文件连记录一起删除，内容不变则不新增`() {
        deviceRepository.save(Device(
            id = deviceId, name = "图标测试平板", scene = "FAMILY",
            onlineStatus = "OFFLINE", hmacSecret = "hs_test_secret"
        ))

        // 首次上报：新装应用，落一张图标
        report(payload(1))
        val firstFileId = iconFileId()
        assertEquals(1L, deviceFileCount(), "首张图标应只落一个文件")
        assertNotNull(fileStorageService.resolve(firstFileId), "新图标必须真实落盘")

        // 内容变化：应生成新文件并删掉旧的（记录 + 盘）
        report(payload(2))
        val secondFileId = iconFileId()
        assertNotEquals(firstFileId, secondFileId, "图标内容变化必须换文件（否则两端看到的还是旧图）")
        assertEquals(1L, deviceFileCount(), "换图标不能累积文件：旧的必须删掉")
        assertNull(fileStorageService.resolve(firstFileId), "旧图标记录应已删除（下载 404 而不是给出旧图）")
        assertNotNull(fileStorageService.resolve(secondFileId), "新图标必须可下载")

        // 内容没变：不能每次上报都重新落盘
        report(payload(2))
        assertEquals(1L, deviceFileCount(), "图标未变化时不应新增文件")
        assertEquals(secondFileId, iconFileId(), "图标未变化时地址不应漂移（否则客户端缓存全部失效）")
    }

    @Test
    fun `图标内容相同但应用信息变化时不误删在用图标`() {
        val devId = "$deviceId-2"
        deviceRepository.save(Device(
            id = devId, name = "图标测试平板2", scene = "FAMILY",
            onlineStatus = "OFFLINE", hmacSecret = "hs_test_secret"
        ))
        appManageService.syncInventory(
            devId,
            AppInventoryRequest(listOf(AppInventoryItem(packageName = "com.demo.b", appName = "B", iconBase64 = payload(7))))
        )
        val app = installedAppRepository.findByDeviceIdAndPackageName(devId, "com.demo.b")!!
        val id = fileStorageService.idFromUrl(app.iconUrl)!!

        // 只改版本号（图标内容不变）：走的是 toUpdate 分支但不该碰图标文件
        appManageService.syncInventory(
            devId,
            AppInventoryRequest(
                listOf(AppInventoryItem(packageName = "com.demo.b", appName = "B", versionName = "2.0", iconBase64 = payload(7)))
            )
        )
        assertTrue(fileStorageService.resolve(id) != null, "只是版本变化时，在用图标文件不能被删除")
        assertEquals(id, fileStorageService.idFromUrl(installedAppRepository.findByDeviceIdAndPackageName(devId, "com.demo.b")!!.iconUrl))
    }
}
