package com.padguard.core.engine.command

import com.padguard.core.common.TimeProvider
import com.padguard.core.data.model.AckStatus
import com.padguard.core.data.model.Command
import com.padguard.core.data.model.CommandType
import com.padguard.core.data.repository.CommandRepository
import com.padguard.core.data.repository.PolicyRepository
import com.padguard.core.data.repository.UnlockRequestRepository
import com.padguard.core.engine.admin.Capability
import com.padguard.core.engine.admin.DeviceAdminBridgePort
import com.padguard.core.engine.enforcer.AppLimitEnforcer
import com.padguard.core.engine.enforcer.EyeCareEnforcer
import com.padguard.core.engine.enforcer.PeripheralEnforcerPort
import com.padguard.core.engine.guard.GuardMasterSwitchPort
import com.padguard.core.engine.lock.LockControllerPort
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.padguard.core.common.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * [CommandExecutor] 远程指令分发的行为测试（JVM，纯 mock）。
 *
 * 守护核心故障链路中的「离线 / 重连后家长解锁指令 / 开始计时指令不落地」：
 * - UNLOCK 必须释放全部锁定（这是「无法解锁」最直接的对端）；
 * - 重复 msgId 必须幂等（只执行一次），否则轮询重发会反复拉起锁屏页 / 重复动作；
 * - START_TIMER_CONFIRM 必须弹出确认框（这是「次日计时入口」可达的前提）。
 */
class CommandExecutorTest {

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

    private fun build(
        lockController: LockControllerPort = Mockito.mock(LockControllerPort::class.java),
        admin: DeviceAdminBridgePort = Mockito.mock(DeviceAdminBridgePort::class.java),
        commandRepository: CommandRepository = Mockito.mock(CommandRepository::class.java)
    ): CommandExecutor = CommandExecutor(
        admin = admin,
        lockController = lockController,
        policyRepository = Mockito.mock(PolicyRepository::class.java),
        unlockRequestRepository = Mockito.mock(UnlockRequestRepository::class.java),
        peripheralEnforcer = Mockito.mock(PeripheralEnforcerPort::class.java),
        appLimitEnforcer = Mockito.mock(AppLimitEnforcer::class.java),
        eyeCareEnforcer = Mockito.mock(EyeCareEnforcer::class.java),
        timeProvider = Mockito.mock(TimeProvider::class.java),
        guardMasterSwitch = Mockito.mock(GuardMasterSwitchPort::class.java),
        commandRepository = commandRepository
    )

    @Test
    fun `UNLOCK 释放全部锁定并消解锁屏`() = runBlocking {
        val lockController = Mockito.mock(LockControllerPort::class.java)
        val exec = build(lockController)
        val ack = exec.execute(
            Command(msgId = "m1", type = CommandType.UNLOCK, payload = emptyMap()),
            "device-1"
        )
        assertEquals(AckStatus.SUCCESS, ack.status)
        // 释放锁才真正解除「无法解锁」：cancelTempUnlock + releaseAll 都必须被调用
        Mockito.verify(lockController).cancelTempUnlock()
        Mockito.verify(lockController).releaseAll()
    }

    @Test
    fun `协程取消必须原样上抛 不得伪装成 EXEC_CRASH`() = runBlocking {
        val admin = Mockito.mock(DeviceAdminBridgePort::class.java)
        val repo = Mockito.mock(CommandRepository::class.java)
        Mockito.`when`(admin.can(Capability.REBOOT)).thenThrow(CancellationException("scope cancelled"))
        val exec = build(admin = admin, commandRepository = repo)

        try {
            exec.execute(Command(msgId = "cancel-1", type = CommandType.REBOOT, payload = emptyMap()), "d")
            fail("CancellationException 必须向上传播，不能被吞成失败回执")
        } catch (e: CancellationException) {
            assertEquals("scope cancelled", e.message)
        }
        Mockito.verifyNoInteractions(repo)
    }

    @Test
    fun `重复 msgId 只执行一次（幂等去重守护轮询重发）`() = runBlocking {
        val lockController = Mockito.mock(LockControllerPort::class.java)
        val exec = build(lockController)
        val cmd = Command(msgId = "dup", type = CommandType.UNLOCK, payload = emptyMap())
        val first = exec.execute(cmd, "d")
        val second = exec.execute(cmd, "d")
        assertEquals(AckStatus.SUCCESS, first.status)
        // 第二次命中幂等去重，回执仍是 SUCCESS（语义见 execute 注释），但执行体只跑一次
        assertEquals(AckStatus.SUCCESS, second.status)
        Mockito.verify(lockController, Mockito.times(1)).releaseAll()
    }

