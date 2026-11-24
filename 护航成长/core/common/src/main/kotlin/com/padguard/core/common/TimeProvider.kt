package com.padguard.core.common

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 受信时间护栏的一次落盘快照（R-P0-1 冷启动兜底）。
 *
 * 三个字段必须取自**同一时刻**（由 [TimeProvider.exportTrustedBaseline] 保证）：
 * - [trustedMs]：该时刻的受信时间，也是恢复后护栏的锚点基准；
 * - [monoMs]：该时刻的单调时钟读数，用于计算"从落盘到现在真实流逝了多久"；
 * - [wallMs]：该时刻的**裸墙钟**（不含服务端 offset），仅在设备真重启、
 *   单调时钟归零后作为唯一的流逝估计源；取裸值是为了让两次差值与 offset 无关。
 *
 * [wallMs] / [monoMs] 为 0 / -1 表示旧版本盘上只有 [trustedMs]（无恢复上下文），
 * 此时只能退化为"直接恢复锚点"，见 [TimeProvider.restoreTrustedBaseline]。
 */
data class TrustedBaseline(
    val trustedMs: Long,
    val wallMs: Long = 0L,
    val monoMs: Long = -1L,
) {
    /** 是否具备补偿"停机期间真实流逝"所需的完整上下文 */
    fun hasResumeContext(): Boolean = trustedMs > 0L && wallMs > 0L && monoMs >= 0L
}

/**
 * 防时间篡改的计时基准。
 *
 * 设计要点（对应说明书「防时间篡改落地逻辑」）：
 * 1. 所有时长统计一律基于 [android.os.SystemClock.elapsedRealtime] —— 该时钟单调递增，
 *    包含深度睡眠时间，且**无法通过修改系统时间伪造**，是本地计时的唯一可信来源。
 * 2. [android.os.SystemClock.uptimeMillis] 不含深度睡眠，用于「屏幕亮起时长」类统计。
 * 3. 系统墙钟时间（System.currentTimeMillis）仅用于日志展示与上报，
 *    通过 [serverOffsetMs] 与服务端标准时间对齐；一旦检测到用户改时间，
 *    墙钟会跳变，但 [elapsedRealtime] 不受影响，因此时长管控无法被绕过。
 */
@Singleton
class TimeProvider @Inject constructor() {

    private val _serverOffsetMs = MutableStateFlow(0L)
    /** 服务端标准时间 = 本地墙钟 + offset */
    val serverOffsetMs: StateFlow<Long> = _serverOffsetMs.asStateFlow()

    /** 单调递增时钟（含休眠），时长统计与时段判定的唯一基准 */
    fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    /** 单调递增时钟（不含深度休眠），用于亮屏时长统计 */
    fun uptimeMillis(): Long = SystemClock.uptimeMillis()

    /** 设备本次开机至今的运行时长 */
    fun sinceBootMs(): Long = SystemClock.elapsedRealtime()

    // ----------------------------------------------------------------------
    // 对时样本窗口（加固）
    //
    // 早期实现是"每个响应直接覆盖 offset"。HTTP 响应到达时刻受网络排队、
    // 长轮询挂起、GC 停顿影响，单次样本本身就带 ±几百毫秒噪声；
    // 更糟的是一次偶发的长延迟（如长轮询被服务端唤醒得晚）会让 offset 瞬间跳变
    // 几百毫秒到数秒，直接落在宵禁 / 时段管控的判定边界上，
    // 表现为"刚好 22:00 时锁机时快时慢"。
    //
    // 因此改为：样本进窗口 → 取中位数抗离群 → 双速收敛写入。
    // ----------------------------------------------------------------------

    private val lock = Any()
    private val samples = ArrayDeque<Long>(SAMPLE_WINDOW)
    private var lastJitterMs = 0L
    @Volatile private var lastSyncNanos = -1L
    @Volatile private var sampleCount = 0L
    @Volatile private var rejectedCount = 0L

