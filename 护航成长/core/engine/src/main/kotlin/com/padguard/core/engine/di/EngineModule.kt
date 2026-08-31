package com.padguard.core.engine.di

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
 * 但 P0 阶段服务端还没有这个能力，先用 [DefaultHolidayProvider] 的内置规则兜底。
 * 保留接口是为了后续换成"服务端下发日历"时不改 [ScheduleEvaluator] 一行代码。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    @Binds
    @Singleton
    abstract fun bindHolidayProvider(impl: DefaultHolidayProvider): HolidayProvider
}
