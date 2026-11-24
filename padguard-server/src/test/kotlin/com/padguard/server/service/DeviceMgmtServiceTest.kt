package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.AppCatalog
import com.padguard.server.domain.AppDistribution
import com.padguard.server.domain.DeviceFeaturePolicy
import com.padguard.server.domain.DevicePermissionPolicy
import com.padguard.server.domain.InstalledApp
import com.padguard.server.dto.AppCatalogDto
import com.padguard.server.dto.AppDistributionDto
import com.padguard.server.dto.CommandDto
import com.padguard.server.dto.FeaturePolicyDto
import com.padguard.server.dto.PermissionPolicyDto
import com.padguard.server.repository.*
import com.padguard.server.security.DeviceOwnershipGuard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.springframework.mock.web.MockMultipartFile
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 覆盖 [DeviceMgmtService]：应用目录库 / 一键分发 / 远程升级 / 权限差量下发 / 功能策略过滤。
 * 全部写操作首行过 [DeviceOwnershipGuard] 归属闸门，越权传播 [BizException]；
 * 分发/升级复用 [CommandType.INSTALL_APP]、权限/功能走 [CommandType.SET_RUNTIME_PERMISSION]/[CommandType.SET_FEATURE_POLICY]。
 * 不加载 Spring 上下文。
 */
class DeviceMgmtServiceTest {

    private lateinit var fileStorage: FileStorageService
    private lateinit var deviceRepo: DeviceRepository
    private lateinit var guard: DeviceOwnershipGuard
    private lateinit var installedAppRepo: InstalledAppRepository
    private lateinit var catalogRepo: AppCatalogRepository
    private lateinit var distributionRepo: AppDistributionRepository
    private lateinit var permPolicyRepo: DevicePermissionPolicyRepository
    private lateinit var featurePolicyRepo: DeviceFeaturePolicyRepository
    private lateinit var command: CommandService
    private lateinit var om: ObjectMapper
    private lateinit var svc: DeviceMgmtService

    @BeforeEach
    fun setup() {
        fileStorage = mock(FileStorageService::class.java)
        deviceRepo = mock(DeviceRepository::class.java)
        guard = mock(DeviceOwnershipGuard::class.java)
        installedAppRepo = mock(InstalledAppRepository::class.java)
        catalogRepo = mock(AppCatalogRepository::class.java)
        distributionRepo = mock(AppDistributionRepository::class.java)
        permPolicyRepo = mock(DevicePermissionPolicyRepository::class.java)
        featurePolicyRepo = mock(DeviceFeaturePolicyRepository::class.java)
        command = mock(CommandService::class.java)
        om = ObjectMapper()
        svc = DeviceMgmtService(fileStorage, deviceRepo, guard, installedAppRepo, catalogRepo,
            distributionRepo, permPolicyRepo, featurePolicyRepo, command, om)
        // 写操作下游：默认成功
        // issueCommand 是 4 参数（priority 有默认值 "HIGH"）：Kotlin 编译期会补第 4 个真实值，
        // 因此桩/verify 必须给满 4 个 matcher，否则 matcher 与 raw 值混用 -> InvalidUseOfMatchersException
        `when`(command.issueCommand(anyString(), anyString(), any(), anyString())).thenReturn(
            CommandDto(msgId = "m1", type = "X", status = "X", payload = emptyMap(), createdAt = 0L, executedAt = null)
        )
        // toCatalogDto 的 apkUrl 是非空 String，mock 默认返回 null 会 NPE，故给默认桩
        `when`(fileStorage.url(anyString())).thenReturn("/v1/files/f1")
        `when`(catalogRepo.save(any())).thenAnswer { it.arguments[0] }
        `when`(distributionRepo.save(any())).thenAnswer { it.arguments[0] }
        `when`(installedAppRepo.save(any())).thenAnswer { it.arguments[0] }
        `when`(permPolicyRepo.save(any())).thenAnswer { it.arguments[0] }
        `when`(featurePolicyRepo.save(any())).thenAnswer { it.arguments[0] }
    }

    // ---------------- uploadCatalog ----------------

