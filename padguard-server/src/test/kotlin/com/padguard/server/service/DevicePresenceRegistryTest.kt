package com.padguard.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 在线态「长轮询挂起」回归测试。
 *
 * 复现的现场故障：孩子端 `GET /api/v1/device/commands?waitMs=20000` 挂起 20 秒等指令，
 * 期间不会再发任何 HTTP 请求；服务端按「约 5.4 秒无信号」判离线 —— 每轮长轮询返回前
 * 必被判一次 OFFLINE 并推 WS，返回后下一请求又立刻 ONLINE，家长端看到反复横跳，
 * 还连带刷出「守护进程失联 / 管控连接中断」告警。
 *
 * 钉住四条：
 * 1. 挂起期间连接活着，绝不判离线；
 * 2. 宽限是绝对到期时刻，到期后**不立刻**判离线（扫描期间已续约 lastSeenAt），
 *    但真断网后仍按常规阈值及时判离线，不会把设备永久钉在在线；
 * 3. 没有挂起的设备仍按原阈值秒级判定，宽限不得外溢到别的设备；
 * 4. 同一设备多个并发长轮询取最晚截止时刻，解绑清表能清掉宽限。
 */
class DevicePresenceRegistryTest {

    private val t0 = 1_700_000_000_000L

    /** 模拟 PresenceMonitorService 每 1s 扫一次，累计返回这段时间内被判离线的设备 */
    private fun scanRange(r: DevicePresenceRegistry, from: Long, to: Long): List<String> {
        val timedOut = mutableListOf<String>()
        var t = from
        while (t <= to) {
            timedOut += r.takeTimedOut(t)
            t += 1_000L
        }
        return timedOut
    }

    @Test
    fun `long poll hold keeps device online well beyond the normal timeout`() {
        val r = DevicePresenceRegistry()
        r.touch("d1", t0)
        r.hold("d1", 20_000L, t0)

        // 20 秒挂起期间一次都不许判离线（原实现 5.4s 就会翻一次）
        val timedOut = scanRange(r, t0 + 1_000L, t0 + 19_000L)
        assertTrue(timedOut.isEmpty(), "挂起期间不应判离线，实际=$timedOut")
        assertTrue(r.onlineIds().contains("d1"))
        // 扫描线程持续续约，lastSeenAt 不能停在请求发起时刻
        assertTrue((r.lastSeenAt("d1") ?: 0L) >= t0 + 18_000L)
    }

    @Test
    fun `hold expiry resumes normal timeout instead of flipping immediately`() {
        val r = DevicePresenceRegistry()
        r.touch("d1", t0)
        r.hold("d1", 20_000L, t0)
        scanRange(r, t0 + 1_000L, t0 + 19_000L)

        // 宽限刚到期这一帧：lastSeenAt 已被续约，不能立刻闪一下离线
        assertTrue(r.takeTimedOut(t0 + 20_000L).isEmpty())

        // 设备真的不再发任何请求 → 仍按常规阈值（1.8×3s=5.4s）及时判离线
        val after = scanRange(r, t0 + 21_000L, t0 + 27_000L)
        assertEquals(listOf("d1"), after)
        assertFalse(r.onlineIds().contains("d1"))
    }

    @Test
    fun `device without hold still times out at the adaptive threshold`() {
        val r = DevicePresenceRegistry()
        r.touch("d1", t0)
        assertEquals(emptyList<String>(), r.takeTimedOut(t0 + 5_000L))
        assertEquals(listOf("d1"), r.takeTimedOut(t0 + 6_000L))
    }

    @Test
    fun `hold on one device does not protect another device`() {
        val r = DevicePresenceRegistry()
        r.touch("d1", t0)
        r.touch("d2", t0)
        r.hold("d1", 20_000L, t0)

        // 每秒扫描，d2 在第 6 秒被判离线，d1 全程保持在线
        val timedOut = scanRange(r, t0 + 1_000L, t0 + 8_000L)
        assertEquals(listOf("d2"), timedOut.distinct())
        assertTrue(r.onlineIds().contains("d1"))
    }

    @Test
    fun `hold duration is capped so a stuck connection cannot pin device online forever`() {
        val r = DevicePresenceRegistry()
        r.touch("d1", t0)
        // 客户端即使传入荒谬的挂起时长，也只按 MAX_HOLD_MS 兜底
        r.hold("d1", 999_999_999L, t0)

        assertTrue(scanRange(r, t0 + 1_000L, t0 + 30_000L).isEmpty())
        // 兜底窗口过后仍会照常收敛为离线
        assertEquals(listOf("d1"), scanRange(r, t0 + 31_000L, t0 + 37_000L).distinct())
    }

    @Test
    fun `concurrent long polls extend the hold to the latest deadline`() {
        val r = DevicePresenceRegistry()
        r.touch("d1", t0)
        r.hold("d1", 20_000L, t0)            // 截止 t0+20s
        r.hold("d1", 20_000L, t0 + 10_000L) // 另一条循环新起的长轮询，截止 t0+30s

        assertTrue(scanRange(r, t0 + 1_000L, t0 + 25_000L).isEmpty())
        assertEquals(listOf("d1"), scanRange(r, t0 + 31_000L, t0 + 37_000L).distinct())
    }

    @Test
    fun `markOffline clears the hold so unbound device cannot stay online`() {
        val r = DevicePresenceRegistry()
        r.touch("d1", t0)
        r.hold("d1", 20_000L, t0)
        r.markOffline("d1")

        assertEquals(emptyList<String>(), r.takeTimedOut(t0 + 30_000L))
        assertEquals(null, r.lastSeenAt("d1"))
    }
}
