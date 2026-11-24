package com.padguard.core.engine.lock

import android.content.Context
import android.content.SharedPreferences
import com.padguard.core.common.Logger
import com.padguard.core.common.TimeProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * [LockController] 持久化语义的 JVM 行为测试。
 *
 * 重点守护「解绑/远程解锁后设备不会重新被锁」这一不变量：
 * REMOTE 锁定必须落盘（指令一次性，进程死不掉），而 releaseAll() / tempUnlock() /
 * releaseLock(REMOTE) 必须把落盘标记一并清除，否则进程重启后 onDeviceBoot()/init 的
 * restoreRemoteLock() 会把已解绑的设备再次锁上，表现为「家长端解绑了，孩子端却重启后
 * 又卡在锁屏页」——正是 handleRemoteUnbind 缺漏锁态清理时的回归点。
 *
 * JVM rig 说明：
 * - Context / SharedPreferences 为 final 类，Mockito 5 inline mockmaker 直接 mock；
 *   用 write-through 的内存 Map 充当「同一设备持久化存储」，跨 LockController 实例共享，
 *   第二个实例模拟「进程重启后重新构造」；
 * - Logger 关闭（android.util.Log 在 JVM 是 stub）。
 */
class LockControllerTest {

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

    /**
     * 返回一个 mock 的 SharedPreferences，其读写落在一个可跨实例共享的内存 Map 上，
     * 模拟「同一设备的持久化存储」。Editor 的 put/remove/clear 同步写 Map，apply()/commit() 为空操作。
     */
    private fun sharedPrefsBackedBy(map: MutableMap<String, Any?>): SharedPreferences {
        val prefs = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java)

        fun writeThrough(inv: org.mockito.invocation.InvocationOnMock): SharedPreferences.Editor {
            val key = inv.arguments[0] as String
            if (inv.arguments.size > 1) map[key] = inv.arguments[1]
            return editor
        }

        Mockito.`when`(editor.putString(Mockito.anyString(), Mockito.nullable(String::class.java)))
            .thenAnswer { writeThrough(it) }
        Mockito.`when`(editor.putBoolean(Mockito.anyString(), Mockito.anyBoolean()))
            .thenAnswer { writeThrough(it) }
        Mockito.`when`(editor.putInt(Mockito.anyString(), Mockito.anyInt()))
            .thenAnswer { writeThrough(it) }
        Mockito.`when`(editor.putLong(Mockito.anyString(), Mockito.anyLong()))
            .thenAnswer { writeThrough(it) }
        Mockito.`when`(editor.putFloat(Mockito.anyString(), Mockito.anyFloat()))
            .thenAnswer { writeThrough(it) }
        Mockito.`when`(editor.remove(Mockito.anyString())).thenAnswer { inv ->
            map.remove(inv.arguments[0] as String); editor
        }
        Mockito.`when`(editor.clear()).thenAnswer { map.clear(); editor }
        Mockito.doAnswer { }.`when`(editor).apply()
        Mockito.`when`(editor.commit()).thenReturn(true)
        Mockito.`when`(prefs.edit()).thenReturn(editor)

