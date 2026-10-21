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
}
