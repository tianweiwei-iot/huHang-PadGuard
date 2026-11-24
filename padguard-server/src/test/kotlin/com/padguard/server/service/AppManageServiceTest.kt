package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ChildErr
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.AppPolicy
import com.padguard.server.domain.Device
import com.padguard.server.domain.InstalledApp
import com.padguard.server.dto.AppHiddenBatchRequest
import com.padguard.server.dto.AppHiddenRequest
import com.padguard.server.dto.AppInstallRequest
import com.padguard.server.dto.AppInventoryItem
import com.padguard.server.dto.AppInventoryRequest
import com.padguard.server.dto.CommandDto
import com.padguard.server.repository.AppPolicyRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.InstalledAppRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/**
 * [AppManageService] 的越权与授权意图护栏。
 *
 * 与 `AppIconLifecycleTest` 的分工：那边覆盖图标 hash / 旧图标删除顺序；
 * 本类不重复图标链路，重点放在：
 * - 家长端每个方法是否都过了唯一归属校验（installApp 是远程静默安装，漏校验后果最严重）；
 * - 设备上报的 hidden 是观测值，**不得反向覆盖**服务端家长授权意图（历史线上事故根因）；
 * - 卸载保留审计痕迹、自身包名保护、批量去重与只重建一次策略包。
 */
class AppManageServiceTest {

    private lateinit var fileStorage: FileStorageService
    private lateinit var devices: DeviceRepository
    private lateinit var guard: DeviceOwnershipGuard
    private lateinit var installed: InstalledAppRepository
    private lateinit var policies: AppPolicyRepository
    private lateinit var command: CommandService
    private lateinit var policyExt: PolicyExtensionService
    private lateinit var converger: AppDistributionConverger
    private lateinit var svc: AppManageService

    /** 每次 issueCommand 的 payload，按调用顺序记录（避开泛型 ArgumentCaptor 的不变性问题）。 */
    private val issuedPayloads = mutableListOf<Map<*, *>>()
    private val issuedTypes = mutableListOf<String>()

    private val cmdDto = CommandDto(
        msgId = "m1", type = "X", status = "PENDING",
        payload = emptyMap(), createdAt = 0L, executedAt = null
    )

    @BeforeEach
    fun setUp() {
        fileStorage = mock(FileStorageService::class.java)
        devices = mock(DeviceRepository::class.java)
        guard = mock(DeviceOwnershipGuard::class.java)
        installed = mock(InstalledAppRepository::class.java)
        policies = mock(AppPolicyRepository::class.java)
        command = mock(CommandService::class.java)
        policyExt = mock(PolicyExtensionService::class.java)
        converger = mock(AppDistributionConverger::class.java)
        issuedPayloads.clear()
        issuedTypes.clear()

        // requireOwned 返回非空 Device，必须给真实对象而非让 mock 返回 null
        `when`(guard.requireOwned(anyString(), anyString())).thenReturn(Device(id = "d1", userId = "u1"))
        `when`(devices.existsById(anyString())).thenReturn(true)
        `when`(installed.findByDeviceId(anyString())).thenReturn(emptyList())
        `when`(installed.findByDeviceIdAndInstalledTrue(anyString())).thenReturn(emptyList())
        `when`(policies.findByDeviceId(anyString())).thenReturn(emptyList())
        `when`(fileStorage.store(anyString(), any(ByteArray::class.java) ?: ByteArray(0), any(), any(), any()))
            .thenReturn("/v1/files/icon")
        // issueCommand 的 priority 有默认值：JVM 上是 4 参，桩必须给满 4 个 matcher
        `when`(command.issueCommand(anyString(), anyString(), any(), anyString())).thenAnswer { a ->
            issuedTypes.add(a.arguments[1] as String)
            @Suppress("UNCHECKED_CAST")
            issuedPayloads.add((a.arguments[2] as? Map<*, *>) ?: emptyMap<String, Any?>())
            cmdDto
        }
        svc = AppManageService(fileStorage, devices, guard, installed, policies, command, policyExt, converger)
    }

