package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.domain.AppPolicy
import com.padguard.server.domain.Device
import com.padguard.server.domain.DeviceSetting
import com.padguard.server.domain.Policy
import com.padguard.server.dto.*
import com.padguard.server.mqtt.MqttGateway
import com.padguard.server.repository.*
import com.padguard.server.security.DeviceOwnershipGuard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.*

/**
 * `PolicyExtensionService` 是家长端策略下发（时段/应用/上网/护眼/锁机/救援通道）的唯一写入面，
 * 此前**零单测**。其写入路径全部经 [PolicyExtensionService.rebuildPolicy] 落到「由子表重建策略包」，
 * 历史上出过不少"存了但孩子端不生效 / 存错变砖"的静默缺陷：
 * - 非法 appListMode 把全设备应用挂起（白名单空 = 变砖）；
 * - 护眼强度越界导致档位失真；
 * - 定时锁机时间格式不校验，存进去孩子端解析失败静默降级为"永不锁机"；
 * - 应用黑名单"只增不减"，家长取消后解不开；
 * - 子表与策略包漂移无人自愈（虚假锁定态）。
 *
 * 覆盖：
 * - 读接口默认兜底：每日时长缺省 120、appListMode 缺省 BLACKLIST、safeSearch 缺省 false、护眼回读；
 * - 写接口关键回落/校验：WHITELIST 保留 / 非法 mode→BLACKLIST、护眼强度钳到 [1,5]、
 *   定时锁机 HH:mm 校验、应用黑名单全量语义（新增+解除）；
 * - [PolicyExtensionService.ensureRebuilt] 漂移检测触发重建、回滚态跳过自愈。
 *
 * `rebuildPolicy` 在本测试（无 Spring 事务）下会真实执行 [PolicyVersions.allocate]
 * （降级为 `nextVersion`，已 stub），并因 `afterCommit` 无事务立即执行 `mqttGateway.publishPolicyNotify`
 * （落到 mock）。不加载 Spring 上下文，沿用既定 Mockito 占位范式（非空参数 matcher 补 `?: 默认值`；
 * `any()` 仅用于可空参数）。
 */
class PolicyExtensionServiceTest {
    private val deviceRepository = mock(DeviceRepository::class.java)
    private val ownershipGuard = mock(DeviceOwnershipGuard::class.java)
    private val policyRepository = mock(PolicyRepository::class.java)
    private val appPolicyRepository = mock(AppPolicyRepository::class.java)
    private val timeRestrictionRepository = mock(TimeRestrictionRepository::class.java)
    private val deviceSettingRepository = mock(DeviceSettingRepository::class.java)
    private val policyTemplateRepository = mock(PolicyTemplateRepository::class.java)
    private val installedAppRepository = mock(InstalledAppRepository::class.java)
    private val subscriptionRepository = mock(SubscriptionRepository::class.java)
    private val commandService = mock(CommandService::class.java)
    private val mqttGateway = mock(MqttGateway::class.java)
    private val objectMapper = ObjectMapper()
    private val contentFilterService = mock(ContentFilterService::class.java)
    private val auditService = mock(AuditService::class.java)

    private val deviceId = "d1"
    private val userId = "u1"

    private fun svc(): PolicyExtensionService = PolicyExtensionService(
        deviceRepository, ownershipGuard, policyRepository, appPolicyRepository,
        timeRestrictionRepository, deviceSettingRepository, policyTemplateRepository,
        installedAppRepository, subscriptionRepository, commandService, mqttGateway,
        objectMapper, contentFilterService, auditService
    )

    private var savedSetting: DeviceSetting? = null

    /** 让 rebuildPolicy 在无事务单测下走通：桩好所有子表读取与版本分配、MQTT 推送。 */
    private fun stubRebuild() {
        `when`(deviceRepository.findById(anyString() ?: "")).thenReturn(
            Optional.of(Device().apply { id = deviceId; this.userId = userId })
        )
        `when`(policyRepository.findFirstByDeviceIdOrderByVersionDesc(anyString() ?: "")).thenReturn(null)
        `when`(appPolicyRepository.findByDeviceId(anyString() ?: "")).thenReturn(emptyList())
        `when`(timeRestrictionRepository.findByDeviceId(anyString() ?: "")).thenReturn(emptyList())
        `when`(installedAppRepository.findByDeviceId(anyString() ?: "")).thenReturn(emptyList())
        `when`(contentFilterService.blockedCategoriesForDevice(anyString() ?: "")).thenReturn(emptySet())
        `when`(subscriptionRepository.markExpiredIfStale(anyString() ?: "", anyString() ?: "", anyLong())).thenReturn(0)
        `when`(subscriptionRepository.findByUserId(anyString() ?: "")).thenReturn(null)
        `when`(policyRepository.nextVersion(anyString() ?: "")).thenReturn(1)
        `when`(deviceSettingRepository.findById(anyString() ?: "")).thenAnswer { Optional.of(DeviceSetting(deviceId = deviceId)) }
        `when`(deviceSettingRepository.save(any(DeviceSetting::class.java) ?: DeviceSetting(deviceId)))
            .thenAnswer { inv -> savedSetting = inv.arguments[0] as DeviceSetting; inv.arguments[0] }
    }