    /** 最近一个窗口内样本的最大离散度（ms），用于可观测性与自诊断 */
    fun jitterMs(): Long = synchronized(lock) { lastJitterMs }

    /**
     * 距离上次成功对时经过的**单调**时长（ms）。
     *
     * 用 [System.nanoTime] 而不是 [SystemClock.elapsedRealtime]：本类跑在纯 JVM 单元测试里，
     * `SystemClock` 是 android.jar 的 stub，一调用就抛 "Stub!"；
     * 而 nanoTime 是标准 JDK，在 Android 与 JVM 上都是单调递增且不受改系统时间影响，
     * 对"多久没对上时"这种相对度量完全够用。
     */
    fun sinceLastSyncMs(): Long {
        val base = lastSyncNanos
        if (base < 0) return Long.MAX_VALUE
        return (System.nanoTime() - base) / 1_000_000L
    }

    /** 对时是否已陈旧（超过 [STALE_AFTER_MS] 未拿到新样本） */
    fun isStale(): Boolean = sinceLastSyncMs() > STALE_AFTER_MS

    /**
     * 最近一次成功对时样本的 nanoTime 标记；-1 表示从未对时成功。
     *
     * 供 [com.padguard.core.engine.guard.TamperDetector] 的时钟篡改判定使用：
     * 「墙钟跳变之后是否出现过新的服务端对时样本」是区分**合法校时**（NTP/运营商把不准的钟
     * 拨正，服务器会证明墙钟与真实时间一致）与**人为篡改**（服务器证明墙钟偏离真实时间）的
     * 唯一本地依据；离线（无新样本）时无法自证清白，必须按 fail-closed 处置。
     * 用 nanoTime 而非墙钟：被比较的检测时刻可能晚于墙钟被篡改的时间点，墙钟本身不可信。
     */
    fun lastSyncMarkNanos(): Long = lastSyncNanos

    /** 已接受 / 已拒收的样本计数，排查"为什么 offset 不动"时直接看这个 */
    fun stats(): String = "samples=$sampleCount rejected=$rejectedCount jitter=${jitterMs()}ms age=${if (lastSyncNanos < 0) "never" else "${sinceLastSyncMs()}ms"}"

    /**
     * 冷启动时注入上一次持久化的 offset，让首帧就能拿到接近正确的服务端时间。
     *
     * 不注入也能工作（首个样本到达时会走"快速收敛"全量覆盖），
     * 但那样开机后的头一两次时段判定会落在本地墙钟上 —— 若平板时钟本身不准，
     * 这一两秒的窗口里宵禁判定可能出错。注入只是把窗口缩到最短。
     */
    fun seedOffset(offsetMs: Long) {
        if (offsetMs == 0L) return
        // 持久化的 offset 来自「同一台设备上一次会话」，可信范围应远小于实时样本阈值：
        // 限制到 ±2h，避免设备被改到离谱时间后把上次的偏移原样注入，进而把日切 / 周末判定
        // 整体带偏一整天（表现为「学生端日用量与家长端对不上」）。
        // 真正的 ±24h 脏样本拒收仍由 [syncServerTime] 兜底。
        if (offsetMs !in -MAX_SEED_OFFSET_MS..MAX_SEED_OFFSET_MS) return
        synchronized(lock) {
            if (sampleCount > 0) return   // 已有真实样本，持久值不再覆盖
            _serverOffsetMs.value = offsetMs
        }
        Logger.d("TimeProvider") { "offset seeded from disk: ${offsetMs}ms" }
    }

    /** 当前 offset 原值，供上层持久化 */
    fun currentOffsetMs(): Long = _serverOffsetMs.value

    /**
     * 当前受信时间锚点（UTC 毫秒）；-1 表示尚未建立。
     *
     * 保留给诊断与旧调用方；**持久化请改用 [exportTrustedBaseline]** —— 单独一个
     * trustedMs 无法还原"从落盘到现在流逝了多久"，恢复时会把时间冻结在关机时刻。
     */
    fun currentTrustedMs(): Long = synchronized(clockLock) { anchorTrustedMs }

