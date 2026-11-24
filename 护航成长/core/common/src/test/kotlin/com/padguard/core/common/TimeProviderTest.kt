package com.padguard.core.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [TimeProvider] 时钟校准逻辑的单元测试。
 *
 * 注意：运行在本地 JVM（非 Robolectric）上，因此任何会调到 [android.util.Log]
 * 的路径都必须规避 —— 这里在 @Before 里关掉 [Logger.enabled]，
 * 否则 Logger 会触发 Android 框架的 Stub 抛异常。
 * elapsedRealtime / uptimeMillis 等依赖 SystemClock 的方法不在本测试范围内。
 */
class TimeProviderTest {

    private var originalLoggerEnabled = true

    @Before
    fun setUp() {
        originalLoggerEnabled = Logger.enabled
        Logger.enabled = false
    }

    @After
    fun tearDown() {
        Logger.enabled = originalLoggerEnabled
    }

    @Test
    fun `now returns local wall clock before any sync`() {
        val tp = TimeProvider()
        val before = System.currentTimeMillis()
        val now = tp.now()
        val after = System.currentTimeMillis()
        assertTrue("now should fall within [before, after]", now in before..after)
    }

    // 服务端时间一律基于"当前真实时间 + 一个小偏移"构造。
    //
    // 不能硬编码某个历史时间戳（例如 1_700_000_000_000L = 2023-11）：
    // 随着真实时间推移，它与本地墙钟的差值会越来越大，
    // 一旦超过 implausible 阈值（24h）就会被当成脏样本拒收，
    // 于是测试在某一天突然开始失败 —— 这类"时间炸弹"正是要避免的。
    private fun serverTimeAheadBy(offsetMs: Long): Long = System.currentTimeMillis() + offsetMs

    @Test
    fun `syncServerTime with zero round-trip aligns now to server time`() {
        val tp = TimeProvider()
        val serverTimeMs = serverTimeAheadBy(2_500)
        tp.syncServerTime(serverTimeMs)

        val now = tp.now()
        // offset = serverTimeMs - localNow，故 now() = localNow' + offset ≈ serverTimeMs
        assertTrue(
            "now=$now 应约等于 serverTime=$serverTimeMs",
            kotlin.math.abs(now - serverTimeMs) < 5000
        )
    }

    @Test
    fun `syncServerTime compensates half of the round-trip latency`() {
        val tp = TimeProvider()
        val serverTimeMs = serverTimeAheadBy(2_500)
        val roundTripMs = 2000L
        tp.syncServerTime(serverTimeMs, roundTripMs)

        val now = tp.now()
        // 网络延迟折半补偿：now() ≈ serverTimeMs + roundTripMs / 2
        val expected = serverTimeMs + roundTripMs / 2
        assertTrue(
            "now=$now 应约等于 serverTime+RTT/2=$expected",
            kotlin.math.abs(now - expected) < 5000
        )
    }

    @Test
    fun `detectClockTampering is false when offset is unchanged`() {
        val tp = TimeProvider()
        tp.syncServerTime(serverTimeAheadBy(2_500))
        val currentOffset = tp.serverOffsetMs.value
        assertFalse(
            "offset 未漂移时不应判定为篡改",
            tp.detectClockTampering(currentOffset)
        )
    }

    @Test
    fun `detectClockTampering is true when drift exceeds tolerance`() {
        val tp = TimeProvider()
        tp.syncServerTime(serverTimeAheadBy(2_500))
        // 假设上次记录的 offset 与当前相差 1 小时，容忍度仅 1 分钟
        val previousOffset = tp.serverOffsetMs.value - 60 * 60 * 1000L
        assertTrue(
            "漂移 1 小时 > 容忍 1 分钟，应判定为篡改",
            tp.detectClockTampering(previousOffset, toleranceMs = 60_000L)
        )
    }