        Mockito.`when`(prefs.getBoolean(Mockito.anyString(), Mockito.anyBoolean())).thenAnswer { inv ->
            (map[inv.arguments[0] as String] as? Boolean) ?: inv.arguments[1]
        }
        Mockito.`when`(prefs.getString(Mockito.anyString(), Mockito.nullable(String::class.java))).thenAnswer { inv ->
            (map[inv.arguments[0] as String] as? String) ?: inv.arguments[1]
        }
        Mockito.`when`(prefs.getInt(Mockito.anyString(), Mockito.anyInt())).thenAnswer { inv ->
            (map[inv.arguments[0] as String] as? Int) ?: inv.arguments[1]
        }
        Mockito.`when`(prefs.getLong(Mockito.anyString(), Mockito.anyLong())).thenAnswer { inv ->
            (map[inv.arguments[0] as String] as? Long) ?: inv.arguments[1]
        }
        Mockito.`when`(prefs.getFloat(Mockito.anyString(), Mockito.anyFloat())).thenAnswer { inv ->
            (map[inv.arguments[0] as String] as? Float) ?: inv.arguments[1]
        }
        Mockito.`when`(prefs.contains(Mockito.anyString())).thenAnswer { inv -> map.containsKey(inv.arguments[0] as String) }
        return prefs
    }

    /** 构造一个返回共享 prefs 的 Context mock */
    private fun contextWith(prefs: SharedPreferences): Context {
        val ctx = Mockito.mock(Context::class.java)
        Mockito.`when`(ctx.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(prefs)
        return ctx
    }

    /**
     * 返回 mock 的 TimeProvider。
     *
     * 关键：纯 JVM 单测里 android.os.SystemClock 是 stub，一调用就抛 "Stub!"；
     * 而 [LockController.tempUnlock] 会调用 elapsedRealtime()，必须用 mock 避开该 stub，
     * 否则测试在 tempUnlock 处抛 RuntimeException（与产品逻辑无关，纯测试环境问题）。
     */
    private fun fakeTimeProvider(): TimeProvider {
        val tp = Mockito.mock(TimeProvider::class.java)
        Mockito.`when`(tp.elapsedRealtime()).thenReturn(1_000L)
        Mockito.`when`(tp.now()).thenReturn(1_000_000L)
        return tp
    }

    @Test
    fun `requestLock REMOTE persists and releaseAll clears it so restart does NOT re-lock`() {
        val prefs = sharedPrefsBackedBy(mutableMapOf())
        val ctx = contextWith(prefs)

        // 设备在线、家长远程锁屏
        val online = LockController(TimeProvider(), ctx)
        assertFalse("初始不应锁定", online.state.value.locked)
        online.requestLock(LockReason.REMOTE, detail = "家长已远程锁定设备")
        assertTrue("REMOTE 锁定后应为锁定态", online.state.value.locked)
        assertTrue("REMOTE 锁定必须落盘", prefs.getBoolean("remote_locked", false))

        // 家长端解绑 → 触发 releaseAll（对应 handleRemoteUnbind 中的锁态清理）
        online.releaseAll()
        assertFalse("releaseAll 后应为解锁态", online.state.value.locked)
        assertFalse("releaseAll 必须清除落盘的 REMOTE 标记", prefs.getBoolean("remote_locked", false))

        // 模拟进程重启：重新构造 LockController，init 会 restoreRemoteLock()
        val afterRestart = LockController(TimeProvider(), ctx)
        assertFalse("重启后不应因残留落盘标记而重新锁定", afterRestart.state.value.locked)
    }

    @Test
    fun `tempUnlock clears persisted remote lock`() {
        val prefs = sharedPrefsBackedBy(mutableMapOf())
        val ctx = contextWith(prefs)

        val lc = LockController(fakeTimeProvider(), ctx)
        lc.requestLock(LockReason.REMOTE, detail = "家长已远程锁定设备")
        assertTrue("REMOTE 锁定必须落盘", prefs.getBoolean("remote_locked", false))

        // 家长批准解锁申请 → tempUnlock
        lc.tempUnlock(30)
        assertFalse("tempUnlock 后应为解锁态", lc.state.value.locked)
        assertFalse("tempUnlock 必须清除落盘的 REMOTE 标记", prefs.getBoolean("remote_locked", false))
    }

    @Test
    fun `releaseLock REMOTE clears persisted remote lock`() {
        val prefs = sharedPrefsBackedBy(mutableMapOf())
        val ctx = contextWith(prefs)

        val lc = LockController(TimeProvider(), ctx)
        lc.requestLock(LockReason.REMOTE, detail = "家长已远程锁定设备")
        assertTrue("REMOTE 锁定必须落盘", prefs.getBoolean("remote_locked", false))

        lc.releaseLock(LockReason.REMOTE)
        assertFalse("releaseLock(REMOTE) 后应为解锁态", lc.state.value.locked)
        assertFalse("releaseLock(REMOTE) 必须清除落盘标记", prefs.getBoolean("remote_locked", false))
    }

    // ==================== AWAITING_START 等待页（2026-11-09：每日计时须孩子确认） ====================

    /**
     * 等待页由 PolicyEngine.tick 每轮经 syncAutoReasons 差集派生/释放：
     * awaitingStart 成立 → 挂 AWAITING_START；孩子确认后会话转 active →
     * 下一轮差集不再包含 → 自动释放。锁定原因本身不落盘（与 DAILY_LIMIT 同型）。
     */
    @Test
    fun `AWAITING_START is raised and released by syncAutoReasons diff`() {
        val ctx = contextWith(sharedPrefsBackedBy(mutableMapOf()))
        val lc = LockController(fakeTimeProvider(), ctx)

        // 日切后进入待确认：awaitingStart=true → 等待页
        lc.syncAutoReasons(
            mapOf(LockReason.AWAITING_START to LockDetail("今日使用计时尚未开始"))
        )
        assertTrue("等待页必须锁住（未确认期间不可自由使用）", lc.state.value.locked)
        assertTrue(
            "主原因应为 AWAITING_START（等待开始）",
            lc.state.value.primaryReason == LockReason.AWAITING_START
        )

        // 孩子确认、会话转 active：下一轮差集不再包含 → 解锁
        lc.syncAutoReasons(emptyMap())
        assertFalse("确认后等待页必须解除", lc.state.value.locked)
    }

    /**
     * 家长 UNLOCK（releaseAll）压不灭等待页：解锁指令清空原因集合，
     * 但自动原因由下一轮自检重新派生 —— 等待开始不是故障态，
     * 不能被"解锁"永久消掉（否则 UNLOCK 就成了绕过入口）。
     */
    @Test
    fun `UNLOCK cannot permanently dismiss AWAITING_START`() {
        val ctx = contextWith(sharedPrefsBackedBy(mutableMapOf()))
        val lc = LockController(fakeTimeProvider(), ctx)

        lc.syncAutoReasons(mapOf(LockReason.AWAITING_START to LockDetail("今日使用计时尚未开始")))
        assertTrue(lc.state.value.locked)

        // 家长下发 UNLOCK（releaseAll）
        lc.releaseAll()
        assertFalse("UNLOCK 后暂时解锁", lc.state.value.locked)

        // 下一轮自检：awaitingStart 仍成立 → 等待页重新挂上
        lc.syncAutoReasons(mapOf(LockReason.AWAITING_START to LockDetail("今日使用计时尚未开始")))
        assertTrue("等待页必须经自检重新挂上（UNLOCK 不是绕过入口）", lc.state.value.locked)
    }

    /**
     * 暂停计时期间设备不可操作是硬承诺：家长临时解锁/远程 UNLOCK 都不能留下可用态。
     * 与 AWAITING_START 不同，TIMER_PAUSED 在 requestLock 层豁免临时解锁抑制。
     */
    @Test
    fun `TIMER_PAUSED cannot be bypassed by temp unlock or UNLOCK`() {
        val ctx = contextWith(sharedPrefsBackedBy(mutableMapOf()))
        val lc = LockController(fakeTimeProvider(), ctx)
        val paused = mapOf(LockReason.TIMER_PAUSED to LockDetail("计时已暂停"))

        lc.syncAutoReasons(paused)
        assertTrue(lc.state.value.reasons.contains(LockReason.TIMER_PAUSED))

        // 家长误批一次解锁申请 → 清空锁定态，但自动原因下一轮必须重新派生
        lc.tempUnlock(30)
        assertFalse("tempUnlock 会先清空全部原因", lc.state.value.locked)
        lc.syncAutoReasons(paused)
        assertTrue("临时解锁不得压过暂停计时（否则孩子申请一次解锁即可绕过）",
            lc.state.value.reasons.contains(LockReason.TIMER_PAUSED))

        lc.releaseAll()
        lc.syncAutoReasons(paused)
        assertTrue("UNLOCK 后下一轮自检必须重新挂上暂停锁",
            lc.state.value.reasons.contains(LockReason.TIMER_PAUSED))

        // 孩子点「继续计时」→ 会话 resume，差集里不再有该原因 → 立即释放
        lc.syncAutoReasons(emptyMap())
        assertFalse("恢复计时后必须解锁", lc.state.value.locked)
    }

    /** AWAITING_START 不落盘：重启后由策略自检重新派生，不靠残留标记恢复 */
    @Test
    fun `AWAITING_START is not persisted`() {
        val prefs = sharedPrefsBackedBy(mutableMapOf())
        val ctx = contextWith(prefs)

        val lc = LockController(TimeProvider(), ctx)
        lc.syncAutoReasons(mapOf(LockReason.AWAITING_START to LockDetail("今日使用计时尚未开始")))
        assertTrue(lc.state.value.locked)

        // 模拟进程重启：无任何落盘残留，等待页由重启后的自检重新派生
        val afterRestart = LockController(TimeProvider(), ctx)
        assertFalse("重启后不应有残留锁定态（等待页走自检重新派生）", afterRestart.state.value.locked)
        assertFalse("等待页绝不写 remote_lock 落盘文件", prefs.getBoolean("remote_locked", false))
    }
}