    /**
     * 导出一份可安全跨重启恢复的护栏快照，三个字段取自同一时刻。
     *
     * 这里刻意先走一次 [observe] 把锚点推进到"此刻"再取值：护栏内部
     * (anchorTrustedMs, anchorMonoMs) 本就是配对的，落盘瞬间的裸墙钟与单调读数
     * 必须与它对应同一个瞬间，否则恢复时算出的流逝量会带上一个 tick 的系统误差。
     */
    fun exportTrustedBaseline(): TrustedBaseline {
        val wall = System.currentTimeMillis()
        val mono = monotonicMs()
        val trusted = observe(wall + _serverOffsetMs.value, mono)
        return TrustedBaseline(trustedMs = trusted, wallMs = wall, monoMs = mono)
    }

    /**
     * 用上一次落盘的护栏快照恢复时间篡改基线。
     *
     * 与 [seedOffset] 只恢复 offset 不同，这里恢复的是护栏的**锚点本身**：
     * 否则进程重启后首帧 [observe] 因锚点为 -1 直接返回裸墙钟，孩子改系统时间即可
     * 在冷启动窗口绕过时段锁 / 宵禁 / 定时锁机（违反"离线时间管控必须本地独立生效"红线）。
     *
     * ## 为什么必须补偿"停机期间的真实流逝"
     * 只把 [TrustedBaseline.trustedMs] 原样设回锚点，等于把时间**冻结在关机那一刻**：
     * 昨晚 23:00 关机（宵禁中）、今早 08:00 开机，护栏仍认为是 23:00 → 白天继续锁机；
     * 日切、每日额度、时段边界全部落在错误的时间点上，联网对时之前一直错。
     * 更隐蔽的是它会屏蔽 [observe] 里"单调时钟归零 → 以墙钟重建锚点"的原有兜底，
     * 于是连那层自愈也一起失效。补偿逻辑见 [resumedTrustedMs]。
     *
     * 恢复之后护栏依然完整：第一帧起受信时间 = 补偿后的锚点 + 真实单调增量，
     * 墙钟怎么跳都被 [observe] 钳制，离线管控不依赖服务器对时。
     *
     * 守卫：只在锚点尚未建立（本会话从未对时 / 从未 observe）时生效；
     * 一旦会话内已建立锚点（对时或被 observe 初始化），迟到的恢复值不得覆盖。
     */
    fun restoreTrustedBaseline(baseline: TrustedBaseline): Boolean =
        restoreTrustedBaseline(baseline, System.currentTimeMillis(), monotonicMs())

    /** [restoreTrustedBaseline] 的可注入测试缝：墙钟与单调读数由调用方给定。 */
    internal fun restoreTrustedBaseline(
        baseline: TrustedBaseline,
        wallNowMs: Long,
        monoNowMs: Long,
    ): Boolean {
        if (baseline.trustedMs <= 0L) return false
        // 旧版盘只有 trustedMs，没有 wall/mono 上下文：无法安全估计流逝量，
        // 保持旧行为（直接恢复锚点）；本地首个 tick 写出三元组后即自动升级。
        val resumed = if (baseline.hasResumeContext()) {
            resumedTrustedMs(baseline, wallNowMs, monoNowMs)
        } else {
            baseline.trustedMs
        }
        if (resumed <= 0L) return false
        synchronized(clockLock) {
            if (anchorTrustedMs >= 0L) return false   // 本会话已有锚点，不覆盖
            anchorTrustedMs = resumed
            anchorMonoMs = monoNowMs
        }
        Logger.d("TimeProvider") {
            "trusted baseline restored: persisted=${baseline.trustedMs} resumed=$resumed " +
                "via=${if (baseline.hasResumeContext()) "elapsed-compensated" else "legacy-anchor-only"}"
        }
        return true
    }