    @Test
    fun `detectClockTampering is false when drift within tolerance`() {
        val tp = TimeProvider()
        tp.syncServerTime(serverTimeAheadBy(2_500))
        val currentOffset = tp.serverOffsetMs.value
        // 小漂移（1s），大容忍度（1 分钟）
        assertFalse(
            "漂移 1s < 容忍 1 分钟，不应判定为篡改",
            tp.detectClockTampering(currentOffset + 1000L, toleranceMs = 60_000L)
        )
    }

    // ==================== P0-4：样本滤波与双速收敛 ====================

    @Test
    fun `implausible sample is rejected and does not move offset`() {
        val tp = TimeProvider()
        tp.syncServerTime(System.currentTimeMillis() + 5_000)   // 先建立一个正常 offset
        val settled = tp.serverOffsetMs.value

        // 本地墙钟被改到三天后 —— 这种样本必须丢弃，否则时段判定全错
        tp.syncServerTime(System.currentTimeMillis() + 3 * 24 * 3600 * 1000L)
        assertTrue(
            "离谱样本不应改变 offset（settled=$settled, now=${tp.serverOffsetMs.value}）",
            kotlin.math.abs(tp.serverOffsetMs.value - settled) < 3_000
        )
    }

    @Test
    fun `single noisy sample does not yank the offset`() {
        val tp = TimeProvider()
        val base = System.currentTimeMillis() + 500
        repeat(4) { tp.syncServerTime(base) }        // 收敛到 +500 附近
        val settled = tp.serverOffsetMs.value

        // 一次网络排队造成的 2s 离群样本（仍在 3s 快速收敛阈值内 → 走平滑）
        tp.syncServerTime(base + 2_000)
        assertTrue(
            "单次离群样本不应把 offset 直接拽走 2s（settled=$settled, now=${tp.serverOffsetMs.value}）",
            kotlin.math.abs(tp.serverOffsetMs.value - settled) < 1_000
        )
    }

    @Test
    fun `real clock jump converges immediately`() {
        val tp = TimeProvider()
        val base = System.currentTimeMillis() + 500
        repeat(4) { tp.syncServerTime(base) }

        // 用户把系统时间拨快 1 小时 —— 这是真实跳变，必须一轮就跟上，
        // 平滑收敛会让 offset 爬十几秒，期间所有时段判定都是错的。
        val jumped = System.currentTimeMillis() + 3600_000
        repeat(3) { tp.syncServerTime(jumped) }
        assertTrue(
            "真实跳变应快速收敛（offset=${tp.serverOffsetMs.value}）",
            kotlin.math.abs(tp.serverOffsetMs.value - 3600_000) < 5_000
        )
    }

    @Test
    fun `seedOffset only applies before any real sample`() {
        val tp = TimeProvider()
        tp.seedOffset(1234L)
        assertTrue("冷启动应接受持久值", tp.serverOffsetMs.value == 1234L)

        val base = System.currentTimeMillis() + 7_000
        repeat(3) { tp.syncServerTime(base) }
        // 已有真实样本后，迟到的持久值不得覆盖
        tp.seedOffset(999_999L)
        assertTrue(
            "已有真实样本后持久值不得覆盖（offset=${tp.serverOffsetMs.value}）",
            tp.serverOffsetMs.value != 999_999L
        )
    }

    @Test
    fun `zero or negative serverTime is ignored`() {
        val tp = TimeProvider()
        tp.syncServerTime(0)
        tp.syncServerTime(-1)
        assertTrue("占位/非法时间戳不应产生 offset", tp.serverOffsetMs.value == 0L)
    }

    // ==================== 单调护栏：改系统时间不能改写管控时间轴 ====================

    private val t0 = 1_700_000_000_000L

    @Test
    fun `wall clock rollback does not rewind trusted time`() {
        // 场景：孩子断网后把系统时间往回拨 1 小时，试图让"今天的额度/时段锁"整体后移。
        val tp = TimeProvider()
        tp.observe(t0, 0L)

        // 墙钟倒退 1 小时，但单调时钟如实走了 60 秒
        val trusted = tp.observe(t0 - 3_600_000L, 60_000L)

        assertTrue(
            "回拨后受信时间仍应前进到约 t0+60s，实际=$trusted",
            trusted in (t0 + 55_000L)..(t0 + 65_000L)
        )
    }