    @Test
    fun `uploadCatalog 空文件抛 PARAM_ERROR`() {
        // MockMultipartFile 是 Spring 的真实类（不是 Mockito mock）：空字节天然 isEmpty=true，
        // 不能对它打 when 桩（会抛 MissingMethodInvocationException）
        val file = MockMultipartFile("f", "x.apk", "application/vnd.android.package-archive", ByteArray(0))
        val ex = assertThrows(BizException::class.java) {
            svc.uploadCatalog("u1", "d1", file, "com.x", 1, null, null, null, null)
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun `uploadCatalog 空包名抛 PARAM_ERROR`() {
        val file = MockMultipartFile("f", "x.apk", "application/vnd.android.package-archive", "bytes".toByteArray())
        val ex = assertThrows(BizException::class.java) {
            svc.uploadCatalog("u1", "d1", file, "", 1, null, null, null, null)
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun `uploadCatalog 成功上传并返回带 apkUrl 的 DTO`() {
        val file = MockMultipartFile("f", "x.apk", "application/vnd.android.package-archive", "bytes".toByteArray())
        // storeStream 前两参非空（String / InputStream），matcher 占位返回 null 会触发 Kotlin 非空检查
        // NPE，须按既定范式补 `?: 默认值`；后三参可空直接 any()
        `when`(fileStorage.storeStream(
            anyString() ?: "",
            any(java.io.InputStream::class.java) ?: java.io.ByteArrayInputStream(ByteArray(0)),
            any(), any(), any()
        )).thenReturn(FileStorageService.StoredFile("f1", "/v1/files/f1", "x.apk"))
        `when`(fileStorage.url("f1")).thenReturn("/v1/files/f1")

        val dto: AppCatalogDto = svc.uploadCatalog("u1", "d1", file, "com.x", 10, "AppX", null, null, null)

        assertEquals("com.x", dto.packageName)
        assertEquals("/v1/files/f1", dto.apkUrl)
        // 上传不触发指令下发
        verify(command, never()).issueCommand(anyString(), anyString(), any(), anyString())
        verify(catalogRepo).save(any())
    }

    // ---------------- listCatalog ----------------

    @Test
    fun `listCatalog 按包名升序且同包名版本降序`() {
        `when`(catalogRepo.findByUserId("u1")).thenReturn(
            listOf(
                AppCatalog(id = "1", userId = "u1", packageName = "com.a", versionCode = 1),
                AppCatalog(id = "2", userId = "u1", packageName = "com.a", versionCode = 5),
                AppCatalog(id = "3", userId = "u1", packageName = "com.b", versionCode = 1)
            )
        )
        val list = svc.listCatalog("u1", "d1")
        assertEquals(listOf("com.a", "com.a", "com.b"), list.map { it.packageName })
        assertEquals(listOf(5L, 1L, 1L), list.map { it.versionCode })
    }

    // ---------------- deleteCatalog ----------------

    @Test
    fun `deleteCatalog 不存在抛 PARAM_ERROR`() {
        `when`(catalogRepo.findById("cid")).thenReturn(java.util.Optional.empty())
        val ex = assertThrows(BizException::class.java) {
            svc.deleteCatalog("u1", "d1", "cid")
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun `deleteCatalog 越权抛 DEVICE_NOT_OWNED`() {
        `when`(catalogRepo.findById("cid")).thenReturn(
            java.util.Optional.of(AppCatalog(id = "cid", userId = "other", packageName = "com.x"))
        )
        val ex = assertThrows(BizException::class.java) {
            svc.deleteCatalog("u1", "d1", "cid")
        }
        assertEquals(ParentErr.DEVICE_NOT_OWNED, ex.code)
    }

    @Test
    fun `deleteCatalog 成功删除`() {
        val entity = AppCatalog(id = "cid", userId = "u1", packageName = "com.x")
        `when`(catalogRepo.findById("cid")).thenReturn(java.util.Optional.of(entity))
        svc.deleteCatalog("u1", "d1", "cid")
        verify(catalogRepo).delete(entity)
    }

    // ---------------- distribute ----------------

    @Test
    fun `distribute 目录不存在抛 PARAM_ERROR`() {
        `when`(catalogRepo.findById("cid")).thenReturn(java.util.Optional.empty())
        val ex = assertThrows(BizException::class.java) {
            svc.distribute("u1", "d1", "cid", false)
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun `distribute 越权抛 DEVICE_NOT_OWNED`() {
        `when`(catalogRepo.findById("cid")).thenReturn(
            java.util.Optional.of(AppCatalog(id = "cid", userId = "other", packageName = "com.x", versionCode = 10))
        )
        val ex = assertThrows(BizException::class.java) {
            svc.distribute("u1", "d1", "cid", false)
        }
        assertEquals(ParentErr.DEVICE_NOT_OWNED, ex.code)
    }

    @Test
    fun `distribute 非强制且已装相同版本以上提示无需分发`() {
        `when`(catalogRepo.findById("cid")).thenReturn(
            java.util.Optional.of(AppCatalog(id = "cid", userId = "u1", packageName = "com.x", versionCode = 10))
        )
        `when`(installedAppRepo.findByDeviceIdAndPackageName("d1", "com.x"))
            .thenReturn(InstalledApp(deviceId = "d1", packageName = "com.x", versionCode = 10L))
        val ex = assertThrows(BizException::class.java) {
            svc.distribute("u1", "d1", "cid", false)
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
        verify(command, never()).issueCommand(anyString(), anyString(), any(), anyString())
    }

    @Test
    fun `distribute 强制下发 INSTALL_APP 且 payload 含下载地址`() {
        `when`(catalogRepo.findById("cid")).thenReturn(
            java.util.Optional.of(
                AppCatalog(id = "cid", userId = "u1", packageName = "com.x", versionCode = 10, fileId = "f1")
            )
        )
        `when`(installedAppRepo.findByDeviceIdAndPackageName("d1", "com.x")).thenReturn(null)
        `when`(fileStorage.url("f1")).thenReturn("/v1/files/f1")

        val dto: AppDistributionDto = svc.distribute("u1", "d1", "cid", true)

        assertEquals("INSTALL", dto.kind)
        val captor = ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, Any?>>
        verify(command).issueCommand(eq("d1") ?: "d1", eq(CommandType.INSTALL_APP) ?: "", captor.capture(), anyString())
        val p = captor.value
        assertEquals("/v1/files/f1", p[CommandKey.APK_URL])
        assertEquals("com.x", p[CommandKey.PACKAGE_NAME])
        assertEquals(10L, p[CommandKey.VERSION_CODE])
    }

    // ---------------- upgrade ----------------

    @Test
    fun `upgrade 已是最新抛 PARAM_ERROR`() {
        `when`(catalogRepo.findById("cid")).thenReturn(
            java.util.Optional.of(AppCatalog(id = "cid", userId = "u1", packageName = "com.x", versionCode = 10))
        )
        `when`(installedAppRepo.findByDeviceIdAndPackageName("d1", "com.x"))
            .thenReturn(InstalledApp(deviceId = "d1", packageName = "com.x", versionCode = 10L))
        val ex = assertThrows(BizException::class.java) {
            svc.upgrade("u1", "d1", "cid")
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    // ---------------- setPermissions（差量下发） ----------------

    @Test
    fun `setPermissions 新授权只下发新增部分`() {
        `when`(permPolicyRepo.findByDeviceId("d1")).thenReturn(null)
        val dto = svc.setPermissions("u1", "d1", PermissionPolicyDto(permissions = listOf("a", "b")))
        assertEquals(setOf("a", "b"), dto.permissions.toSet())

        val captor = ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, Any?>>
        verify(command, times(2)).issueCommand(eq("d1") ?: "d1", eq(CommandType.SET_RUNTIME_PERMISSION) ?: "", captor.capture(), anyString())
        val pairs = captor.allValues.map {
            (it[CommandKey.PERMISSION] as String) to (it[CommandKey.GRANT] as Boolean)
        }.toSet()
        assertEquals(setOf("a" to true, "b" to true), pairs)
        // 落库逗号分隔
        verify(permPolicyRepo).save(any())
    }

    @Test
    fun `setPermissions 移除权限下发收回指令`() {
        `when`(permPolicyRepo.findByDeviceId("d1")).thenReturn(
            DevicePermissionPolicy(deviceId = "d1").apply { grantedPermissions = "a" }
        )
        svc.setPermissions("u1", "d1", PermissionPolicyDto(permissions = listOf("b")))
        val captor = ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, Any?>>
        verify(command, times(2)).issueCommand(eq("d1") ?: "d1", eq(CommandType.SET_RUNTIME_PERMISSION) ?: "", captor.capture(), anyString())
        val pairs = captor.allValues.map {
            (it[CommandKey.PERMISSION] as String) to (it[CommandKey.GRANT] as Boolean)
        }.toSet()
        assertEquals(setOf("a" to false, "b" to true), pairs)
    }

    // ---------------- setFeatures（仅放行已知 key） ----------------

    @Test
    fun `setFeatures 过滤未知 featureKey`() {
        `when`(featurePolicyRepo.findByDeviceId("d1")).thenReturn(null)
        svc.setFeatures(
            "u1", "d1",
            FeaturePolicyDto(
                features = mapOf(
                    CommandKey.FEATURE_CAMERA_DISABLED to true,
                    "bogus_key" to true,
                    CommandKey.FEATURE_USB_DEBUG_DISABLED to false
                )
            )
        )
        val captor = ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, Any?>>
        verify(command).issueCommand(eq("d1") ?: "d1", eq(CommandType.SET_FEATURE_POLICY) ?: "", captor.capture(), anyString())
        val p = captor.value
        assertTrue(p.containsKey(CommandKey.FEATURE_CAMERA_DISABLED))
        assertTrue(p.containsKey(CommandKey.FEATURE_USB_DEBUG_DISABLED))
        assertFalse(p.containsKey("bogus_key"))
        verify(featurePolicyRepo).save(any())
    }

    // ---------------- getFeatures（JSON 损坏回落空） ----------------

    @Test
    fun `getFeatures JSON 损坏回落空`() {
        // om 是真实 ObjectMapper（非 mock），生产 getFeatures 在 runCatching 里做真实解析：
        // 非法 JSON 直接抛异常被吞，回落 emptyMap，无需也不能对真实对象打 when 桩
        `when`(featurePolicyRepo.findByDeviceId("d1")).thenReturn(
            DeviceFeaturePolicy(deviceId = "d1").apply { featuresJson = "not-json" }
        )
        val dto = svc.getFeatures("u1", "d1")
        assertTrue(dto.features.isEmpty())
    }

    @Test
    fun `getFeatures 正常解析`() {
        `when`(featurePolicyRepo.findByDeviceId("d1")).thenReturn(
            DeviceFeaturePolicy(deviceId = "d1").apply { featuresJson = "{\"camera_disabled\":\"true\"}" }
        )
        val dto = svc.getFeatures("u1", "d1")
        assertEquals(mapOf("camera_disabled" to true), dto.features)
    }
}
