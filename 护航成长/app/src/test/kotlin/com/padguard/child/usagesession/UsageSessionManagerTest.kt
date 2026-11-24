package com.padguard.child.usagesession

import android.content.Context
import android.content.SharedPreferences
import com.padguard.core.common.TimeProvider
import com.padguard.core.data.repository.AuthRepository
import com.padguard.core.data.repository.UsageRepository
import com.padguard.core.engine.lock.LockController
import com.padguard.core.transport.RemoteDataSource
import com.padguard.core.transport.http.ApiResult
import com.padguard.core.transport.http.CredentialStore
import com.padguard.core.common.Logger
import com.padguard.core.transport.http.UsageSessionDto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * [UsageSessionManager] 计时会话中枢的行为测试（JVM，纯 mock）。
 *
 * 守护的核心故障链路：**时间到锁屏 → 跨日锁屏不消失 → 无法解锁 / 无法开次日计时**。
 * 其中「跨日无法重置」「次日无法起表」由本类直接守住；命令解锁 / 确认由 [com.padguard.core.engine.command.CommandExecutor] 测试守住。
 *
 * SharedPreferences 用 write-through 内存 Map 充当持久化（同 LockControllerTest 模式）。
 * Logger 在 JVM 是 stub，无需关闭。
 */
class UsageSessionManagerTest {

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

    // 与 UsageSessionManager 内部 private const 对齐（改动需同步）
    private companion object {
        const val KEY_DAY = "day"
        const val KEY_ACTIVE = "active"
        const val KEY_REMAINING = "remaining_ms"
        const val KEY_SERVER_NOW = "server_now"
        const val KEY_ELAPSED = "saved_elapsed"
        const val KEY_TIME_CONTROL = "time_control_enabled"
        const val KEY_QUOTA = "quota_minutes"
        const val KEY_EXTENDED = "extended_minutes"
        const val YESTERDAY = "20261109"
        const val TODAY = "20261110"
        const val TODAY_WALL = 1_700_000_000_000L
    }

    private fun fakePrefs(map: MutableMap<String, Any?>): SharedPreferences {
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
        Mockito.`when`(editor.remove(Mockito.anyString())).thenAnswer {
            map.remove(it.arguments[0] as String); editor
        }
        Mockito.`when`(prefs.edit()).thenReturn(editor)
        Mockito.`when`(prefs.getString(Mockito.anyString(), Mockito.nullable(String::class.java)))
            .thenAnswer { map[it.arguments[0] as String] as? String }
        Mockito.`when`(prefs.getBoolean(Mockito.anyString(), Mockito.anyBoolean()))
            .thenAnswer { map[it.arguments[0] as String] as? Boolean ?: it.arguments[1] as Boolean }
        Mockito.`when`(prefs.getLong(Mockito.anyString(), Mockito.anyLong()))
            .thenAnswer { map[it.arguments[0] as String] as? Long ?: it.arguments[1] as Long }
        Mockito.`when`(prefs.getInt(Mockito.anyString(), Mockito.anyInt()))
            .thenAnswer { map[it.arguments[0] as String] as? Int ?: it.arguments[1] as Int }
        return prefs
    }

    private data class Fixture(
        val manager: UsageSessionManager,
        val remote: RemoteDataSource,
        val credentials: CredentialStore,
        val authRepository: AuthRepository,
        val time: TimeProvider
    )

    private fun build(map: MutableMap<String, Any?>): Fixture =
        buildAt(map, TODAY_WALL, 0L)