    @Test
    fun `wall clock forward jump cannot skip a lock window`() {
        // 场景：把时间往前拨 4 小时，试图"跳过"整段禁用时段。
        val tp = TimeProvider()
        tp.observe(t0, 0L)

        val trusted = tp.observe(t0 + 4 * 3_600_000L, 60_000L)

        assertTrue(
            "前拨应被忽略，受信时间仍约等于 t0+60s，实际=$trusted",
            trusted in (t0 + 55_000L)..(t0 + 65_000L)
        )
    }

    @Test
    fun `normal drift aligned with physical timeline still follows the wall clock`() {
        // 墙钟与物理时间线（单调推进）基本一致时，受信钟完美跟随墙钟（R-P2-1 钳制不影响正常情形）。
        val tp = TimeProvider()
        tp.observe(t0, 0L)

        // 单调走了 60s，墙钟也走了 60s（两者一致，落在物理时间线窗口内）
        val trusted = tp.observe(t0 + 60_000L, 60_000L)
        assertTrue("窗口内应跟随墙钟，实际=$trusted", trusted == t0 + 60_000L)
    }

    @Test
    fun `slow rollback cannot rewind trusted time via repeated small steps (R-P2-1)`() {
        // 慢漂移攻击：孩子每次把墙钟回拨 1 分钟（<=2min），但真实只过 2 秒，
        // 反复 10 次（共回拨 10 分钟，真实只过 20 秒）。
        // 治本后受信时钟被钳制在物理时间线 [anchor, projected] 内，绝不会倒退，
        // 也不会被回拨带偏——最多按真实流逝推进，锁机窗口不会被整体后移，额度不会回血。
        val tp = TimeProvider()
        tp.observe(t0, 0L)

        var last = t0
        repeat(10) { i ->
            val wall = last - 60_000L
            val mono = (i + 1) * 2_000L
            last = tp.observe(wall, mono)
        }

        assertTrue(
            "慢漂移回拨后受信时间不应倒退到 t0 之前，实际=$last",
            last >= t0
        )
        assertTrue(
            "受信时间不应被回拨带偏快进（应≈真实流逝 20s），实际=$last 偏离=${last - t0}",
            last in (t0)..(t0 + 25_000L)
        )
    }

    @Test
    fun `small forward nudges cannot skip a lock window (R-P2-1)`() {
        // 快进攻击：孩子每次把墙钟前拨 1 分钟（<=2min），但真实只过 2 秒，
        // 反复 10 次（共前拨 10 分钟，真实只过 20 秒），试图"快进"跳过禁用时段。
        // 治本后受信时钟钳制在物理时间线上界，绝不会超前物理真实流逝。
        val tp = TimeProvider()
        tp.observe(t0, 0L)

        var last = t0
        repeat(10) { i ->
            val wall = last + 60_000L
            val mono = (i + 1) * 2_000L
            last = tp.observe(wall, mono)
        }

        assertTrue(
            "快进前拨后受信时间不应超前物理时间线（应≈真实流逝 20s），实际=$last 偏离=${last - t0}",
            last in (t0 + 15_000L)..(t0 + 25_000L)
        )
    }

    @Test
    fun `device reboot resets the anchor instead of freezing time`() {
        // 重启后单调时钟归零：不能因为"倒退"就把时间冻住，必须以墙钟重建锚点。
        val tp = TimeProvider()
        tp.observe(t0, 5_000_000L)

        val trusted = tp.observe(t0 + 20_000L, 100L)
        assertTrue("重启后应以墙钟重建锚点，实际=$trusted", trusted == t0 + 20_000L)
    }

