package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.SystemSetting
import com.padguard.server.dto.AdminPlanDto
import com.padguard.server.dto.AdminSettingsDto
import com.padguard.server.dto.AdminSettingsSaveDto
import com.padguard.server.repository.SystemSettingRepository
import com.padguard.server.security.AdminPermissionService
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 系统设置（读取 / 保存）。W25 从 AdminService 抽离。
 */
@Service
class AdminSettingsService(
    private val adminPerm: AdminPermissionService,
    private val systemSettingRepository: SystemSettingRepository,
    private val billingService: BillingService,
    private val objectMapper: ObjectMapper,
    private val auditService: AuditService,
    private val env: Environment
) {
    private val log = LoggerFactory.getLogger(AdminSettingsService::class.java)

    fun settings(adminId: String): AdminSettingsDto {
        requireRead(adminPerm, adminId, "settings")
        return settingsInternal()
    }

    /**
     * 读取设置的内部实现，不带闸门。供 [saveSettings] 在保存成功后回读最新值。
     */
    private fun settingsInternal(): AdminSettingsDto {
        val store = loadSettingsStore(systemSettingRepository, objectMapper)
        val plans = billingService.plans().map { p ->
            val price = store.planPrices[p.key] ?: p.amountFen
            AdminPlanDto(id = p.key, name = p.label, priceFen = price, deviceLimit = p.deviceLimit, features = PLAN_FEATURES[p.key] ?: emptyList())
        }
        return AdminSettingsDto(
            brand = store.brand, currency = store.currency, trialDays = store.trialDays,
            passwordPolicy = store.passwordPolicy, mfaRequired = store.mfaRequired,
            mockPay = store.mockPay, plans = plans
        )
    }

    @Transactional
    fun saveSettings(adminId: String, req: AdminSettingsSaveDto): AdminSettingsDto {
        adminPerm.require(adminId, "settings", "write")
        val cur = loadSettingsStore(systemSettingRepository, objectMapper)
        val next = SettingsStore(
            brand = req.brand ?: cur.brand,
            currency = req.currency ?: cur.currency,
            trialDays = req.trialDays ?: cur.trialDays,
            passwordPolicy = req.passwordPolicy ?: cur.passwordPolicy,
            mfaRequired = req.mfaRequired ?: cur.mfaRequired,
            mockPay = req.mockPay ?: cur.mockPay,
            planPrices = cur.planPrices.toMutableMap().apply {
                req.plans?.forEach { put(it.id.uppercase(), it.priceFen) }
            }
        )
        // W22：示例支付是生产环境的安全红线。后台「系统设置」允许运营把 mockPay 打开，
        // 但若是生产/预发等非开发 profile，落库后计费侧会据此跳过支付验签。故非开发环境一律拒绝开启。
        if (next.mockPay && !isDevProfile(env)) {
            throw BizException(ParentErr.PARAM_ERROR, "示例支付仅允许在开发环境（local/dev）开启，生产环境禁止开启", Audience.PARENT)
        }
        systemSettingRepository.save(SystemSetting("admin.settings", objectMapper.writeValueAsString(next)))
        auditService.record(adminId, null, AdminAudit.SETTINGS_WRITE, targetType = "settings", targetId = "admin.settings", detail = "修改系统设置")
        return settingsInternal()
    }
}
