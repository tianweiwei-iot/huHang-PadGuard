package com.padguard.child.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.PolicyRepository
import com.padguard.core.data.repository.LogRepository
import com.padguard.core.data.repository.UsageRepository
import com.padguard.core.data.model.policy.AppLimitRule
import com.padguard.core.data.model.policy.AppLimitPolicy
import com.padguard.core.data.model.policy.MinorModePolicy
import com.padguard.core.data.model.policy.SchedulePolicy
import com.padguard.core.data.model.policy.ScheduleRule
import com.padguard.core.data.model.policy.ScheduleAction
import com.padguard.core.engine.PolicyEngine
import com.padguard.core.transport.Downlink
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.common.TimeProvider
import com.padguard.core.common.Logger
import com.padguard.child.usagesession.UsageSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * 首页 ViewModel。
 *
 * 关键点：
 * - 今日使用时长 = max(策略下发的限额, 真实 UsageStats)，前者是上限，后者是实况。
 *   当 UsageStats 不可用时降级为策略下发值，避免首页空白。
 * - 计划时间轴 = 来自 SchedulePolicy.rules，按起止排序，
 *   标注当前指针（用服务端校准后的 now）。
 * - 最近拦截 = LogRepository 的拦截类日志（type 包含"APP_BLOCKED"/"URL_BLOCKED"），
 *   取最近 5 条。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val policyRepository: PolicyRepository,
    private val usageRepository: UsageRepository,
    private val logRepository: LogRepository,
    private val policyEngine: PolicyEngine,
    private val timeProvider: TimeProvider,
    private val usageSessionManager: UsageSessionManager,
    private val remoteDataSource: RemoteDataSource
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /**
     * 下行通道是否在线。
     *
     * 首帧先给一次 [RemoteDataSource.isOnline] 快照：[Downlink] 是**无 replay 的** SharedFlow，
     * 晚来的订阅者收不到历史上最后一次连通性变化，只 collect downlink 会让首页长期停在初始值上。
     * 之后由 [Downlink.ConnectivityChanged] 驱动后续变化。
     */
    private val onlineFlow: Flow<Boolean> = flow {
        emit(remoteDataSource.isOnline())
        remoteDataSource.downlink
            .filterIsInstance<Downlink.ConnectivityChanged>()
            .collect { emit(it.online) }
    }.distinctUntilChanged()

    /**
     * 用户态 + 在线态 合流。
     *
     * kotlinx.coroutines 的 [combine] 重载最多支持 5 个上游流，而这里需要
     * 「昵称 / 在线 / 策略 / 时长 / 拦截 / 会话」共 6 路输入。
     * 把两路**无业务耦合**的标量先合成 Pair 是最小侵入的做法：
     * 比起改用 `combine(List<Flow<*>>)` 丢泛型后再手动强转，Pair 保留了编译期类型。
     */
    private val identityFlow: Flow<Pair<String, Boolean>> =
        combine(authRepository.studentName, onlineFlow) { name, online -> name to online }

    init {
        viewModelScope.launch {
            combine(
                identityFlow,
                policyRepository.observePolicy(),
                usageRepository.observeTodayUsageMinutes(),
                logRepository.observeRecentBlocks(limit = 5),
                usageSessionManager.state
            ) { identity, rawPolicy, usedMinutes, blocks, session ->
                val studentName = identity.first
                val online = identity.second
                val nowMillis = timeProvider.now()
                // 时间轴是「策略时区」的一天：游标、读数、时段判定必须同一时区，
                // 否则会出现“图例说进行中、游标却停在别处”的自相矛盾
                val zone = policyZone(rawPolicy.schedule)
                val nowMinutes = minutesOfDay(nowMillis, zone)

                // 未成年人模式开启时，首页显示的额度**必须走分龄派生后的值**。
                // 直接用 policy.appLimit 会显示家长旧配置（如 120 分钟），
                // 而实际锁机按分龄基线（60 分钟）执行 ——
                // 表现为"首页还剩 80 分钟，设备却已经锁了"。
                val policy = rawPolicy.copy(
                    appLimit = rawPolicy.minorMode.coerceAppLimit(rawPolicy.appLimit)
                )

                // 额度必须与锁机判定取同一档位（工作日 / 周末），
                // 否则首页显示的"剩余时长"和实际锁屏时机对不上。
                // 服务端权威会话激活时，首页直接采用会话派生值，保证三端一致；
                // 会话未激活（孩子尚未确认计时 / 离线）时回退到本地策略值，避免首页空白。
                val dailyQuota: Int
                val remaining: Int
                val used: Int
                if (session.active) {
                    // 权威口径：剩余由服务端锚点 (startEpoch / serverNow) 派生，本地仅用单调时钟做平滑插值。
                    // timeProvider.now() = 本地墙钟 + offset，而 baselineServerNow 也是同一 offset 系，
                    // 作差时 offset 相互抵消，不会因平板与服务端存在时钟偏移而漂移 ——
                    // 这样首页倒计时与锁机判定 (UsageSessionManager.isExpired) 始终同源，且与家长端头条一致。
                    val liveRemaining = usageSessionManager.liveRemainingMs(timeProvider.now())
                    dailyQuota = session.quotaMinutes
                    remaining = (liveRemaining / 60_000L).toInt().coerceAtLeast(0)
                    used = (dailyQuota - remaining).coerceAtLeast(0)
                } else {
                    dailyQuota = policy.appLimit.quotaFor(isWeekend(nowMillis, zone))
                    remaining = (dailyQuota - usedMinutes).coerceAtLeast(0)
                    used = usedMinutes
                }

                HomeUiState(
                    studentName = studentName,
                    // 在线态必须取自真实下行通道：写死 true 会让断网、Token 失效、
                    // MQTT 掉线这三种状态在首页全部显示为"在线"，
                    // 家长端看到设备离线、孩子端却显示一切正常，故障无从归因。
                    statusLabel = if (online) "在线" else "离线",
                    isOnline = online,
                    usedMinutes = used,
                    quotaMinutes = dailyQuota,
                    remainingMinutes = remaining,
                    nowLabel = formatNow(nowMillis, zone),
                    nowMinutes = nowMinutes,
                    scheduleItems = buildScheduleSlots(policy.schedule, nowMillis, zone),
                    minorMode = buildMinorModeUi(policy.minorMode, nowMinutes, nowMillis),
                    recentBlocks = blocks.map {
                        RecentBlockUi(
                            appName = it.summary,
                            timeLabel = formatRelative(it.timestamp)
                        )
                    }
                )
            }
                .flowOn(Dispatchers.Default)
                .collect { _state.value = it }
        }
    }

    /** 计划时段以服务端下发的时区为准（通常 Asia/Shanghai）；
     *  即使设备时区被改错，时间轴仍按家长配置的时区走，与锁机判定保持一致 */
    /** 今天是否按"周末"额度计算（与锁机判定同口径，避免首页剩余时长和实际锁屏时机对不上） */
    private fun isWeekend(nowMillis: Long, zone: ZoneId): Boolean {
        val dow = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().dayOfWeek
        return dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY
    }

    private fun policyZone(schedule: SchedulePolicy): ZoneId =
        runCatching { ZoneId.of(schedule.timezone) }.getOrElse {
            Logger.w("HomeViewModel") { "invalid timezone ${schedule.timezone}, fallback" }
            ZoneId.systemDefault()
        }

    private fun buildScheduleSlots(
        schedule: SchedulePolicy,
        nowMillis: Long,
        zone: ZoneId
    ): List<ScheduleSlotUi> {
        if (schedule.rules.isEmpty()) return emptyList()
        val nowMinutes = minutesOfDay(nowMillis, zone)
        return schedule.rules
            .sortedBy { it.startMinutes() }
            .map { rule ->
                val isLocked = rule.action == ScheduleAction.LOCK
                ScheduleSlotUi(
                    timeRange = "${rule.start} – ${rule.end}",
                    label = if (isLocked) "休息" else "可用",
                    isLocked = isLocked,
                    isCurrent = isCurrentSlot(rule, nowMinutes),
                    // 供游标卡尺时间轴做几何定位：0..1440 分钟（跨零点由 crossesMidnight 标记）
                    startMinutes = rule.startMinutes(),
                    endMinutes = rule.endMinutes(),
                    crossesMidnight = rule.crossesMidnight()
                )
            }
    }

    /**
     * 未成年人模式在首页的展示态。
     *
     * 这里刻意**不**只显示"已开启"，而是把三件孩子真正需要知道的事说清楚：
     * 档位（为什么是这个额度）、宵禁区间、以及现在是不是就在宵禁里。
     * 只给一个开关态的话，孩子被锁屏时看到的锁屏页文案和首页对不上，
     * 第一反应是"应用坏了"而不是"到睡觉时间了"。
     */
    private fun buildMinorModeUi(
        minorMode: MinorModePolicy,
        nowMinutes: Int,
        nowMillis: Long
    ): MinorModeUi {
        if (!minorMode.enabled) return MinorModeUi.Disabled
        val inCurfew = minorMode.curfewActiveAt(nowMinutes, nowMillis)
        return MinorModeUi.Enabled(
            ageBandLabel = minorMode.ageBand.label,
            curfewRange = "${minorMode.effectiveCurfewStart()} – ${minorMode.effectiveCurfewEnd()}",
            inCurfew = inCurfew,
            // 豁免期是家长临时给的，孩子应该看得到还剩多久，
            // 否则"明明在宵禁里却能玩"会让他以为管控失效、下次继续硬闯。
            exemptRemainingMinutes = minorMode.parentExemptUntil.takeIf { it > nowMillis }
                ?.let { ((it - nowMillis) / 60_000L).toInt() + 1 } ?: 0
        )
    }

    private fun isCurrentSlot(rule: ScheduleRule, nowMinutes: Int): Boolean {
        val start = rule.startMinutes()
        val end = rule.endMinutes()
        return if (rule.crossesMidnight()) {
            nowMinutes >= start || nowMinutes < end
        } else {
            nowMinutes in start until end
        }
    }

    private fun formatNow(millis: Long, zone: ZoneId): String {
        // 注意：LocalTime.ofInstant 是 Java 9 才新增的方法，minSdk=26 设备运行时并不存在，
        // 会触发 NoSuchMethodError（首页直接闪退）。改用 Java 8 原生等价的 atZone().toLocalTime()。
        val time = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        return "%02d:%02d".format(time.hour, time.minute)
    }

    /** 当天已过的分钟数（0..1439），与 [formatNow] 用同一时区，保证读数与游标刻度一致 */
    private fun minutesOfDay(millis: Long, zone: ZoneId): Int =
        // 同上：避免 LocalTime.ofInstant（Java 9+），用 atZone().toLocalTime() 取代
        Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().toSecondOfDay() / 60

    private fun formatRelative(millis: Long): String {
        val diffMin = ((timeProvider.now() - millis) / 60_000L).coerceAtLeast(0)
        return when {
            diffMin < 1 -> "刚刚"
            diffMin < 60 -> "${diffMin} 分钟前"
            else -> "${diffMin / 60} 小时前"
        }
    }
}

