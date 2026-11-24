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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 计时暂停的来源。
 *
 * 两种来源的**恢复语义不同**，不能合并成一个布尔：
 * - [SCREEN_OFF]：电源键熄屏自动暂停，屏幕点亮后自动继续（这是用户承诺的默认路径）；
 * - [MANUAL]：长按悬浮球确认暂停，必须点锁屏页「继续计时」才恢复。
 *   若也允许亮屏自动继续，孩子按一下电源键就能绕过手动暂停，功能形同虚设。
 */
enum class TimerPauseSource { SCREEN_OFF, MANUAL }

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
        val timeControlEnabled: Boolean = true,
        /**
         * 离线本地起表后待同步服务端的标志。
         *
         * 孩子离线时点「开始计时」：按本地受信钟立即起表并落盘（管控离线本地独立生效），
         * 置此标志；联网后的下一次 [refresh] 发现服务端会话仍未 active，会自动补发
         * `start()`（补发的是孩子已做过的确认，不是擅自起表），成功后由权威值覆盖并清零。
         */
        val pendingStartSync: Boolean = false,

        /** 计时暂停中：本地剩余冻结，设备由 LockReason.TIMER_PAUSED 保持不可操作 */
        val paused: Boolean = false,

        /** 暂停来源；只有同源恢复才允许自动继续（见 [TimerPauseSource]） */
        val pauseSource: TimerPauseSource? = null
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

    /**
     * 计时会话基线（baselineServerNow / baselineRemainingMs / baselineElapsedRealtime）的读写锁。
     *
     * 这些字段被多条协程路径共享：主循环每 tick 的 [checkDayRollover] 与 [liveRemainingMs]/[isExpired] 读路径、
     * 以及持 [refreshMutex] 的 [refresh]/[confirmStart]→[applyDto] 写路径。
     * 用普通对象锁统一串行化，避免"日切重置被 applyDto 覆盖回 active"或"剩余/到期读到撕裂基线"的竞态
     * （表现为偶发日切晚几秒生效或额度临界少算一拍，且下一 tick 自愈，故隐蔽）。
     * 选 synchronized 而非再入一把协程 Mutex：读路径是非 suspend 函数，且两把锁对象不同、不会嵌套死锁。
     */
    private val baselineLock = Any()

    /**
     * 暂停起始的单调时钟读数；0 表示未在暂停中。
     *
     * 与三个 baseline 字段同属 [baselineLock] 保护：暂停/恢复与 applyDto、
     * checkDayRollover 可能来自不同协程，必须串行化，否则会出现"恢复把新基线的
     * elapsed 又加了一遍"这类隐蔽的额度漂移。
     */
    @Volatile
    private var pauseStartedElapsed: Long = 0L

    @Volatile
    private var pauseSource: TimerPauseSource? = null

    /**
     * 暂停时长待补报服务端的单条记录（复用既有"休息时长从额度扣除"幂等通道）。
     *
     * 同一天多次暂停合并进同一 restId：网络重试/乱序下服务端按 id 去重，
     * "少扣"比"多扣"安全（宁可孩子少用，不能凭一次弱网重复回血）。
     */
    private data class PendingPauseRest(val restId: String, val restMs: Long)

    @Volatile
    private var pendingPauseRest: PendingPauseRest? = null
    private val pauseRestMutex = Mutex()

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
                is ApiResult.Success -> {
                    applyDto(r.value)
                    // 离线本地起表的补同步：服务端尚未收到 start（active=false）时补发一次。
                    // 补发的语义是"重试孩子已做过的确认"（pendingStartSync 只在确认后置位），
                    // 不是擅自起表；成功后 applyDto 已用权威值覆盖并清零标志。
                    if (!r.value.active && r.value.timeControlEnabled && _state.value.pendingStartSync) {
                        when (val s = remote.startUsageSession()) {
                            is ApiResult.Success -> applyDto(s.value)
                            else -> Logger.w(TAG) { "usage-session start resync failed, keep local start" }
                        }
                    }
                }
                is ApiResult.BizError -> Logger.w(TAG) { "usage-session get rejected: ${r.code} ${r.message}" }
                is ApiResult.Failure -> Logger.w(TAG) { "usage-session get failed: ${r.message}" }
            }
            // 暂停时长的补报离线失败后在这里重试（恢复计时可能发生在完全无网的时刻）。
            // 成功清标志后，下一次 refresh 的 applyDto 才会采用服务端已加回的权威剩余。
            if (pendingPauseRest != null) {
                flushPauseRestSafely()
            }
        }
    }

    /** 补报失败只记日志；CancellationException 必须放行，否则会吞掉协程取消 */
    private suspend fun flushPauseRestSafely() {
        try {
            flushPauseRestReport()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.w(TAG, t) { "pause rest report failed" }
        }
    }

    /**
     * 孩子确认"计时开始"：服务端锁定今日起点与额度（幂等）。
     *
     * **离线口径**（家长确认：本地立即起表）：网络失败时按本地受信钟起表并落盘
     * （`pendingStartSync=true`），联网后由 [refresh] 的补发通道用服务端 `start()` 对齐；
     * 服务端拒绝（BizError）不算离线，维持等待页。
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
                    // 离线本地起表（额度取上次已知值；从未拿到过会话时额度未知，不起表）
                    val quotaMin = _state.value.quotaMinutes
                    if (quotaMin > 0) {
                        Logger.w(TAG) { "usage-session start offline, starting locally with $quotaMin min" }
                        startLocally(quotaMin)
                        true
                    } else {
                        _state.update { it.copy(error = r.message) }
                        Logger.w(TAG) { "usage-session start failed offline without known quota: ${r.message}" }
                        false
                    }
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
     * 现在改为：收到服务端权威剩余时同时记下当时 `timeProvider.monotonicMs()`
     * （注入缝：Android 走 `SystemClock.elapsedRealtime`，纯 JVM 测试降级到 `System.nanoTime`），
     * 本地推进量 = `monotonicMs() - baselineElapsedRealtime`。
     * 单调时钟只随真实流逝前进，不受用户改时间 / NTP 校时跳变影响，无法被倒拨。
     *
     * 副作用（可接受且方向正确）：单调时钟在设备休眠期间同样前进，
     * 因此熄屏期间也会计入消耗 —— 宁可"多算"也不能"少算"，
     * 且每 15 秒的服务端权威刷新会立刻把偏差纠正回来。
     *
     * 2026-11-18 起该前提被"熄屏暂停"改写：暂停期间由 [pauseStartedElapsed] 冻结推进，
     * 恢复时把暂停区间从基线里扣除；上面的单调时钟口径仍然成立（只是暂停段不再计入）。
     */
    fun liveRemainingMs(): Long = synchronized(baselineLock) { liveRemainingMsLocked() }

    /** 调用方必须持有 [baselineLock]；暂停时返回冻结值 */
    private fun liveRemainingMsLocked(): Long {
        if (pauseStartedElapsed != 0L) return baselineRemainingMs.coerceAtLeast(0L)
        if (baselineRemainingMs <= 0L) return 0L
        val advanced = timeProvider.monotonicMs() - baselineElapsedRealtime
        // 单调时钟不会倒流，但仍防御负值（极端 ROM 行为）以免"越用越多"
        return maxOf(0L, baselineRemainingMs - maxOf(0L, advanced))
    }

    /**
     * 暂停计时。
     *
     * 只处理"已激活 + 时间管控开启"的会话；重复暂停幂等。冻结方式是**重设基线**而不是
     * 另存一个计数器：暂停瞬间把当时剩余写成新基线，并把基线时刻钉在单调时钟上，
     * 于是所有读路径（玻璃球、倒计时浮层、isExpired）天然同源，不会出现两处数字打架。
     *
     * 立即请求 [LockReason.TIMER_PAUSED]，不等 ≤15s 的下一轮 tick —— "确认暂停后
     * 平板立刻不可操作"是本次功能的核心体验；引擎每轮差集会重派生，不会把这条
     * 手动挂上的原因解掉（见 PolicyEngine 的 3.55 段）。
     */
    fun pause(source: TimerPauseSource) {
        val s = _state.value
        if (!s.active || !s.timeControlEnabled || s.paused) return
        synchronized(baselineLock) {
            if (pauseStartedElapsed != 0L) return
            val now = timeProvider.monotonicMs()
            val frozen = liveRemainingMsLocked()
            baselineServerNow = timeProvider.now()
            baselineRemainingMs = frozen
            baselineElapsedRealtime = now
            pauseStartedElapsed = now
            pauseSource = source
        }
        _state.update { it.copy(paused = true, pauseSource = source, remainingMs = liveRemainingMs()) }
        persistPauseFields()
        lockController.requestLock(LockReason.TIMER_PAUSED, 0L, PAUSED_DETAIL)
        Logger.i(TAG) { "usage timer paused: source=$source remainingMs=${_state.value.remainingMs}" }
    }

    /**
     * 恢复计时。
     *
     * @param expectedSource 非空时只恢复**同源**暂停：亮屏广播只传 SCREEN_OFF，
     *        因此不会把孩子主动确认的手动暂停一并解除（电源键不能成为绕过入口）。
     */
    fun resume(expectedSource: TimerPauseSource? = null) {
        val s = _state.value
        if (!s.paused) return
        if (expectedSource != null && s.pauseSource != expectedSource) return

        var pauseMs = 0L
        var newPending: PendingPauseRest? = null
        synchronized(baselineLock) {
            val now = timeProvider.monotonicMs()
            // elapsedRealtime 在设备重启后归零；落盘的老暂停起点可能大于 now，
            // 钳到 0 避免负时长。暂停态本身仍保持到孩子手动恢复，方向安全。
            pauseMs = if (pauseStartedElapsed > 0L) {
                (now - pauseStartedElapsed).coerceAtLeast(0L)
            } else 0L
            // 基线时间前移 pauseMs：本地剩余从冻结值继续走，暂停段不消耗额度。
            baselineElapsedRealtime = now
            pauseStartedElapsed = 0L
            pauseSource = null
            // 同一天合并进同一 id（服务端按 id 幂等），避免弱网重试造成重复回血。
            val previous = pendingPauseRest
            newPending = if (pauseMs > 0L || previous != null) {
                PendingPauseRest(
                    restId = previous?.restId
                        ?: "pause-${usageRepository.dayKey(timeProvider.now())}-$now",
                    restMs = (previous?.restMs ?: 0L) + pauseMs
                )
            } else null
            pendingPauseRest = newPending
        }
        _state.update {
            it.copy(
                paused = false,
                pauseSource = null,
                remainingMs = liveRemainingMs()
            )
        }
        persistPauseFields()
        // 立即释放锁，不等下一轮 tick（恢复后平板必须马上可操作）。
        lockController.releaseLock(LockReason.TIMER_PAUSED)
        Logger.i(TAG) { "usage timer resumed: source=${s.pauseSource} pausedMs=$pauseMs" }
        if (newPending != null) {
            scope.launch { flushPauseRestSafely() }
        }
    }

    fun isTimerPaused(): Boolean = _state.value.paused

    /**
     * 把暂停区间作为"休息"补报服务端，从每日总时长中扣掉，保证三端口径一致。
     * 复用 [RemoteDataSource.reportEyeCareRest] 的幂等队列语义（服务端只认 restId 去重，
     * 不校验来源），因此**不需要新增任何服务端表字段/契约**。
     */
    private suspend fun flushPauseRestReport() {
        if (!credentials.isBound) return
        pauseRestMutex.withLock {
            val pending = pendingPauseRest ?: return
            when (val r = remote.reportEyeCareRest(pending.restId, pending.restMs)) {
                is ApiResult.Success -> {
                    clearPendingPauseRest()
                    Logger.i(TAG) { "pause rest reported: id=${pending.restId} ms=${pending.restMs}" }
                }
                is ApiResult.BizError -> {
                    // 重复/非法：服务端已有这份额度或会话已过期，不要再重试
                    clearPendingPauseRest()
                    Logger.w(TAG) { "pause rest rejected (drop): ${r.code} ${r.message}" }
                }
                is ApiResult.Failure -> {
                    // 离线保留，由 refresh()/下一轮重试补发（离线本地口径已经先生效）
                    Logger.w(TAG) { "pause rest report failed (retry later): ${r.message}" }
                }
            }
        }
    }

    /** 清除暂停源与待报记录并落盘；调用方负责释放锁（清锁放在锁外便于串行化） */
    private fun clearPauseStateLocked() {
        pauseStartedElapsed = 0L
        pauseSource = null
        pendingPauseRest = null
    }

    private fun clearPendingPauseRest() {
        synchronized(baselineLock) { pendingPauseRest = null }
        persistPauseFields()
    }

    /** 家长关闭时间管控 / 会话作废 / 日切 / 解绑：撤销暂停且不补报额度（新的一天自有无额度） */
    private fun discardPause() {
        val wasPaused: Boolean
        synchronized(baselineLock) {
            wasPaused = pauseStartedElapsed != 0L
            clearPauseStateLocked()
        }
        _state.update { it.copy(paused = false, pauseSource = null) }
        persistPauseFields()
        if (wasPaused) lockController.releaseLock(LockReason.TIMER_PAUSED)
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

    /**
     * 新的一天尚未确认「开始计时」（供 GuardService 叠加"已绑定"后传给
     * `PolicyEngine.tick` 挂 [LockReason.AWAITING_START] 等待页）。
     *
     * 与 [UiState.needsStartConfirm] 的区别：本方法额外排除离线本地起表
     * （`pendingStartSync`，那已经是"确认过了"）。
     */
    fun isAwaitingStart(): Boolean {
        val s = _state.value
        return !s.active && !s.pendingStartSync && s.timeControlEnabled
    }

    /**
     * 日切重置（每 15s tick 调用，必须**本地独立生效、不等网络**）。
     *
     * ## 修的 bug：昨天用满额度锁屏 → 到今天仍锁屏
     * `DAILY_LIMIT` 由 `isExpired()` 每轮重派生，而 `restorePersistedSession()`
     * 只在进程冷启动跑一次 —— 进程整夜存活（息屏后 ROM 不一定杀它）跨零点时，
     * 内存里 `active=true, remaining=0` 一直成立，离线时 `refresh()` 失败又不改状态，
     * 于是"昨天的耗尽快照"把今天的锁屏也占了。这里每 tick 用受信钟比对落盘
     * `KEY_DAY`，跨日立即重置为待确认（不计时、不锁屏、弹确认），并把"今天这份"
     * restamp 落盘，防下一次重启再按跨日分支走一遍。
     *
     * ## 时钟
     * `dayKey` 取 `TimeProvider.now()`（受信钟）：回拨不倒退、前拨不超过物理流逝，
     * 孩子改系统时间无法伪造/推迟日切（真重启墙钟差由 A4 基线补偿兜住）。
     * 方向安全：误判跨日只会把设备送进等待页（多锁不多用）。
     */
    fun checkDayRollover() {
        val savedDay = runCatching { sessionPrefs.getString(KEY_DAY, null) }.getOrNull()
        val normalized = UsageSessionRestore.normalizeDayKey(savedDay) ?: return
        val todayKey = usageRepository.dayKey(timeProvider.now())
        if (normalized == todayKey) return

        val nowWall = timeProvider.now()
        val nowElapsed = timeProvider.monotonicMs()
        val timeControl = _state.value.timeControlEnabled
        // R42：内存态 quotaMinutes 是「基础 + 已批延时」的总额（applyDto 里相加得出），
        // 而落盘 KEY_QUOTA 与服务端 DTO 的 quotaMinutes 都是**纯基础额度**。
        // 跨日只清 extendedMinutes 会让昨天的延时固化进今天的基础额度：
        // 进程整夜存活 + 今天离线确认起表时，startLocally 直接拿膨胀值当今日总额（最多多 120 分钟）。
        // 这里把总额回落到基础值，磁盘快照也同步写基础值，与 UsageSessionRestore 的跨日分支同口径。
        val baseQuotaMinutes = (_state.value.quotaMinutes - _state.value.extendedMinutes).coerceAtLeast(0)
        // 重置为"今日待确认"：active=false → isExpired() 变 false，
        // 下一轮 tick 的 syncAutoReasons 自动释放 DAILY_LIMIT（无需手动 releaseLock）。
        synchronized(baselineLock) {
            baselineServerNow = nowWall
            baselineRemainingMs = 0L
            baselineElapsedRealtime = nowElapsed
            // 跨日必须清暂停：昨天暂停留下的冻结值与锁定态不能带进今天，
            // 否则孩子今天一开机就卡在「计时已暂停」等待页且无额度信息。
            clearPauseStateLocked()
        }
        lockController.releaseLock(LockReason.TIMER_PAUSED)
        _state.update { s ->
            s.copy(
                needsStartConfirm = timeControl,
                active = false,
                remainingMs = 0L,
                countdownActive = false,
                pendingDelayMinutes = 0,
                quotaMinutes = baseQuotaMinutes,
                extendedMinutes = 0,
                startEpoch = null,
                pendingStartSync = false,
                paused = false,
                pauseSource = null
            )
        }
        // restamp：把"今天这份"落盘（基础额度沿用上次已知值供 UI 显示；延时按天清零），
        // 否则 KEY_DAY 仍是昨天：本进程内正常，但下一次冷启动又命中跨日分支再清一遍。
        runCatching {
            sessionPrefs.edit()
                .putString(KEY_DAY, todayKey)
                .putBoolean(KEY_ACTIVE, false)
                .putLong(KEY_REMAINING, 0L)
                .putLong(KEY_SERVER_NOW, nowWall)
                .putLong(KEY_ELAPSED, nowElapsed)
                .putInt(KEY_QUOTA, baseQuotaMinutes)
                .putInt(KEY_EXTENDED, 0)
                .putBoolean(KEY_TIME_CONTROL, timeControl)
                .putBoolean(KEY_PENDING_START, false)
                .putBoolean(KEY_PAUSED, false)
                .putString(KEY_PAUSE_SOURCE, null)
                .putLong(KEY_PAUSE_STARTED_ELAPSED, 0L)
                .putString(KEY_PAUSE_REST_ID, null)
                .putLong(KEY_PAUSE_REST_MS, 0L)
                // 日切是 A13 每日确认制的权威快照：所有键要么一起落、要么都不落，
                // 与 startLocally 同口径同步提交，避免进程被杀留下半套 dayKey。
                .commit()
        }.onFailure { Logger.w(TAG) { "persist day-rollover snapshot failed: ${it.message}" } }
        Logger.i(TAG) { "usage-session day rollover: $normalized -> $todayKey (waiting start confirm)" }
    }

    /**
     * 离线本地起表（见 [confirmStart] 的离线口径）。只在持有 [refreshMutex] 时调用。
     */
    private fun startLocally(quotaMinutes: Int) {
        val nowWall = timeProvider.now()
        val nowElapsed = timeProvider.monotonicMs()
        val totalMs = quotaMinutes * 60_000L
        synchronized(baselineLock) {
            clearPauseStateLocked()
            baselineServerNow = nowWall
            baselineRemainingMs = totalMs
            baselineElapsedRealtime = nowElapsed
        }
        val todayKey = usageRepository.dayKey(nowWall)
        _state.update { s ->
            s.copy(
                needsStartConfirm = false,
                active = true,
                remainingMs = totalMs,
                startEpoch = nowWall,
                pendingStartSync = true,
                paused = false,
                pauseSource = null,
                error = null
            )
        }
        runCatching {
            sessionPrefs.edit()
                .putString(KEY_DAY, todayKey)
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_REMAINING, totalMs)
                .putLong(KEY_SERVER_NOW, nowWall)
                .putLong(KEY_ELAPSED, nowElapsed)
                .putInt(KEY_QUOTA, quotaMinutes)
                .putInt(KEY_EXTENDED, 0)
                .putBoolean(KEY_TIME_CONTROL, true)
                .putBoolean(KEY_PENDING_START, true)
                .putBoolean(KEY_PAUSED, false)
                .putString(KEY_PAUSE_SOURCE, null)
                .putLong(KEY_PAUSE_STARTED_ELAPSED, 0L)
                .putString(KEY_PAUSE_REST_ID, null)
                .putLong(KEY_PAUSE_REST_MS, 0L)
                .commit()
        }.onFailure { Logger.w(TAG) { "persist local-start snapshot failed: ${it.message}" } }
    }

    /** 解绑 / 退出管控时清空会话状态，避免陈旧会话误导 UI 与锁定 */
    fun reset() {
        synchronized(baselineLock) {
            clearPauseStateLocked()
            baselineServerNow = 0L
            baselineRemainingMs = 0L
            baselineElapsedRealtime = 0L
        }
        _state.value = UiState()
        // 落盘副本必须一起清掉：解绑后换一个家庭重新绑定，若沿用上一家的"今日剩余"，
        // 新家长的额度会被旧家庭的进度直接吃掉（甚至一绑上就是"已用完"）。
        runCatching { sessionPrefs.edit().clear().commit() }
        // 若早期因会话到期锁过屏，这里把 DAILY_LIMIT 原因释放掉（新一轮 tick 也会由 syncAutoReasons 兜底）
        lockController.releaseLock(LockReason.DAILY_LIMIT)
        lockController.releaseLock(LockReason.TIMER_PAUSED)
    }

    /** persistPauseFields 的原子快照（见方法内注释：所有键必须同一时刻取） */
    private data class PersistFields(
        val started: Long,
        val src: TimerPauseSource?,
        val rest: PendingPauseRest?,
        val serverNow: Long,
        val elapsed: Long,
        val remaining: Long
    )

    /**
     * 暂停态与待补报记录落盘：进程被杀后不能"重启即自动恢复计时"绕过手动暂停。
     *
     * ## 为什么必须同时落盘 elapsed / serverNow（2026-11-18 修复）
     * [KEY_REMAINING] 是"此刻"的值（暂停时为冻结值，恢复后从冻结值继续走），
     * 而冷启动 [UsageSessionRestore.plan] 按
     * `max(nowElapsed - KEY_ELAPSED, nowWall - KEY_SERVER_NOW)` 算流逝量再减 remaining。
     * 若这两个时间键还停在 [applyDto] 的旧基线，流逝量会把整段暂停也算进去，
     * 与已冻结的 remaining 叠加 —— "暂停→恢复→离线被杀→冷启动"一次就多扣一段，
     * 临界点上还会误挂 DAILY_LIMIT。故 remaining 与它的时间基准必须同帧落盘。
     */
    private fun persistPauseFields() {
        val snapshot = synchronized(baselineLock) {
            // 先钉时间戳再取 remaining：remaining 的计量时刻只会晚于/等于这两个读数，
            // 冷启动算出的流逝量因此只会略大于真实值（多扣几毫秒，方向安全）。
            val nowMono = timeProvider.monotonicMs()
            val wallNow = timeProvider.now()
            PersistFields(
                started = pauseStartedElapsed,
                src = pauseSource,
                rest = pendingPauseRest,
                serverNow = wallNow,
                elapsed = nowMono,
                remaining = liveRemainingMsLocked()
            )
        }
        runCatching {
            sessionPrefs.edit()
                .putLong(KEY_REMAINING, snapshot.remaining)
                // 与 remaining 同帧的时间基准：冷启动续算的唯一依据
                .putLong(KEY_SERVER_NOW, snapshot.serverNow)
                .putLong(KEY_ELAPSED, snapshot.elapsed)
                .putBoolean(KEY_PAUSED, snapshot.started != 0L)
                .putString(KEY_PAUSE_SOURCE, snapshot.src?.name)
                .putLong(KEY_PAUSE_STARTED_ELAPSED, snapshot.started)
                .putString(KEY_PAUSE_REST_ID, snapshot.rest?.restId)
                .putLong(KEY_PAUSE_REST_MS, snapshot.rest?.restMs ?: 0L)
                .commit()
        }.onFailure { Logger.w(TAG) { "persist timer pause failed: ${it.message}" } }
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
        // 暂停中（或暂停额度补报尚未被服务端确认）时，服务端 DTO 的 remainingMs
        // 还没有把暂停段加回来；照写会让进程重启后把冻结值丢掉（"杀进程即绕过暂停"）。
        val effectiveRemaining = if (pauseStartedElapsed != 0L || pendingPauseRest != null) {
            liveRemainingMs()
        } else dto.remainingMs
        runCatching {
            sessionPrefs.edit()
                .putString(KEY_DAY, dto.dayKey)
                .putBoolean(KEY_ACTIVE, dto.active)
                .putLong(KEY_REMAINING, effectiveRemaining)
                .putLong(KEY_SERVER_NOW, dto.serverNow)
                .putLong(KEY_ELAPSED, timeProvider.monotonicMs())
                .putInt(KEY_QUOTA, dto.quotaMinutes)
                .putInt(KEY_EXTENDED, dto.extendedMinutes)
                .putBoolean(KEY_TIME_CONTROL, dto.timeControlEnabled)
                // 离线本地起表标志：重启后同日续算时据此继续补发 start()
                .putBoolean(KEY_PENDING_START, _state.value.pendingStartSync)
                // commit 而非 apply：这是离线判「当日总时长到期」的剩余额度唯一来源（见 persistSession 头部注释），
                // 必须同步落盘，否则进程恰在刷盘前被杀会读到上一轮（剩余更多）的快照，孩子凭一次精准崩溃可多获一拍配额。
                // 本方法只在低频状态转移（服务端 DTO 落地 / 冷启动恢复）时调用，不在每 tick 采样路径上，
                // commit 的同步写盘不会阻塞采样线程（与 R6 REMOTE 锁改 commit 原则一致）。
                .commit()
        }
    }

    /**
     * 冷启动恢复上次落盘的会话（构造期调用，早于任何一次网络请求）。
     *
     * 决策全部在纯函数 [UsageSessionRestore.plan] 里（那里穷举了同日续算 / 跨日重起表 /
     * 未激活 / 总开关关闭四种分支），本方法只负责"读偏好 → 应用计划 → 必要时立即回写"。
     *
     * 关键点：**跨日绝不能沿用昨天的剩余额度**（新的一天一开机就"已用完"），
     * 但也**不能什么都不做** —— 旧实现 `clear()` 后直接返回，状态停在默认值，
     * 离线时 `isExpired()` 恒为 false、「计时开始」浮层也不弹，当天总时长额度整天消失。
     * 现在改为按上次已知额度重新起表，并立刻把"今天这份"落盘，
     * 否则每次重启都会再按满额度重算（重启即续杯）。
     */
    private fun restorePersistedSession() {
        runCatching {
            val savedDay = sessionPrefs.getString(KEY_DAY, null) ?: return
            val nowElapsed = timeProvider.monotonicMs()
            val nowWall = timeProvider.now()
            // 两侧格式不同、口径相同：服务端下发 ISO（2026-11-04），
            // 本地 dayKey() 是紧凑格式（20261104），归一化在纯函数里做。
            val todayKey = usageRepository.dayKey(nowWall)
            val plan = UsageSessionRestore.plan(
                snapshot = UsageSessionRestore.Snapshot(
                    dayKey = savedDay,
                    active = sessionPrefs.getBoolean(KEY_ACTIVE, false),
                    remainingMs = sessionPrefs.getLong(KEY_REMAINING, 0L),
                    serverNow = sessionPrefs.getLong(KEY_SERVER_NOW, 0L),
                    savedElapsed = sessionPrefs.getLong(KEY_ELAPSED, 0L),
                    quotaMinutes = sessionPrefs.getInt(KEY_QUOTA, 0),
                    extendedMinutes = sessionPrefs.getInt(KEY_EXTENDED, 0),
                    timeControlEnabled = sessionPrefs.getBoolean(KEY_TIME_CONTROL, true),
                    paused = sessionPrefs.getBoolean(KEY_PAUSED, false),
                    pauseSource = sessionPrefs.getString(KEY_PAUSE_SOURCE, null),
                    pauseStartedElapsed = sessionPrefs.getLong(KEY_PAUSE_STARTED_ELAPSED, 0L)
                ),
                todayKey = todayKey,
                nowElapsed = nowElapsed,
                nowWall = nowWall
            ) ?: return

            val restoredPauseSource = plan.pauseSource?.let { raw ->
                runCatching { TimerPauseSource.valueOf(raw) }.getOrNull()
            }
            synchronized(baselineLock) {
                baselineServerNow = plan.serverNow
                baselineRemainingMs = plan.remainingMs
                baselineElapsedRealtime = nowElapsed
                pauseStartedElapsed = if (plan.paused) plan.pauseStartedElapsed else 0L
                pauseSource = if (plan.paused) restoredPauseSource else null
                // 待补报记录只在同一天有效：跨日后服务端会话已换日，旧 restId 会被拒绝。
                pendingPauseRest = if (plan.paused || plan.restamp) null else {
                    val id = sessionPrefs.getString(KEY_PAUSE_REST_ID, null)
                    val ms = sessionPrefs.getLong(KEY_PAUSE_REST_MS, 0L)
                    if (!id.isNullOrBlank() && ms > 0L) PendingPauseRest(id, ms) else null
                }
            }

            val defaults = UiState()
            _state.value = defaults.copy(
                // 恢复态不是"新的开始"，不弹确认浮层（与同日续算一致）
                needsStartConfirm = plan.needsStartConfirm,
                active = plan.active,
                serverNow = plan.serverNow,
                remainingMs = plan.remainingMs,
                countdownActive = plan.active && plan.remainingMs <= defaults.countdownThresholdMs,
                extendedMinutes = plan.extendedMinutes,
                quotaMinutes = plan.quotaMinutes + plan.extendedMinutes,
                // 起点未落盘（恢复态只需剩余量），留空不影响任何判定
                startEpoch = null,
                timeControlEnabled = plan.timeControlEnabled,
                // 离线本地起表 + 进程被杀：同日续算仍处于"已确认"态，重启后继续补发 start()
                pendingStartSync = plan.active && sessionPrefs.getBoolean(KEY_PENDING_START, false),
                paused = plan.paused,
                pauseSource = if (plan.paused) restoredPauseSource else null
            )
            if (plan.restamp) persistRestoredSession(plan, todayKey, nowElapsed)
            Logger.i(TAG) {
                "usage-session restored: savedDay=$savedDay today=$todayKey active=${plan.active} " +
                    "remainingMs=${plan.remainingMs} restamp=${plan.restamp}"
            }
        }
    }

    /**
     * 跨日重起表后立刻落盘。
     *
     * 不落盘的话，`KEY_DAY` 仍是昨天：本次进程内表现正常，但**下一次重启**
     * 又会命中"跨日"分支，再按满额度重算一遍 —— 等于"重启就能无限续杯"。
     */
    private fun persistRestoredSession(
        plan: UsageSessionRestore.Plan,
        todayKey: String,
        nowElapsed: Long
    ) {
        runCatching {
            sessionPrefs.edit()
                .putString(KEY_DAY, todayKey)
                .putBoolean(KEY_ACTIVE, plan.active)
                .putLong(KEY_REMAINING, plan.remainingMs)
                .putLong(KEY_SERVER_NOW, plan.serverNow)
                .putLong(KEY_ELAPSED, nowElapsed)
                .putInt(KEY_QUOTA, plan.quotaMinutes)
                .putInt(KEY_EXTENDED, plan.extendedMinutes)
                .putBoolean(KEY_TIME_CONTROL, plan.timeControlEnabled)
                .putBoolean(KEY_PAUSED, plan.paused)
                .putString(KEY_PAUSE_SOURCE, plan.pauseSource)
                .putLong(KEY_PAUSE_STARTED_ELAPSED, plan.pauseStartedElapsed)
                .putString(KEY_PAUSE_REST_ID, null)
                .putLong(KEY_PAUSE_REST_MS, 0L)
                .commit()
        }.onFailure { Logger.w(TAG) { "persist restored usage-session failed: ${it.message}" } }
    }

    /** 把服务端会话 DTO 落到本地状态与平滑倒计时基线 */
    private fun applyDto(dto: UsageSessionDto) {
        val s0 = _state.value
        // 离线本地起表（pendingStartSync）尚未被服务端知晓（dto.active=false）时：
        // 不覆盖回"待确认"，否则孩子的确认会被静默撤销（等待页闪回）；
        // 保留本地起表，由 refresh 的补发通道对齐服务端。
        // 例外：家长关闭「时间管控」（timeControlEnabled=false）——本地起表随之撤销。
        if (s0.pendingStartSync && s0.active && !dto.active && dto.timeControlEnabled) {
            Logger.i(TAG) { "usage-session local start pending, skip inactive dto (resync in flight)" }
            return
        }
        // 会话作废 / 家长关掉时间管控：暂停失去依据，必须立刻撤销（含锁与待补报记录），
        // 否则"关掉总开关"仍被 TIMER_PAUSED 锁住，或离线暂停残留到下一天。
        if (s0.paused && (!dto.active || !dto.timeControlEnabled)) discardPause()
        val pausedNow = _state.value.paused
        // 暂停中，或暂停额度已上报但服务端 DTO 尚未带上回补时，冻结本地剩余与基线：
        // 否则 15s 刷新会把剩余先打回"未回补"的旧值，出现倒计时倒跳、
        // 临界点上甚至瞬间挂 DAILY_LIMIT。补报确认后清标志，下一次刷新自然对齐权威值。
        val holdRemaining = pausedNow || pendingPauseRest != null
        synchronized(baselineLock) {
            baselineServerNow = dto.serverNow
            if (!holdRemaining) {
                baselineRemainingMs = dto.remainingMs
                // 与权威剩余同帧记录单调时钟读数（见 liveRemainingMs 注释：本地推进量不能用墙钟）
                baselineElapsedRealtime = timeProvider.monotonicMs()
            }
        }
        // 落盘一份快照：断网 / 进程被杀后仍能按"上次已知剩余"继续判到期（见 persistSession）
        val effectiveRemaining = if (holdRemaining) liveRemainingMs() else dto.remainingMs
        val countdownActive = dto.active && effectiveRemaining <= dto.countdownThresholdMs
        _state.update { s ->
            s.copy(
                // 只在该设备的"时间管控"确实开启时才要求确认开始计时。
                // 原实现只看 active，家长已关掉时间管控却仍弹"平板使用计时开始"，
                // 孩子被引导去确认一个不存在的配额。
                needsStartConfirm = !dto.active && dto.timeControlEnabled && !pausedNow,
                active = dto.active,
                serverNow = dto.serverNow,
                remainingMs = effectiveRemaining,
                countdownActive = countdownActive,
                countdownThresholdMs = dto.countdownThresholdMs,
                delayOptions = dto.delayOptions,
                maxDailyExtendMinutes = dto.maxDailyExtendMinutes,
                extendedMinutes = dto.extendedMinutes,
                pendingDelayMinutes = dto.pendingDelayMinutes,
                quotaMinutes = dto.quotaMinutes + dto.extendedMinutes,
                startEpoch = dto.startEpoch,
                timeControlEnabled = dto.timeControlEnabled,
                // 服务端已确认起表（active）或家长关掉管控时，离线起表的待同步标志作废
                pendingStartSync = if (dto.active || !dto.timeControlEnabled) false else s.pendingStartSync,
                error = null
            )
        }
        persistSession(dto)
        Logger.i(TAG) {
            "usage-session applied: active=${dto.active} remainingMs=$effectiveRemaining " +
                "quota=${dto.quotaMinutes} extended=${dto.extendedMinutes} countdown=$countdownActive " +
                "timeControl=${dto.timeControlEnabled} paused=$pausedNow held=$holdRemaining"
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

        /** 离线本地起表待同步服务端的标志（见 [UiState.pendingStartSync]） */
        private const val KEY_PENDING_START = "pending_start_sync"

        private const val KEY_PAUSED = "timer_paused"
        private const val KEY_PAUSE_SOURCE = "timer_pause_source"
        private const val KEY_PAUSE_STARTED_ELAPSED = "timer_pause_started_elapsed"
        private const val KEY_PAUSE_REST_ID = "timer_pause_rest_id"
        private const val KEY_PAUSE_REST_MS = "timer_pause_rest_ms"

        /** 暂停锁屏页的副文案；TIMER_PAUSED 由锁屏页专属主题渲染，这里只供其它原因并存时阅读 */
        private const val PAUSED_DETAIL = "计时已暂停，点「继续计时」后恢复"
    }
}