    /** saveAll 是泛型方法，Kotlin 侧 any() 推不出 S；按方法名计数最稳。 */
    private fun saveAllCount(): Int =
        mockingDetails(installed).invocations.count { it.method.name == "saveAll" }

    private fun existing(
        pkg: String = "com.a", hidden: Boolean = true,
        appName: String? = "计算器", versionName: String? = "1.0", versionCode: Long? = 5L
    ) = InstalledApp(
        id = "row-$pkg", deviceId = "d1", packageName = pkg,
        appName = appName, versionName = versionName, versionCode = versionCode,
        installed = true, firstSeenAt = 1L, lastSeenAt = 1L
    ).apply { this.hidden = hidden }

    // ----------------------------- syncInventory -----------------------------

    @Test
    fun `syncInventory 设备不存在抛孩子端参数错误码`() {
        `when`(devices.existsById("d1")).thenReturn(false)
        val ex = assertThrows(BizException::class.java) {
            svc.syncInventory("d1", AppInventoryRequest(listOf(AppInventoryItem("com.a"))))
        }
        assertEquals(ChildErr.PARAM_ERROR, ex.code, "用 ParentErr 会被异常处理器落到 HTTP 200，孩子端当成功处理")
        assertEquals(0, saveAllCount())
    }

    @Test
    fun `syncInventory 新应用默认隐藏并下发隐藏指令与重建策略包`() {
        val r = svc.syncInventory("d1", AppInventoryRequest(listOf(AppInventoryItem("com.new", appName = "新应用"))))

        assertEquals(1, r["inserted"])
        assertEquals(CommandType.SET_APP_HIDDEN, issuedTypes.single())
        assertEquals("com.new", issuedPayloads.single()[CommandKey.PACKAGE_NAME])
        assertEquals(true, issuedPayloads.single()[CommandKey.HIDDEN])
        verify(policyExt, times(1)).rebuildPolicy("d1")
    }

    @Test
    fun `syncInventory 设备上报 hidden false 绝不覆盖家长授权意图`() {
        // 历史事故：密码门禁模型下设备上报 hidden 恒为 false，旧实现无条件回写，
        // 导致家长「一键取消全部授权」在下一个上报周期被整体恢复。
        val row = existing(pkg = "com.a", hidden = true, versionCode = 5L)
        `when`(installed.findByDeviceId("d1")).thenReturn(listOf(row))

        val item = AppInventoryItem(
            packageName = "com.a", appName = "计算器", versionName = "1.0",
            versionCode = 5L, hidden = false
        )
        svc.syncInventory("d1", AppInventoryRequest(listOf(item)))

        assertTrue(row.hidden, "授权意图由家长独家维护，设备无权改写")
        assertEquals(0, saveAllCount(), "授权意图漂移只告警，不得反向写库")
    }

    @Test
    fun `syncInventory 上报缺字段不得把应用名退化成包名`() {
        val row = existing(pkg = "com.a", appName = "计算器", versionName = "1.0", versionCode = 5L)
        row.updateTime = null
        `when`(installed.findByDeviceId("d1")).thenReturn(listOf(row))

        // 被隐藏的应用读不到包信息，上报里只剩一个裸包名
        svc.syncInventory("d1", AppInventoryRequest(listOf(AppInventoryItem(packageName = "com.a"))))

        assertEquals("计算器", row.appName)
        assertEquals("1.0", row.versionName)
        assertEquals(5L, row.versionCode)
    }

    @Test
    fun `syncInventory 卸载只置 installed false 不物理删除记录`() {
        val row = existing(pkg = "com.gone")
        `when`(installed.findByDeviceId("d1")).thenReturn(listOf(row))

        val r = svc.syncInventory("d1", AppInventoryRequest(emptyList()))

        assertEquals(1, r["removed"])
        assertFalse(row.installed, "孩子自己卸载是常见对抗手段，删记录就永远看不到曾装过")
        assertEquals(1, saveAllCount())
        verify(installed, never()).deleteAll()
    }