    private fun settingWith(block: DeviceSetting.() -> Unit): DeviceSetting =
        DeviceSetting(deviceId = deviceId).apply(block)

    @Test
    fun rebuildPolicy_carriesConsentRevokedToChild() {
        stubRebuild()
        `when`(deviceRepository.findById(deviceId)).thenReturn(
            Optional.of(Device().apply { id = deviceId; userId = userId; consentRevoked = true })
        )

        svc().rebuildPolicy(deviceId)

        val captor = ArgumentCaptor.forClass(Policy::class.java)
        verify(policyRepository).save(captor.capture())
        val monitoring = objectMapper.readTree(captor.value.packageJson).path("monitoring")
        assertTrue(
            monitoring.path("consentRevoked").asBoolean(false),
            "撤回同意必须写进孩子端会拉取的策略包，否则只有服务端停落库、端上照样采集"
        )
        verify(mqttGateway).publishPolicyNotify(eq(deviceId) ?: deviceId, anyInt())
    }

    // ---- 读接口默认兜底（不触发 rebuild）----

    @Test
    fun getDailyLimit_returnsDefault120WhenUnset() {
        `when`(deviceSettingRepository.findById(anyString() ?: "")).thenReturn(Optional.of(settingWith { dailyLimitMinutes = 0 }))

        assertEquals(120, svc().getDailyLimit(userId, deviceId))
    }

    @Test
    fun getDailyLimit_returnsConfiguredValue() {
        `when`(deviceSettingRepository.findById(anyString() ?: "")).thenReturn(Optional.of(settingWith { dailyLimitMinutes = 200 }))

        assertEquals(200, svc().getDailyLimit(userId, deviceId))
    }

    @Test
    fun getAppListPolicy_fallsBackToBlacklistWhenUnset() {
        `when`(deviceSettingRepository.findById(anyString() ?: "")).thenReturn(Optional.of(settingWith { appListMode = null }))
        `when`(appPolicyRepository.findByDeviceId(anyString() ?: "")).thenReturn(emptyList())

        val dto = svc().getAppListPolicy(userId, deviceId)

        assertEquals("BLACKLIST", dto.mode)
        assertTrue(dto.requirePasswordOnLaunch) // 缺省 true，杜绝"装完即玩"
    }

    @Test
    fun getAppListPolicy_reflectsWhitelistMode() {
        `when`(deviceSettingRepository.findById(anyString() ?: "")).thenReturn(Optional.of(settingWith { appListMode = "WHITELIST" }))
        `when`(appPolicyRepository.findByDeviceId(anyString() ?: "")).thenReturn(emptyList())

        assertEquals("WHITELIST", svc().getAppListPolicy(userId, deviceId).mode)
    }

    @Test
    fun getWebPolicy_splitsBlockedUrlsAndDefaultsSafeSearch() {
        `when`(deviceSettingRepository.findById(anyString() ?: "")).thenReturn(
            Optional.of(settingWith { webBlockedUrls = "a.com,b.com"; webSafeSearch = null })
        )
        `when`(contentFilterService.blockedCategoriesForDevice(anyString() ?: "")).thenReturn(setOf("GAMBLING"))

        val dto = svc().getWebPolicy(userId, deviceId)

        assertEquals(listOf("a.com", "b.com"), dto.blockedUrls)
        assertFalse(dto.safeSearch)
        assertEquals(listOf("GAMBLING"), dto.blockedCategories)
    }

    @Test
    fun getEyeProtection_readsEnabledAndLevel() {
        `when`(deviceSettingRepository.findById(anyString() ?: "")).thenReturn(
            Optional.of(settingWith { eyeProtectionEnabled = true; eyeProtectionLevel = 3 })
        )

        val dto = svc().getEyeProtection(userId, deviceId)

        assertTrue(dto.enabled)
        assertEquals(3, dto.level)
    }

    @Test
    fun getSystemLockRelaxed_defaultsFalse() {
        `when`(deviceSettingRepository.findById(anyString() ?: "")).thenReturn(Optional.of(settingWith { systemLockRelaxed = null }))

        assertFalse(svc().getSystemLockRelaxed(userId, deviceId) ?: false)
    }

    // ---- 写接口回落/校验（经 rebuildPolicy）----