    /**
     * 旧版磁盘格式兼容入口：只有受信时刻、没有流逝上下文时只恢复锚点。
     * 新代码一律传 [TrustedBaseline]，本重载仅用于存量升级与历史用例。
     */
    fun restoreTrustedBaseline(trustedMs: Long) {
        // mono 基准必须取真实当前读数：否则后续 observe 算 projected 时会把
        // "锚点单调值 0 → 现在"整段当成流逝，受信时间瞬间跳到几十年后。
        restoreTrustedBaseline(
            TrustedBaseline(trustedMs = trustedMs),
            System.currentTimeMillis(),
            monotonicMs(),
        )
    }

    /**
     * 从落盘快照推算"此刻应有的受信时间"——纯函数，便于离线单测。
     *
     * 三条路径的优先级是安全取舍，不是随意排序：
     * 1. **单调时钟坐标系连续**（`monoNow >= monoMs`，即进程被杀/服务被拉起、
     *    设备并**没有**重启）：`elapsedRealtime` 不随改系统时间变化，两个读数之差
     *    就是真实物理流逝，完全不参考墙钟。这是防篡改最强的一条路径，
     *    孩子离线把时钟前拨 8 小时也无法让受信时间快进。
     * 2. **单调时钟归零**（设备真重启，或读数异常）：系统没有跨重启的单调源，
     *    只能借裸墙钟差估计停机时长。这里存在无法根除的残余边界——
     *    "关机期间前拨系统时间"与"真的关机了 8 小时"在信息论上不可区分；
     *    但恢复之后护栏立即接管，墙钟再改都无效，且联网对时会一次性校正。
     *    宁可让这一下生效，也不能让设备每次重启都把时间冻住（可用性灾难）。
     * 3. **回拨 / 脏值**：绝不倒退，保持上次受信时刻（联网对时负责校正）。
     *
     * 若 [TrustedBaseline.monoMs] 与 [monoNowMs] 不一致但差值仍为正（例如开机后
     * 极短时间内落盘又立刻重启），会走路径 1 而少补一段流逝——这是"防篡改优先"
     * 的有意取舍：改墙钟是高频攻击，该场景极窄且后果只是时间暂时偏慢。
     */
    internal fun resumedTrustedMs(
        baseline: TrustedBaseline,
        wallNowMs: Long,
        monoNowMs: Long,
    ): Long {
        if (!baseline.hasResumeContext()) return -1L
        val deltaMono = monoNowMs - baseline.monoMs
        if (deltaMono in 0..MAX_RESUME_GAP_MS) {
            return plusElapsed(baseline.trustedMs, deltaMono)
        }
        val deltaWall = wallNowMs - baseline.wallMs
        if (deltaWall in 0..MAX_RESUME_GAP_MS) {
            return plusElapsed(baseline.trustedMs, deltaWall)
        }
        return baseline.trustedMs
    }

    /** 防止脏盘值把加法撑到溢出后变成负时间 */
    private fun plusElapsed(base: Long, deltaMs: Long): Long =
        if (base > Long.MAX_VALUE - deltaMs) base else base + deltaMs

