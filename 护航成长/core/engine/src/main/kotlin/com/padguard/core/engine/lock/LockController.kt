package com.padguard.core.engine.lock

import android.annotation.SuppressLint
import android.content.Context
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 锁定态中枢。
 *
 * ## 为什么需要一个"中枢"
 * 会触发锁屏的来源有六个：时段锁机、未成年人模式宵禁、每日总时长耗尽、单应用限额耗尽、
 * 护眼强制休息、远程锁屏指令。
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
 *
 * ## REMOTE 锁定为什么必须持久化
 * [LockReason.REMOTE] 只能由远程指令置位、远程指令清除，而**指令是一次性的**：
 * 服务端在回执后即把锁屏指令收敛为终态，不会再补发。若 REMOTE 态只活在内存里，
 * 进程被回收（息屏后被 ROM 杀掉是常态）或设备重启后，锁定态凭空消失：
 * - 孩子端表现为"锁屏自己没了"——重启即成绕过手段；
 * - 更糟的是，压着系统 keyguard 的记账还在，家长随后点「解锁」时 App 侧早已
 *   "未锁定"，解锁链路会误判为"无需操作"，系统锁永远没人撤 —— 正是
 *   「家长反复解锁、孩子端始终黑屏」的隐蔽成因之一。
 * 因此 REMOTE 态落盘（[PREFS_NAME]）：置位即写、清除即删、进程启动即恢复。
 * 重启不该成为管控真空，家长解锁指令到达前设备保持锁定，是安全的失败方向。
 *
 * ## 为什么所有状态变更必须串行化
 *
 * 本类的调用方不止一个线程：策略引擎的自检循环（[com.padguard.core.engine.PolicyEngine.tick]）、
 * 无障碍服务回调线程、远程指令执行线程都会同时请求/释放锁定原因。
 * 而 `requestLock` / `releaseLock` 本质是**读—改—写**三步：
 * 读 `_state.value` → 算差集 → 写回。没有任何同步时，两个线程并发执行会互相覆盖，
 * 后写的一方把先写的一方的 `LockReason` 静默丢掉 —— `reasons` 因此变空、
 * `locked` 变 false，锁屏页被判定"未锁定"而收起，表现为**"家长明明锁了屏，孩子照常能用"**，
 * 且没有任何异常日志可查。
 *
 * 因此这里用 [stateLock] 把状态变更全部串行化；临时解锁的到期清理原本还藏在
 * `isTempUnlockActive()` 这个"看起来只是查询"的方法里，现在拆成显式的到期清算 +
 * 纯查询两步，均在锁内完成。
 */
