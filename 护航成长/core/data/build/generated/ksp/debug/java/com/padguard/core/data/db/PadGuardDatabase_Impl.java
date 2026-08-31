package com.padguard.core.data.db;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class PadGuardDatabase_Impl extends PadGuardDatabase {
  private volatile PolicyDao _policyDao;

  private volatile BehaviorLogDao _behaviorLogDao;

  private volatile CommandRecordDao _commandRecordDao;

  private volatile AppUsageDao _appUsageDao;

  private volatile DailyUsageDao _dailyUsageDao;

  private volatile RiskEventDao _riskEventDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(1) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `policy` (`id` INTEGER NOT NULL, `version` INTEGER NOT NULL, `encryptedJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `behavior_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `logId` TEXT NOT NULL, `type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL, `uploaded` INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_behavior_log_logId` ON `behavior_log` (`logId`)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_behavior_log_uploaded` ON `behavior_log` (`uploaded`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `command_record` (`msgId` TEXT NOT NULL, `type` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL, `executedAt` INTEGER, `status` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, `errorMessage` TEXT, PRIMARY KEY(`msgId`))");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_command_record_receivedAt` ON `command_record` (`receivedAt`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `app_usage` (`packageName` TEXT NOT NULL, `dayKey` TEXT NOT NULL, `usedMs` INTEGER NOT NULL, `launchCount` INTEGER NOT NULL, `lastUpdateAt` INTEGER NOT NULL, PRIMARY KEY(`packageName`, `dayKey`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `daily_usage` (`dayKey` TEXT NOT NULL, `totalMs` INTEGER NOT NULL, `lastUpdateAt` INTEGER NOT NULL, PRIMARY KEY(`dayKey`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `risk_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventId` TEXT NOT NULL, `type` TEXT NOT NULL, `level` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `detailJson` TEXT NOT NULL, `uploaded` INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_risk_event_eventId` ON `risk_event` (`eventId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'dfd388d7ebac69c80c9d20eb7de9682b')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `policy`");
        db.execSQL("DROP TABLE IF EXISTS `behavior_log`");
        db.execSQL("DROP TABLE IF EXISTS `command_record`");
        db.execSQL("DROP TABLE IF EXISTS `app_usage`");
        db.execSQL("DROP TABLE IF EXISTS `daily_usage`");
        db.execSQL("DROP TABLE IF EXISTS `risk_event`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsPolicy = new HashMap<String, TableInfo.Column>(5);
        _columnsPolicy.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPolicy.put("version", new TableInfo.Column("version", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPolicy.put("encryptedJson", new TableInfo.Column("encryptedJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPolicy.put("updatedAt", new TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsPolicy.put("source", new TableInfo.Column("source", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysPolicy = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesPolicy = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoPolicy = new TableInfo("policy", _columnsPolicy, _foreignKeysPolicy, _indicesPolicy);
        final TableInfo _existingPolicy = TableInfo.read(db, "policy");
        if (!_infoPolicy.equals(_existingPolicy)) {
          return new RoomOpenHelper.ValidationResult(false, "policy(com.padguard.core.data.db.PolicyEntity).\n"
                  + " Expected:\n" + _infoPolicy + "\n"
                  + " Found:\n" + _existingPolicy);
        }
        final HashMap<String, TableInfo.Column> _columnsBehaviorLog = new HashMap<String, TableInfo.Column>(6);
        _columnsBehaviorLog.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBehaviorLog.put("logId", new TableInfo.Column("logId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBehaviorLog.put("type", new TableInfo.Column("type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBehaviorLog.put("timestamp", new TableInfo.Column("timestamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBehaviorLog.put("payloadJson", new TableInfo.Column("payloadJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBehaviorLog.put("uploaded", new TableInfo.Column("uploaded", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysBehaviorLog = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesBehaviorLog = new HashSet<TableInfo.Index>(2);
        _indicesBehaviorLog.add(new TableInfo.Index("index_behavior_log_logId", true, Arrays.asList("logId"), Arrays.asList("ASC")));
        _indicesBehaviorLog.add(new TableInfo.Index("index_behavior_log_uploaded", false, Arrays.asList("uploaded"), Arrays.asList("ASC")));
        final TableInfo _infoBehaviorLog = new TableInfo("behavior_log", _columnsBehaviorLog, _foreignKeysBehaviorLog, _indicesBehaviorLog);
        final TableInfo _existingBehaviorLog = TableInfo.read(db, "behavior_log");
        if (!_infoBehaviorLog.equals(_existingBehaviorLog)) {
          return new RoomOpenHelper.ValidationResult(false, "behavior_log(com.padguard.core.data.db.BehaviorLogEntity).\n"
                  + " Expected:\n" + _infoBehaviorLog + "\n"
                  + " Found:\n" + _existingBehaviorLog);
        }
        final HashMap<String, TableInfo.Column> _columnsCommandRecord = new HashMap<String, TableInfo.Column>(7);
        _columnsCommandRecord.put("msgId", new TableInfo.Column("msgId", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCommandRecord.put("type", new TableInfo.Column("type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCommandRecord.put("receivedAt", new TableInfo.Column("receivedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCommandRecord.put("executedAt", new TableInfo.Column("executedAt", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCommandRecord.put("status", new TableInfo.Column("status", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCommandRecord.put("retryCount", new TableInfo.Column("retryCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCommandRecord.put("errorMessage", new TableInfo.Column("errorMessage", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysCommandRecord = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesCommandRecord = new HashSet<TableInfo.Index>(1);
        _indicesCommandRecord.add(new TableInfo.Index("index_command_record_receivedAt", false, Arrays.asList("receivedAt"), Arrays.asList("ASC")));
        final TableInfo _infoCommandRecord = new TableInfo("command_record", _columnsCommandRecord, _foreignKeysCommandRecord, _indicesCommandRecord);
        final TableInfo _existingCommandRecord = TableInfo.read(db, "command_record");
        if (!_infoCommandRecord.equals(_existingCommandRecord)) {
          return new RoomOpenHelper.ValidationResult(false, "command_record(com.padguard.core.data.db.CommandRecordEntity).\n"
                  + " Expected:\n" + _infoCommandRecord + "\n"
                  + " Found:\n" + _existingCommandRecord);
        }
        final HashMap<String, TableInfo.Column> _columnsAppUsage = new HashMap<String, TableInfo.Column>(5);
        _columnsAppUsage.put("packageName", new TableInfo.Column("packageName", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppUsage.put("dayKey", new TableInfo.Column("dayKey", "TEXT", true, 2, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppUsage.put("usedMs", new TableInfo.Column("usedMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppUsage.put("launchCount", new TableInfo.Column("launchCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsAppUsage.put("lastUpdateAt", new TableInfo.Column("lastUpdateAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysAppUsage = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesAppUsage = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoAppUsage = new TableInfo("app_usage", _columnsAppUsage, _foreignKeysAppUsage, _indicesAppUsage);
        final TableInfo _existingAppUsage = TableInfo.read(db, "app_usage");
        if (!_infoAppUsage.equals(_existingAppUsage)) {
          return new RoomOpenHelper.ValidationResult(false, "app_usage(com.padguard.core.data.db.AppUsageEntity).\n"
                  + " Expected:\n" + _infoAppUsage + "\n"
                  + " Found:\n" + _existingAppUsage);
        }
        final HashMap<String, TableInfo.Column> _columnsDailyUsage = new HashMap<String, TableInfo.Column>(3);
        _columnsDailyUsage.put("dayKey", new TableInfo.Column("dayKey", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyUsage.put("totalMs", new TableInfo.Column("totalMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsDailyUsage.put("lastUpdateAt", new TableInfo.Column("lastUpdateAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysDailyUsage = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesDailyUsage = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoDailyUsage = new TableInfo("daily_usage", _columnsDailyUsage, _foreignKeysDailyUsage, _indicesDailyUsage);
        final TableInfo _existingDailyUsage = TableInfo.read(db, "daily_usage");
        if (!_infoDailyUsage.equals(_existingDailyUsage)) {
          return new RoomOpenHelper.ValidationResult(false, "daily_usage(com.padguard.core.data.db.DailyUsageEntity).\n"
                  + " Expected:\n" + _infoDailyUsage + "\n"
                  + " Found:\n" + _existingDailyUsage);
        }
        final HashMap<String, TableInfo.Column> _columnsRiskEvent = new HashMap<String, TableInfo.Column>(7);
        _columnsRiskEvent.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsRiskEvent.put("eventId", new TableInfo.Column("eventId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsRiskEvent.put("type", new TableInfo.Column("type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsRiskEvent.put("level", new TableInfo.Column("level", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsRiskEvent.put("timestamp", new TableInfo.Column("timestamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsRiskEvent.put("detailJson", new TableInfo.Column("detailJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsRiskEvent.put("uploaded", new TableInfo.Column("uploaded", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysRiskEvent = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesRiskEvent = new HashSet<TableInfo.Index>(1);
        _indicesRiskEvent.add(new TableInfo.Index("index_risk_event_eventId", true, Arrays.asList("eventId"), Arrays.asList("ASC")));
        final TableInfo _infoRiskEvent = new TableInfo("risk_event", _columnsRiskEvent, _foreignKeysRiskEvent, _indicesRiskEvent);
        final TableInfo _existingRiskEvent = TableInfo.read(db, "risk_event");
        if (!_infoRiskEvent.equals(_existingRiskEvent)) {
          return new RoomOpenHelper.ValidationResult(false, "risk_event(com.padguard.core.data.db.RiskEventEntity).\n"
                  + " Expected:\n" + _infoRiskEvent + "\n"
                  + " Found:\n" + _existingRiskEvent);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "dfd388d7ebac69c80c9d20eb7de9682b", "8fe246ee928e9afaddc8e0dc6ea130c4");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "policy","behavior_log","command_record","app_usage","daily_usage","risk_event");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `policy`");
      _db.execSQL("DELETE FROM `behavior_log`");
      _db.execSQL("DELETE FROM `command_record`");
      _db.execSQL("DELETE FROM `app_usage`");
      _db.execSQL("DELETE FROM `daily_usage`");
      _db.execSQL("DELETE FROM `risk_event`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(PolicyDao.class, PolicyDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(BehaviorLogDao.class, BehaviorLogDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(CommandRecordDao.class, CommandRecordDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(AppUsageDao.class, AppUsageDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(DailyUsageDao.class, DailyUsageDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(RiskEventDao.class, RiskEventDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public PolicyDao policyDao() {
    if (_policyDao != null) {
      return _policyDao;
    } else {
      synchronized(this) {
        if(_policyDao == null) {
          _policyDao = new PolicyDao_Impl(this);
        }
        return _policyDao;
      }
    }
  }

  @Override
  public BehaviorLogDao behaviorLogDao() {
    if (_behaviorLogDao != null) {
      return _behaviorLogDao;
    } else {
      synchronized(this) {
        if(_behaviorLogDao == null) {
          _behaviorLogDao = new BehaviorLogDao_Impl(this);
        }
        return _behaviorLogDao;
      }
    }
  }

  @Override
  public CommandRecordDao commandRecordDao() {
    if (_commandRecordDao != null) {
      return _commandRecordDao;
    } else {
      synchronized(this) {
        if(_commandRecordDao == null) {
          _commandRecordDao = new CommandRecordDao_Impl(this);
        }
        return _commandRecordDao;
      }
    }
  }

  @Override
  public AppUsageDao appUsageDao() {
    if (_appUsageDao != null) {
      return _appUsageDao;
    } else {
      synchronized(this) {
        if(_appUsageDao == null) {
          _appUsageDao = new AppUsageDao_Impl(this);
        }
        return _appUsageDao;
      }
    }
  }

  @Override
  public DailyUsageDao dailyUsageDao() {
    if (_dailyUsageDao != null) {
      return _dailyUsageDao;
    } else {
      synchronized(this) {
        if(_dailyUsageDao == null) {
          _dailyUsageDao = new DailyUsageDao_Impl(this);
        }
        return _dailyUsageDao;
      }
    }
  }

  @Override
  public RiskEventDao riskEventDao() {
    if (_riskEventDao != null) {
      return _riskEventDao;
    } else {
      synchronized(this) {
        if(_riskEventDao == null) {
          _riskEventDao = new RiskEventDao_Impl(this);
        }
        return _riskEventDao;
      }
    }
  }
}
