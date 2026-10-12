package com.padguard.server.service

import com.padguard.server.common.BizException
import com.padguard.server.common.ParentErr
import com.padguard.server.domain.UsageSession
import com.padguard.server.dto.UsageSessionDto
import com.padguard.server.repository.DeviceSettingRepository
import com.padguard.server.repository.UsageSessionRepository
import com.padguard.server.ws.WebSocketPushService
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
 * 详见 [com.padguard.server.domain.UsageSession] 的字段说明与一致性设计。
 */
@Service
class UsageSessionService(
    private val usageSessionRepository: UsageSessionRepository,
    private val deviceSettingRepository: DeviceSettingRepository,
    private val deviceService: DeviceService,
    private val webSocketPush: WebSocketPushService
) {
    private val log = LoggerFactory.getLogger(UsageSessionService::class.java)
    private val zoneId = ZoneId.of("Asia/Shanghai")

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
     * 之所以需要这个方法：家长端统计页此前用「孩子端上报的累计前台用量」作头条
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
        val elapsedMin = ((now - startEpoch) / 60_000L).coerceAtLeast(0L).toInt()
        return minOf(elapsedMin, totalMinutes)
    }

    /**
     * 孩子端申请延时。
     *
     * 校验：档位必须落在 [UsageSession.DELAY_OPTIONS]；单日累计延时不超过
     * [UsageSession.MAX_DAILY_EXTEND_MINUTES]（防刷）；今日必须先确认开始计时。
     * 成功后通知家长端（WebSocket），让"孩子申请了延时"实时可见。
     */
    @Transactional
    fun extend(deviceId: String, minutes: Int): UsageSessionDto {
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        val entity = usageSessionRepository.findByDeviceId(deviceId)
        if (entity == null || entity.dayKey != today || entity.startEpoch == null) {
            throw BizException(ParentErr.PARAM_ERROR, "今日计时尚未开始，无法延时")
        }
        if (minutes !in UsageSession.DELAY_OPTIONS) {
            throw BizException(ParentErr.PARAM_ERROR, "非法的延时档位：$minutes 分钟")
        }
        if (entity.extendedMinutes + minutes > UsageSession.MAX_DAILY_EXTEND_MINUTES) {
            throw BizException(
                ParentErr.PARAM_ERROR,
                "今日延时已达上限 ${UsageSession.MAX_DAILY_EXTEND_MINUTES} 分钟"
            )
        }
        entity.extendedMinutes += minutes
        entity.lastDelayEpoch = now
        entity.updatedAt = now
        usageSessionRepository.save(entity)
        log.info("usage session extended device={} +{}min total={}", deviceId, minutes, entity.extendedMinutes)

        deviceService.getUserId(deviceId)?.let { userId ->
            webSocketPush.pushToUser(
                userId,
                mapOf(
                    "type" to "usage.delay",
                    "deviceId" to deviceId,
                    "minutes" to minutes,
                    "extendedMinutes" to entity.extendedMinutes,
                    "at" to now
                )
            )
        }
        return toDto(entity, now, deviceId)
    }

    /** 解析当日额度：按工作日/周末取家长在「时间管控」页设置的值 */
    private fun resolveQuota(deviceId: String, now: Long): Int {
        val ds = deviceSettingRepository.findById(deviceId).orElse(null) ?: return 120
        return if (isWeekend(now)) (ds.weekendLimitMinutes ?: 180) else (ds.weekdayLimitMinutes ?: 120)
    }

    private fun toDto(s: UsageSession, now: Long, deviceId: String): UsageSessionDto {
        val started = s.startEpoch != null && s.dayKey == dayKey(now)
        val start = s.startEpoch ?: now
        // 进行中的会话：额度以「当前 DeviceSetting 的家长设置」为准，而非 start 时锁定的值。
        // 这样家长端修改「时间管控」额度后能立即（下次刷新）在三端生效，而不是冻结到次日起，
        // 避免出现"明明改了额度、孩子端当天却照旧"的"设置不生效"问题。
        val liveQuota = if (started) resolveQuota(deviceId, now) else s.quotaMinutes
        val totalMinutes = liveQuota + s.extendedMinutes
        val remainingMs = if (started) maxOf(0L, totalMinutes * 60_000L - (now - start)) else 0L
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
            lastDelayEpoch = s.lastDelayEpoch
        )
    }
}
