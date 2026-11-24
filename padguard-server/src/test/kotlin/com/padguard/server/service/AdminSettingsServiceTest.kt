package com.padguard.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.SystemSetting
import com.padguard.server.dto.AdminPlanPriceDto
import com.padguard.server.dto.AdminSettingsSaveDto
import com.padguard.server.dto.PlanDto
import com.padguard.server.repository.SystemSettingRepository
import com.padguard.server.security.AdminPermissionService
import com.padguard.server.service.AdminAudit.SETTINGS_WRITE
import com.padguard.server.service.AuditService
import com.padguard.server.service.BillingService
import com.padguard.server.service.SettingsStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.core.env.Environment
import java.util.*

class AdminSettingsServiceTest {

    private lateinit var perm: AdminPermissionService
    private lateinit var sys: SystemSettingRepository
    private lateinit var billing: BillingService
    private lateinit var audit: AuditService
    private lateinit var env: Environment
    private lateinit var svc: AdminSettingsService
    private lateinit var om: ObjectMapper

    @BeforeEach
    fun setup() {
        perm = mock(AdminPermissionService::class.java)
        sys = mock(SystemSettingRepository::class.java)
        billing = mock(BillingService::class.java)
        audit = mock(AuditService::class.java)
        env = mock(Environment::class.java)
        om = ObjectMapper().registerModule(KotlinModule.Builder().build())
        svc = AdminSettingsService(perm, sys, billing, om, audit, env)
        `when`(billing.plans()).thenReturn(listOf(
            PlanDto("MONTHLY", "月付", 1, 3800, "¥38", 3),
            PlanDto("YEARLY", "年付", 12, 38000, "¥380", 5)
        ))
        `when`(sys.findById(anyString())).thenReturn(Optional.empty())
    }

    @Test
    fun settings_readGate_throwsIfNoReadPrivilege() {
        doThrow(BizException(ParentErr.FORBIDDEN, "x", Audience.PARENT)).`when`(perm).require(anyString(), anyString(), anyString())
        val ex = assertThrows(BizException::class.java) { svc.settings("actor") }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
    }

    @Test
    fun settings_returnsPlansWithBillingPriceWhenNoOverride() {
        val dto = svc.settings("actor")
        assertEquals("PadGuard", dto.brand)
        assertEquals(3800, dto.plans.first { it.id == "MONTHLY" }.priceFen)
    }

    @Test
    fun settings_planPriceOverrideMerges() {
        `when`(sys.findById(eq("admin.settings") ?: "admin.settings")).thenReturn(
            Optional.of(SystemSetting("admin.settings", om.writeValueAsString(SettingsStore(planPrices = mapOf("MONTHLY" to 9999)))))
        )
        val dto = svc.settings("actor")
        assertEquals(9999, dto.plans.first { it.id == "MONTHLY" }.priceFen)
    }

    @Test
    fun saveSettings_writeGate_throwsIfNoWritePrivilege() {
        doThrow(BizException(ParentErr.FORBIDDEN, "x", Audience.PARENT)).`when`(perm).require(anyString(), anyString(), anyString())
        val ex = assertThrows(BizException::class.java) { svc.saveSettings("actor", AdminSettingsSaveDto()) }
        assertEquals(ParentErr.FORBIDDEN, ex.code)
    }

    @Test
    fun saveSettings_mockPayRejectedOnNonDevProfile() {
        `when`(env.activeProfiles).thenReturn(arrayOf("prod"))
        val ex = assertThrows(BizException::class.java) {
            svc.saveSettings("actor", AdminSettingsSaveDto(mockPay = true))
        }
        assertEquals(ParentErr.PARAM_ERROR, ex.code)
    }

    @Test
    fun saveSettings_mockPayAllowedOnDevProfile_andMergesAndUppercasesPlanKeys() {
        // 让 save 后回读能反映最新值：复用同一 SystemSetting 实例，save 时写回其 valueJson
        val saved = SystemSetting("admin.settings", "")
        `when`(sys.findById(anyString())).thenReturn(Optional.of(saved))
        `when`(sys.save(any(SystemSetting::class.java))).thenAnswer { inv ->
            val s = inv.arguments[0] as SystemSetting
            saved.valueJson = s.valueJson
            saved
        }
        `when`(env.activeProfiles).thenReturn(arrayOf("local"))
        val req = AdminSettingsSaveDto(brand = "PadX", mockPay = true, plans = listOf(AdminPlanPriceDto("monthly", 1234)))
        val dto = svc.saveSettings("actor", req)
        assertEquals("PadX", dto.brand)
        assertEquals(true, dto.mockPay)
        // planPrices 键被统一大写：monthly -> MONTHLY
        assertEquals(1234, dto.plans.first { it.id == "MONTHLY" }.priceFen)
        verify(sys).save(any(SystemSetting::class.java) ?: saved)
        verify(audit).record(
            eq("actor") ?: "actor", any(), eq(SETTINGS_WRITE) ?: SETTINGS_WRITE,
            any(), any(), any(), any() ?: "SUCCESS", any()
        )
    }

    @Test
    fun saveSettings_partialUpdate_keepsUnsentFieldsAtDefaults() {
        val saved = SystemSetting("admin.settings", "")
        `when`(sys.findById(anyString())).thenReturn(Optional.of(saved))
        `when`(sys.save(any(SystemSetting::class.java))).thenAnswer { inv ->
            val s = inv.arguments[0] as SystemSetting
            saved.valueJson = s.valueJson
            saved
        }
        `when`(env.activeProfiles).thenReturn(arrayOf("local"))
        val dto = svc.saveSettings("actor", AdminSettingsSaveDto(trialDays = 45))
        assertEquals(45, dto.trialDays)
        assertEquals("PadGuard", dto.brand) // 未传字段保持默认，不可被写成 null
        assertEquals(false, dto.mockPay)
    }
}
