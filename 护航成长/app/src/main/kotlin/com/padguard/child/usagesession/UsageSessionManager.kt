package com.padguard.child.usagesession

import android.content.Context
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.UsageRepository
import com.padguard.core.transport.http.CredentialStore
import com.padguard.core.engine.lock.LockController
import com.padguard.core.engine.lock.LockDetail
import com.padguard.core.engine.lock.LockReason
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.http.ApiResult
import com.padguard.core.transport.http.UsageSessionDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 平板使用计时会话管理器（孩子端运行时中枢）。
 *
 * ## 职责边界
 * 本类只负责"三端一致的使用计时"这一件事：从服务端拉取权威会话、在孩子确认后启动、
 * 提供实时剩余、处理延时申请，并把"到期未延时"翻译为 [LockController] 的 DAILY_LIMIT 请求。
 * 它**不**做策略判定、不做应用拦截——那些仍归 [com.padguard.core.engine.PolicyEngine]。
 *
 * ## 为什么日总时长锁定不直接在这里调 lockController，而是经 PolicyEngine.tick
 * [PolicyEngine.tick] 每轮都会用 `syncAutoReasons` 对锁定原因做差集同步，
 * 任何"非本轮判定成立"的自动原因会被立刻释放。若本类自行 `requestLock(DAILY_LIMIT)`，
 * 下一轮 tick 会因为引擎没把 DAILY_LIMIT 列入 autoReasons 而把它解掉，引发锁屏闪烁。
 * 因此本类只暴露 [isExpired]，由 [com.padguard.core.engine.PolicyEngine.tick] 在统一入口处
 * 把到期挂进 autoReasons，沿用与时段锁/宵禁完全相同的生命周期管理。
 *
 * ## 时间一致性
 * 权威剩余由服务端用 `serverNow - startEpoch` 派生。本端只在本地用墙钟做"平滑倒计时"插值，
 * 真正用于锁定的到期判定也以服务端下发的基线为准（[isExpired] 用 timeProvider.now() 减基线），
 * 三端看到的同一份数字不会因改本地时间而漂移——唯一的本地时间来源是校准后的墙钟。
 */
