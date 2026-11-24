package com.padguard.server.service

import com.padguard.server.domain.DeviceSetting
import com.padguard.server.domain.SensitiveWord
import com.padguard.server.domain.UrlCategory
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.DeviceSettingRepository
import com.padguard.server.repository.SensitiveWordRepository
import com.padguard.server.repository.UrlCategoryRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.*

/**
 * `ContentFilterService` 是内容安全管控的信任关键路径（网址分类、安全搜索开关、按分类屏蔽、敏感词扫描），
 * 此前**零单测**。一旦回归改坏：要么孩子能绕过分类屏蔽打开高危站点，要么敏感词扫描失效（涉密内容静默放行）。
 *
 * 覆盖：
 *  - [ContentFilterService.classifyUrl] 本地域名规则命中（色情/赌博/暴力/诈骗 + 多分类）；
 *  - [ContentFilterService.scanText] 敏感词命中 + 掩码最小披露（不在服务端落明文）；
 *  - [ContentFilterService.setSafeSearch] / [ContentFilterService.setBlockedCategories] 归属校验 + 落库 + rebuild 下发 + 审计；
 *  - [ContentFilterService.blockedCategoriesForDevice] 默认屏蔽 + 家长自定义的并集；
 *  - [ContentFilterService.listCategories] 分类库映射。
 * 不加载 Spring 上下文，沿用既定 Mockito 占位范式（非空参数 matcher 补 `?: 默认值`）。
 */
class ContentFilterServiceTest {
    private val deviceRepository = mock(DeviceRepository::class.java)
    private val ownershipGuard = mock(DeviceOwnershipGuard::class.java)
    private val deviceSettingRepository = mock(DeviceSettingRepository::class.java)
    private val urlCategoryRepository = mock(UrlCategoryRepository::class.java)
    private val sensitiveWordRepository = mock(SensitiveWordRepository::class.java)
    private val auditService = mock(AuditService::class.java)
    private val policyExtensionService = mock(PolicyExtensionService::class.java)

    private val userId = "u1"
    private val deviceId = "d1"

    private fun svc(): ContentFilterService = ContentFilterService(
        deviceRepository, ownershipGuard, deviceSettingRepository,
        urlCategoryRepository, sensitiveWordRepository, auditService, policyExtensionService
    )

    private fun stubOwned() {
        `when`(ownershipGuard.requireOwned(anyString() ?: "", anyString() ?: ""))
            .thenReturn(com.padguard.server.domain.Device(id = deviceId, userId = userId))
    }

    private fun stubSetting(): DeviceSetting {
        val s = DeviceSetting(deviceId = deviceId)
        `when`(deviceSettingRepository.findById(anyString() ?: "")).thenReturn(Optional.of(s))
        return s
    }

    // ---- classifyUrl（纯函数，无 DB）----

    @Test
    fun classifyUrl_pornHost_hitsPornCategory() {
        assertEquals(listOf("cat_porn"), svc().classifyUrl("https://www.pornhub.com/video"))
    }

    @Test
    fun classifyUrl_gamblingKeyword_hitsGambling() {
        assertEquals(listOf("cat_gambling"), svc().classifyUrl("https://bet365.com"))
    }

    @Test
    fun classifyUrl_cleanUrl_noCategory() {
        assertTrue(svc().classifyUrl("https://example.com/healthy").isEmpty())
    }

    @Test
    fun classifyUrl_multiCategory_returnsAll() {
        // 同时命中赌博与色情关键字
        val cats = svc().classifyUrl("https://casino-porn.example.com")
        assertTrue(cats.contains("cat_gambling"))
        assertTrue(cats.contains("cat_porn"))
    }

    // ---- scanText（本地词库，需先刷新缓存）----

    private fun withWordCache(vararg words: SensitiveWord): ContentFilterService {
        `when`(sensitiveWordRepository.findAll()).thenReturn(words.toList())
        val s = svc()
        s.refreshWordCache()
        return s
    }

