package com.padguard.server.service

import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.UsageSession
import com.padguard.server.dto.UsageSessionDto
import com.padguard.server.repository.DeviceSettingRepository
import com.padguard.server.repository.UsageSessionRepository
import com.padguard.server.service.UsageDelayService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.time.Instant
import java.util.Optional

/**
 * [UsageSessionService] 护眼休息扣除逻辑的单元测试。
 *
 * 需求落点（需求 B）：休息时长由孩子端结束后上报 [reportRest]，服务端累加进
 * [UsageSession.restAccumulatedMs]，并在 [UsageSessionService.usedMinutesIfActive]
 * 与 [UsageSessionService.toDto]（剩余派生）中统一扣减 —— 使孩子端倒计时 / 家长端看板 /
 * 服务端锁机判定三端同源排除休息，杜绝"休息被计入每日总时长额度"。
 *
 * 仓储层用内存假实现（[FakeSessionRepo]），避免 Mockito 默认 mock 丢弃"保存后实体被修改"的副作用；
 * 协作服务（DeviceSettingRepository / DeviceService / UsageDelayService）用 Mockito 桩。
 */
@DisplayName("UsageSessionService —— 护眼休息不计入每日总时长")
class UsageSessionServiceTest {

    private lateinit var sessionRepo: FakeSessionRepo
    private lateinit var deviceSettingRepo: DeviceSettingRepository
    private lateinit var deviceService: DeviceService
    private lateinit var delayService: UsageDelayService
    private lateinit var service: UsageSessionService

    private val today: String = Instant.now().atZone(SERVER_ZONE_ID).toLocalDate().toString()

