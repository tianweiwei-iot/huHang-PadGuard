package com.padguard.server.service

import com.padguard.server.common.Audience
import com.padguard.server.common.BizException
import com.padguard.server.domain.AccountDeletionRequest
import com.padguard.server.domain.Appeal
import com.padguard.server.domain.Device
import com.padguard.server.domain.GuardianConsent
import com.padguard.server.domain.LocationTrack
import com.padguard.server.domain.Screenshot
import com.padguard.server.domain.UsageLog
import com.padguard.server.domain.User
import com.padguard.server.dto.Under14GuardianConsentRequest
import com.padguard.server.repository.AccountDeletionRepository
import com.padguard.server.repository.AppealRepository
import com.padguard.server.repository.DeviceRepository
import com.padguard.server.repository.GuardianConsentRepository
import com.padguard.server.repository.ErasureOutcome
import com.padguard.server.repository.LocationTrackRepository
import com.padguard.server.repository.ParentErasureDao
import com.padguard.server.repository.ScreenshotRepository
import com.padguard.server.repository.UserRepository
import com.padguard.server.repository.UsageLogRepository
import com.padguard.server.security.DeviceOwnershipGuard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.ArgumentMatchers.startsWith
import org.mockito.Mockito.*
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.PageRequest
import java.time.LocalDate
import java.util.*