    @Test
    fun setAppListPolicy_preservesWhitelistModeAndPasswordFlags() {
        stubRebuild()

        svc().setAppListPolicy(
            userId, deviceId,
            AppListPolicyRequest(mode = "WHITELIST", requirePasswordOnLaunch = false,
                requirePasswordOnInstall = false, requirePasswordOnUninstall = false)
        )

        assertNotNull(savedSetting)
        assertEquals("WHITELIST", savedSetting!!.appListMode)
        assertFalse(savedSetting!!.requirePasswordOnLaunch ?: false)
    }

    @Test
    fun setAppListPolicy_invalidModeFallsBackToBlacklist() {
        stubRebuild()

        svc().setAppListPolicy(userId, deviceId, AppListPolicyRequest(mode = "WEIRD_MODE"))

        assertEquals("BLACKLIST", savedSetting!!.appListMode)
    }

    @Test
    fun setEyeProtection_clampsLevelToValidRange() {
        stubRebuild()

        svc().setEyeProtection(userId, deviceId, EyeProtectionRequest(enabled = true, filterLevel = 99))
        assertEquals(5, savedSetting!!.eyeProtectionLevel)
        assertTrue(savedSetting!!.eyeProtectionEnabled)

        svc().setEyeProtection(userId, deviceId, EyeProtectionRequest(enabled = true, filterLevel = 0))
        assertEquals(1, savedSetting!!.eyeProtectionLevel)
    }

    @Test
    fun updateSmartShutdown_rejectsBadTimeFormat() {
        // 非法格式必须在入口拦掉（否则孩子端解析失败静默降级为"永不锁机"）
        assertThrows(IllegalArgumentException::class.java) {
            svc().updateSmartShutdown(userId, deviceId, SmartShutdownRequest(enabled = true, startTime = "99:99", endTime = "06:00"))
        }
    }

    @Test
    fun updateSmartShutdown_storesValidWindow() {
        stubRebuild()

        svc().updateSmartShutdown(userId, deviceId, SmartShutdownRequest(enabled = true, startTime = "22:00", endTime = "06:00"))

        assertTrue(savedSetting!!.smartShutdownEnabled)
        assertEquals("22:00", savedSetting!!.smartShutdownStart)
        assertEquals("06:00", savedSetting!!.smartShutdownEnd)
    }

    @Test
    fun setAppBlacklist_fullSemanticsBlocksNewAndUnblocksRemoved() {
        stubRebuild()
        // 存量：com.keep 与 com.old 都已被家长标记为管控
        `when`(appPolicyRepository.findByDeviceId(anyString() ?: "")).thenReturn(
            listOf(
                AppPolicy(packageName = "com.keep", isBlocked = true),
                AppPolicy(packageName = "com.old", isBlocked = true)
            )
        )
        var savedPolicies = mutableListOf<AppPolicy>()
        `when`(appPolicyRepository.save(any(AppPolicy::class.java) ?: AppPolicy(packageName = "x")))
            .thenAnswer { inv -> savedPolicies.add(inv.arguments[0] as AppPolicy); inv.arguments[0] }

        // 新名单只含 com.keep：com.old 应被解除管控并落库；com.keep 已管控不变（不重复写）
        svc().setAppBlacklist(userId, deviceId, UpdateBlacklistRequest(blockedPackages = listOf("com.keep")))

        val old = savedPolicies.firstOrNull { it.packageName == "com.old" }
        assertNotNull(old)
        assertFalse(old!!.isBlocked) // 不在新名单内 → 解除管控
        // com.keep 已管控、未变化 → 不应有它的写操作（无"只增不减"副作用）
        assertNull(savedPolicies.firstOrNull { it.packageName == "com.keep" })
    }

    // ---- ensureRebuilt 漂移自愈 ----

    @Test
    fun ensureRebuilt_rebuildsWhenDriftDetected() {
        stubRebuild()
        // 当前策略包是过期内容（与子表重建结果不一致）→ 应触发自愈重建
        `when`(policyRepository.findFirstByDeviceIdOrderByVersionDesc(anyString() ?: "")).thenReturn(
            Policy().apply { id = "p1"; this.deviceId = deviceId; version = 1; packageJson = "STALE_PACKAGE"; source = "NORMAL"; updatedAt = 1 }
        )

        svc().ensureRebuilt(deviceId)

        verify(policyRepository).save(any(Policy::class.java) ?: Policy())
    }

    @Test
    fun ensureRebuilt_skipsSelfHealDuringRollback() {
        stubRebuild()
        // 版本回滚态：自愈会把刚回滚的包又建回当前配置，必须跳过
        `when`(policyRepository.findFirstByDeviceIdOrderByVersionDesc(anyString() ?: "")).thenReturn(
            Policy().apply { id = "p1"; this.deviceId = deviceId; version = 1; packageJson = "STALE_PACKAGE"; source = "ROLLBACK"; updatedAt = 1 }
        )

        svc().ensureRebuilt(deviceId)

        verify(policyRepository, never()).save(any(Policy::class.java) ?: Policy())
    }
}