    @Test
    fun `syncInventory 用本次真实台账收敛分发状态`() {
        svc.syncInventory("d1", AppInventoryRequest(listOf(AppInventoryItem("com.a"))))
        verify(converger).onInventoryInstalled(
            eq("d1") ?: "d1",
            (any(Collection::class.java) as? Collection<String>) ?: emptyList()
        )
    }

    // ----------------------------- 归属校验（IDOR） -----------------------------

    @Test
    fun `installApp 越权被拒且绝不签发远程安装指令`() {
        `when`(guard.requireOwned("u1", "d1")).thenThrow(
            BizException(ParentErr.DEVICE_NOT_OWNED, "设备不属于当前用户", Audience.PARENT)
        )
        val req = AppInstallRequest(apkUrl = "https://x/a.apk", packageName = "com.evil")

        val ex = assertThrows(BizException::class.java) { svc.installApp("u1", "d1", req) }
        assertEquals(ParentErr.DEVICE_NOT_OWNED, ex.code)
        assertTrue(issuedTypes.isEmpty(), "漏校验等于允许任意家长往别人平板静默装 APK")
        verify(installed, never()).save(any(InstalledApp::class.java))
    }

    @Test
    fun `installApp 成功签发 INSTALL_APP 且 payload 契约完整并预置台账`() {
        val req = AppInstallRequest(apkUrl = "https://x/a.apk", packageName = "com.a", versionCode = 9L, appName = "A")
        val dto = svc.installApp("u1", "d1", req)

        assertEquals("m1", dto.msgId)
        assertEquals(CommandType.INSTALL_APP, issuedTypes.single())
        val p = issuedPayloads.single()
        assertEquals("https://x/a.apk", p[CommandKey.APK_URL])
        assertEquals("com.a", p[CommandKey.PACKAGE_NAME])
        assertEquals(9L, p[CommandKey.VERSION_CODE])
        verify(installed).save(any(InstalledApp::class.java))
    }

    @Test
    fun `installApp 缺省 versionCode 回落 0`() {
        svc.installApp("u1", "d1", AppInstallRequest(apkUrl = "u", packageName = "com.a"))
        assertEquals(0L, issuedPayloads.single()[CommandKey.VERSION_CODE])
    }

    @Test
    fun `listApps 必过归属校验`() {
        svc.listApps("u1", "d1")
        verify(guard).requireOwned(eq("u1") ?: "u1", eq("d1") ?: "d1")
    }

    @Test
    fun `listApps 非系统应用优先且策略合并输出`() {
        val sys = InstalledApp(id = "s", deviceId = "d1", packageName = "com.sys", appName = "Zystem", isSystem = true)
        val user = InstalledApp(id = "u", deviceId = "d1", packageName = "com.user", appName = "alpha")
        `when`(installed.findByDeviceIdAndInstalledTrue("d1")).thenReturn(listOf(sys, user))
        `when`(policies.findByDeviceId("d1")).thenReturn(
            listOf(AppPolicy(deviceId = "d1", packageName = "com.user", isBlocked = true, dailyLimitMinutes = 30))
        )

        val list = svc.listApps("u1", "d1")
        assertEquals(listOf("com.user", "com.sys"), list.map { it.packageName })
        assertTrue(list[0].blocked)
        assertEquals(30, list[0].dailyLimitMinutes)
    }

    @Test
    fun `listApps onlyInstalled false 走全量台账`() {
        svc.listApps("u1", "d1", onlyInstalled = false)
        verify(installed).findByDeviceId("d1")
        verify(installed, never()).findByDeviceIdAndInstalledTrue("d1")
    }

    // ----------------------------- 自身保护 / 指令语义 -----------------------------

    @Test
    fun `uninstallApp 拒绝空包名`() {
        val ex = assertThrows(BizException::class.java) { svc.uninstallApp("u1", "d1", "  ") }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
        assertTrue(issuedTypes.isEmpty())
    }