    /**
     * 对齐服务端标准时间。
     *
     * @param serverTimeMs 服务端下发的时间戳（UTC 毫秒）
     * @param roundTripMs  **仅在 serverTimeMs 是「请求到达时刻」加盖时**才传往返耗时做折半补偿。
     *
     * ## 为什么调用方通常必须传 0（重要）
     * 本项目的 serverTime 由服务端在**响应写出那一刻**加盖（长轮询更是要等服务端挂起结束才写响应），
     * 它与"本地收到响应的时刻"只差一个单向网络时延。此时若再补 `roundTripMs / 2`，
     * 长轮询会把 20 秒挂起时长的一半（约 10 秒）凭空加到 offset 上，
     * 导致终端时钟系统性偏快、每轮抖动 —— 表现为上报时间对不上、指令 since 落到未来被过滤。
     * 因此 [com.padguard.core.transport.http.ApiCaller] 统一以 **0** 调用本方法。
     */
    fun syncServerTime(serverTimeMs: Long, roundTripMs: Long = 0L) {
        if (serverTimeMs <= 0) return
        val localNow = System.currentTimeMillis()
        val sample = serverTimeMs + roundTripMs / 2 - localNow

        // 脏样本拒收：本地墙钟与服务端相差超过 24h，只有两种可能 ——
        // 服务端下发了 0/占位值，或本地时钟被改到了离谱的时间点。
        // 无论哪种，直接采纳都会把后续所有时段判定带偏，宁可丢弃等下一个样本。
        if (sample !in -MAX_PLAUSIBLE_OFFSET_MS..MAX_PLAUSIBLE_OFFSET_MS) {
            rejectedCount++
            Logger.w("TimeProvider") { "implausible offset sample ${sample}ms rejected" }
            return
        }

        val next = synchronized(lock) {
            if (samples.size >= SAMPLE_WINDOW) samples.removeFirst()
            samples.addLast(sample)
            sampleCount++
            lastSyncNanos = System.nanoTime()

            val sorted = samples.sorted()
            lastJitterMs = sorted.last() - sorted.first()
            val median = sorted[sorted.size / 2]
            val current = _serverOffsetMs.value

            // 双速收敛：
            // - 首次对时 / 偏差超过阈值 → 全量采纳。这是真实的时钟跳变
            //   （用户改了系统时间、设备跨时区、冷启动首个样本），
            //   平滑反而会让 offset 花上十几秒才爬到正确值，期间判定全错。
            // - 否则 → 一阶低通。α=0.25 意味着约 4 个样本（≈12 秒）追平 96%，
            //   足以吃掉单次网络排队带来的百毫秒级噪声。
            val target = if (current == 0L || kotlin.math.abs(median - current) > FAST_CONVERGE_MS) {
                median
            } else {
                current + (median - current) / SMOOTHING_DIVISOR
            }
            _serverOffsetMs.value = target
            target
        }
        // 服务端时间是唯一权威来源：对时成功即把受信时钟锚定过去，
        // 这样即便此前墙钟被大幅篡改过，也能在联网瞬间校正回来。
        reanchorFromServer(System.currentTimeMillis() + next)
        Logger.d("TimeProvider") { "server time synced, offset=${next}ms (${stats()})" }
    }

    // ----------------------------------------------------------------------
    // 单调护栏（防时间篡改的最后一环）
    //
    // 只做 offset 补偿并不够：offset 是"上一次对时"的快照，孩子断网后把系统时间
    // 往前拨 2 小时，`wall + offset` 立刻跟着倒退 2 小时 —— 时段锁、宵禁、定时锁机
    // 的判定窗口整体后移，等于把当晚的锁机直接跳过；往前拨则可能"跳过"整段禁用时段。
    // 而直到下一次联网对时之前，本地没有任何东西能纠正它（[TamperDetector] 只上报不处置）。
    //
    // 因此 now() 不再直接返回墙钟，而是返回一个**只会单调推进**的受信时钟：
    // - 以 (锚点时间, 锚点单调时钟) 为基准，用单调时钟的增量推进；
    // - 墙钟小幅领先/落后（正常晶振漂移、NTP 微调）时跟随墙钟；
    // - 墙钟大幅跳变（回拨或前拨）时**忽略墙钟**，继续按单调时钟推进；
    // - 每次成功对时（服务端时间是权威值）重新锚定，把累积误差一次抹平。
    //
    // 单调时钟用 [SystemClock.elapsedRealtime]（含深睡，改系统时间不影响）；
    // 纯 JVM 单测里它是 android.jar 的 stub 会抛异常，故降级到 System.nanoTime()。
    // ----------------------------------------------------------------------

    private val clockLock = Any()
    /** 锚点：受信时间（UTC 毫秒）；-1 表示尚未建立 */
    private var anchorTrustedMs = -1L
    /** 锚点：对应的单调时钟读数 */
    private var anchorMonoMs = 0L
    /** 单步允许的墙钟偏差：超过即视为人为跳变。取 2 分钟与篡改判定阈值一致 */
    private val maxWallStepMs = CLOCK_STEP_TOLERANCE_MS
    /** SystemClock 是否可用于本进程（JVM 单测环境下为 false） */
    @Volatile private var systemClockUsable = true

