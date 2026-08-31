package com.padguard.parent.di

import com.padguard.data.local.LocalDataSource
import com.padguard.domain.repository.AuthRepository
import com.padguard.domain.repository.DeviceRepository
import com.padguard.domain.repository.MonitorRepository
import com.padguard.domain.repository.PolicyRepository
import com.padguard.domain.repository.StatisticsRepository
import com.padguard.domain.repository.AlertRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 依赖注入 - Repository 绑定模块
 * 将 Domain 层接口绑定到 Data 层实现
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideAuthRepository(
        localDataSource: LocalDataSource
    ): AuthRepository = localDataSource

    @Provides
    @Singleton
    fun provideDeviceRepository(
        localDataSource: LocalDataSource
    ): DeviceRepository = localDataSource

    @Provides
    @Singleton
    fun provideMonitorRepository(
        localDataSource: LocalDataSource
    ): MonitorRepository = localDataSource

    @Provides
    @Singleton
    fun providePolicyRepository(
        localDataSource: LocalDataSource
    ): PolicyRepository = localDataSource

    @Provides
    @Singleton
    fun provideStatisticsRepository(
        localDataSource: LocalDataSource
    ): StatisticsRepository = localDataSource

    @Provides
    @Singleton
    fun provideAlertRepository(
        localDataSource: LocalDataSource
    ): AlertRepository = localDataSource
}