@Singleton
class LockController @Inject constructor(
    private val timeProvider: TimeProvider,
    @ApplicationContext context: Context
) : LockControllerPort {

    private val remoteLockPrefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(LockState())
    val state: StateFlow<LockState> = _state.asStateFlow()

    /** 保护 [_state] 与三个临时解锁字段的读—改—写（见类注释「为什么所有状态变更必须串行化」） */
    private val stateLock = Any()

    init {
        synchronized(stateLock) { restoreRemoteLockLocked() }
    }

    /**
     * 进程启动时恢复持久化的 REMOTE 锁定。
     *
     * 在 init 里做而不是懒加载：任何收集者（LockScreenPresenter 的解锁分流、
     * GuardService 的周期自检）拿到 state 的第一帧就必须已经包含 REMOTE，
     * 否则会出现"启动瞬间判定未锁定 → 误触解锁链路"的竞态。
     *
     * 必须在持有 [stateLock] 时调用。
     */
    private fun restoreRemoteLockLocked() {
        if (!remoteLockPrefs.getBoolean(KEY_REMOTE_LOCKED, false)) return
        val detail = remoteLockPrefs.getString(KEY_REMOTE_DETAIL, "").orEmpty()
        val current = _state.value
        if (LockReason.REMOTE in current.reasons) return
        _state.value = current.copy(
            reasons = current.reasons + LockReason.REMOTE,
            details = current.details + (LockReason.REMOTE to detail)
        )
        Logger.i(TAG) { "remote lock restored after process restart (detail='$detail')" }
    }

    /**
     * 持久化 REMOTE 远端锁。
     *
     * 必须用 [SharedPreferences.Editor.commit] 而不是 [apply]：REMOTE 是一次性指令
     * （服务端回执后即收敛为终态、不再补发），落盘失败等于"锁凭空消失"。
     * [apply] 是异步写盘，若 [requestLock] 之后进程在后台写线程 flush 前被系统强杀/崩溃，
     * 重启后 [restoreRemoteLockLocked] 读不到 → REMOTE 丢失 → 孩子重启即绕过远端锁（fail-open 窄窗口）。
     * [commit] 同步落盘、保证返回前已写入；REMOTE 锁是低频一次性操作，同步写开销可忽略，
     * 且调用方（[CommandExecutor] 远程指令通道）本就不在主线程，不会造成 UI 卡顿。
     */
    @SuppressLint("ApplySharedPref") // 故意 commit：REMOTE 锁是低频一次性操作，须保证返回前已落盘，防重启绕过
    private fun persistRemoteLock(detail: String) {
        remoteLockPrefs.edit()
            .putBoolean(KEY_REMOTE_LOCKED, true)
            .putString(KEY_REMOTE_DETAIL, detail)
            .commit()
    }

    /**
     * 清除持久化的 REMOTE 远端锁。
     *
     * 同样用 [commit] 而非 [apply]：保证"家长已解锁"在进程死亡重启后仍被尊重，
     * 否则可能残留 REMOTE 导致"家长已解锁、孩子端仍锁"的误锁（虽 fail-closed，但解锁指令可重发，
     * 这里同步落盘让解锁结果确定可见）。
     */
    @SuppressLint("ApplySharedPref") // 故意 commit：保证"家长已解锁"在进程死亡重启后仍被尊重，不残留误锁
    private fun clearPersistedRemoteLock() {
        if (!remoteLockPrefs.getBoolean(KEY_REMOTE_LOCKED, false)) return
        remoteLockPrefs.edit().remove(KEY_REMOTE_LOCKED).remove(KEY_REMOTE_DETAIL).commit()
        Logger.i(TAG) { "persisted remote lock cleared" }
    }

    /** 临时解锁到期的单调时钟点；0 表示未处于临时解锁 */
    private var tempUnlockUntilElapsed: Long = 0L

    /** 临时解锁到期的墙钟点，用于重启后兜底判定 */
    private var tempUnlockUntilWall: Long = 0L

    /** 本次临时解锁放行的应用；空串表示整机放行。见 [tempUnlock] 的说明 */
    private var tempUnlockPackage: String = ""

    /**
     * 在持有 [stateLock] 时调用：若临时解锁已到期则就地清算。
     *
     * 单独成一步而不是塞进查询方法：早期实现把"顺手清状态"藏在 `isTempUnlockActive()` 里，
     * 一个看似只读的方法实际会写 `_state`，调用方完全无法预期，也让加锁边界说不清楚。
     */
    private fun expireTempUnlockIfNeededLocked() {
        if (tempUnlockUntilElapsed == 0L) return
        val elapsedExpired = timeProvider.elapsedRealtime() >= tempUnlockUntilElapsed
        val wallExpired = timeProvider.now() >= tempUnlockUntilWall
        if (elapsedExpired || wallExpired) {
            tempUnlockUntilElapsed = 0L
            tempUnlockUntilWall = 0L
            tempUnlockPackage = ""
            _state.value = _state.value.copy(tempUnlockUntilMillis = 0L)
            Logger.i(TAG) { "temp unlock expired (elapsed=$elapsedExpired, wall=$wallExpired)" }
        }
    }

    /** 纯查询：临时解锁是否覆盖该包名。必须在持有 [stateLock] 时调用，且已先做过到期清算。 */
    private fun isTempUnlockActiveLocked(packageName: String? = null): Boolean {
        if (tempUnlockUntilElapsed == 0L) return false
        // 整机放行（未指定包名）覆盖任何应用；指定了包名则只对那一个应用生效
        return tempUnlockPackage.isEmpty() || packageName == null || packageName == tempUnlockPackage
    }

    /**
     * 请求锁定。
     *
     * @param reason 锁定来源
     * @param untilMillis 预计解锁的墙钟时间戳，用于 UI 倒计时；0 表示无明确期限
     * @param detail 展示给孩子的补充说明（如"今日剩余时长已用完"）
     */
    override fun requestLock(reason: LockReason, untilMillis: Long, detail: String) {
        var applied = false
        synchronized(stateLock) {
            expireTempUnlockIfNeededLocked()
            // 临时解锁期内屏蔽除"远程强制锁屏"和"暂停计时"之外的所有自动锁定，
            // 否则家长刚放行 30 分钟就被时段规则立刻锁回去，等于放行无效。
            // TIMER_PAUSED 是本次功能的硬承诺：暂停期间必须不可操作，家长误批一次
            // 解锁申请不能让孩子在停止计时的状态下继续玩（与 REMOTE 同级豁免）。
            if (reason != LockReason.REMOTE && reason != LockReason.TIMER_PAUSED &&
                isTempUnlockActiveLocked()
            ) {
                Logger.d(TAG) { "lock($reason) suppressed by temp unlock" }
            } else {
                val current = _state.value
                if (reason !in current.reasons || current.details[reason] != detail) {
                    val reasons = current.reasons + reason
                    val details = current.details + (reason to detail)
                    val untils =
                        if (untilMillis > 0) current.untilMillis + (reason to untilMillis)
                        else current.untilMillis - reason
                    _state.value = LockState(reasons, details, untils)
                    applied = true
                }
            }
        }
        if (!applied) return
        // REMOTE 置位即落盘：指令是一次性的，不回写的话进程一死锁定态就凭空消失。
        // 放在锁外：SharedPreferences 写入是 IO，不应拖长临界区。
        if (reason == LockReason.REMOTE) persistRemoteLock(detail)
        Logger.i(TAG) { "lock requested: $reason, until=$untilMillis, reasons=${_state.value.reasons}" }
    }

    /** 解除某一来源的锁定；其余来源仍生效时设备保持锁定 */
    fun releaseLock(reason: LockReason) {
        val removed = synchronized(stateLock) {
            val current = _state.value
            if (reason !in current.reasons) {
                false
            } else {
                _state.value = LockState(
                    reasons = current.reasons - reason,
                    details = current.details - reason,
                    untilMillis = current.untilMillis - reason
                )
                true
            }
        }
        if (!removed) return
        if (reason == LockReason.REMOTE) clearPersistedRemoteLock()
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
    override fun releaseAll() {
        val had = synchronized(stateLock) {
            val current = _state.value
            if (current.reasons.isEmpty()) {
                false
            } else {
                // 清空锁定原因，但保留"仍在临时解锁中"的展示字段，避免 UI 倒计时被无谓打断
                _state.value = LockState(tempUnlockUntilMillis = current.tempUnlockUntilMillis)
                true
            }
        }
        if (!had) return
        clearPersistedRemoteLock()
        Logger.i(TAG) { "all locks released" }
    }

    /**
     * 临时解锁指定时长。
     *
     * 会立刻清空当前锁定态，并在到期前抑制自动锁定。到期后不需要显式恢复：
     * [isTempUnlockActive] 返回 false 后，下一轮自检会把仍成立的原因重新锁上。
     *
     * @param packageName 家长放行的是**哪个应用**；空表示整台设备放行。
     *
     * ## 为什么必须带上包名
     * 孩子端最常见的申请入口是「应用拦截页」（单应用时长/次数用完），
     * 而不是整机锁屏页。家长审批通过的是"再玩 30 分钟这一个应用"。
     * 如果临时解锁只作用于整机锁定态，拦截页会在下一次 15 秒采样时立刻重新弹出 ——
     * 家长明明点了同意、孩子那边纹丝不动，正是"同意放行后毫无反应"的直接成因。
     * 因此放行范围必须跟着包名走，由 [isTempUnlockActive] 同时供限额判定与挂起恢复使用。
     */
    override fun tempUnlock(durationMinutes: Int, packageName: String) {
        val minutes = durationMinutes.coerceIn(1, MAX_TEMP_UNLOCK_MINUTES)
        val durationMs = minutes * 60_000L
        synchronized(stateLock) {
            tempUnlockUntilElapsed = timeProvider.elapsedRealtime() + durationMs
            tempUnlockUntilWall = timeProvider.now() + durationMs
            tempUnlockPackage = packageName.trim()
            _state.value = LockState(tempUnlockUntilMillis = tempUnlockUntilWall)
        }
        // 临时解锁即放行：REMOTE 锁定态一并清除（家长主动放行优先于一切锁定）
        clearPersistedRemoteLock()
        Logger.i(TAG) { "temp unlock for $minutes min, pkg='${packageName.trim()}', until=${tempUnlockUntilWall()}" }
    }

    /** 临时解锁到期墙钟点（供日志/展示读取，需在锁内取以免读到撕裂值） */
    private fun tempUnlockUntilWall(): Long = synchronized(stateLock) { tempUnlockUntilWall }

    override fun cancelTempUnlock() {
        val cancelled = synchronized(stateLock) {
            if (tempUnlockUntilElapsed == 0L) {
                false
            } else {
                tempUnlockUntilElapsed = 0L
                tempUnlockUntilWall = 0L
                tempUnlockPackage = ""
                _state.value = _state.value.copy(tempUnlockUntilMillis = 0L)
                true
            }
        }
        if (cancelled) Logger.i(TAG) { "temp unlock cancelled" }
    }

    /**
     * 本轮放行覆盖到的包名；空串表示整机放行（对所有应用生效）。
     *
     * 注意只在 [isTempUnlockActive] 判定为**有效期内**时才有意义，取用前必须先判定。
     */
    fun tempUnlockTarget(): String = synchronized(stateLock) { tempUnlockPackage }

    /**
     * 临时解锁是否仍在有效期内。
     *
     * 双时钟判定：单调时钟为主（防改时间），墙钟为辅（防重启后单调时钟归零导致的"永久解锁"）。
     * 两者任一判定为过期即视为过期 —— 宁可提前恢复管控，也不能出现管控真空。
     *
     * 本方法会在锁内先清算已到期的临时解锁，再返回纯查询结果。
     */
    fun isTempUnlockActive(packageName: String? = null): Boolean = synchronized(stateLock) {
        expireTempUnlockIfNeededLocked()
        isTempUnlockActiveLocked(packageName)
    }

    /** 重启后调用：单调时钟已归零，临时解锁一律失效，避免出现管控真空 */
    fun onDeviceBoot() {
        synchronized(stateLock) {
            tempUnlockUntilElapsed = 0L
            tempUnlockUntilWall = 0L
            tempUnlockPackage = ""
            _state.value = LockState()
            // 重启不应成为管控真空：持久化的 REMOTE 锁定在开机后继续生效，
            // 直到家长解锁指令到达（自动锁定原因由策略自检重新派生）。
            restoreRemoteLockLocked()
        }
        Logger.i(TAG) { "state reset after boot" }
    }

    companion object {
        private const val TAG = "LockController"

        /** REMOTE 锁定态的持久化文件（见类注释「REMOTE 锁定为什么必须持久化」） */
        private const val PREFS_NAME = "padguard_remote_lock"
        private const val KEY_REMOTE_LOCKED = "remote_locked"
        private const val KEY_REMOTE_DETAIL = "remote_detail"

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

    /** 优先级：远程 > 时段 > 宵禁 > 定时锁机 > 暂停计时 > 每日总时长 > 等待开始 > 单应用限额 > 护眼 */
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

    /**
     * 未成年人模式夜间宵禁（指南：每日 22 时至次日 6 时不向未成年人提供服务）。
     *
     * 单独成一个原因而不是并入 [SCHEDULE]，是因为两者的解除条件不同：
     * 时段锁由家长配置，宵禁由分龄档位自动派生，且只允许**家长豁免**（[LockController.tempUnlock]）
     * 临时压过，孩子端不能自行关闭。混在一起会导致家长在控制端关掉"时段管控"时
     * 把合规强制项一起关掉。
     */
    CURFEW,

    /**
     * 定时锁机（家长端「上网管控 → 定时锁机」独立开关）。
     *
     * 与 [SCHEDULE]（时段规则表）分开：两者在家长端是互不相关的两个 UI，
     * 合成一个原因会让其中一个改动另一个的数据源，也会让孩子说不清"为什么被锁了"。
     */
    SMART_SHUTDOWN,

    /**
     * 暂停计时（熄屏自动暂停 / 孩子长按悬浮球确认暂停）。
     *
     * 与 [AWAITING_START] 同型：未恢复计时前设备不可操作，恢复后由
     * [com.padguard.core.engine.PolicyEngine.tick] 每轮差集自动释放。
     *
     * 与 [AWAITING_START] 的不同点：**临时解锁不能压过它**。
     * 暂停期间"平板不可操作"是本次功能的硬承诺；家长误批一次解锁申请
     * 或远程下发 UNLOCK，都不能把暂停态变成可用态（否则孩子只需申请一次解锁
     * 即可在停止计时的状态下继续玩）。恢复的唯一出口是会话 `resume()`。
     */
    TIMER_PAUSED,

    /** 每日总时长耗尽 */
    DAILY_LIMIT,

    /**
     * 新的一天尚未确认「开始计时」。
     *
     * 与 [DAILY_LIMIT] 的区别：这不是"用完了"，而是"还没开始"。日切后本地重置会话，
     * 在孩子点「开始计时」确认（或离线本地起表）之前，设备保持等待页 ——
     * 未确认期间不计时、不消耗额度，但也不能自由使用（否则"一直不确认"就是绕过）。
     *
     * 解除条件只有一个：会话转 active（确认成功）。因此它由
     * [com.padguard.core.engine.PolicyEngine.tick] 按会话状态每轮重派生，
     * 家长 UNLOCK / TEMP_UNLOCK 暂时压过后，到期/下一轮会重新挂上 ——
     * 这是刻意行为：等待开始不是故障态，不能被"解锁指令"永久消掉。
     */
    AWAITING_START,

    /** 单应用限额耗尽 */
    APP_LIMIT,

    /** 护眼强制休息 */
    EYE_CARE
}

/** 一条锁定原因的展示信息 */
data class LockDetail(val text: String, val untilMillis: Long = 0L)