    @BeforeEach
    fun setUp() {
        sessionRepo = FakeSessionRepo()
        deviceSettingRepo = mock(DeviceSettingRepository::class.java)
        deviceService = mock(DeviceService::class.java)
        delayService = mock(UsageDelayService::class.java)
        // 未配置 DeviceSetting → 回落默认工作日额度 120min
        `when`(deviceSettingRepo.findById(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty())
        `when`(delayService.pendingDelayMinutes(org.mockito.ArgumentMatchers.anyString())).thenReturn(0)

        // 今日已开始计时：起点 30 分钟前，额度 120min
        sessionRepo.table["device-1"] = UsageSession(
            deviceId = "device-1",
            dayKey = today,
            startEpoch = System.currentTimeMillis() - 30 * 60_000L,
            quotaMinutes = 120,
            extendedMinutes = 0,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        service = UsageSessionService(sessionRepo, deviceSettingRepo, deviceService, delayService)
    }

    @Test
    @DisplayName("reportRest 累加 restAccumulatedMs 并从剩余时长扣除")
    fun `reportRest deducts rest from remaining`() {
        service.reportRest("device-1", "r1", 10 * 60_000L)

        val entity = sessionRepo.table["device-1"]!!
        assertThat(entity.restAccumulatedMs).isEqualTo(10 * 60_000L)
        assertThat(entity.restIds).isEqualTo("r1")

        // 剩余 = 总额度(120) - 墙钟流逝(≈30) - 休息(10) = 80min
        val dto = service.get("device-1")
        assertThat(dto.remainingMinutes).isEqualTo(80)
    }

    @Test
    @DisplayName("usedMinutesIfActive 排除护眼休息时长")
    fun `usedMinutesIfActive excludes rest`() {
        assertThat(service.usedMinutesIfActive("device-1")).isEqualTo(30)
        service.reportRest("device-1", "r1", 10 * 60_000L)
        assertThat(service.usedMinutesIfActive("device-1")).isEqualTo(20)
    }

    @Test
    @DisplayName("同一 restId 幂等：重复上报只计一次")
    fun `reportRest is idempotent per restId`() {
        service.reportRest("device-1", "r1", 10 * 60_000L)
        service.reportRest("device-1", "r1", 10 * 60_000L) // 网络重试
        val entity = sessionRepo.table["device-1"]!!
        assertThat(entity.restAccumulatedMs).isEqualTo(10 * 60_000L)
        assertThat(entity.restIds).isEqualTo("r1")
    }

    @Test
    @DisplayName("restMs 钳制到当日总额度上限，防止异常值放大扣除")
    fun `reportRest clamps to total quota`() {
        service.reportRest("device-1", "r1", 200 * 60_000L) // 远超 120min
        val entity = sessionRepo.table["device-1"]!!
        assertThat(entity.restAccumulatedMs).isEqualTo(120 * 60_000L)
    }

    @Test
    @DisplayName("跨日会话的休息上报被忽略（不计入当前额度）")
    fun `reportRest ignored for non-today session`() {
        val old = sessionRepo.table["device-1"]!!
        old.dayKey = Instant.now().atZone(SERVER_ZONE_ID).minusDays(1).toLocalDate().toString()
        service.reportRest("device-1", "r1", 10 * 60_000L)
        assertThat(sessionRepo.table["device-1"]!!.restAccumulatedMs).isEqualTo(0L)
    }

    @Test
    @DisplayName("非法入参（空 deviceId / 空 restId / 非正 restMs）被静默忽略")
    fun `reportRest ignores invalid args`() {
        service.reportRest("", "r1", 10_000L)
        service.reportRest("device-1", "", 10_000L)
        service.reportRest("device-1", "r1", 0L)
        service.reportRest("device-1", "r1", -5_000L)
        assertThat(sessionRepo.table["device-1"]!!.restAccumulatedMs).isEqualTo(0L)
    }

    @Test
    @DisplayName("设备不存在时不抛异常、不落库")
    fun `reportRest no-op for unknown device`() {
        service.reportRest("ghost", "r1", 10_000L)
        assertThat(sessionRepo.table).doesNotContainKey("ghost")
    }

    @Test
    @DisplayName("start 跨日复用实体时重置休息累计与去重集合")
    fun `start resets rest accumulators on new day`() {
        // 先上报一次休息，使 restAccumulatedMs / restIds 有值
        service.reportRest("device-1", "r1", 10 * 60_000L)
        assertThat(sessionRepo.table["device-1"]!!.restAccumulatedMs).isEqualTo(10 * 60_000L)

        // 模拟跨日：把当前会话 dayKey 改成昨天，再 start（应视为新的一天初始化）
        val old = sessionRepo.table["device-1"]!!
        old.dayKey = Instant.now().atZone(SERVER_ZONE_ID).minusDays(1).toLocalDate().toString()
        service.start("device-1")

        val fresh = sessionRepo.table["device-1"]!!
        assertThat(fresh.restAccumulatedMs).isEqualTo(0L)
        assertThat(fresh.restIds).isEqualTo("")
    }

    @Test
    @DisplayName("去重集合有界：连续上报超过16条时仅保留最近16条，防止 restIds 字段无限增长")
    fun `reportRest bounds dedupe set to last 16`() {
        for (i in 1..20) {
            service.reportRest("device-1", "r$i", 1_000L)
        }
        val entity = sessionRepo.table["device-1"]!!
        val ids = entity.restIds.split(',').filter { it.isNotBlank() }
        assertThat(ids).hasSize(16)            // takeLast(16) 截断
        assertThat(ids.last()).isEqualTo("r20") // 保留最近写入
        // 每条不同 restId 均为首次计入，累计 = 20 * 1000ms
        assertThat(entity.restAccumulatedMs).isEqualTo(20 * 1_000L)
    }
}

// ============ 内存假仓储 ============

class FakeSessionRepo : FakeJpaRepository<UsageSession, String>(), UsageSessionRepository {
    override val table = LinkedHashMap<String, UsageSession>()
    override fun idOf(entity: UsageSession): String = entity.deviceId
    override fun findByDeviceId(deviceId: String): UsageSession? = table[deviceId]

    override fun deleteByDeviceId(deviceId: String): Int {
        val before = table.size
        table.remove(deviceId)
        return before - table.size
    }
}
