package com.padguard.core.engine.di

import com.padguard.core.engine.admin.DeviceAdminBridge
import com.padguard.core.engine.admin.DeviceAdminBridgePort
import com.padguard.core.engine.enforcer.PeripheralEnforcer
import com.padguard.core.engine.enforcer.PeripheralEnforcerPort
import com.padguard.core.engine.guard.GuardMasterSwitch
import com.padguard.core.engine.guard.GuardMasterSwitchPort
import com.padguard.core.engine.lock.LockController
import com.padguard.core.engine.lock.LockControllerPort
import com.padguard.core.engine.schedule.DefaultHolidayProvider
import com.padguard.core.engine.schedule.HolidayProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * engine 层的依赖装配。
 *
 * ## 为什么这个 Module 这么小
 * engine 层所有实现类（各 Enforcer、LockController、CommandExecutor、TamperDetector、
 * ScheduleEvaluator、DeviceAdminBridge）都已标注 `@Singleton @Inject constructor`，
 * Hilt 能直接构造，**不需要**再写 `@Provides`。
 *
 * 若在这里额外补一份 `@Provides fun provideXxx(...) = Xxx(...)`，会出现
 * "构造注入 + 手写 Provides" 的重复绑定，Hilt 会编译失败（DuplicateBindings），
 * 或者更糟——两处各自持有一个"单例"，导致 [com.padguard.core.engine.lock.LockController]
 * 的锁定态在服务与 UI 之间不一致，排障时会怀疑到锁逻辑本身。
 *
 * 所以这里只保留**无法靠构造注入解决**的部分：接口到实现的绑定。
 *
 * ## HolidayProvider 为什么留成接口
 * 节假日/调休表在校园场景需要按地区、按学年替换（甚至由服务端下发），
 * 但早期阶段服务端还没有这个能力，先用 [DefaultHolidayProvider] 的内置规则兜底。
 * 保留接口是为了后续换成"服务端下发日历"时不改 [ScheduleEvaluator] 一行代码。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    @Binds
    @Singleton
    abstract fun bindHolidayProvider(impl: DefaultHolidayProvider): HolidayProvider

    /**
     * 把 [DeviceAdminBridge] 绑定到可测性端口 [DeviceAdminBridgePort]。
     *
     * [com.padguard.core.engine.command.CommandExecutor] 只依赖端口而非具体类，
     * 因此单测可以 mock 一个不引用任何 Android 类型的接口，脱离 Android 运行时跑通；
     * 其余 8 个消费者仍直接注入具体类 [DeviceAdminBridge]（不同 key，不冲突）。
     */
    @Binds
    @Singleton
    abstract fun bindDeviceAdminBridge(impl: DeviceAdminBridge): DeviceAdminBridgePort

    /**
     * 把 [LockController] 绑定到可测性端口 [LockControllerPort]。
     * [com.padguard.core.engine.command.CommandExecutor] 只依赖端口而非具体类，单测可 mock
     * 零 Android 引用的接口；其余消费者仍直接注入具体类 [LockController]，不同 key 不冲突。
     */
    @Binds
    @Singleton
    abstract fun bindLockController(impl: LockController): LockControllerPort

    /**
     * 把 [PeripheralEnforcer] 绑定到可测性端口 [PeripheralEnforcerPort]。
     * [com.padguard.core.engine.command.CommandExecutor] 只依赖端口；其余消费者仍注入具体类。
     */
    @Binds
    @Singleton
    abstract fun bindPeripheralEnforcer(impl: PeripheralEnforcer): PeripheralEnforcerPort

    /**
     * 把 [GuardMasterSwitch] 绑定到可测性端口 [GuardMasterSwitchPort]。
     * [com.padguard.core.engine.command.CommandExecutor] 只依赖端口；其余消费者仍注入具体类。
     */
    @Binds
    @Singleton
    abstract fun bindGuardMasterSwitch(impl: GuardMasterSwitch): GuardMasterSwitchPort
}
