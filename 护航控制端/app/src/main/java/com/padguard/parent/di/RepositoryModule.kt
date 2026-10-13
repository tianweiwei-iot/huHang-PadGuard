package com.padguard.parent.di

import com.padguard.parent.domain.repository.BillingRepository
import com.padguard.parent.domain.repository.AlertRepository
import com.padguard.parent.domain.repository.AuthRepository
import com.padguard.parent.domain.repository.DeviceRepository
import com.padguard.parent.domain.repository.LocationRepository
import com.padguard.parent.domain.repository.MessageRepository
import com.padguard.parent.domain.repository.MonitorRepository
import com.padguard.parent.domain.repository.PolicyRepository
import com.padguard.parent.domain.repository.StatisticsRepository
import com.padguard.parent.data.remote.ApiDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 依赖注入 - Repository 绑定模块
 * 将 Domain 层接口绑定到真实的 [ApiDataSource]（接真线，连后端 REST）。
 *
 * 说明：Mock 阶段的 [com.padguard.parent.data.local.LocalDataSource] 已停止作为仓储实现绑定，
 * 但保留其类定义以便历史测试或回退；生产代码统一走 [ApiDataSource]。
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideAuthRepository(
        apiDataSource: ApiDataSource
    ): AuthRepository = apiDataSource

    @Provides
    @Singleton
    fun provideDeviceRepository(
        apiDataSource: ApiDataSource
    ): DeviceRepository = apiDataSource

    @Provides
    @Singleton
    fun provideMonitorRepository(
        apiDataSource: ApiDataSource
    ): MonitorRepository = apiDataSource

    @Provides
    @Singleton
    fun provideMessageRepository(
        apiDataSource: ApiDataSource
    ): MessageRepository = apiDataSource

    @Provides
    @Singleton
    fun provideLocationRepository(
        apiDataSource: ApiDataSource
    ): LocationRepository = apiDataSource

    @Provides
    @Singleton
    fun providePolicyRepository(
        apiDataSource: ApiDataSource
    ): PolicyRepository = apiDataSource

    @Provides
    @Singleton
    fun provideStatisticsRepository(
        apiDataSource: ApiDataSource
    ): StatisticsRepository = apiDataSource

    @Provides
    @Singleton
    fun provideAlertRepository(
        apiDataSource: ApiDataSource
    ): AlertRepository = apiDataSource

    @Provides
    @Singleton
    fun provideBillingRepository(
        apiDataSource: ApiDataSource
    ): BillingRepository = apiDataSource

}
