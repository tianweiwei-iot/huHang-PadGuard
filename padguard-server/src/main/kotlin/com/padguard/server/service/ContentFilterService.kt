package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.DeviceSetting
import com.padguard.server.domain.SensitiveWord
import com.padguard.server.domain.UrlCategory
import com.padguard.server.dto.ContentCategoryDto
import com.padguard.server.dto.SensitiveHitDto
import com.padguard.server.dto.TextScanResultDto
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.DeviceSettingRepository
import com.padguard.server.repository.SensitiveWordRepository
import com.padguard.server.repository.UrlCategoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import jakarta.annotation.PostConstruct

/**
 * 内容管控服务（P2 补强）。
 *
 * 覆盖"最大差距项"：网址分类库、搜索引擎强制安全搜索、按分龄/分类屏蔽、敏感词扫描。
 *
 * ## 架构取向（与教程 §3 一致：主流稳妥、预留云端）
 * - **本地兜底**：内置高风险域名规则 + 本地敏感词库，保证无外部依赖即可跑通链路。
 * - **云端扩展点**：通过 [UrlClassifier] / [ContentScanner] 两个接口预留云端分类 API 与 AI 鉴黄/NLP 位置，
 *   后续接入时实现接口并替换注入即可，无需改动调用方。
 * - **涉密只告警不全文展示**：扫描命中只回掩码词 + 分类，明文绝不外泄。
 */
