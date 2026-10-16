package com.padguard.child.usagesession

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.transport.http.CredentialStore
import com.padguard.core.engine.lock.LockController
import com.padguard.core.engine.lock.LockDetail
import com.padguard.core.engine.lock.LockReason
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.http.ApiResult
import com.padguard.core.transport.http.UsageSessionDto
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
    private val credentials: CredentialStore
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
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 最近一次刷新得到的基线，用于本地平滑倒计时 */
    private var baselineServerNow: Long = 0L
    private var baselineRemainingMs: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()

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

    /** 本地平滑倒计时：在 [now] 时刻的实时剩余（毫秒），永不为负 */
    fun liveRemainingMs(now: Long): Long =
        maxOf(0L, baselineRemainingMs - (now - baselineServerNow))

    /** 计时已激活且实时剩余已耗尽（用于 PolicyEngine 统一挂 DAILY_LIMIT） */
    fun isExpired(): Boolean {
        val s = _state.value
        if (!s.active) return false
        return liveRemainingMs(timeProvider.now()) <= 0L
    }

    /** 解绑 / 退出管控时清空会话状态，避免陈旧会话误导 UI 与锁定 */
    fun reset() {
        baselineServerNow = 0L
        baselineRemainingMs = 0L
        _state.value = UiState()
        // 若早期因会话到期锁过屏，这里把 DAILY_LIMIT 原因释放掉（新一轮 tick 也会由 syncAutoReasons 兜底）
        lockController.releaseLock(LockReason.DAILY_LIMIT)
    }

    /** 把服务端会话 DTO 落到本地状态与平滑倒计时基线 */
    private fun applyDto(dto: UsageSessionDto) {
        baselineServerNow = dto.serverNow
        baselineRemainingMs = dto.remainingMs
        val countdownActive = dto.active && dto.remainingMs <= dto.countdownThresholdMs
        _state.update { s ->
            s.copy(
                needsStartConfirm = !dto.active,
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
                error = null
            )
        }
        Logger.i(TAG) {
            "usage-session applied: active=${dto.active} remainingMs=${dto.remainingMs} " +
                "quota=${dto.quotaMinutes} extended=${dto.extendedMinutes} countdown=$countdownActive"
        }
    }

    companion object {
        private const val TAG = "UsageSessionMgr"
    }
}
