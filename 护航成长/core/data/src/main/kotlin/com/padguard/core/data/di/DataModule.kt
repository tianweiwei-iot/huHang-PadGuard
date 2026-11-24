package com.padguard.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.padguard.core.data.db.AppUsageDao
import com.padguard.core.data.db.BehaviorLogDao
import com.padguard.core.data.db.CommandRecordDao
import com.padguard.core.data.db.DailyUsageDao
import com.padguard.core.data.db.PadGuardDatabase
import com.padguard.core.data.db.PolicyDao
import com.padguard.core.data.db.RiskEventDao
import com.padguard.core.data.db.UnlockRequestDao
import com.padguard.core.data.db.AgreementDao
import com.padguard.core.data.db.ParentMessageDao
import com.padguard.core.data.repository.MessageRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 迁移辅助：列存在则跳过，不存在才 ADD。
 *
 * 为什么不用裸 ALTER ADD COLUMN：Android SQLite（API 29 = SQLite 3.22）不支持
 * `ADD COLUMN IF NOT EXISTS`；历史版本的 schema 导出与迁移标注出现过错位
 * （v3 的导出 json 已含 firstUsedAt/lastUsedAt，但迁移 3→4 还在做 ADD），
 * 防御式检查让迁移在"真实设备库状态与导出 json 有出入"时幂等执行，
 * 而不是 duplicate column 崩溃。
 */
private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
    db.query("PRAGMA table_info($table)").use { cursor ->
        val nameIdx = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIdx) == column) return true
        }
    }
    return false
}

private fun ensureAppUsageWallClockColumns(db: SupportSQLiteDatabase) {
    if (!columnExists(db, "app_usage", "firstUsedAt")) {
        db.execSQL("ALTER TABLE app_usage ADD COLUMN firstUsedAt INTEGER NOT NULL DEFAULT 0")
    }
    if (!columnExists(db, "app_usage", "lastUsedAt")) {
        db.execSQL("ALTER TABLE app_usage ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0")
    }
    // 历史行没有这两个时间点，用 lastUpdateAt 兜底回填（它至少是"最近一次写入的时刻"），
    // 比直接填 0 更接近真实；后续写入会由 DAO 层按真实首次/末次语义覆盖。
    db.execSQL("UPDATE app_usage SET firstUsedAt = lastUpdateAt WHERE firstUsedAt = 0")
    db.execSQL("UPDATE app_usage SET lastUsedAt = lastUpdateAt WHERE lastUsedAt = 0")
}

/**
 * v1 -> v2：新增 unlock_request（解锁申请本地留痕）。
 *
 * 此前该步骤没有显式迁移：v1 老库升级只能触发 destructive 清库，
 * 日志缓存/风险事件全部丢失。按 schema 1.json→2.json 差异补齐。
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS unlock_request (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                requestId TEXT NOT NULL,
                packageName TEXT NOT NULL,
                appLabel TEXT NOT NULL,
                reasonText TEXT NOT NULL,
                durationMinutes INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                status TEXT NOT NULL,
                decidedAt INTEGER,
                uploaded INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_unlock_request_requestId ON unlock_request(requestId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_unlock_request_status ON unlock_request(status)")
    }
}

/**
 * v2 -> v3：app_usage 补两列墙钟时间 + 新增 agreement（协议签署留痕）。
 * 此前同样无显式迁移路径，全靠 destructive 兜底。
 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ensureAppUsageWallClockColumns(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agreement (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                version TEXT NOT NULL,
                signedAt INTEGER NOT NULL,
                snMask TEXT NOT NULL,
                deviceId TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agreement_signedAt ON agreement(signedAt)")
    }
}

/**
 * v3 -> v4：schema 3.json 与 4.json 结构完全一致（仅版本号递增），本步骤按理应为 no-op。
 *
 * 历史包袱：旧代码在这里执行 ADD COLUMN firstUsedAt——但 v3 库已含该列，
 * 真实升级会 duplicate column 崩溃。改为防御式补列（列在则跳过），
 * 兼容"导出 json 与真实设备库状态错位"的历史库。
 */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ensureAppUsageWallClockColumns(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agreement (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                version TEXT NOT NULL,
                signedAt INTEGER NOT NULL,
                snMask TEXT NOT NULL,
                deviceId TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agreement_signedAt ON agreement(signedAt)")
    }
}

/**
 * v4 -> v5：新增 parent_message 表（家长发布信息的本地持久化）。
 *
 * 仅建表 + 索引，不动任何既有表，因此不会触发 destructiveMigration 清空策略/日志缓存。
 */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS parent_message (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                serverMsgId TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                contentType TEXT NOT NULL,
                mediaUrl TEXT NOT NULL,
                mediaName TEXT NOT NULL,
                durationSec INTEGER NOT NULL,
                blocking INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                isRead INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_parent_message_serverMsgId ON parent_message(serverMsgId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_parent_message_timestamp ON parent_message(timestamp)")
    }
}

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "padguard_auth")
private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "padguard_config")

/**
 * 全量升级链。暴露为 internal 供 MigrationChainTest 校验"任何存量版本号
 * （1..5）都有相邻迁移路径"——这是防 destructive 清库的核心保证：
 * 缺一步，该版本的老库升级时就会整库重建（当日累计时长归零=绕过限额）。
 */
internal val PADGUARD_MIGRATIONS: List<Migration> =
    listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PadGuardDatabase =
        Room.databaseBuilder(context, PadGuardDatabase::class.java, PadGuardDatabase.NAME)
            // 全量升级链 1..5（见 PADGUARD_MIGRATIONS）：老库里存着当日累计时长，
            // 一旦整库重建，孩子已用的时长归零，等于白送一段绕过限额的使用时间。
            .addMigrations(*PADGUARD_MIGRATIONS.toTypedArray())
            // destructive 只允许发生在降级（schema 版本回退，如回滚安装）：
            // 升级路径必须全部显式迁移，缺路径宁可启动失败（开发期立刻暴露），
            // 也不能静默清掉本地管控数据。
            .fallbackToDestructiveMigrationOnDowngrade()
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

    @Provides
    @Singleton
    fun provideParentMessageDao(db: PadGuardDatabase): ParentMessageDao = db.parentMessageDao()

    @Provides
    @Singleton
    fun provideMessageRepository(dao: ParentMessageDao): MessageRepository = MessageRepository(dao)
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