    /** 以指定的"当前"墙钟/单调钟构造：冷启动恢复在构造期读时间，必须先注入再建 manager */
    private fun buildAt(map: MutableMap<String, Any?>, nowWall: Long, mono: Long): Fixture {
        val prefs = fakePrefs(map)
        val ctx = Mockito.mock(Context::class.java)
        Mockito.`when`(ctx.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(prefs)
        val tp = Mockito.mock(TimeProvider::class.java)
        Mockito.`when`(tp.now()).thenReturn(nowWall)
        Mockito.`when`(tp.monotonicMs()).thenReturn(mono)
        val ur = Mockito.mock(UsageRepository::class.java)
        // 任意墙钟都落在今天：冷启动测试会把墙钟推进到恢复时刻，
        // 若只 mock TODAY_WALL，未覆盖的时间戳会让 dayKey 返回 null 被误判跨日。
        Mockito.`when`(ur.dayKey(Mockito.anyLong())).thenReturn(TODAY)
        val remote = Mockito.mock(RemoteDataSource::class.java)
        val credentials = Mockito.mock(CredentialStore::class.java)
        val authRepository = Mockito.mock(AuthRepository::class.java)
        val manager = UsageSessionManager(
            remote = remote,
            timeProvider = tp,
            lockController = Mockito.mock(LockController::class.java),
            authRepository = authRepository,
            credentials = credentials,
            usageRepository = ur,
            appContext = ctx
        )
        return Fixture(manager, remote, credentials, authRepository, tp)
    }

    // ==================== 跨日重置（守护「跨日锁屏不消失」）====================

    @Test
    fun `checkDayRollover 跨日把会话重置为待确认`() {
        val map = mutableMapOf<String, Any?>()
        val (m) = build(map)
        // 落盘快照：昨天用满额度
        map[KEY_DAY] = YESTERDAY
        map[KEY_ACTIVE] = true
        map[KEY_REMAINING] = 0L
        map[KEY_TIME_CONTROL] = true
        m.checkDayRollover()
        val s = m.state.value
        assertFalse("跨日必须退出 active", s.active)
        assertTrue("时间管控开启时应进入待确认（弹开始计时）", s.needsStartConfirm)
        assertEquals("落盘必须 restamp 到今天，否则下次重启又命中跨日", TODAY, map[KEY_DAY])
    }

    @Test
    fun `跨日必须把含延时的总额回落为基础额度`() = runBlocking {
        val map = mutableMapOf<String, Any?>()
        val f = build(map)
        Mockito.`when`(f.credentials.isBound).thenReturn(true)
        Mockito.`when`(f.authRepository.getDeviceId()).thenReturn("device-1")
        // 服务端口径：quotaMinutes=基础 120，extendedMinutes=昨日已批 30；
        // 内存态按 UI 口径展示为总额 150。
        val dto = UsageSessionDto(
            active = true, quotaMinutes = 120, extendedMinutes = 30,
            remainingMs = 90L * 60_000L, dayKey = TODAY,
            startEpoch = TODAY_WALL, timeControlEnabled = true
        )
        Mockito.`when`(f.remote.getUsageSession()).thenReturn(ApiResult.Success(dto, TODAY_WALL))
        f.manager.refresh()
        assertEquals(150, f.manager.state.value.quotaMinutes)

        map[KEY_DAY] = YESTERDAY
        f.manager.checkDayRollover()

        val s = f.manager.state.value
        assertEquals("昨天的延时不得固化进今天的基础额度", 120, s.quotaMinutes)
        assertEquals(0, s.extendedMinutes)
        assertEquals("磁盘快照也必须写基础额度，否则离线起表仍会继承膨胀值", 120, map[KEY_QUOTA])
        assertEquals(0, map[KEY_EXTENDED])
    }

    @Test
    fun `checkDayRollover 跨日且时间管控关闭时不弹确认`() {
        // 前置 prefs 必须在 build() 之前填好：restorePersistedSession() 在构造时即读初值，
        // 之后才填的 prefs 不会回灌到 _state（timeControlEnabled 来自内存态而非 prefs）。
        val map = mutableMapOf<String, Any?>(
            KEY_DAY to YESTERDAY,
            KEY_ACTIVE to true,
            KEY_TIME_CONTROL to false
        )
        val (m) = build(map)
        m.checkDayRollover()
        val s = m.state.value
        assertFalse(s.active)
        assertFalse("总开关关闭时跨日也不应弹确认（关总开关就别锁）", s.needsStartConfirm)
    }

    @Test
    fun `checkDayRollover 同日幂等不重置`() {
        // 前置 prefs 必须在 build() 之前填好：否则 restorePersistedSession() 构造时读到空 map，
        // _state.active 停在默认 false，checkDayRollover 同日幂等分支也不会把它改回 true。
        val map = mutableMapOf<String, Any?>(
            KEY_DAY to TODAY,
            KEY_ACTIVE to true,
            KEY_REMAINING to 0L,
            KEY_TIME_CONTROL to true
        )
        val (m) = build(map)
        m.checkDayRollover()
        val s = m.state.value
        assertTrue("同日不应清空 active", s.active)
        assertFalse(s.needsStartConfirm)
    }

    @Test
    fun `跨日重置后 isAwaitingStart 为 true（次日计时入口可达）`() {
        val map = mutableMapOf<String, Any?>()
        val (m) = build(map)
        map[KEY_DAY] = YESTERDAY
        map[KEY_ACTIVE] = true
        m.checkDayRollover()
        assertTrue(m.isAwaitingStart())
    }

    // ==================== isExpired 语义 ====================

    @Test
    fun `isExpired 在 active 且剩余耗尽时为 true`() {
        // 构造前 preset 同日快照，init 的 restore 会把它应用为 active 态
        val map = mutableMapOf<String, Any?>(
            KEY_DAY to TODAY,
            KEY_ACTIVE to true,
            KEY_REMAINING to 0L,
            KEY_TIME_CONTROL to true
        )
        val (m) = build(map)
        assertTrue(m.isExpired())
    }

    @Test
    fun `isExpired 在时间管控关闭时为 false（关总开关就别锁）`() {
        val map = mutableMapOf<String, Any?>(
            KEY_DAY to TODAY,
            KEY_ACTIVE to true,
            KEY_REMAINING to 0L,
            KEY_TIME_CONTROL to false
        )
        val (m) = build(map)
        assertFalse(m.isExpired())
    }

    // ==================== 确认开始计时（守护「无法开次日计时」）====================

    @Test
    fun `confirmStart 在线成功起表`() = runBlocking {
        val map = mutableMapOf<String, Any?>()
        val f = build(map)
        Mockito.`when`(f.credentials.isBound).thenReturn(true)
        Mockito.`when`(f.authRepository.getDeviceId()).thenReturn("device-1")
        val dto = UsageSessionDto(
            active = true,
            quotaMinutes = 120,
            remainingMs = 120L * 60_000L,
            dayKey = TODAY,
            startEpoch = TODAY_WALL,
            timeControlEnabled = true
        )
        Mockito.`when`(f.remote.startUsageSession()).thenReturn(ApiResult.Success(dto, TODAY_WALL))
        val ok = f.manager.confirmStart()
        assertTrue(ok)
        val s = f.manager.state.value
        assertTrue("起表后必须 active", s.active)
        assertEquals(120, s.quotaMinutes)
        assertFalse("在线起表无需离线补发", s.pendingStartSync)
    }

    @Test
    fun `confirmStart 离线时按本地受信钟起表并标记待同步`() = runBlocking {
        val map = mutableMapOf<String, Any?>()
        val f = build(map)
        Mockito.`when`(f.credentials.isBound).thenReturn(true)
        Mockito.`when`(f.authRepository.getDeviceId()).thenReturn("device-1")
        // 先一次成功刷新，把配额落到本地状态（quotaMinutes=120）
        val base = UsageSessionDto(
            active = false,
            quotaMinutes = 120,
            remainingMs = 0L,
            dayKey = TODAY,
            timeControlEnabled = true,
            remainingMinutes = 0
        )
        Mockito.`when`(f.remote.getUsageSession()).thenReturn(ApiResult.Success(base, TODAY_WALL))
        f.manager.refresh()
        // 然后 start 接口失败（离线）→ 走本地起表
        Mockito.`when`(f.remote.startUsageSession()).thenReturn(ApiResult.Failure(0, "offline"))
        val ok = f.manager.confirmStart()
        assertTrue(ok)
        val s = f.manager.state.value
        assertTrue("离线也应立即起表（管控离线本地独立生效）", s.active)
        assertTrue("离线起表必须标记待同步，联网后由 refresh 补发", s.pendingStartSync)
    }

    // ==================== 暂停计时（2026-11-18）====================

    /** 同日 active 快照：剩余 60 分钟，基线时刻与受信墙钟一致（advance=0） */
    private fun activeFixture(): Pair<Fixture, MutableMap<String, Any?>> {
        val map = mutableMapOf<String, Any?>(
            KEY_DAY to TODAY,
            KEY_ACTIVE to true,
            KEY_REMAINING to 60L * 60_000L,
            KEY_SERVER_NOW to TODAY_WALL,
            KEY_EXTENDED to 0,
            KEY_TIME_CONTROL to true
        )
        return build(map) to map
    }

    @Test
    fun `pause 冻结剩余且手动暂停不会被亮屏自动恢复`() {
        val (f) = activeFixture()
        val m = f.manager
        val hour = 60L * 60_000L
        assertEquals(hour, m.liveRemainingMs())

        Mockito.`when`(f.time.monotonicMs()).thenReturn(10_000L)
        assertEquals(hour - 10_000L, m.liveRemainingMs())

        m.pause(TimerPauseSource.MANUAL)
        assertTrue(m.state.value.paused)
        val frozen = m.liveRemainingMs()
        assertEquals(hour - 10_000L, frozen)

        Mockito.`when`(f.time.monotonicMs()).thenReturn(310_000L)
        assertEquals("暂停期间冻结，不推进也不倒退", frozen, m.liveRemainingMs())

        m.resume(TimerPauseSource.SCREEN_OFF)
        assertTrue("亮屏广播不得解除手动暂停（否则电源键即绕过）", m.state.value.paused)

        m.resume(TimerPauseSource.MANUAL)
        assertFalse(m.state.value.paused)
        assertEquals("恢复瞬间不能把暂停 5 分钟扣掉", frozen, m.liveRemainingMs())

        Mockito.`when`(f.time.monotonicMs()).thenReturn(320_000L)
        assertEquals(frozen - 10_000L, m.liveRemainingMs())
    }

    @Test
    fun `暂停期间服务端 DTO 不覆盖冻结剩余`() = runBlocking {
        val (f, map) = activeFixture()
        Mockito.`when`(f.credentials.isBound).thenReturn(true)
        Mockito.`when`(f.authRepository.getDeviceId()).thenReturn("device-1")
        Mockito.`when`(f.time.monotonicMs()).thenReturn(10_000L)
        f.manager.pause(TimerPauseSource.SCREEN_OFF)
        val frozen = f.manager.liveRemainingMs()

        // 服务端尚未收到暂停补报：DTO 的 remaining 还没加回暂停段
        val stale = UsageSessionDto(
            active = true,
            quotaMinutes = 60,
            remainingMs = 0L,
            dayKey = TODAY,
            startEpoch = TODAY_WALL,
            timeControlEnabled = true
        )
        Mockito.`when`(f.remote.getUsageSession()).thenReturn(ApiResult.Success(stale, TODAY_WALL))
        f.manager.refresh()

        assertEquals("冻结值不能被未回补的 DTO 打回，否则会瞬间倒跳甚至误锁", frozen, f.manager.liveRemainingMs())
        assertEquals(frozen, map[KEY_REMAINING])
    }

    @Test
    fun `跨日重置清除暂停态与待补报记录`() {
        val map = mutableMapOf<String, Any?>(
            KEY_DAY to "20261109",
            KEY_ACTIVE to true,
            KEY_REMAINING to 5L * 60_000L,
            KEY_TIME_CONTROL to true,
            "timer_paused" to true,
            "timer_pause_source" to "MANUAL",
            "timer_pause_started_elapsed" to 100L,
            "timer_pause_rest_id" to "pause-old",
            "timer_pause_rest_ms" to 5_000L
        )
        val (m) = build(map)
        m.checkDayRollover()
        val s = m.state.value
        assertFalse("新的一天不能残留昨天的暂停锁", s.paused)
        assertNull(s.pauseSource)
        assertEquals(false, map["timer_paused"])
        assertEquals(null, map["timer_pause_rest_id"])
    }

    @Test
    fun `家长关闭时间管控时自动撤销暂停`() = runBlocking {
        val (f) = activeFixture()
        Mockito.`when`(f.credentials.isBound).thenReturn(true)
        Mockito.`when`(f.authRepository.getDeviceId()).thenReturn("device-1")
        f.manager.pause(TimerPauseSource.MANUAL)
        val dto = UsageSessionDto(
            active = true, quotaMinutes = 60, remainingMs = 60L * 60_000L,
            dayKey = TODAY, timeControlEnabled = false
        )
        Mockito.`when`(f.remote.getUsageSession()).thenReturn(ApiResult.Success(dto, TODAY_WALL))
        f.manager.refresh()
        assertFalse("关掉总开关就必须撤销暂停锁，不能把设备锁死", f.manager.state.value.paused)
        assertNull(f.manager.state.value.pauseSource)
    }

    @Test
    fun `暂停恢复后落盘时间基准与冻结剩余同帧_离线冷启动不重复扣暂停段`() {
        // 复现链路：active → 暂停 → 恢复 → 进程被杀 → 冷启动 restorePersistedSession。
        // 旧实现 persistPauseFields 只写 remaining、不写 elapsed/serverNow，
        // 冷启动 plan() 仍拿暂停前的旧基线算流逝，把整段暂停又扣一次。
        val hour = 60L * 60_000L
        val map = mutableMapOf<String, Any?>(
            KEY_DAY to TODAY,
            KEY_ACTIVE to true,
            KEY_REMAINING to hour,
            // 旧基线：位置 t=0，此刻 remaining=hour
            KEY_SERVER_NOW to TODAY_WALL,
            KEY_EXTENDED to 0,
            KEY_TIME_CONTROL to true
        )
        // t=0 建会话（基线 elapsed=0）
        val f = build(map)
        val m = f.manager
        assertEquals(hour, m.liveRemainingMs())

        // t=10_000 暂停 5 分钟前的起点：先把单调钟推到 10s 再暂停
        Mockito.`when`(f.time.monotonicMs()).thenReturn(10_000L)
        m.pause(TimerPauseSource.MANUAL)
        val frozen = m.liveRemainingMs()
        assertEquals(hour - 10_000L, frozen)
        // 落盘的 remaining 必须是冻结值
        assertEquals(frozen, map[KEY_REMAINING])

        // 暂停 5 分钟（单调钟到 310_000），墙钟同步前进 5 分钟
        val wallAfterPause = TODAY_WALL + 300_000L
        Mockito.`when`(f.time.monotonicMs()).thenReturn(310_000L)
        Mockito.`when`(f.time.now()).thenReturn(wallAfterPause)
        m.resume(TimerPauseSource.MANUAL)
        assertEquals("恢复瞬间不扣暂停段", frozen, m.liveRemainingMs())

        // 关键断言：落盘的时间基准被推到恢复时刻，与冻结 remaining 同帧。
        // 旧实现这里仍是 TODAY_WALL / 0L，冷启动就会重复扣 5 分钟。
        assertEquals("elapsed 必须同步到恢复时刻的单调钟", 310_000L, map[KEY_ELAPSED])
        assertEquals("serverNow 必须同步到恢复时刻的墙钟", wallAfterPause, map[KEY_SERVER_NOW])
        assertEquals("remaining 仍是冻结值", frozen, map[KEY_REMAINING])

        // 冷启动：单调钟再走 10s（恢复到冷启动之间真实经过），墙钟同步 10s。
        // 正确口径 = frozen - 10s；旧实现会算成 frozen - 310s（多扣 5 分钟暂停段）。
        val coldMono = 320_000L
        val coldWall = wallAfterPause + 10_000L
        val cold = buildAt(map, coldWall, coldMono).manager
        assertEquals(
            "离线冷启动只能扣恢复后真实经过的 10s，不能把暂停 5 分钟重复计入消耗",
            frozen - 10_000L, cold.liveRemainingMs()
        )
        assertFalse("恢复后冷启动不应残留暂停态", cold.state.value.paused)
    }
}