    @Test
    fun `START_TIMER_CONFIRM 弹确认框（守护次日计时入口可达）`() = runBlocking {
        val exec = build()
        // UNDISPATCHED：让收集协程在 execute() 之前就在当前线程完成订阅注册，
        // 否则 effects 是 replay=0 的 SharedFlow，execute 的 tryEmit 会先于订阅发生，
        // 收集者永远收不到这条已被缓冲/丢弃的副作用而 first{} 永久挂起（真实 Gradle 跑也是竞态偶发挂死）。
        val deferred = async(start = CoroutineStart.UNDISPATCHED) { exec.effects.first { it is EngineEffect.ShowStartTimerConfirm } }
        val ack = exec.execute(
            Command(
                msgId = "t1",
                type = CommandType.START_TIMER_CONFIRM,
                payload = mapOf("requestId" to "req-1")
            ),
            "d"
        )
        assertEquals(AckStatus.SUCCESS, ack.status)
        val effect = deferred.await()
        assertEquals(true, effect is EngineEffect.ShowStartTimerConfirm && effect.requestId == "req-1")
    }

    @Test
    fun `START_TIMER_CONFIRM 缺 requestId 判坏载荷`() = runBlocking {
        val exec = build()
        val ack = exec.execute(
            Command(msgId = "t2", type = CommandType.START_TIMER_CONFIRM, payload = emptyMap()),
            "d"
        )
        assertEquals(AckStatus.FAILED, ack.status)
    }

    /**
     * 卸载自身自我保护（红线）：release 包名精确相等时，UNINSTALL_APP 必须被 SELF_PROTECT 拦截，
     * 否则一条远程指令即可让设备彻底脱管。
     */
    @Test
    fun `UNINSTALL_APP 携带自身包名被自我保护拦截`() = runBlocking {
        val admin = Mockito.mock(DeviceAdminBridgePort::class.java)
        Mockito.`when`(admin.can(Capability.SILENT_UNINSTALL)).thenReturn(true)
        Mockito.`when`(admin.selfPackageName).thenReturn("com.padguard.child")
        val exec = build(admin = admin)
        val ack = exec.execute(
            Command(
                msgId = "u1",
                type = CommandType.UNINSTALL_APP,
                payload = mapOf("packageName" to "com.padguard.child")
            ),
            "d"
        )
        assertEquals(AckStatus.FAILED, ack.status)
        assertEquals("SELF_PROTECT", ack.errorCode)
    }

    /**
     * 回归：debug/flavor 变体（applicationId 带 .debug 后缀）下，卸载自身也必须被拦截。
     *
     * 旧实现用写死常量 "com.padguard.child" + endsWith(".child")，对 "com.padguard.child.debug"
     * 既不相等、也不以 ".child" 结尾 → 漏防 → 非 DO 设备可被静默卸载脱管。改用运行时精确相等后修复。
     */
    @Test
    fun `UNINSTALL_APP 携带 debug 变体自身包名同样被拦截`() = runBlocking {
        val admin = Mockito.mock(DeviceAdminBridgePort::class.java)
        Mockito.`when`(admin.can(Capability.SILENT_UNINSTALL)).thenReturn(true)
        Mockito.`when`(admin.selfPackageName).thenReturn("com.padguard.child.debug")
        val exec = build(admin = admin)
        val ack = exec.execute(
            Command(
                msgId = "u2",
                type = CommandType.UNINSTALL_APP,
                payload = mapOf("packageName" to "com.padguard.child.debug")
            ),
            "d"
        )
        assertEquals(AckStatus.FAILED, ack.status)
        assertEquals("SELF_PROTECT", ack.errorCode)
    }

    /**
     * 回归：以 ".child" 结尾的无关应用（如 com.school.child）不应被误伤拦截。
     *
     * 旧实现 endsWith(".child") 会把它当成管控端自身拒绝卸载，错误拦截家长对无关应用的合法卸载指令。
     */
    @Test
    fun `UNINSTALL_APP 不误伤以 child 结尾的无关应用`() = runBlocking {
        val admin = Mockito.mock(DeviceAdminBridgePort::class.java)
        Mockito.`when`(admin.can(Capability.SILENT_UNINSTALL)).thenReturn(true)
        Mockito.`when`(admin.selfPackageName).thenReturn("com.padguard.child")
        val exec = build(admin = admin)
        val ack = exec.execute(
            Command(
                msgId = "u3",
                type = CommandType.UNINSTALL_APP,
                payload = mapOf("packageName" to "com.school.child")
            ),
            "d"
        )
        // 通过自我保护检查后才会走到 emit（SUCCESS），故 SUCCESS 证明未被误拦
        assertEquals(AckStatus.SUCCESS, ack.status)
    }
}