    /**
     * 单调递增时钟（含休眠），时长统计与时段判定的唯一基准。
     *
     * 已是可注入的测试缝：Android 上走 [SystemClock.elapsedRealtime]，
     * 纯 JVM 单测下自动降级到 [System.nanoTime]（不会像直接调用 `android.os.SystemClock`
     * 那样抛 "Stub!"）。[com.padguard.child.usagesession.UsageSessionManager] 等需要本地
     * 单调增量的地方一律经由此方法，而非直接触碰 `android.os.SystemClock`，
     * 这样既能离线防改时间，又能在纯 JVM 下被精确 mock 与单测。
     */
    fun monotonicMs(): Long {
        if (systemClockUsable) {
            try {
                return SystemClock.elapsedRealtime()
            } catch (t: Throwable) {
                systemClockUsable = false
            }
        }
        return System.nanoTime() / 1_000_000L
    }

    /**
     * 受信当前时间（UTC 毫秒）：**只前进，不后退**。
     *
     * 未对时前退化为本地墙钟（此时无更可信来源）；一旦建立锚点，
     * 任何超过 [CLOCK_STEP_TOLERANCE_MS] 的墙钟跳变都会被忽略，
     * 改系统时间既不能让额度回血，也不能跳过禁用时段。
     */
    fun now(): Long {
        val wall = System.currentTimeMillis() + _serverOffsetMs.value
        return guard(wall)
    }

    /**
     * 用单调时钟给墙钟加护栏。抽成独立方法便于单测直接注入"墙钟值 + 单调值"。
     */
    private fun guard(wall: Long): Long = observe(wall, monotonicMs())

    /**
     * 护栏核心：给定同一时刻的墙钟与单调时钟读数，产出受信时间。
     *
     * 实现为 internal 而非 private，是为了让单测能在不依赖"真的去改系统时间"的前提下
     * 覆盖回拨 / 前拨 / 正常漂移三条分支 —— 这三条分支是本类唯一的安全语义所在。
     */
    internal fun observe(wall: Long, mono: Long): Long = synchronized(clockLock) {
        val anchor = anchorTrustedMs
        // 首次调用，或设备重启导致单调时钟归零 → 重建锚点（重启后只能以墙钟为准）
        if (anchor < 0L || mono < anchorMonoMs) {
            anchorTrustedMs = wall
            anchorMonoMs = mono
            return wall
        }
        val projected = anchor + (mono - anchorMonoMs)
        // 大跳变（墙钟与物理时间线偏离超容差）→ 直接丢弃墙钟，按物理时间线推进
        // （防篡改关键分支，对应"断网后大幅改时间"）。
        // 小步（含正常晶振 / NTP 漂移）→ 跟随墙钟。
        val candidate = if (kotlin.math.abs(wall - projected) <= maxWallStepMs) wall else projected
        // 受信时钟钳制在物理时间线 [anchor, projected] 内（R-P2-1 治本）：
        // - 下界 anchor：绝不允许比上次受信更旧 —— 防"每次回拨 ≤2min、真实只过极短"的
        //   慢漂移累积把受信时钟带退，从而离线把锁机窗口整体后移 / 让额度回血；
        // - 上界 projected：绝不允许超前物理真实流逝 —— 防"每次前拨 ≤2min、真实只过极短"
        //   的快进累积跳过整段禁用时段。
        // 正常墙钟（与物理时间线基本一致）落在窗口内，跟随且约等于墙钟；
        // 人为跳变被钳制，管控判定既不会倒退也不会快进，时间篡改彻底失效。
        val chosen = candidate.coerceIn(anchor, projected)
        anchorTrustedMs = chosen
        anchorMonoMs = mono
        chosen
    }

