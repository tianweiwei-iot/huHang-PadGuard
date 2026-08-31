package com.padguard.core.data.db;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.EntityUpsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomDatabaseKt;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class AppUsageDao_Impl implements AppUsageDao {
  private final RoomDatabase __db;

  private final SharedSQLiteStatement __preparedStmtOfDeleteDay;

  private final SharedSQLiteStatement __preparedStmtOfDeleteBefore;

  private final EntityUpsertionAdapter<AppUsageEntity> __upsertionAdapterOfAppUsageEntity;

  public AppUsageDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__preparedStmtOfDeleteDay = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM app_usage WHERE dayKey = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteBefore = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM app_usage WHERE dayKey < ?";
        return _query;
      }
    };
    this.__upsertionAdapterOfAppUsageEntity = new EntityUpsertionAdapter<AppUsageEntity>(new EntityInsertionAdapter<AppUsageEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT INTO `app_usage` (`packageName`,`dayKey`,`usedMs`,`launchCount`,`lastUpdateAt`) VALUES (?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final AppUsageEntity entity) {
        statement.bindString(1, entity.getPackageName());
        statement.bindString(2, entity.getDayKey());
        statement.bindLong(3, entity.getUsedMs());
        statement.bindLong(4, entity.getLaunchCount());
        statement.bindLong(5, entity.getLastUpdateAt());
      }
    }, new EntityDeletionOrUpdateAdapter<AppUsageEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE `app_usage` SET `packageName` = ?,`dayKey` = ?,`usedMs` = ?,`launchCount` = ?,`lastUpdateAt` = ? WHERE `packageName` = ? AND `dayKey` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final AppUsageEntity entity) {
        statement.bindString(1, entity.getPackageName());
        statement.bindString(2, entity.getDayKey());
        statement.bindLong(3, entity.getUsedMs());
        statement.bindLong(4, entity.getLaunchCount());
        statement.bindLong(5, entity.getLastUpdateAt());
        statement.bindString(6, entity.getPackageName());
        statement.bindString(7, entity.getDayKey());
      }
    });
  }

  @Override
  public Object addUsage(final String dayKey, final String pkg, final long deltaMs, final long now,
      final Continuation<? super Unit> $completion) {
    return RoomDatabaseKt.withTransaction(__db, (__cont) -> AppUsageDao.DefaultImpls.addUsage(AppUsageDao_Impl.this, dayKey, pkg, deltaMs, now, __cont), $completion);
  }

  @Override
  public Object addLaunch(final String dayKey, final String pkg, final long now,
      final Continuation<? super Unit> $completion) {
    return RoomDatabaseKt.withTransaction(__db, (__cont) -> AppUsageDao.DefaultImpls.addLaunch(AppUsageDao_Impl.this, dayKey, pkg, now, __cont), $completion);
  }

  @Override
  public Object deleteDay(final String dayKey, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteDay.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, dayKey);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteDay.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteBefore(final String beforeDayKey,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteBefore.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, beforeDayKey);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteBefore.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object upsert(final AppUsageEntity entity, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __upsertionAdapterOfAppUsageEntity.upsert(entity);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<AppUsageEntity>> observeByDay(final String dayKey) {
    final String _sql = "SELECT * FROM app_usage WHERE dayKey = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, dayKey);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"app_usage"}, new Callable<List<AppUsageEntity>>() {
      @Override
      @NonNull
      public List<AppUsageEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfPackageName = CursorUtil.getColumnIndexOrThrow(_cursor, "packageName");
          final int _cursorIndexOfDayKey = CursorUtil.getColumnIndexOrThrow(_cursor, "dayKey");
          final int _cursorIndexOfUsedMs = CursorUtil.getColumnIndexOrThrow(_cursor, "usedMs");
          final int _cursorIndexOfLaunchCount = CursorUtil.getColumnIndexOrThrow(_cursor, "launchCount");
          final int _cursorIndexOfLastUpdateAt = CursorUtil.getColumnIndexOrThrow(_cursor, "lastUpdateAt");
          final List<AppUsageEntity> _result = new ArrayList<AppUsageEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final AppUsageEntity _item;
            final String _tmpPackageName;
            _tmpPackageName = _cursor.getString(_cursorIndexOfPackageName);
            final String _tmpDayKey;
            _tmpDayKey = _cursor.getString(_cursorIndexOfDayKey);
            final long _tmpUsedMs;
            _tmpUsedMs = _cursor.getLong(_cursorIndexOfUsedMs);
            final int _tmpLaunchCount;
            _tmpLaunchCount = _cursor.getInt(_cursorIndexOfLaunchCount);
            final long _tmpLastUpdateAt;
            _tmpLastUpdateAt = _cursor.getLong(_cursorIndexOfLastUpdateAt);
            _item = new AppUsageEntity(_tmpPackageName,_tmpDayKey,_tmpUsedMs,_tmpLaunchCount,_tmpLastUpdateAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object get(final String dayKey, final String pkg,
      final Continuation<? super AppUsageEntity> $completion) {
    final String _sql = "SELECT * FROM app_usage WHERE dayKey = ? AND packageName = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindString(_argIndex, dayKey);
    _argIndex = 2;
    _statement.bindString(_argIndex, pkg);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<AppUsageEntity>() {
      @Override
      @Nullable
      public AppUsageEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfPackageName = CursorUtil.getColumnIndexOrThrow(_cursor, "packageName");
          final int _cursorIndexOfDayKey = CursorUtil.getColumnIndexOrThrow(_cursor, "dayKey");
          final int _cursorIndexOfUsedMs = CursorUtil.getColumnIndexOrThrow(_cursor, "usedMs");
          final int _cursorIndexOfLaunchCount = CursorUtil.getColumnIndexOrThrow(_cursor, "launchCount");
          final int _cursorIndexOfLastUpdateAt = CursorUtil.getColumnIndexOrThrow(_cursor, "lastUpdateAt");
          final AppUsageEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpPackageName;
            _tmpPackageName = _cursor.getString(_cursorIndexOfPackageName);
            final String _tmpDayKey;
            _tmpDayKey = _cursor.getString(_cursorIndexOfDayKey);
            final long _tmpUsedMs;
            _tmpUsedMs = _cursor.getLong(_cursorIndexOfUsedMs);
            final int _tmpLaunchCount;
            _tmpLaunchCount = _cursor.getInt(_cursorIndexOfLaunchCount);
            final long _tmpLastUpdateAt;
            _tmpLastUpdateAt = _cursor.getLong(_cursorIndexOfLastUpdateAt);
            _result = new AppUsageEntity(_tmpPackageName,_tmpDayKey,_tmpUsedMs,_tmpLaunchCount,_tmpLastUpdateAt);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