/**
 * 首页 UI 状态。
 *
 * 全部是不可变数据，由 ViewModel 一次性组装好后下发到 Compose，
 * 避免 Composable 里再做重计算（影响首帧）。
 */
data class HomeUiState(
    val studentName: String = "",
    // 默认值必须取"未知"而非"在线"：首帧数据尚未就绪时撒谎成正面向，
    // 会让刚进首页的家长看到在线、实际通道还没建立。
    val statusLabel: String = "连接中",
    val isOnline: Boolean = false,
    val usedMinutes: Int = 0,
    val quotaMinutes: Int = 0,
    val remainingMinutes: Int = 0,
    val nowLabel: String = "--:--",
    /** 当前时刻的当天分钟数（0..1439），供时间轴游标定位；-1 表示未知 */
    val nowMinutes: Int = -1,
    val scheduleItems: List<ScheduleSlotUi> = emptyList(),
    /** 未成年人模式展示态；未开启时为 [MinorModeUi.Disabled] */
    val minorMode: MinorModeUi = MinorModeUi.Disabled,
    val recentBlocks: List<RecentBlockUi> = emptyList()
)

/** 未成年人模式在首页的展示态 */
sealed interface MinorModeUi {
    data object Disabled : MinorModeUi

    data class Enabled(
        val ageBandLabel: String,
        /** 宵禁区间文案，如 "22:00 – 06:00" */
        val curfewRange: String,
        /** 当前是否正处于宵禁 */
        val inCurfew: Boolean,
        /** 家长豁免剩余分钟数；0 表示无豁免 */
        val exemptRemainingMinutes: Int = 0
    ) : MinorModeUi
}

data class ScheduleSlotUi(
    val timeRange: String,
    val label: String,
    val isLocked: Boolean,
    val isCurrent: Boolean,
    /** 起始分钟（0..1440），供时间轴定位 */
    val startMinutes: Int = 0,
    /** 结束分钟（0..1440）；跨零点时小于 [startMinutes] */
    val endMinutes: Int = 0,
    /** 是否跨零点（用于时间轴把它拆成两段画） */
    val crossesMidnight: Boolean = false
)

data class RecentBlockUi(
    val appName: String,
    val timeLabel: String
)