    @Test
    fun `server sync re-anchors after tampering`() {
        // 被篡改过（回拨）之后一旦联网对时，必须立刻校正回真实时间。
        val tp = TimeProvider()
        tp.observe(t0, 0L)
        tp.observe(t0 - 3_600_000L, 60_000L)   // 回拨，受信时间保持在 t0 附近

        val realNow = System.currentTimeMillis()
        tp.syncServerTime(realNow)

        val now = tp.now()
        assertTrue(
            "对时后应重新锚定到服务端时间，now=$now realNow=$realNow",
            kotlin.math.abs(now - realNow) < 5_000
        )
    }

    // ==================== P0-1：离线时间护栏冷启动持久化（R-P0-1 / 红线） ====================
    //
    // 离线红线要求：时间管控在断网、无服务器时也必须本地独立生效。
    // 原实现锚点(anchorTrustedMs)纯内存，进程重启后首帧 observe 因 anchor<0 退裸墙钟，
    // 冷启动/重启窗口改系统时间即可绕过锁机。修复：持久化受信锚点，开机恢复。

    @Test
    fun `trusted baseline restored from disk blocks rollback after reboot`() {
        // 模拟"上一会话建立锚点并持久化"，再模拟"重启后从磁盘恢复"，验证改时间仍被拦。
        val tp = TimeProvider()
        tp.observe(t0, 0L)
        val persisted = tp.currentTrustedMs()
        assertTrue("锚点应已建立", persisted == t0)

        // 新进程（重启）：从磁盘恢复受信基线（mono 基准取当前单调读数）
        val tp2 = TimeProvider()
        tp2.restoreTrustedBaseline(persisted)
        val base = tp2.monotonicMs()

        // 孩子重启后把系统时间往回拨 1 小时，试图让锁机窗口整体后移
        val trusted = tp2.observe(t0 - 3_600_000L, base + 60_000L)
        assertTrue(
            "重启+恢复后回拨仍应被护栏忽略，受信时间约 t0+60s，实际=$trusted",
            trusted in (t0 + 55_000L)..(t0 + 65_000L)
        )

        // 前拨 4 小时试图跳过锁机窗口，同样应被忽略
        val trusted2 = tp2.observe(t0 + 4 * 3_600_000L, base + 120_000L)
        assertTrue(
            "重启+恢复后前拨仍应被护栏忽略，受信时间约 t0+120s，实际=$trusted2",
            trusted2 in (t0 + 115_000L)..(t0 + 125_000L)
        )
    }

    @Test
    fun `first now after restore is anchored not bare wall`() {
        // 最关键红线用例：从未联网的真实 now()（用 System.currentTimeMillis）也必须被锚定，
        // 不能退回裸墙钟。t0 是固定锚点（远离真实时间），若 now() 退回裸墙钟会差好几年。
        val tp = TimeProvider()
        tp.restoreTrustedBaseline(t0)

        val now = tp.now()
        assertTrue(
            "恢复锚点后首个 now() 必须锚定到 t0 附近而非裸墙钟，now=$now",
            kotlin.math.abs(now - t0) < 10_000L
        )
    }

    @Test
    fun `restoreTrustedBaseline only applies before session anchor exists`() {
        // 会话内已建立锚点后，迟到的持久值不得覆盖（与 seedOffset 同口径）。
        val tp = TimeProvider()
        tp.observe(t0, 0L)
        // 墙钟前拨 10 分钟（超过 2min 容忍）→ 护栏返回投影值 t0+50s，锚点被建立
        tp.observe(t0 + 10 * 60 * 1000L, 50_000L)
        val established = tp.currentTrustedMs()
        assertTrue("会话锚点应已建立为 t0+50s，实际=$established", established in (t0 + 45_000L)..(t0 + 55_000L))

        // 迟到一个离谱的持久值，必须被忽略
        tp.restoreTrustedBaseline(t0 - 999_999_999_000L)
        assertTrue(
            "已建立锚点后持久值不得覆盖，currentTrustedMs=${tp.currentTrustedMs()}",
            tp.currentTrustedMs() == established
        )
    }