@Service
class ContentFilterService(
    private val deviceRepository: DeviceRepository,
    private val ownershipGuard: com.padguard.server.security.DeviceOwnershipGuard,
    private val deviceSettingRepository: DeviceSettingRepository,
    private val urlCategoryRepository: UrlCategoryRepository,
    private val sensitiveWordRepository: SensitiveWordRepository,
    private val auditService: AuditService
) {

    private val log = LoggerFactory.getLogger(ContentFilterService::class.java)

    /** 本地敏感词缓存（启动时加载，种子更新后刷新）。云端 AI 识别为兜底补充。 */
    private val wordCache = AtomicReference<List<SensitiveWord>>(emptyList())

    /**
     * 本地内置的高风险域名关键字 -> 分类映射，作为云端分类库的本地兜底与示例。
     * 真实运营应接入云端分类 API（数十亿条），此处仅覆盖典型样本，证明链路打通。
     * 分类 id 与 [seedDictionaries] 中的 url_categories 种子保持一致。
     */
    private val LOCAL_DOMAIN_RULES: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\b(porn|xxx|adult|sex|av\b|onlyfans|xvideos|pornhub)\b""") to "cat_porn",
        Regex("""(?i)\b(casino|bet\b|gamble|lottery|彩票|博彩|bet365)\b""") to "cat_gambling",
        Regex("""(?i)\b(violence|kill|blood|暴力|杀人|血腥)\b""") to "cat_violence",
        Regex("""(?i)\b(fraud|scam|诈骗|传销|刷单|兼职赚钱)\b""") to "cat_fraud"
    )

    /** 云端网址分类 Provider（预留）。实现后注入即可替换本地规则。 */
    interface UrlClassifier {
        /** 返回命中网址的分类 id 列表（url_categories.id） */
        fun classify(url: String): List<String>
    }

    /** 云端内容扫描 Provider（预留）。实现后注入即可替换本地词典。 */
    interface ContentScanner {
        /** 返回敏感命中（涉密内容只告警、不全文展示） */
        fun scan(text: String): List<SensitiveHitDto>
    }

    @Transactional
    fun setSafeSearch(userId: String, deviceId: String, enabled: Boolean) {
        requireOwned(userId, deviceId)
        val s = deviceSettingOf(deviceId)
        s.webSafeSearch = enabled
        deviceSettingRepository.save(s)
        auditService.record(
            userId, deviceId, AuditAction.CONTENT_SAFE_SEARCH_UPDATE,
            targetType = "device", targetId = deviceId, detail = "enabled=$enabled"
        )
    }

    @Transactional
    fun setBlockedCategories(userId: String, deviceId: String, categories: List<String>) {
        requireOwned(userId, deviceId)
        // 校验分类存在，避免脏数据写入
        val valid = categories.filter { urlCategoryRepository.existsById(it) }.toSet()
        val s = deviceSettingOf(deviceId)
        s.webBlockedCategories = valid.joinToString(",")
        deviceSettingRepository.save(s)
        auditService.record(
            userId, deviceId, AuditAction.CONTENT_CATEGORY_BLOCK_UPDATE,
            targetType = "device", targetId = deviceId, detail = "categories=${valid.joinToString(",")}"
        )
    }

    /**
     * 该设备实际应屏蔽的分类集合：默认屏蔽（blocked_by_default）+ 家长自定义。
     * 策略包下发时直接携带该集合，孩子端据此拦截对应分类网址。
     */
    fun blockedCategoriesForDevice(deviceId: String): Set<String> {
        val byDefault = urlCategoryRepository.findAll().filter { it.blockedByDefault }.map { it.id }.toSet()
        val custom = deviceSettingOf(deviceId).webBlockedCategories
            ?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty()
        return byDefault + custom
    }

    fun listCategories(): List<ContentCategoryDto> =
        urlCategoryRepository.findAll().map {
            ContentCategoryDto(it.id, it.name, it.description, it.riskLevel, it.blockedByDefault)
        }

    /** 本地网址分类（兜底实现）。云端分类实现通过 [UrlClassifier] 接口替换。 */
    fun classifyUrl(url: String): List<String> {
        val host = runCatching { URL(url).host }.getOrElse { "" }
        return LOCAL_DOMAIN_RULES.mapNotNull { (regex, cat) ->
            if (regex.containsMatchIn(host) || regex.containsMatchIn(url)) cat else null
        }.toSet().toList()
    }

    /** 本地敏感词扫描（兜底实现）。云端 AI 识别通过 [ContentScanner] 接口替换。 */
    fun scanText(text: String): TextScanResultDto {
        val hits = wordCache.get().mapNotNull { w ->
            if (text.contains(w.word, ignoreCase = true)) {
                SensitiveHitDto(w.category, w.level, maskWord(w.word))
            } else null
        }
        return TextScanResultDto(hit = hits.isNotEmpty(), hitCount = hits.size, hits = hits.distinctBy { it.category to it.maskedWord })
    }

    /**
     * 命中词做掩码，避免在日志 / 告警中二次传播明文（涉密内容只告警、不全文展示）。
     * 仅保留首字符，符合"可定位、不泄露"的最小披露原则。
     */
    private fun maskWord(word: String): String =
        if (word.length <= 1) "*" else word.first() + "*".repeat(word.length - 1)

    /**
     * 种子数据：幂等播种分类库与敏感词库，并刷新本地缓存。
     * 供 [com.padguard.server.config.DataInitializer] 在应用就绪时调用（postgres 与 h2 双覆盖）。
     */
    @Transactional
    fun seedDictionaries() {
        if (urlCategoryRepository.count() == 0L) {
            urlCategoryRepository.saveAll(
                listOf(
                    UrlCategory("cat_porn", "色情低俗", "涉黄/成人内容", "HIGH", true, now()),
                    UrlCategory("cat_gambling", "赌博博彩", "赌博/博彩/彩票", "HIGH", true, now()),
                    UrlCategory("cat_violence", "暴力血腥", "暴力/血腥/自伤", "HIGH", true, now()),
                    UrlCategory("cat_fraud", "诈骗风险", "诈骗/传销/刷单", "MEDIUM", false, now()),
                    UrlCategory("cat_game", "游戏", "游戏类应用/站点", "MEDIUM", false, now()),
                    UrlCategory("cat_social", "社交", "社交/聊天类站点", "LOW", false, now())
                )
            )
        }
        if (sensitiveWordRepository.count() == 0L) {
            sensitiveWordRepository.saveAll(
                DEFAULT_SENSITIVE_WORDS.mapIndexed { i, (w, c) ->
                    SensitiveWord("sw_$i", w, c, "HIGH", now())
                }
            )
        }
        refreshWordCache()
    }

    @Transactional
    fun refreshWordCache() {
        wordCache.set(sensitiveWordRepository.findAll())
    }

    @PostConstruct
    fun loadCacheOnStart() = runCatching { refreshWordCache() }
        .onFailure { log.warn("load sensitive words cache failed: {}", it.message) }

    private fun deviceSettingOf(deviceId: String): DeviceSetting =
        deviceSettingRepository.findById(deviceId).orElse(DeviceSetting(deviceId = deviceId))

    /**
     * 设备归属校验已收敛至 [com.padguard.server.security.DeviceOwnershipGuard]（唯一实现），
     * 消除逐 Service 复制私有副本的漏网风险（详见该组件类注释）。
     */
    private fun requireOwned(userId: String, deviceId: String) =
        ownershipGuard.requireOwned(userId, deviceId)

    private fun now() = System.currentTimeMillis()

    companion object {
        /** 默认敏感词种子（本地兜底库），真实运营应接入云端 AI 识别并定期更新。 */
        private val DEFAULT_SENSITIVE_WORDS = listOf(
            "色情" to "PORN", "裸聊" to "PORN", "约炮" to "PORN", "成人内容" to "PORN",
            "赌博" to "GAMBLING", "博彩" to "GAMBLING", "彩票" to "GAMBLING", "赌场" to "GAMBLING",
            "诈骗" to "FRAUD", "传销" to "FRAUD", "刷单" to "FRAUD", "兼职赚钱" to "FRAUD",
            "暴力" to "VIOLENCE", "血腥" to "VIOLENCE", "自残" to "VIOLENCE"
        )
    }
}