    @Test
    fun `uninstallApp 拒绝卸载管控端自身`() {
        for (pkg in listOf("com.padguard.child", "x.child", "org.demo.child")) {
            val ex = assertThrows(BizException::class.java) { svc.uninstallApp("u1", "d1", pkg) }
            assertEquals(ParentErr.PARAM_ERROR, ex.code)
        }
        assertTrue(issuedTypes.isEmpty(), "自身卸载必须在唯一指令入口被挡住，不产生任何指令记录")
    }

    @Test
    fun `uninstallApp 成功下发并置台账未安装`() {
        val row = existing(pkg = "com.a")
        `when`(installed.findByDeviceIdAndPackageName("d1", "com.a")).thenReturn(row)

        svc.uninstallApp("u1", "d1", "com.a")

        assertEquals(CommandType.UNINSTALL_APP, issuedTypes.single())
        assertEquals("com.a", issuedPayloads.single()[CommandKey.PACKAGE_NAME])
        assertFalse(row.installed)
    }

    @Test
    fun `setAppHidden 应用不存在抛参数错误`() {
        val ex = assertThrows(BizException::class.java) {
            svc.setAppHidden("u1", "d1", AppHiddenRequest("com.missing", true))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
        assertTrue(issuedTypes.isEmpty())
        verify(policyExt, never()).rebuildPolicy(anyString())
    }

    @Test
    fun `setAppHidden 写回意图下发指令并重建策略包`() {
        val row = existing(pkg = "com.a", hidden = false)
        `when`(installed.findByDeviceIdAndPackageName("d1", "com.a")).thenReturn(row)

        svc.setAppHidden("u1", "d1", AppHiddenRequest("com.a", true))

        assertTrue(row.hidden, "不等下一次上报周期，家长端开关要立刻有状态")
        assertEquals(CommandType.SET_APP_HIDDEN, issuedTypes.single())
        assertEquals(true, issuedPayloads.single()[CommandKey.HIDDEN])
        verify(policyExt).rebuildPolicy("d1")
    }

    @Test
    fun `setAppsHiddenBatch 过滤自身包名去重且只重建一次策略包`() {
        val sent = svc.setAppsHiddenBatch(
            "u1", "d1",
            AppHiddenBatchRequest(packages = listOf("com.a", "com.a", "", "com.padguard.child", "com.b"), hidden = true)
        )

        assertEquals(2, sent)
        assertEquals(2, issuedTypes.size, "去重后每个包只发一条")
        assertEquals(listOf("com.a", "com.b"), issuedPayloads.map { it[CommandKey.PACKAGE_NAME] })
        verify(policyExt, times(1)).rebuildPolicy("d1")
    }

    @Test
    fun `setAppsHiddenBatch all true 作用于全部已安装应用`() {
        `when`(installed.findByDeviceIdAndInstalledTrue("d1")).thenReturn(
            listOf(existing("com.a"), existing("com.b"))
        )
        val sent = svc.setAppsHiddenBatch("u1", "d1", AppHiddenBatchRequest(all = true, hidden = true))
        assertEquals(2, sent)
        verify(installed).findByDeviceIdAndInstalledTrue("d1")
    }

    @Test
    fun `setAppLimit 0 表示不限制落 null 正数原样落库`() {
        val policy = AppPolicy(deviceId = "d1", packageName = "com.a")
        `when`(policies.findByDeviceIdAndPackageName("d1", "com.a")).thenReturn(policy)

        svc.setAppLimit("u1", "d1", "com.a", 45)
        assertEquals(45, policy.dailyLimitMinutes)
        svc.setAppLimit("u1", "d1", "com.a", 0)
        assertNull(policy.dailyLimitMinutes)
        verify(policyExt, times(2)).rebuildPolicy("d1")
    }

    @Test
    fun `setSuspendedBatch 去重过滤空包名且回写台账`() {
        val row = existing("com.a")
        `when`(installed.findByDeviceId("d1")).thenReturn(listOf(row))

        val sent = svc.setSuspendedBatch("u1", "d1", listOf("com.a", "com.a", "", "com.padguard.child"), true)

        assertEquals(1, sent)
        assertEquals(CommandType.SET_APP_SUSPENDED, issuedTypes.single())
        assertTrue(row.suspended)
    }
}
