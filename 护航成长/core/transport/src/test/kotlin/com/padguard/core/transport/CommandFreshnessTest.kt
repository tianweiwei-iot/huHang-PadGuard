package com.padguard.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CommandFreshness] 的时效语义测试（新增缺陷 H）。
 *
 * 重点钉住的是：`expiresAt` **不在 HMAC canonical 串里**，把它改成 0 或远期
 * 不会让签名失效，所以旧实现的"expiresAt < now 才过期"等于没有有效期。
 * 这里用已签名的 `timestamp` 给最长寿命封顶，正常指令行为必须一字不变。
 */
class CommandFreshnessTest {

    /** 服务端当前 TTL：timestamp + 5min（CommandService.issueCommand） */
    private val t0 = 1_767_225_600_000L
    private val ttl = 5 * 60 * 1000L
    private val cap = CommandFreshness.MAX_COMMAND_LIFETIME_MS

    private fun verdict(
        timestamp: Long = t0,
        expiresAt: Long = t0 + ttl,
        now: Long = t0 + 60_000L
    ) = CommandFreshness.evaluate(timestamp, expiresAt, now)

    private fun assertRejectedContains(vararg result: Pair<CommandFreshness.Verdict, String>) {
        result.forEach { (v, fragment) ->
            assertTrue("应当被拒绝且原因含「$fragment」，实际 $v", v is CommandFreshness.Verdict.Rejected)
            val reason = (v as? CommandFreshness.Verdict.Rejected)?.reason.orEmpty()
            assertTrue("拒绝原因应含「$fragment」：$reason", reason.contains(fragment))
        }
    }

    // ---------- 正常路径：不得改变任何既有行为 ----------

    @Test
    fun `server issued command within 5min TTL stays fresh`() {
        assertEquals(CommandFreshness.Verdict.Fresh, verdict())
        assertEquals(t0 + ttl, CommandFreshness.effectiveExpiryAt(t0, t0 + ttl))
    }

    @Test
    fun `explicit expiry still rejects first and keeps original reason`() {
        val v = verdict(now = t0 + ttl + 1)
        assertRejectedContains(v to "expired at")
    }

    @Test
    fun `timestamp too far in future is rejected`() {
        val v = verdict(timestamp = t0 + 6 * 60_000L, expiresAt = t0 + 6 * 60_000L + ttl, now = t0)
        assertTrue(v is CommandFreshness.Verdict.Rejected)
        assertTrue((v as CommandFreshness.Verdict.Rejected).reason.contains("too far in future"))
    }

    // ---------- 核心回归：expiresAt 未签名，可被改成 0 / 远期 ----------

    /** 旧实现：expiresAt=0 → 永不过期，20 分钟后的旧 UNLOCK 仍会执行 */
    @Test
    fun `zeroed expiresAt cannot make an old command live forever`() {
        val v = verdict(expiresAt = 0L, now = t0 + 20 * 60_000L)
        assertRejectedContains(v to "signed timestamp cap exceeded")
        assertRejectedContains(v to "NOT covered by HMAC")
    }

    @Test
    fun `far future expiresAt is capped by signed timestamp`() {
        val forgedExpiry = t0 + 365L * 24 * 3600 * 1000
        assertEquals(t0 + cap, CommandFreshness.effectiveExpiryAt(t0, forgedExpiry))
        val v = verdict(expiresAt = forgedExpiry, now = t0 + 20 * 60_000L)
        assertRejectedContains(v to "signed timestamp cap exceeded")
    }

    /** 封顶是"最长寿命"，不是"一到 15 分钟就拒"：窗口内仍放行，由 msgId 幂等窗口兜底 */
    @Test
    fun `within cap is still fresh with zeroed expiresAt`() {
        assertEquals(CommandFreshness.Verdict.Fresh, verdict(expiresAt = 0L, now = t0 + 14 * 60_000L))
        assertEquals(CommandFreshness.Verdict.Fresh, verdict(expiresAt = 0L, now = t0 + cap))
        val v = verdict(expiresAt = 0L, now = t0 + cap + 1)
        assertRejectedContains(v to "signed timestamp cap exceeded")
    }

    /** 服务端将来把 TTL 调大也不会全量拒绝，只是被封顶（收紧而非中断） */
    @Test
    fun `larger server TTL is capped not rejected`() {
        assertEquals(CommandFreshness.Verdict.Fresh, verdict(expiresAt = t0 + 30 * 60_000L, now = t0 + 10 * 60_000L))
        assertEquals(t0 + cap, CommandFreshness.effectiveExpiryAt(t0, t0 + 30 * 60_000L))
        assertRejectedContains(
            verdict(expiresAt = t0 + 30 * 60_000L, now = t0 + 20 * 60_000L) to "signed timestamp cap exceeded"
        )
    }

    /** expiresAt 可以缩短有效期（服务端或中间代理主动收紧），封顶不能把它放宽 */
    @Test
    fun `explicit expiry may shorten but never extend the cap`() {
        val short = t0 + 60_000L
        assertEquals(short, CommandFreshness.effectiveExpiryAt(t0, short))
        assertRejectedContains(verdict(expiresAt = short, now = short + 1) to "expired at")
    }

    // ---------- 边界：如实记录，不谎称覆盖 ----------

    /** timestamp 缺失时没有签名锚点，只能维持旧行为；真实服务端不会这样下发 */
    @Test
    fun `missing signed timestamp falls back to legacy expiresAt only`() {
        assertEquals(CommandFreshness.Verdict.Fresh, verdict(timestamp = 0L, expiresAt = 0L))
        assertEquals(Long.MAX_VALUE, CommandFreshness.effectiveExpiryAt(0L, 0L))
        assertRejectedContains(verdict(timestamp = 0L, expiresAt = t0 - 1, now = t0) to "expired at")
    }

    /** 脏 timestamp 不能因加法溢出把 cap 算成负数，进而把正常指令误判过期 */
    @Test
    fun `dirty timestamp near Long MAX does not produce false expiry`() {
        val dirty = Long.MAX_VALUE - 10L
        assertEquals(Long.MAX_VALUE, CommandFreshness.effectiveExpiryAt(dirty, 0L))
        // 极端读数下也不能把 cap 算成负数而误判过期；脏值应交给验签关卡处理
        assertEquals(CommandFreshness.Verdict.Fresh, CommandFreshness.evaluate(dirty, 0L, dirty))
    }

    /**
     * 判定只依赖传入的**受信**时刻：now 回拨一天会被"未来偏差"挡住（既有语义），
     * now 越过封顶则被"签名封顶"挡住 —— 两端都不能靠改时钟让旧指令复活。
     */
    @Test
    fun `evaluation depends only on the supplied trusted now`() {
        assertRejectedContains(
            CommandFreshness.evaluate(t0, 0L, t0 - 24 * 3600 * 1000L) to "too far in future",
            CommandFreshness.evaluate(t0, 0L, t0 + cap + 1) to "cap exceeded"
        )
    }
}