    @Test
    fun scanText_hitSensitiveWord_masksAndReports() {
        val s = withWordCache(
            SensitiveWord("sw0", "色情", "PORN", "HIGH", 0),
            SensitiveWord("sw1", "赌博", "GAMBLING", "HIGH", 0)
        )
        val res = s.scanText("这是一段包含色情的文本，还有赌博内容")
        assertTrue(res.hit)
        assertEquals(2, res.hitCount)
        // 命中词做掩码：仅保留首字符，不在服务端落明文
        assertTrue(res.hits.any { it.category == "PORN" && it.maskedWord == "色*" })
        assertTrue(res.hits.any { it.category == "GAMBLING" && it.maskedWord == "赌*" })
    }

    @Test
    fun scanText_cleanText_noHit() {
        val s = withWordCache(SensitiveWord("sw0", "色情", "PORN", "HIGH", 0))
        val res = s.scanText("这是一段正常的聊天内容")
        assertFalse(res.hit)
        assertEquals(0, res.hitCount)
        assertTrue(res.hits.isEmpty())
    }

    // ---- setSafeSearch ----

    @Test
    fun setSafeSearch_persistsAndRebuildsAndAudits() {
        stubOwned()
        var saved: DeviceSetting? = null
        `when`(deviceSettingRepository.save(any(com.padguard.server.domain.DeviceSetting::class.java) ?: DeviceSetting(deviceId = deviceId)))
            .thenAnswer { inv -> saved = inv.arguments[0] as DeviceSetting; inv.arguments[0] }

        svc().setSafeSearch(userId, deviceId, true)

        // 归属校验必须执行
        verify(ownershipGuard).requireOwned(userId, deviceId)
        // 安全搜索开关落库
        assertTrue(saved?.webSafeSearch == true)
        // 策略必须 rebuild 才下发（不 rebuild 则孩子端永远收不到变更）
        verify(policyExtensionService).rebuildPolicy(deviceId)
        // 操作留痕（action/result 为非空 String 用 anyString，其余可空参数用 any 以匹配 null）
        verify(auditService).record(
            any(), any(), anyString() ?: "",
            any(), any(), any(), anyString() ?: "", any()
        )
    }

    // ---- setBlockedCategories ----

    @Test
    fun setBlockedCategories_filtersToExistingAndPersists() {
        stubOwned()
        `when`(urlCategoryRepository.existsById("cat_porn")).thenReturn(true)
        `when`(urlCategoryRepository.existsById("nonexistent")).thenReturn(false)
        var saved: DeviceSetting? = null
        `when`(deviceSettingRepository.save(any(com.padguard.server.domain.DeviceSetting::class.java) ?: DeviceSetting(deviceId = deviceId)))
            .thenAnswer { inv -> saved = inv.arguments[0] as DeviceSetting; inv.arguments[0] }

        // 传入一个不存在的分类：必须被校验过滤掉，只保留 cat_porn
        svc().setBlockedCategories(userId, deviceId, listOf("cat_porn", "nonexistent"))

        assertEquals("cat_porn", saved?.webBlockedCategories)
        verify(policyExtensionService).rebuildPolicy(deviceId)
    }

    // ---- blockedCategoriesForDevice ----

    @Test
    fun blockedCategoriesForDevice_unionOfDefaultAndCustom() {
        `when`(urlCategoryRepository.findAll()).thenReturn(
            listOf(
                UrlCategory("cat_porn", blockedByDefault = true),
                UrlCategory("cat_gambling", blockedByDefault = true),
                UrlCategory("cat_game", blockedByDefault = false)
            )
        )
        val s = stubSetting()
        s.webBlockedCategories = "cat_violence"

        val blocked = svc().blockedCategoriesForDevice(deviceId)

        // 默认屏蔽（cat_porn, cat_gambling）+ 家长自定义（cat_violence）的并集
        assertTrue(blocked.contains("cat_porn"))
        assertTrue(blocked.contains("cat_gambling"))
        assertTrue(blocked.contains("cat_violence"))
        // 非默认且未自定义的不应包含
        assertFalse(blocked.contains("cat_game"))
    }

    // ---- listCategories ----

    @Test
    fun listCategories_mapsDto() {
        `when`(urlCategoryRepository.findAll()).thenReturn(
            listOf(UrlCategory("cat_porn", "色情低俗", "涉黄", "HIGH", true))
        )
        val cats = svc().listCategories()
        assertEquals(1, cats.size)
        assertEquals("cat_porn", cats[0].id)
        assertEquals("HIGH", cats[0].riskLevel)
        assertTrue(cats[0].blockedByDefault)
    }
}