class ComplianceServiceTest {
    private val deviceRepository = mock(DeviceRepository::class.java)
    private val ownershipGuard = mock(DeviceOwnershipGuard::class.java)
    private val auditService = mock(AuditService::class.java)
    private val purgeService = mock(DeviceDataPurgeService::class.java)
    private val appealRepository = mock(AppealRepository::class.java)
    private val deletionRepository = mock(AccountDeletionRepository::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val policyService = mock(PolicyExtensionService::class.java)
    private val usageLogRepository = mock(UsageLogRepository::class.java)
    private val locationTrackRepository = mock(LocationTrackRepository::class.java)
    private val screenshotRepository = mock(ScreenshotRepository::class.java)
    private val guardianConsentRepository = mock(GuardianConsentRepository::class.java)
    private val erasureDao = mock(ParentErasureDao::class.java)

    private fun svcWithErasure() = ComplianceService(
        deviceRepository, ownershipGuard, auditService, purgeService, appealRepository, deletionRepository,
        userRepository, 172_800_000L, policyService, usageLogRepository, locationTrackRepository,
        screenshotRepository, guardianConsentRepository, erasureDao
    )

    private fun svc() = ComplianceService(
        deviceRepository, ownershipGuard, auditService, purgeService, appealRepository, deletionRepository, userRepository
    )

    private fun svcWithDetails() = ComplianceService(
        deviceRepository, ownershipGuard, auditService, purgeService, appealRepository, deletionRepository,
        userRepository, 172_800_000L, policyService, usageLogRepository, locationTrackRepository,
        screenshotRepository, guardianConsentRepository
    )

    private fun dev(id: String) = Device(id = id, userId = "u1", name = "t", consentRevoked = false)

    @Test
    fun withdrawConsent_setsFlagAndAudits() {
        val d1 = dev("d1"); val d2 = dev("d2")
        `when`(deviceRepository.findByUserId("u1")).thenReturn(listOf(d1, d2))

        val n = svc().withdrawConsent("u1", null)
        assertEquals(2, n)
        assertTrue(d1.consentRevoked == true)
        assertTrue(d2.consentRevoked == true)
        verify(auditService).record(eq("u1"), eq("d1"), any() ?: "", any(), any(), any(), any() ?: "", any())
        verify(auditService).record(eq("u1"), eq("d2"), any() ?: "", any(), any(), any(), any() ?: "", any())
    }

    @Test
    fun withdrawConsent_rebuildsPolicySoChildStopsLocally() {
        val d1 = dev("d1")
        `when`(deviceRepository.findByUserId("u1")).thenReturn(listOf(d1))

        svcWithDetails().withdrawConsent("u1", null)

        verify(policyService).rebuildPolicy("d1")
    }

    @Test
    fun reinstateConsent_rebuildsPolicySoChildResumes() {
        val d1 = dev("d1").apply { consentRevoked = true }
        `when`(deviceRepository.findByUserId("u1")).thenReturn(listOf(d1))

        svcWithDetails().reinstateConsent("u1", null)

        verify(policyService).rebuildPolicy("d1")
    }

    @Test
    fun withdrawConsent_unknownDevice_throws() {
        `when`(deviceRepository.findByUserId("u1")).thenReturn(listOf(dev("d1")))
        assertThrows(BizException::class.java) { svc().withdrawConsent("u1", listOf("dx")) }
    }

    @Test
    fun exportData_containsAccountAndDevices() {
        `when`(deviceRepository.findByUserId("u1")).thenReturn(listOf(dev("d1")))
        `when`(userRepository.findById("u1")).thenReturn(Optional.of(User(id = "u1", phone = "13800001111", status = "active")))
        `when`(appealRepository.findByUserIdOrderByCreatedAtDesc(anyString(), any(Pageable::class.java) ?: PageRequest.of(0, 100))).thenReturn(emptyList())
        `when`(deletionRepository.findByUserId("u1")).thenReturn(emptyList())

        val map = svc().exportData("u1")
        assertNotNull(map["account"])
        @Suppress("UNCHECKED_CAST")
        val account = map["account"] as Map<String, Any?>
        assertEquals("138****1111", account["phone"])
        @Suppress("UNCHECKED_CAST")
        val devices = map["devices"] as List<Map<String, Any?>>
        assertEquals(1, devices.size)
    }

    @Test
    fun exportData_includesUsageLocationAndScreenshotDetails() {
        `when`(deviceRepository.findByUserId("u1")).thenReturn(listOf(dev("d1")))
        `when`(userRepository.findById("u1")).thenReturn(Optional.of(User(id = "u1", phone = "13800001111", status = "active")))
        `when`(appealRepository.findByUserIdOrderByCreatedAtDesc(
            anyString() ?: "u1", any(Pageable::class.java) ?: PageRequest.of(0, 100)
        )).thenReturn(emptyList())
        `when`(deletionRepository.findByUserId("u1")).thenReturn(emptyList())
        `when`(usageLogRepository.findByDeviceIdOrderByReceivedAtDesc(
            eq("d1") ?: "d1", any(Pageable::class.java) ?: PageRequest.of(0, 100)
        )).thenReturn(listOf(UsageLog(id = "u1-log", deviceId = "d1", logId = "log-1", type = "USAGE_TOTAL")))
        `when`(locationTrackRepository.findByDeviceIdOrderByTsDesc(
            eq("d1") ?: "d1", any(Pageable::class.java) ?: PageRequest.of(0, 100)
        )).thenReturn(listOf(LocationTrack(id = "loc-1", deviceId = "d1", lat = 31.2, lng = 121.5)))
        `when`(screenshotRepository.findByDeviceIdOrderByCapturedAtDesc(
            eq("d1") ?: "d1", any(Pageable::class.java) ?: PageRequest.of(0, 100)
        )).thenReturn(listOf(Screenshot(id = "shot-row", deviceId = "d1", shotId = "shot-1")))

        val map = svcWithDetails().exportData("u1")

        @Suppress("UNCHECKED_CAST")
        val usage = map["usageLogs"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val locations = map["locationTracks"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val shots = map["screenshots"] as List<Map<String, Any?>>
        assertEquals("log-1", usage.single()["logId"])
        assertEquals(31.2, locations.single()["lat"] as Double)
        assertEquals("shot-1", shots.single()["shotId"])
        assertEquals(ComplianceService.EXPORT_DETAIL_LIMIT, map["detailLimitPerDevice"])
    }

    @Test
    fun requestDeletion_createsTicketAndIsIdempotent() {
        `when`(deletionRepository.findByUserId("u1")).thenReturn(emptyList())
        val created = svc().requestAccountDeletion("u1", "want out")
        assertTrue(created.ticket.isNotBlank())
        assertEquals("PENDING", created.status)

        `when`(deletionRepository.findByUserId("u1")).thenReturn(
            listOf(AccountDeletionRequest(id = "x", userId = "u1", ticket = created.ticket, status = "PENDING", requestedAt = 1, executeAt = 2))
        )
        val again = svc().requestAccountDeletion("u1", "again")
        assertEquals(created.ticket, again.ticket)
    }

    @Test
    fun confirmDeletion_purgesAndAnonymizes() {
        val req = AccountDeletionRequest(id = "adr1", userId = "u1", ticket = "T1", status = "PENDING", requestedAt = 1, executeAt = 2)
        `when`(deletionRepository.findByUserIdAndTicket("u1", "T1")).thenReturn(req)
        `when`(deviceRepository.findByUserId("u1")).thenReturn(listOf(dev("d1")))
        `when`(purgeService.purge("d1", "ACCOUNT_ERASE")).thenReturn(mapOf("screenshots" to 1))
        `when`(erasureDao.findDeviceIds(eq("u1") ?: "u1")).thenReturn(listOf("d1"))
        `when`(erasureDao.erase(eq("u1") ?: "u1", anyString() ?: "", anyList() ?: emptyList()))
            .thenReturn(ErasureOutcome(emptyMap(), listOf("d1")))
        val user = User(id = "u1", phone = "13800001111", passwordHash = "h", status = "active", tokenVersion = 0)
        `when`(userRepository.findById("u1")).thenReturn(Optional.of(user))

        svcWithErasure().confirmAccountDeletion("u1", "T1")

        assertEquals("EXECUTED", req.status)
        assertTrue(user.phone!!.startsWith("deleted_"))
        assertEquals("deleted", user.status)
        assertEquals(1, user.tokenVersion) // 令牌吊销
        // 假注销根因：必须走集中式清理链，不能只匿名账号留下 alerts/工单/订单归属。
        verify(erasureDao).erase(eq("u1") ?: "u1", startsWith("erased_"), eq(listOf("d1")) ?: emptyList())
        verify(deviceRepository).deleteAll(any())
        verify(auditService).record(eq("u1"), any(), any() ?: "", any(), any(), any(), any() ?: "", any())
    }

    @Test
    fun confirmDeletion_beforeCooldown_throwsWithoutErasingAnything() {
        val future = System.currentTimeMillis() + 3_600_000L
        val req = AccountDeletionRequest(id = "adr2", userId = "u1", ticket = "T2", status = "PENDING", requestedAt = 1, executeAt = future)
        `when`(deletionRepository.findByUserIdAndTicket("u1", "T2")).thenReturn(req)

        val ex = assertThrows(BizException::class.java) { svcWithErasure().confirmAccountDeletion("u1", "T2") }

        assertTrue(ex.message!!.contains("冷静期"))
        assertEquals("PENDING", req.status) // 拒绝后仍可反悔，未进入不可逆流程
        verify(erasureDao, never()).erase(anyString() ?: "", anyString() ?: "", anyList() ?: emptyList())
    }

    @Test
    fun confirmDeletion_purgeFailureRollsBackWithoutFakeSuccess() {
        val req = AccountDeletionRequest(id = "adr3", userId = "u1", ticket = "T3", status = "PENDING", requestedAt = 1, executeAt = 2)
        `when`(deletionRepository.findByUserIdAndTicket("u1", "T3")).thenReturn(req)
        `when`(erasureDao.findDeviceIds(eq("u1") ?: "u1")).thenReturn(listOf("d1"))
        `when`(purgeService.purge("d1", "ACCOUNT_ERASE")).thenThrow(RuntimeException("disk down"))

        assertThrows(BizException::class.java) { svcWithErasure().confirmAccountDeletion("u1", "T3") }

        assertEquals("PENDING", req.status)
        verify(erasureDao, never()).erase(anyString() ?: "", anyString() ?: "", anyList() ?: emptyList())
        verify(userRepository, never()).save(any(User::class.java))
    }

    @Test
    fun confirmDeletion_withoutErasureDao_failsClosed() {
        val req = AccountDeletionRequest(id = "adr4", userId = "u1", ticket = "T4", status = "PENDING", requestedAt = 1, executeAt = 2)
        `when`(deletionRepository.findByUserIdAndTicket("u1", "T4")).thenReturn(req)

        assertThrows(BizException::class.java) { svc().confirmAccountDeletion("u1", "T4") }
        assertEquals("PENDING", req.status)
    }

    @Test
    fun confirmDeletion_unknownTicket_throws() {
        `when`(deletionRepository.findByUserIdAndTicket("u1", "bad")).thenReturn(null)
        assertThrows(BizException::class.java) { svc().confirmAccountDeletion("u1", "bad") }
    }

    @Test
    fun submitAppeal_persistsAndAudits() {
        `when`(ownershipGuard.requireOwned("u1", "d1")).thenReturn(dev("d1"))
        val id = svc().submitAppeal("u1", "d1", "误锁", "请解锁", "p@x.com")
        assertTrue(id.isNotBlank())
        verify(appealRepository).save(any(Appeal::class.java))
        verify(auditService).record(eq("u1"), eq("d1"), any() ?: "", any(), any(), any(), any() ?: "", any())
    }

    @Test
    fun under14GuardianConsent_persistsForYoungerChild() {
        val birth = LocalDate.now().minusYears(10).toString()
        `when`(ownershipGuard.requireOwned("u1", "d1")).thenReturn(dev("d1"))
        `when`(guardianConsentRepository.findByUserIdAndDeviceIdOrderByCreatedAtDesc("u1", "d1"))
            .thenReturn(emptyList())

        val dto = svcWithDetails().submitUnder14GuardianConsent(
            "u1", Under14GuardianConsentRequest("d1", birth, true, "2026-11-23-v1")
        )

        val captor = ArgumentCaptor.forClass(GuardianConsent::class.java)
        verify(guardianConsentRepository).save(captor.capture())
        assertEquals(birth, captor.value.childBirthDate)
        assertTrue(captor.value.consent)
        assertTrue(dto.consented)
        verify(auditService).record(
            eq("u1"), eq("d1"), eq(AuditAction.GUARDIAN_CONSENT_UNDER14) ?: AuditAction.GUARDIAN_CONSENT_UNDER14,
            any(), any(), any(), any() ?: "", any()
        )
    }

    @Test
    fun under14GuardianConsent_rejectsChildAged14OrOlder() {
        val birth = LocalDate.now().minusYears(14).toString()
        assertThrows(BizException::class.java) {
            svcWithDetails().submitUnder14GuardianConsent(
                "u1", Under14GuardianConsentRequest("d1", birth, true, "v1")
            )
        }
        verify(guardianConsentRepository, never()).save(any(GuardianConsent::class.java) ?: GuardianConsent())
    }

    @Test
    fun under14GuardianConsent_isIdempotentForSameBirthAndVersion() {
        val birth = LocalDate.now().minusYears(10).toString()
        `when`(guardianConsentRepository.findByUserIdAndDeviceIdOrderByCreatedAtDesc("u1", "d1")).thenReturn(
            listOf(GuardianConsent(
                id = "gc1", userId = "u1", deviceId = "d1", childBirthDate = birth,
                consent = true, consentTextVersion = "v1", createdAt = 123L
            ))
        )

        val dto = svcWithDetails().submitUnder14GuardianConsent(
            "u1", Under14GuardianConsentRequest("d1", birth, true, "v1")
        )

        assertEquals(123L, dto.decidedAt)
        verify(guardianConsentRepository, never()).save(any(GuardianConsent::class.java) ?: GuardianConsent())
    }
}
