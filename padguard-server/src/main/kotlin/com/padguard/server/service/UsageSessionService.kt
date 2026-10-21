package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.common.SERVER_ZONE_ID
import com.padguard.server.domain.DeviceSetting
import com.padguard.server.domain.UsageSession
import com.padguard.server.dto.UsageSessionDto
import com.padguard.server.repository.DeviceSettingRepository
import com.padguard.server.repository.UsageSessionRepository
import com.padguard.server.service.UsageDelayService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * 平板使用计时会话服务（三端时间一致的权威来源）。
 *
 * 计时由服务端统一锚定：孩子端确认"计时开始"时记录 `startEpoch`，三端据此派生剩余时长，
 * 因此家长端 / 孩子端 / 服务端显示同一份数字，不再各自按本地时钟计算。
 *
 * **延时不再在本服务内直接生效**：孩子端的"申请延时"只是发起 [UsageDelayService] 工单，
 * 必须经家长审批（[UsageDelayService.approve]）后才会累加额度。本服务只负责"计时与剩余"，
 * 详见 [com.padguard.server.domain.UsageSession] 的字段说明与一致性设计。
 */
@Service
class UsageSessionService(
    private val usageSessionRepository: UsageSessionRepository,
    private val deviceSettingRepository: DeviceSettingRepository,
    private val deviceService: DeviceService,
    private val usageDelayService: UsageDelayService
) {
    private val log = LoggerFactory.getLogger(UsageSessionService::class.java)
    private val zoneId = SERVER_ZONE_ID

    private fun dayKey(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate().toString()

    private fun isWeekend(epochMillis: Long): Boolean {
        val dow = Instant.ofEpochMilli(epochMillis).atZone(zoneId).dayOfWeek
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
    }

    /**
     * 孩子端确认"计时开始"。
     *
     * 幂等：今日已确认过则沿用原起点，避免孩子重启应用/息屏重新解锁后把时钟重置，
     * 否则"确认一次 = 额度刷新一次"会让管控形同虚设。
     */
    @Transactional
    fun start(deviceId: String): UsageSessionDto {
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        val existing = usageSessionRepository.findByDeviceId(deviceId)
        if (existing != null && existing.dayKey == today && existing.startEpoch != null) {
            return toDto(existing, now, deviceId)
        }
        val quota = resolveQuota(deviceId, now)
        val entity = (existing ?: UsageSession(deviceId = deviceId)).apply {
            this.dayKey = today
            this.startEpoch = now
            this.quotaMinutes = quota
            this.extendedMinutes = 0
            this.lastDelayEpoch = null
            // 新的一天重置护眼休息累计与去重集合（休息累计按日清零，避免跨日延续）
            this.restAccumulatedMs = 0L
            this.restIds = ""
            if (this.createdAt == 0L) this.createdAt = now
            this.updatedAt = now
        }
        usageSessionRepository.save(entity)
        log.info("usage session started device={} quota={} day={}", deviceId, quota, today)
        return toDto(entity, now, deviceId)
    }

    /**
     * 读取当前会话与剩余时长。
     *
     * 返回的 [UsageSessionDto.serverNow] 是服务端墙钟，三端一律用它减去 [UsageSessionDto.startEpoch]
     * 计算已用时长，避免各端本地时钟漂移导致数字对不上。
     */
    fun get(deviceId: String): UsageSessionDto {
        val now = System.currentTimeMillis()
        val entity = usageSessionRepository.findByDeviceId(deviceId)
        if (entity == null) {
            return UsageSessionDto(deviceId = deviceId, serverNow = now, active = false, dayKey = dayKey(now))
        }
        return toDto(entity, now, deviceId)
    }

    /**
     * 当日进行中的会话若存在，返回服务端权威「已用分钟」。
     *
     * 公式：`已用 = 墙钟流逝(now - startEpoch)`，**封顶到当日总额度**（额度耗尽即视为已用满）。
     * 这是「今日已用」头条数字在三端的**唯一权威口径**：孩子端首页、家长端看板、服务端锁机判定
     * （[toDto] 的 remaining 派生、[com.padguard.child.usagesession.UsageSessionManager.isExpired]）
     * 全部以此为准。
     *
     * 之所以需要这个方法：家长端统计页早期用「孩子端上报的累计前台用量」作头条
     * （[com.padguard.server.service.StatisticsService.sumMinutes]），它与本值之间隔着
     * 「息屏 / 锁屏 / 切后台」的空闲时长，云端真实使用场景下会差出数分钟，表现为
     * 「家长端与孩子端时间对不上」。统一走本方法即可从定义上消除该差异。
     */
    fun usedMinutesIfActive(deviceId: String): Int? {
        val now = System.currentTimeMillis()
        val entity = usageSessionRepository.findByDeviceId(deviceId) ?: return null
        val startEpoch = entity.startEpoch ?: return null
        if (entity.dayKey != dayKey(now)) return null
        val totalMinutes = resolveQuota(deviceId, now) + entity.extendedMinutes
        // 已用 = 墙钟流逝 - 护眼强制休息时长（休息期间设备被锁定，不属于使用，须从每日总时长扣除）
        val elapsedMin = ((now - startEpoch - entity.restAccumulatedMs) / 60_000L).coerceAtLeast(0L).toInt()
        return minOf(elapsedMin, totalMinutes)
    }

    /**
     * 批量取多台设备的「今日已用分钟」——[usedMinutesIfActive] 的列表版。
     *
     * 存在理由：后台设备列表要给每台设备显示今日用量，逐台调 [usedMinutesIfActive]
     * 会退化成 N 次数据库往返（N+1）。这里把会话表的一次 `findAllById` 提出来，
     * 其余判定（跨日重置、护眼休息扣减、封顶到总额度）与单点版本**逐字保持一致**，
     * 保证"列表里的数字"和"点进去看到的数字"永远同源，不会各算一套。
     *
     * 返回值**只包含有真实数据的设备**：今日未开始计时的设备不在 map 里，
     * 调用方据此显示"—"，而不是拿 0 冒充"用了 0 分钟"。
     */
    fun usedMinutesBatch(deviceIds: Collection<String>): Map<String, Int> {
        if (deviceIds.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        val sessions = usageSessionRepository.findAllById(deviceIds).associateBy { it.deviceId }
        val out = HashMap<String, Int>(deviceIds.size)
        for (id in deviceIds) {
            val s = sessions[id] ?: continue
            val startEpoch = s.startEpoch ?: continue
            if (s.dayKey != today) continue
            val totalMinutes = resolveQuota(id, now) + s.extendedMinutes
            val elapsedMin = ((now - startEpoch - s.restAccumulatedMs) / 60_000L).coerceAtLeast(0L).toInt()
            out[id] = minOf(elapsedMin, totalMinutes)
        }
        return out
    }

    /**
     * 上报一次护眼强制休息的实际时长（毫秒），从当日总时长中扣除。
     *
     * 三端一致性的关键落地：休息由孩子端本地 [com.padguard.core.engine.enforcer.EyeCareEnforcer] 触发，
     * 服务端原本只按 `now - startEpoch` 纯墙钟计时，必然把休息算进额度。这里由孩子端在休息结束后
     * 上报实际休息毫秒，服务端累加进 [com.padguard.server.domain.UsageSession.restAccumulatedMs]，
     * 并在 [usedMinutesIfActive] 与 [toDto] 中统一扣减 —— 孩子端倒计时（基线来自 [toDto.remainingMs]）、
     * 家长端看板（[usedMinutesIfActive]）、服务端锁机判定（[toDto] 剩余派生）三端同源排除，
     * 杜绝"休息被计入每日总时长额度"。
     *
     * 幂等：同一 restId 只计一次（[com.padguard.server.domain.UsageSession.restIds] 保留最近若干条去重），
     * 防止网络重试 / 乱序导致的重复扣减；单次休息毫秒钳制到 [0, 当日总额度] 防异常值。
     */
    @Transactional
    fun reportRest(deviceId: String, restId: String, restMs: Long) {
        if (deviceId.isBlank() || restId.isBlank() || restMs <= 0L) {
            log.warn("usage rest report ignored: invalid args device={} restId={} restMs={}", deviceId, restId, restMs)
            return
        }
        val now = System.currentTimeMillis()
        val entity = usageSessionRepository.findByDeviceId(deviceId) ?: return
        // 非今日会话：该次休息属于已过期会话，不再计入当前额度
        if (entity.dayKey != dayKey(now)) return
        // 幂等去重：restId 已记录则跳过（应对重试 / 乱序）。
        // 用有序 List 而非 Set：takeLast(16) 依赖写入顺序（末尾即最近），且保证"最近16条"语义正确。
        val recorded = entity.restIds.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (restId in recorded) return

        val totalMs = (resolveQuota(deviceId, now) + entity.extendedMinutes) * 60_000L
        val capped = restMs.coerceIn(0L, totalMs)
        entity.restAccumulatedMs = (entity.restAccumulatedMs + capped).coerceAtLeast(0L)
        // 维护去重集合（保留最近 16 条，防止字段无限增长）
        entity.restIds = (recorded + restId).takeLast(16).joinToString(",")
        entity.updatedAt = now
        usageSessionRepository.save(entity)
        log.info("usage rest reported device={} restId={} restMs={} totalRestMs={}", deviceId, restId, capped, entity.restAccumulatedMs)
    }

    /**
     * 孩子端申请延时（仅发起审批工单，不立即延长）。
     *
     * 这是 [UsageDelayService.request] 的会话侧入口：只负责把"申请"转交审批流，
     * 真正的额度延长发生在家长 `approve` 之后。返回刷新后的会话 DTO（含 [pendingDelayMinutes]），
     * 孩子端据此展示"已提交，等待家长审核"。
     *
     * 之所以不直接在此累加额度：旧实现正是在这一步直接生效，导致"孩子自己批准自己"、
     * 家长端无法同步——这是本次修复的核心缺陷。
     */
    @Transactional
    fun requestDelay(deviceId: String, minutes: Int): UsageSessionDto {
        usageDelayService.request(deviceId, minutes)
        return get(deviceId)
    }

    /**
     * 解析当日额度：按工作日/周末取家长在「时间管控」页设置的值。
     *
     * NULL 回落统一引用 [DeviceSetting] 的实体缺省常量（唯一事实源），
     * 不再本地硬编码 120/180 —— 早期实现与接口回落(60)/下发回落(60)/实体默认(120/180)
     * 四处漂移，存量 NULL 行命中时三端数字对不上。
     */
    private fun resolveQuota(deviceId: String, now: Long): Int {
        val ds = deviceSettingRepository.findById(deviceId).orElse(null)
            ?: return DeviceSetting.DEFAULT_WEEKDAY_LIMIT_MINUTES
        return if (isWeekend(now)) {
            ds.weekendLimitMinutes ?: DeviceSetting.DEFAULT_WEEKEND_LIMIT_MINUTES
        } else {
            ds.weekdayLimitMinutes ?: DeviceSetting.DEFAULT_WEEKDAY_LIMIT_MINUTES
        }
    }

    private fun toDto(s: UsageSession, now: Long, deviceId: String): UsageSessionDto {
        val started = s.startEpoch != null && s.dayKey == dayKey(now)
        val start = s.startEpoch ?: now
        // 进行中的会话：额度以「当前 DeviceSetting 的家长设置」为准，而非 start 时锁定的值。
        // 这样家长端修改「时间管控」额度后能立即（下次刷新）在三端生效，而不是冻结到次日起，
        // 避免出现"明明改了额度、孩子端当天却照旧"的"设置不生效"问题。
        val liveQuota = if (started) resolveQuota(deviceId, now) else s.quotaMinutes
        val totalMinutes = liveQuota + s.extendedMinutes
        // 剩余 = 总额度 - 墙钟流逝 - 护眼强制休息时长（休息不计入每日总时长）
        val remainingMs = if (started) maxOf(0L, totalMinutes * 60_000L - (now - start) - s.restAccumulatedMs) else 0L
        return UsageSessionDto(
            deviceId = s.deviceId,
            serverNow = now,
            active = started,
            dayKey = s.dayKey,
            startEpoch = s.startEpoch,
            quotaMinutes = liveQuota,
            extendedMinutes = s.extendedMinutes,
            remainingMinutes = (remainingMs / 60_000L).toInt(),
            remainingMs = remainingMs,
            countdownThresholdMs = UsageSession.COUNTDOWN_THRESHOLD_MS,
            delayOptions = UsageSession.DELAY_OPTIONS,
            maxDailyExtendMinutes = UsageSession.MAX_DAILY_EXTEND_MINUTES,
            lastDelayEpoch = s.lastDelayEpoch,
            pendingDelayMinutes = usageDelayService.pendingDelayMinutes(s.deviceId)
        )
    }
}
