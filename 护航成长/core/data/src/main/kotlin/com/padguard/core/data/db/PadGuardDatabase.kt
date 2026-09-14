package com.padguard.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 本地管控数据库。
 *
 * 存储内容：策略包缓存、行为日志队列、指令记录、应用时长统计、告警事件。
 * 全部位于应用私有目录（/data/data/com.padguard.child/databases），
 * 非 root 环境下用户与第三方应用均无法直接访问。
 *
 * 注意：未开启 exportSchema 导出到版本控制之外的目录会导致构建告警，
 * 这里通过 ksp arg 指定 schemas 目录（见 core/data/build.gradle.kts）。
 */
@Database(
    entities = [
        PolicyEntity::class,
        BehaviorLogEntity::class,
        CommandRecordEntity::class,
        AppUsageEntity::class,
        DailyUsageEntity::class,
        RiskEventEntity::class,
        UnlockRequestEntity::class,
        AgreementEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class PadGuardDatabase : RoomDatabase() {

    abstract fun policyDao(): PolicyDao
    abstract fun behaviorLogDao(): BehaviorLogDao
    abstract fun commandRecordDao(): CommandRecordDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun dailyUsageDao(): DailyUsageDao
    abstract fun riskEventDao(): RiskEventDao
    abstract fun unlockRequestDao(): UnlockRequestDao
    abstract fun agreementDao(): AgreementDao

    companion object {
        const val NAME = "padguard.db"
    }
}
