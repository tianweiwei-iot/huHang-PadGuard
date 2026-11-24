package com.padguard.core.data.repository

import com.padguard.core.data.model.policy.PolicyPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PolicyCacheFallback] 的纯 JVM 回归（新增缺陷 L）。
 *
 * 钉住三件曾经同时失效的事：
 * 1. 策略行**解不开**不等于「从未下发过策略」——前者必须沿用上次良好快照，只有后者才允许回落不管控基线；
 * 2. 缓存不可信时禁止把默认策略回写到应用管控镜像（否则连那份好快照一起清空）；
 * 3. 保留坏行之后必须让服务端重发能覆盖它，否则「不清表」反而造成永远无法自愈。
 *
 * Room / Keystore 的副作用留在 [PolicyRepository]，这里只验证决策层，
 * 全链路（解密失败 → observe 发射 → mirror 跳过）由静态走查覆盖，需真机回归见报告 B-06。
 */
class PolicyCacheFallbackTest {

    private fun good(version: Int) = PolicyPackage(version = version, deviceId = "D1")

    // ==================== 默认策略的语义 ====================

    @Test
    fun `default package really is an unmanaged baseline`() {
        // 这条是整组测试的前提：回落 default 不是「少管一点」，而是时间/应用管控全放开。
        val d = PolicyPackage.default()
        assertEquals(0, d.version)
        assertTrue(d.schedule.rules.isEmpty())
        assertEquals(0, d.appLimit.dailyTotalMinutes)
        assertTrue(d.app.blacklist.isEmpty())
        assertTrue(d.app.whitelist.isEmpty())
    }

    // ==================== resolve ====================

    @Test
    fun `no entity means never issued a policy and default is trusted`() {
        val r = PolicyCacheFallback.resolve(entityPresent = false, decoded = null, lastKnownGood = null)
        assertEquals(PolicyPackage.default(), r)
        assertTrue(PolicyCacheFallback.shouldMirror(decodeFailed = false, lastKnownGood = null))
    }

    @Test
    fun `lost policy row never yields a trusted empty baseline`() {
        val previous = good(7)
        // 已下发过策略却丢行：有内存好快照必须沿用，绝不返回"宵禁关/名单空"的 default。
        assertSame(
            previous,
            PolicyCacheFallback.resolve(
                entityPresent = false, decoded = null, lastKnownGood = previous, policyRowLost = true
            )
        )
    }

    @Test
    fun `lost policy row without in-memory snapshot still must not be mirrored`() {
        val r = PolicyCacheFallback.resolve(
            entityPresent = false, decoded = null, lastKnownGood = null, policyRowLost = true
        )
        // 冷启动即丢行：只能给 default 让 UI 不崩，但 repository 会把 decodeFailed 置 true，
        // shouldMirror(false,...) 这条断言锁死"default 绝不写进应用管控镜像"。
        assertEquals(PolicyPackage.default(), r)
        assertFalse(PolicyCacheFallback.shouldMirror(decodeFailed = true, lastKnownGood = null))
    }

    @Test
    fun `successful decode wins over last known good`() {
        val decoded = good(9)
        assertSame(decoded, PolicyCacheFallback.resolve(true, decoded, good(8)))
    }

    @Test
    fun `decode failure keeps last known good instead of falling back to unmanaged default`() {
        val previous = good(7)
        val r = PolicyCacheFallback.resolve(entityPresent = true, decoded = null, lastKnownGood = previous)
        assertSame(previous, r)
        assertEquals(7, r.version)
    }

    @Test
    fun `cold start decode failure without any copy still falls back to default`() {
        // 如实记录残余边界：本进程没有任何可信副本时只能回落 default，
        // 但此时 must NOT mirror（下一条测试），且版本上报必须为 0 触发重发。
        assertEquals(
            PolicyPackage.default(),
            PolicyCacheFallback.resolve(entityPresent = true, decoded = null, lastKnownGood = null)
        )
    }

    // ==================== shouldMirror ====================

    @Test
    fun `unreadable cache without copy must not overwrite app policy mirror`() {
        assertFalse(PolicyCacheFallback.shouldMirror(decodeFailed = true, lastKnownGood = null))
    }

    @Test
    fun `unreadable cache with a known good copy may still mirror that copy`() {
        // 写回的是同一份良好策略，不会降低管控强度；不能一律禁写，否则正常同步链路被拖停。
        assertTrue(PolicyCacheFallback.shouldMirror(decodeFailed = true, lastKnownGood = good(7)))
    }

    @Test
    fun `healthy cache always mirrors`() {
        assertTrue(PolicyCacheFallback.shouldMirror(decodeFailed = false, lastKnownGood = null))
    }

    // ==================== shouldAcceptIncoming ====================

    @Test
    fun `healthy cache keeps anti-rollback version gate`() {
        assertFalse(PolicyCacheFallback.shouldAcceptIncoming(false, 7, 7))
        assertFalse(PolicyCacheFallback.shouldAcceptIncoming(false, 6, 7))
        assertTrue(PolicyCacheFallback.shouldAcceptIncoming(false, 8, 7))
    }

    @Test
    fun `corrupt cache accepts re-issued policy even at same or lower version`() {
        // 服务端按"设备已是 v7"只重发 v7；若仍按版本比较丢弃，坏行永远修不回来。
        assertTrue(PolicyCacheFallback.shouldAcceptIncoming(true, 7, 7))
        assertTrue(PolicyCacheFallback.shouldAcceptIncoming(true, 1, 7))
        assertTrue(PolicyCacheFallback.shouldAcceptIncoming(true, 8, 7))
    }

    // ==================== reportedVersion ====================

    @Test
    fun `corrupt cache reports version zero so server re-issues policy`() {
        assertEquals(0, PolicyCacheFallback.reportedVersion(decodeFailed = true, rawVersion = 7))
        assertEquals(7, PolicyCacheFallback.reportedVersion(decodeFailed = false, rawVersion = 7))
    }

    @Test
    fun `negative or dirty version is clamped to zero`() {
        assertEquals(0, PolicyCacheFallback.reportedVersion(decodeFailed = false, rawVersion = -5))
    }
}