@Singleton
class UsageSessionManager @Inject constructor(
    private val remote: RemoteDataSource,
    private val timeProvider: TimeProvider,
    private val lockController: LockController,
    private val authRepository: AuthRepository,
    private val credentials: CredentialStore,
    private val usageRepository: UsageRepository,
    @ApplicationContext appContext: Context
) {

    data class UiState(
        /** 会话尚未激活：需要孩子在弹窗里确认"计时开始" */
        val needsStartConfirm: Boolean = false,
        /** 计时已激活（孩子已确认，服务端已锁起点） */
        val active: Boolean = false,
        /** 最近一次刷新时的服务端墙钟（毫秒），用于本地平滑倒计时插值 */
        val serverNow: Long = 0L,
        /** 最近一次刷新得到的剩余毫秒（在 serverNow 时刻） */
        val remainingMs: Long = 0L,
        /** 剩余 ≤ 阈值：应显示屏幕正上方的倒计时浮层 */
        val countdownActive: Boolean = false,
        /** 倒计时阈值（毫秒），默认 10 分钟 */
        val countdownThresholdMs: Long = 10 * 60_000L,
        /** 延时申请可选档位（分钟） */
        val delayOptions: List<Int> = listOf(15, 30, 45, 60),
        /** 单日累计延时上限（分钟） */
        val maxDailyExtendMinutes: Int = 120,
        /** 已累计延时（分钟） */
        val extendedMinutes: Int = 0,
        /** 当前正在等待家长审批的延时分钟数（>0 表示"已提交，等待审核"） */
        val pendingDelayMinutes: Int = 0,
        /** 今日额度（分钟，含延时） */
        val quotaMinutes: Int = 0,
        /** 计时起点（服务端墙钟，毫秒），未激活为 null */
        val startEpoch: Long? = null,
        /** 进行中的请求，UI 据此禁用按钮 */
        val loading: Boolean = false,
        /** 最近一次错误文案（仅用于调试/日志） */
        val error: String? = null,
        /**
         * 家长「时间管控」总开关（来自服务端会话）。
         * false 时倒计时与到期锁定都必须停用，否则关掉总开关也拦不住锁屏。
         */
        val timeControlEnabled: Boolean = true
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 最近一次刷新得到的基线，用于本地平滑倒计时 */
    private var baselineServerNow: Long = 0L
    private var baselineRemainingMs: Long = 0L
    /**
     * 基线落库时刻的**单调时钟**读数（毫秒，含休眠，无法被用户修改）。
     *
     * 见 [liveRemainingMs] 的说明：本地推进量必须用它，不能用墙钟。
     */
    @Volatile
    private var baselineElapsedRealtime: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()

    /** 最近一次权威会话的落盘副本，用于进程重启 / 离线冷启动时恢复"今日已用" */
    private val sessionPrefs = appContext.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)

    init {
        restorePersistedSession()
    }

    /**
     * 拉取当前会话并刷新 UI 状态。
     *
     * 未绑定 / 凭据未就绪时直接返回，避免空令牌打到服务端触发自愈解绑。
     * 会话未激活 → 置 [UiState.needsStartConfirm]，驱动"计时开始"确认弹窗。
     */
    suspend fun refresh() {
        if (!credentials.isBound) return
        val deviceId = authRepository.getDeviceId()
        if (deviceId.isBlank()) return

        refreshMutex.withLock {
            when (val r = remote.getUsageSession()) {
                is ApiResult.Success -> applyDto(r.value)
                is ApiResult.BizError -> Logger.w(TAG) { "usage-session get rejected: ${r.code} ${r.message}" }
                is ApiResult.Failure -> Logger.w(TAG) { "usage-session get failed: ${r.message}" }
            }
        }
    }

    /**
     * 孩子确认"计时开始"：服务端锁定今日起点与额度（幂等）。
     */
    suspend fun confirmStart(): Boolean {
        if (!credentials.isBound) return false
        val deviceId = authRepository.getDeviceId()
        if (deviceId.isBlank()) return false

        _state.update { it.copy(loading = true, error = null) }
        val result = refreshMutex.withLock {
            when (val r = remote.startUsageSession()) {
                is ApiResult.Success -> {
                    applyDto(r.value)
                    true
                }
                is ApiResult.BizError -> {
                    _state.update { it.copy(error = "${r.code}: ${r.message}") }
                    Logger.w(TAG) { "usage-session start rejected: ${r.code} ${r.message}" }
                    false
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(error = r.message) }
                    Logger.w(TAG) { "usage-session start failed: ${r.message}" }
                    false
                }
            }
        }
        _state.update { it.copy(loading = false) }
        return result
    }

    /**
     * 申请延时（分钟必须落在 [UiState.delayOptions] 内）。
     *
     * 注意：**只发起审批工单，不立即延长**。成功仅把服务端返回的 `pendingDelayMinutes` 落到本地状态，
     * 真正延长要等家长端审批通过、下一次会话刷新（15s 轮询）拿到新的 `extendedMinutes` 才生效。
     * 这与"申请即生效"的旧实现彻底解耦，是本次修复的核心。
     */
    suspend fun requestDelay(minutes: Int): Boolean {
        if (!credentials.isBound) return false
        val deviceId = authRepository.getDeviceId()
        if (deviceId.isBlank()) return false

        _state.update { it.copy(loading = true, error = null) }
        val result = refreshMutex.withLock {
            when (val r = remote.requestDelayUsageSession(minutes)) {
                is ApiResult.Success -> {
                    applyDto(r.value)
                    true
                }
                is ApiResult.BizError -> {
                    _state.update { it.copy(error = "${r.code}: ${r.message}") }
                    Logger.w(TAG) { "usage-session delay request($minutes) rejected: ${r.code} ${r.message}" }
                    false
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(error = r.message) }
                    Logger.w(TAG) { "usage-session delay request($minutes) failed: ${r.message}" }
                    false
                }
            }
        }
        _state.update { it.copy(loading = false) }
        return result
    }

    /**
     * 本地平滑倒计时：当前的实时剩余（毫秒），永不为负。
     *
     * ## 为什么推进量必须用单调时钟（elapsedRealtime）而不是墙钟
     *
     * 早期实现是 `baselineRemainingMs - (timeProvider.now() - baselineServerNow)`，
     * 其中 `timeProvider.now()` = `System.currentTimeMillis() + offset`。
     * 墙钟是**孩子在设置里可以直接改的**：把系统时间往前拨 2 小时，
     * `now - baselineServerNow` 立刻变小甚至为负，剩余时长不减反增，`isExpired()` 永远为假 →
     * 引擎不会挂 `DAILY_LIMIT` → **总时长管控被彻底绕过**，而且没有任何报错。
     * 这正是本产品最致命的一类绕过面（改时间是最低门槛的操作）。
     *
     * 现在改为：收到服务端权威剩余时同时记下当时 `SystemClock.elapsedRealtime()`，
     * 本地推进量 = `elapsedRealtime() - baselineElapsedRealtime`。
     * 单调时钟只随真实流逝前进，不受用户改时间 / NTP 校时跳变影响，无法被倒拨。
     *
     * 副作用（可接受且方向正确）：单调时钟在设备休眠期间同样前进，
     * 因此熄屏期间也会计入消耗 —— 宁可"多算"也不能"少算"，
     * 且每 15 秒的服务端权威刷新会立刻把偏差纠正回来。
     */
    fun liveRemainingMs(): Long {
        if (baselineRemainingMs <= 0L) return 0L
        val advanced = android.os.SystemClock.elapsedRealtime() - baselineElapsedRealtime
        // 单调时钟不会倒流，但仍防御负值（极端 ROM 行为）以免"越用越多"
        return maxOf(0L, baselineRemainingMs - maxOf(0L, advanced))
    }

    /** 计时已激活且实时剩余已耗尽（用于 PolicyEngine 统一挂 DAILY_LIMIT） */
    fun isExpired(): Boolean {
        val s = _state.value
        if (!s.active) return false
        // 家长已关闭「时间管控」总开关：不得再按会话剩余判到期，
        // 否则"关掉总开关"后设备仍会被 DAILY_LIMIT 锁住 —— 管控关不掉是最难解释的故障。
        if (!s.timeControlEnabled) return false
        return liveRemainingMs() <= 0L
    }

    /** 解绑 / 退出管控时清空会话状态，避免陈旧会话误导 UI 与锁定 */
    fun reset() {
        baselineServerNow = 0L
        baselineRemainingMs = 0L
        baselineElapsedRealtime = 0L
        _state.value = UiState()
        // 落盘副本必须一起清掉：解绑后换一个家庭重新绑定，若沿用上一家的"今日剩余"，
        // 新家长的额度会被旧家庭的进度直接吃掉（甚至一绑上就是"已用完"）。
        runCatching { sessionPrefs.edit().clear().apply() }
        // 若早期因会话到期锁过屏，这里把 DAILY_LIMIT 原因释放掉（新一轮 tick 也会由 syncAutoReasons 兜底）
        lockController.releaseLock(LockReason.DAILY_LIMIT)
    }

    /**
     * 把权威会话落盘一份「可离线恢复」的快照。
     *
     * ## 为什么必须落盘
     * 会话原先只活在内存 StateFlow 里，而 [isExpired] 在 `active == false` 时恒返回 false：
     * 孩子把当日额度用到耗尽，再开飞行模式并重启进程，[refresh] 必然失败，
     * 状态停在默认值 → [com.padguard.core.engine.PolicyEngine.tick] 拿到的 `dailyLimitExpired`
     * 永远是 false → **当日总时长管控整天消失**，连「计时开始」确认浮层都不会弹
     * （`needsStartConfirm` 默认也是 false）。这是"断网即解除管控"级别的一类漏洞。
     *
     * 落盘后即使完全离线，重启也能按「上次已知剩余 − 已流逝时间」继续判到期：
     * 恢复值只会让设备更早锁屏，联网后第一帧服务端权威值就会把它纠正回来。
     */
    private fun persistSession(dto: UsageSessionDto) {
        runCatching {
            sessionPrefs.edit()
                .putString(KEY_DAY, dto.dayKey)
                .putBoolean(KEY_ACTIVE, dto.active)
                .putLong(KEY_REMAINING, dto.remainingMs)
                .putLong(KEY_SERVER_NOW, dto.serverNow)
                .putLong(KEY_ELAPSED, android.os.SystemClock.elapsedRealtime())
                .putInt(KEY_QUOTA, dto.quotaMinutes)
                .putInt(KEY_EXTENDED, dto.extendedMinutes)
                .putBoolean(KEY_TIME_CONTROL, dto.timeControlEnabled)
                // apply 而非 commit：这是"尽力而为"的副本，不能阻塞采样线程
                .apply()
        }
    }

    /**
     * 冷启动恢复上次落盘的会话（构造期调用，早于任何一次网络请求）。
     *
     * 只恢复**当天**快照：跨日的剩余额度是另一个数字，沿用会让新的一天一开机就"已用完"。
     */
    private fun restorePersistedSession() {
        runCatching {
            val savedDay = sessionPrefs.getString(KEY_DAY, null) ?: return
            // 两侧格式不同、口径相同：服务端下发 ISO（2026-11-04），
            // 本地 UsageRepository.dayKey() 是紧凑格式（20261104），必须归一化后再比，
            // 否则永远不相等 —— 快照会被每次启动都当成"跨日"清掉，等于没落盘。
            if (savedDay.filter { it.isDigit() } != usageRepository.dayKey()) {
                sessionPrefs.edit().clear().apply()
                return
            }
            // 未激活（今日还没确认开始计时）不需要恢复：保持默认态，由确认流程正常起表。
            if (!sessionPrefs.getBoolean(KEY_ACTIVE, false)) return

            val savedRemaining = sessionPrefs.getLong(KEY_REMAINING, 0L)
            val savedServerNow = sessionPrefs.getLong(KEY_SERVER_NOW, 0L)
            val savedElapsed = sessionPrefs.getLong(KEY_ELAPSED, 0L)
            val nowElapsed = android.os.SystemClock.elapsedRealtime()

            // 两种推进量取大者：
            // ① 单调时钟差 —— 进程被杀重启后仍然连续（含深睡），是首选；
            // ② 墙钟差 —— 设备重启会让 elapsedRealtime 归零，此时①退化为 0，必须靠②补齐停机时间。
            // 取大是刻意的（与 [liveRemainingMs] 的"宁可多算不可少算"同向）：
            // 少算等于白送额度，多算最多提前锁屏，且联网后立刻被服务端权威值纠正。
            val advancedElapsed = (nowElapsed - savedElapsed).coerceAtLeast(0L)
            val advancedWall = (timeProvider.now() - savedServerNow).coerceAtLeast(0L)
            val advance = maxOf(advancedElapsed, advancedWall)
            val remaining = (savedRemaining - advance).coerceAtLeast(0L)

            val defaults = UiState()
            val quota = sessionPrefs.getInt(KEY_QUOTA, 0)
            val extended = sessionPrefs.getInt(KEY_EXTENDED, 0)
            baselineServerNow = savedServerNow + advance
            baselineRemainingMs = remaining
            baselineElapsedRealtime = nowElapsed
            _state.value = defaults.copy(
                // 不弹"计时开始"浮层：这是恢复态，不是新的开始
                needsStartConfirm = false,
                active = true,
                serverNow = baselineServerNow,
                remainingMs = remaining,
                countdownActive = remaining <= defaults.countdownThresholdMs,
                extendedMinutes = extended,
                quotaMinutes = quota + extended,
                // 起点未落盘（恢复态只需剩余量），留空不影响任何判定
                startEpoch = null,
                timeControlEnabled = sessionPrefs.getBoolean(KEY_TIME_CONTROL, true)
            )
            Logger.i(TAG) {
                "usage-session restored: remainingMs=$remaining advance=$advance " +
                    "(elapsed=$advancedElapsed wall=$advancedWall)"
            }
        }
    }

    /** 把服务端会话 DTO 落到本地状态与平滑倒计时基线 */
    private fun applyDto(dto: UsageSessionDto) {
        baselineServerNow = dto.serverNow
        baselineRemainingMs = dto.remainingMs
        // 与权威剩余同帧记录单调时钟读数（见 liveRemainingMs 注释：本地推进量不能用墙钟）
        baselineElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        // 落盘一份快照：断网 / 进程被杀后仍能按"上次已知剩余"继续判到期（见 persistSession）
        persistSession(dto)
        val countdownActive = dto.active && dto.remainingMs <= dto.countdownThresholdMs
        _state.update { s ->
            s.copy(
                // 只在该设备的"时间管控"确实开启时才要求确认开始计时。
                // 原实现只看 active，家长已关掉时间管控却仍弹"平板使用计时开始"，
                // 孩子被引导去确认一个不存在的配额。
                needsStartConfirm = !dto.active && dto.timeControlEnabled,
                active = dto.active,
                serverNow = dto.serverNow,
                remainingMs = dto.remainingMs,
                countdownActive = countdownActive,
                countdownThresholdMs = dto.countdownThresholdMs,
                delayOptions = dto.delayOptions,
                maxDailyExtendMinutes = dto.maxDailyExtendMinutes,
                extendedMinutes = dto.extendedMinutes,
                pendingDelayMinutes = dto.pendingDelayMinutes,
                quotaMinutes = dto.quotaMinutes + dto.extendedMinutes,
                startEpoch = dto.startEpoch,
                timeControlEnabled = dto.timeControlEnabled,
                error = null
            )
        }
        Logger.i(TAG) {
            "usage-session applied: active=${dto.active} remainingMs=${dto.remainingMs} " +
                "quota=${dto.quotaMinutes} extended=${dto.extendedMinutes} countdown=$countdownActive " +
                "timeControl=${dto.timeControlEnabled}"
        }
    }

    companion object {
        private const val TAG = "UsageSessionMgr"

        /** 会话快照专用的 prefs 文件（解绑时整体清空，不能与凭据等共用） */
        private const val PREFS_SESSION = "usage_session_snapshot"

        private const val KEY_DAY = "day"
        private const val KEY_ACTIVE = "active"
        private const val KEY_REMAINING = "remaining_ms"
        private const val KEY_SERVER_NOW = "server_now"
        private const val KEY_ELAPSED = "saved_elapsed"
        private const val KEY_QUOTA = "quota_minutes"
        private const val KEY_EXTENDED = "extended_minutes"
        private const val KEY_TIME_CONTROL = "time_control_enabled"
    }
}
