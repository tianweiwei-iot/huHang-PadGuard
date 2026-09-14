package com.padguard.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.padguard.core.data.db.AppUsageDao
import com.padguard.core.data.db.BehaviorLogDao
import com.padguard.core.data.db.CommandRecordDao
import com.padguard.core.data.db.DailyUsageDao
import com.padguard.core.data.db.PadGuardDatabase
import com.padguard.core.data.db.PolicyDao
import com.padguard.core.data.db.RiskEventDao
import com.padguard.core.data.db.UnlockRequestDao
import com.padguard.core.data.db.AgreementDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "padguard_auth")
private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "padguard_config")

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PadGuardDatabase =
        Room.databaseBuilder(context, PadGuardDatabase::class.java, PadGuardDatabase.NAME)
            // 离线管控场景下数据库损坏会导致管控失效，这里允许重建
            // （策略可从服务端重新拉取，日志丢失可接受，优先保证可用性）
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun providePolicyDao(db: PadGuardDatabase): PolicyDao = db.policyDao()

    @Provides
    @Singleton
    fun provideBehaviorLogDao(db: PadGuardDatabase): BehaviorLogDao = db.behaviorLogDao()

    @Provides
    @Singleton
    fun provideCommandRecordDao(db: PadGuardDatabase): CommandRecordDao = db.commandRecordDao()

    @Provides
    @Singleton
    fun provideAppUsageDao(db: PadGuardDatabase): AppUsageDao = db.appUsageDao()

    @Provides
    @Singleton
    fun provideDailyUsageDao(db: PadGuardDatabase): DailyUsageDao = db.dailyUsageDao()

    @Provides
    @Singleton
    fun provideRiskEventDao(db: PadGuardDatabase): RiskEventDao = db.riskEventDao()

    @Provides
    @Singleton
    fun provideUnlockRequestDao(db: PadGuardDatabase): UnlockRequestDao = db.unlockRequestDao()

    @Provides
    @Singleton
    fun provideAgreementDao(db: PadGuardDatabase): AgreementDao = db.agreementDao()
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    /** 凭据存储：deviceId / token / mqtt 账号 / hmac 密钥 */
    @Provides
    @Singleton
    @AuthDataStore
    fun provideAuthDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.authDataStore

    /** 运行配置：心跳周期、传输模式、上次同步时间等 */
    @Provides
    @Singleton
    @ConfigDataStore
    fun provideConfigDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.configDataStore
}