    /**
     * 带服务端 offset 校准的裸墙钟（UTC 毫秒）。
     *
     * 与 [now] 的根本区别：本方法**不做单调护栏钳制**，返回的是
     * "本地墙钟 + 校准偏移"的真实世界读数。
     *
     * 用途：仅供 [com.padguard.core.engine.guard.TamperDetector] 做**篡改检测**——
     * 篡改检测比较"墙钟增量 vs 单调时钟增量"，需要真实墙钟跳变信号才能发现孩子改时间；
     * 若用被 [now] 钳制的受信钟，篡改动作会被护栏吞掉，检测层将完全失明
     * （这是 R-P2-1 治本后的必要配套：护栏负责"不让篡改生效"，检测负责"让篡改可见"，
     * 二者必须各走各的源，不能共用被钳制的受信钟）。
     * 管控判定仍一律走 [now]，不受本方法影响。
     */
    fun wallClockMs(): Long = System.currentTimeMillis() + _serverOffsetMs.value

    /**
     * 服务端时间权威锚定：对时成功后把受信时钟直接拉到服务端时间。
     *
     * 与 [guard] 的区别：护栏会拒绝一切大跳变，但对时是**可信来源**，
     * 必须允许它把跑偏的时钟（无论是晶振漂移还是被篡改过）一次校正回来。
     */
    private fun reanchorFromServer(serverTimeMs: Long) {
        synchronized(clockLock) {
            anchorTrustedMs = serverTimeMs
            anchorMonoMs = monotonicMs()
        }
    }

    /**
     * 检测本地墙钟是否被篡改。
     *
     * 判定依据：上次同步后落地的偏移量在短时间内出现显著漂移。
     * 一旦命中，调用方应强制重新拉取服务端时间并上报高危告警。
     */
    fun detectClockTampering(previousOffset: Long, toleranceMs: Long = DRIFT_TOLERANCE_MS): Boolean {
        val drift = kotlin.math.abs(_serverOffsetMs.value - previousOffset)
        if (drift > toleranceMs) {
            Logger.w("TimeProvider") { "clock tampering detected, drift=${drift}ms" }
            return true
        }
        return false
    }

    companion object {
        /** 允许的自然时钟漂移阈值：15 分钟 */
        const val DRIFT_TOLERANCE_MS = 15 * 60 * 1000L

        /** 中位数滤波的样本窗口大小 */
        private const val SAMPLE_WINDOW = 5

        /** 偏差超过该值即判定为真实时钟跳变，全量收敛而非平滑 */
        private const val FAST_CONVERGE_MS = 3_000L

        /** 一阶低通除数：4 → α=0.25 */
        private const val SMOOTHING_DIVISOR = 4L

        /** offset 的可信上限：实时对时样本的脏样本拒收阈值（超过 24h 视为无效） */
        private const val MAX_PLAUSIBLE_OFFSET_MS = 24 * 60 * 60 * 1000L

        /** 持久化 offset 的注入上限：同一设备上次会话的偏移，限制到 ±2h 足以覆盖时区/夏令时，
         *  又不至于把「设备被改到离谱时间」的偏移原样带回来污染日切判定 */
        private const val MAX_SEED_OFFSET_MS = 2 * 60 * 60 * 1000L

        /** 超过该时长没拿到新样本即认为对时已陈旧 */
        private const val STALE_AFTER_MS = 30 * 60 * 1000L

        /**
         * 恢复基线时允许补偿的最大停机时长（365 天）。
         * 不是业务上限，只是防 SP 脏值 / 单调时钟坐标系混用 / 加法溢出：
         * 超出后 [resumedTrustedMs] 不做补偿，保持上次受信时刻等联网对时校正。
         */
        private const val MAX_RESUME_GAP_MS = 365L * 24 * 60 * 60 * 1000

        /**
         * 单步墙钟偏差容忍：超过即判定为人为跳变，受信时钟改为按单调时钟推进。
         * 与 [com.padguard.core.engine.guard.TamperDetector.CLOCK_DRIFT_TOLERANCE_MS] 保持同口径，
         * 避免"告警了但管控没变"的割裂。
         */
        const val CLOCK_STEP_TOLERANCE_MS = 2 * 60 * 1000L
    }
}
