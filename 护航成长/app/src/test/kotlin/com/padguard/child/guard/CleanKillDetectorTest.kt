package com.padguard.child.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「守护进程被清理/中断后拉回」判定纯函数矩阵（2026-11-17 EMUI 一键清理事故闭环）。
 *
 * 判定依据是跨进程心跳文件 `guard_alive` 里的 elapsedRealtime：上次心跳就在
 * 10 分钟内、而本次是全新进程 → 上次进程是活着被系统杀掉的。
 * 锁死的四条不误报边界：设备重启 / 新装清数据 / 长时间关机 / 心跳值脏。
 */
class CleanKillDetectorTest {

    private val window = CleanKillDetector.DETECT_WINDOW_MS

    @Test
    fun `被清理后拉回_上次心跳在窗内`() {
        // 一键清理典型形态：主循环 15s 一跳，被杀后 30s 内被看门狗拉回
        assertTrue(CleanKillDetector.killedWhileAlive(prevMono = 100_000L, nowMono = 130_000L, windowMs = window))
        // 刚刚被杀立刻拉回
        assertTrue(CleanKillDetector.killedWhileAlive(prevMono = 100_000L, nowMono = 100_005L, windowMs = window))
        // 窗边界：恰好等于窗口也算（<=），余量判比漏判好——漏一条就是家长少一条证据
        assertTrue(CleanKillDetector.killedWhileAlive(prevMono = 100_000L, nowMono = 100_000L + window, windowMs = window))
    }

    @Test
    fun `设备重启不计数_单调钟归零`() {
        // 重启后 elapsedRealtime 归零：now < prev → 正常重启，绝不能算"被杀"
        assertFalse(CleanKillDetector.killedWhileAlive(prevMono = 500_000L, nowMono = 3_000L, windowMs = window))
    }

    @Test
    fun `新装或清数据不计数_无心跳记录`() {
        assertFalse(CleanKillDetector.killedWhileAlive(prevMono = null, nowMono = 10_000L, windowMs = window))
        // 文件存在但内容脏（写一半/非数字）：toLongOrNull 得 null，同上不计数
        assertFalse(CleanKillDetector.killedWhileAlive(prevMono = null, nowMono = 10_000L, windowMs = window))
    }

    @Test
    fun `脏心跳值不计数`() {
        // 0 / 负数是脏盘值（老版本残留或异常写），绝不能拿去判定
        assertFalse(CleanKillDetector.killedWhileAlive(prevMono = 0L, nowMono = 10_000L, windowMs = window))
        assertFalse(CleanKillDetector.killedWhileAlive(prevMono = -5L, nowMono = 10_000L, windowMs = window))
    }

    @Test
    fun `长时间关机后再开不计数_超出判定窗`() {
        // 关机一下午再开机：心跳是几小时前的（同 boot 的 elapsedRealtime 连续），
        // 已超出"被杀后拉回"的语义——那是普通停止，不是清理事故
        assertFalse(
            CleanKillDetector.killedWhileAlive(prevMono = 100_000L, nowMono = 100_000L + window + 1L, windowMs = window)
        )
        // 用户强停后隔天手动打开：prev 是强停前的旧心跳，超窗同样不计数
        assertFalse(
            CleanKillDetector.killedWhileAlive(prevMono = 1_000L, nowMono = 20 * 60_000L, windowMs = window)
        )
    }
}
