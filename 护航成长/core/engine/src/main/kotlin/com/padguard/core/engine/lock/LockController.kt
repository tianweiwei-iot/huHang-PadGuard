package com.padguard.core.engine.lock

import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 锁定态中枢。
 *
 * ## 为什么需要一个"中枢"
 * 会触发锁屏的来源有五个：时段锁机、每日总时长耗尽、单应用限额耗尽、护眼强制休息、远程锁屏指令。
 * 如果每个来源各自去调 `lockNow()`，会立刻出现三类难以定位的问题：
 *
 * 1. **误解锁**：护眼休息结束时调"解锁"，把仍在生效的就寝时段锁一起解掉；
 * 2. **反复锁屏**：两个来源同时命中，用户刚点亮屏幕就被连锁两次，体验上像死机；
 * 3. **状态不可见**：孩子看到锁屏页却不知道为什么被锁，家长也无从解释。
 *
 * 因此这里用**原因集合**（[LockState.reasons]）而不是布尔值：任一原因存在即锁定，
 * 全部原因清空才解锁，锁屏页展示优先级最高的那条原因文案。
 *
 * ## 临时解锁的时钟选择
 * "临时解锁 30 分钟"必须用 [TimeProvider.elapsedRealtime] 计时。
 * 若用墙钟，孩子把系统时间往前调 30 分钟就能续期 —— 而这恰恰是最容易被想到的绕过方式。
 * 单调时钟在重启后归零，所以这里额外记录墙钟到期点做双重校验：
 * 重启后单调时钟不可比，退回墙钟判定（重启同样是一种代价，且重启会触发策略重新拉取）。
 */
@Singleton
class LockController @Inject constructor(
    private val timeProvider: TimeProvider
) {

    private val _state = MutableStateFlow(LockState())
    val state: StateFlow<LockState> = _state.asStateFlow()

    /** 临时解锁到期的单调时钟点；0 表示未处于临时解锁 */
    private var tempUnlockUntilElapsed: Long = 0L

    /** 临时解锁到期的墙钟点，用于重启后兜底判定 */
    private var tempUnlockUntilWall: Long = 0L

    /**
     * 请求锁定。
     *
     * @param reason 锁定来源
     * @param untilMillis 预计解锁的墙钟时间戳，用于 UI 倒计时；0 表示无明确期限
     * @param detail 展示给孩子的补充说明（如"今日剩余时长已用完"）
     */
    fun requestLock(reason: LockReason, untilMillis: Long = 0L, detail: String = "") {
        // 临时解锁期内屏蔽除远程强制锁屏外的所有自动锁定，
        // 否则家长刚放行 30 分钟就被时段规则立刻锁回去，等于放行无效。
        if (reason != LockReason.REMOTE && isTempUnlockActive()) {
            Logger.d(TAG) { "lock($reason) suppressed by temp unlock" }
            return
        }
        val current = _state.value
        if (reason in current.reasons && current.details[reason] == detail) return

        val reasons = current.reasons + reason
        val details = current.details + (reason to detail)
        val untils = if (untilMillis > 0) current.untilMillis + (reason to untilMillis) else current.untilMillis - reason
        _state.value = LockState(reasons, details, untils)
        Logger.i(TAG) { "lock requested: $reason, until=$untilMillis, reasons=$reasons" }
    }

    /** 解除某一来源的锁定；其余来源仍生效时设备保持锁定 */
    fun releaseLock(reason: LockReason) {
        val current = _state.value
        if (reason !in current.reasons) return
        _state.value = LockState(
            reasons = current.reasons - reason,
            details = current.details - reason,
            untilMillis = current.untilMillis - reason
        )
        Logger.i(TAG) { "lock released: $reason, remaining=${_state.value.reasons}" }
    }

    /**
     * 按"本轮判定结果"整体同步自动锁定原因。
     *
     * 供周期性自检使用：一次性给出当前应该生效的自动锁定集合，
     * 由本方法计算差集完成增删 —— 避免调用方漏调 release 导致锁定残留。
     * [LockReason.REMOTE] 不参与同步：远程锁屏只能由远程解锁指令解除。
     */
    fun syncAutoReasons(active: Map<LockReason, LockDetail>) {
        val autoReasons = LockReason.entries.filter { it != LockReason.REMOTE }
        autoReasons.forEach { reason ->
            val detail = active[reason]
            if (detail != null) {
                requestLock(reason, detail.untilMillis, detail.text)
            } else {
                releaseLock(reason)
            }
        }
    }

    /** 远程解锁：清空所有锁定原因。自动原因若仍成立，下一轮自检会重新锁上。 */
    fun releaseAll() {
        if (_state.value.reasons.isEmpty()) return
        _state.value = LockState()
        Logger.i(TAG) { "all locks released" }
    }

    /**
     * 临时解锁指定时长。
     *
     * 会立刻清空当前锁定态，并在到期前抑制自动锁定。到期后不需要显式恢复：
     * [isTempUnlockActive] 返回 false 后，下一轮自检会把仍成立的原因重新锁上。
     */
    fun tempUnlock(durationMinutes: Int) {
        val minutes = durationMinutes.coerceIn(1, MAX_TEMP_UNLOCK_MINUTES)
        val durationMs = minutes * 60_000L
        tempUnlockUntilElapsed = timeProvider.elapsedRealtime() + durationMs
        tempUnlockUntilWall = timeProvider.now() + durationMs
        _state.value = LockState(tempUnlockUntilMillis = tempUnlockUntilWall)
        Logger.i(TAG) { "temp unlock for $minutes min, until=$tempUnlockUntilWall" }
    }

    fun cancelTempUnlock() {
        if (tempUnlockUntilElapsed == 0L) return
        tempUnlockUntilElapsed = 0L
        tempUnlockUntilWall = 0L
        _state.value = _state.value.copy(tempUnlockUntilMillis = 0L)
        Logger.i(TAG) { "temp unlock cancelled" }
    }

    /**
     * 临时解锁是否仍在有效期内。
     *
     * 双时钟判定：单调时钟为主（防改时间），墙钟为辅（防重启后单调时钟归零导致的"永久解锁"）。
     * 两者任一判定为过期即视为过期 —— 宁可提前恢复管控，也不能出现管控真空。
     */
    fun isTempUnlockActive(): Boolean {
        if (tempUnlockUntilElapsed == 0L) return false
        val elapsedExpired = timeProvider.elapsedRealtime() >= tempUnlockUntilElapsed
        val wallExpired = timeProvider.now() >= tempUnlockUntilWall
        if (elapsedExpired || wallExpired) {
            tempUnlockUntilElapsed = 0L
            tempUnlockUntilWall = 0L
            _state.value = _state.value.copy(tempUnlockUntilMillis = 0L)
            Logger.i(TAG) { "temp unlock expired (elapsed=$elapsedExpired, wall=$wallExpired)" }
            return false
        }
        return true
    }

    /** 重启后调用：单调时钟已归零，临时解锁一律失效，避免出现管控真空 */
    fun onDeviceBoot() {
        tempUnlockUntilElapsed = 0L
        tempUnlockUntilWall = 0L
        _state.value = LockState()
        Logger.i(TAG) { "state reset after boot" }
    }

    companion object {
        private const val TAG = "LockController"

        /** 单次临时解锁上限 8 小时，防止一次误操作让管控长期失效 */
        const val MAX_TEMP_UNLOCK_MINUTES = 480
    }
}