    @Test
    fun `never-synced device with no persisted baseline falls back to wall`() {
        // 反向用例：从未对时、无持久化锚点的设备，now() 退裸墙钟是可接受的（此时无策略可强制）。
        val tp = TimeProvider()
        val before = System.currentTimeMillis()
        val now = tp.now()
        val after = System.currentTimeMillis()
        assertTrue("无基线时 now 应落在裸墙钟区间", now in before..after)
    }

    // ==================== R-P0-1 回归 · 恢复基线不得冻结时间 ====================
    //
    // 原实现只把"上次受信时刻"原样设回锚点，等于把时间冻结在关机那一刻：
    // 23:00 关机（宵禁中）→ 次日 08:00 开机仍判定 23:00，白天继续锁机；
    // 日切 / 每日额度 / 时段边界全部按错误时刻判定，且联网对时之前不会自愈。
    // 更隐蔽的是它屏蔽了 observe 里"单调时钟归零 → 以墙钟重建锚点"的原有兜底。
    // 修复：落盘三元组（受信时刻 + 单调读数 + 裸墙钟），恢复时补偿真实流逝。

    private val eightHoursMs = 8 * 3_600_000L

    @Test
    fun `resume after process restart uses monotonic elapsed and ignores wall jump`() {
        // 最常见的重启场景：守护服务被杀 / 无障碍重连 / 低内存回收，设备本身没重启。
        // elapsedRealtime 坐标系连续，差值就是真实流逝，完全不该参考墙钟。
        val tp = TimeProvider()
        val baseline = TrustedBaseline(trustedMs = t0, wallMs = t0, monoMs = 1_000_000L)

        // 孩子离线把系统时间往前拨 4 小时，试图跳过整段宵禁
        val resumed = tp.resumedTrustedMs(
            baseline,
            wallNowMs = t0 + eightHoursMs + 4 * 3_600_000L,
            monoNowMs = 1_000_000L + eightHoursMs,
        )
        assertEquals("单调源可用时，墙钟跳变不得影响恢复后的受信时间", t0 + eightHoursMs, resumed)
    }

    @Test
    fun `resume after process restart ignores wall clock rollback too`() {
        val tp = TimeProvider()
        val baseline = TrustedBaseline(trustedMs = t0, wallMs = t0, monoMs = 1_000_000L)

        val resumed = tp.resumedTrustedMs(
            baseline,
            wallNowMs = t0 - 3_600_000L,
            monoNowMs = 1_000_000L + eightHoursMs,
        )
        assertEquals("回拨同样不得让受信时间倒退", t0 + eightHoursMs, resumed)
    }

    @Test
    fun `resume after device reboot estimates gap from wall clock delta`() {
        // 设备真重启：elapsedRealtime 归零，没有跨重启的单调源，只能用裸墙钟差估计停机时长。
        val tp = TimeProvider()
        val baseline = TrustedBaseline(trustedMs = t0, wallMs = t0, monoMs = 500_000_000L)

        val resumed = tp.resumedTrustedMs(
            baseline,
            wallNowMs = t0 + eightHoursMs,
            monoNowMs = 5_000L,   // 重启后单调时钟重新从小值开始
        )
        assertEquals("设备重启后应补上真实停机 8 小时，而不是冻结在关机时刻", t0 + eightHoursMs, resumed)
    }

    @Test
    fun `resume never rewinds when both clocks moved backwards`() {
        val tp = TimeProvider()
        val baseline = TrustedBaseline(trustedMs = t0, wallMs = t0, monoMs = 500_000_000L)

        val resumed = tp.resumedTrustedMs(
            baseline,
            wallNowMs = t0 - 3_600_000L,
            monoNowMs = 5_000L,
        )
        assertEquals("RTC 回拨 / 脏值一律不做负补偿，保持上次受信时刻等联网校正", t0, resumed)
    }

    @Test
    fun `resume rejects implausible gap from dirty disk value`() {
        val tp = TimeProvider()
        val baseline = TrustedBaseline(trustedMs = t0, wallMs = t0, monoMs = 0L)
        val huge = 400L * 24 * 3_600_000L   // 超过 365 天上限

        val resumed = tp.resumedTrustedMs(baseline, t0 + huge, huge)
        assertEquals("异常流逝量应被拒收，不能把受信时间推到几十年后", t0, resumed)
    }

    @Test
    fun `resume rejects incomplete baseline without resume context`() {
        val tp = TimeProvider()
        assertEquals(-1L, tp.resumedTrustedMs(TrustedBaseline(trustedMs = t0), t0 + eightHoursMs, 1_000_000L))
        assertEquals(-1L, tp.resumedTrustedMs(TrustedBaseline(trustedMs = 0L, wallMs = t0, monoMs = 1L), t0, 1L))
    }

    @Test
    fun `restored and compensated anchor still blocks wall clock jump`() {
        // 端到端：恢复（补偿 8h）之后护栏必须立刻接管，不能因为"刚从磁盘读回来"就放行。
        val tp = TimeProvider()
        val baseline = TrustedBaseline(trustedMs = t0, wallMs = t0, monoMs = 1_000_000L)
        val monoNow = 1_000_000L + eightHoursMs

        assertTrue("完整快照应恢复成功", tp.restoreTrustedBaseline(baseline, t0 + eightHoursMs, monoNow))
        assertEquals("锚点应是补偿后的时刻", t0 + eightHoursMs, tp.currentTrustedMs())

        val guarded = tp.observe(t0 + eightHoursMs + 4 * 3_600_000L, monoNow + 60_000L)
        assertTrue(
            "恢复后前拨 4h 仍应被护栏忽略，受信时间约 t0+8h+60s，实际=$guarded",
            guarded in (t0 + eightHoursMs + 55_000L)..(t0 + eightHoursMs + 65_000L)
        )
    }

    @Test
    fun `late disk baseline cannot overwrite an established session anchor`() {
        val tp = TimeProvider()
        tp.observe(t0, 0L)
        val established = tp.currentTrustedMs()

        val late = TrustedBaseline(
            trustedMs = t0 + 99L * 24 * 3_600_000L,
            wallMs = t0,
            monoMs = 1_000_000L,
        )
        assertFalse("会话内已有锚点时，迟到的持久值必须被忽略", tp.restoreTrustedBaseline(late, t0, 1_000_000L))
        assertEquals(established, tp.currentTrustedMs())
    }

    @Test
    fun `legacy single value baseline keeps old behaviour and a sane mono anchor`() {
        // 旧版盘只有 trusted_anchor_ms：无法补偿流逝，退化为旧行为；
        // 但单调基准必须取真实当前读数，否则后续 observe 会把受信时间推到几十年后。
        val tp = TimeProvider()
        tp.restoreTrustedBaseline(t0)
        assertEquals("旧格式只恢复锚点本身", t0, tp.currentTrustedMs())

        val mono = tp.monotonicMs()
        val guarded = tp.observe(t0, mono + 1_000L)
        assertTrue("旧格式恢复后首帧不得跳到未来，实际=$guarded", guarded in t0..(t0 + 1_000L))
    }

    @Test
    fun `export baseline captures trusted wall and mono from the same instant`() {
        // 三个字段必须同瞬间配对，否则恢复时算出的流逝量会带一个 tick 的系统误差。
        val tp = TimeProvider()
        val baseline = tp.exportTrustedBaseline()
        assertTrue("导出的快照必须可用于补偿", baseline.hasResumeContext())
        assertTrue(
            "offset 为 0 时受信时刻与裸墙钟应一致，trusted=${baseline.trustedMs} wall=${baseline.wallMs}",
            kotlin.math.abs(baseline.trustedMs - baseline.wallMs) < 1_000L
        )
        assertTrue("单调读数应为正", baseline.monoMs > 0L)
    }
}