/**
 * 锁定态快照。
 *
 * [reasons] 为空即为解锁态。UI 取 [primaryReason] 展示主文案，
 * 其余原因在"详情"里列出，让家长能一眼看清"到底是几条规则同时命中"。
 */
data class LockState(
    val reasons: Set<LockReason> = emptySet(),
    val details: Map<LockReason, String> = emptyMap(),
    val untilMillis: Map<LockReason, Long> = emptyMap(),
    /** 临时解锁到期墙钟时间戳，0 表示不在临时解锁中 */
    val tempUnlockUntilMillis: Long = 0L
) {
    val locked: Boolean get() = reasons.isNotEmpty()

    /** 优先级：远程 > 时段 > 每日总时长 > 单应用限额 > 护眼 */
    val primaryReason: LockReason?
        get() = LockReason.entries.firstOrNull { it in reasons }

    val primaryDetail: String
        get() = primaryReason?.let { details[it] }.orEmpty()

    /** 主原因的预计解锁时间，用于倒计时；0 表示无明确期限 */
    val primaryUntilMillis: Long
        get() = primaryReason?.let { untilMillis[it] } ?: 0L
}

/** 声明顺序即优先级顺序，[LockState.primaryReason] 依赖此顺序 */
enum class LockReason {
    /** 远程锁屏指令，只能由远程解锁 */
    REMOTE,

    /** 时段锁机（就寝、上课） */
    SCHEDULE,

    /** 每日总时长耗尽 */
    DAILY_LIMIT,

    /** 单应用限额耗尽 */
    APP_LIMIT,

    /** 护眼强制休息 */
    EYE_CARE
}

/** 一条锁定原因的展示信息 */
data class LockDetail(val text: String, val untilMillis: Long = 0L)
