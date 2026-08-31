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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class DailyUsageDao_Impl implements DailyUsageDao {
  private final RoomDatabase __db;

  private final SharedSQLiteStatement __preparedStmtOfDeleteBefore;

  private final EntityUpsertionAdapter<DailyUsageEntity> __upsertionAdapterOfDailyUsageEntity;

  public DailyUsageDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__preparedStmtOfDeleteBefore = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM daily_usage WHERE dayKey < ?";
        return _query;
      }
    };
    this.__upsertionAdapterOfDailyUsageEntity = new EntityUpsertionAdapter<DailyUsageEntity>(new EntityInsertionAdapter<DailyUsageEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT INTO `daily_usage` (`dayKey`,`totalMs`,`lastUpdateAt`) VALUES (?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final DailyUsageEntity entity) {
        statement.bindString(1, entity.getDayKey());
        statement.bindLong(2, entity.getTotalMs());
        statement.bindLong(3, entity.getLastUpdateAt());
      }
    }, new EntityDeletionOrUpdateAdapter<DailyUsageEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE `daily_usage` SET `dayKey` = ?,`totalMs` = ?,`lastUpdateAt` = ? WHERE `dayKey` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final DailyUsageEntity entity) {
        statement.bindString(1, entity.getDayKey());
        statement.bindLong(2, entity.getTotalMs());
        statement.bindLong(3, entity.getLastUpdateAt());
        statement.bindString(4, entity.getDayKey());
      }
    });
  }

  @Override
  public Object addUsage(final String dayKey, final long deltaMs, final long now,
      final Continuation<? super Unit> $completion) {
    return RoomDatabaseKt.withTransaction(__db, (__cont) -> DailyUsageDao.DefaultImpls.addUsage(DailyUsageDao_Impl.this, dayKey, deltaMs, now, __cont), $completion);
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
  public Object upsert(final DailyUsageEntity entity,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __upsertionAdapterOfDailyUsageEntity.upsert(entity);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object get(final String dayKey, final Continuation<? super DailyUsageEntity> $completion) {
    final String _sql = "SELECT * FROM daily_usage WHERE dayKey = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, dayKey);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<DailyUsageEntity>() {
      @Override
      @Nullable
      public DailyUsageEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfDayKey = CursorUtil.getColumnIndexOrThrow(_cursor, "dayKey");
          final int _cursorIndexOfTotalMs = CursorUtil.getColumnIndexOrThrow(_cursor, "totalMs");
          final int _cursorIndexOfLastUpdateAt = CursorUtil.getColumnIndexOrThrow(_cursor, "lastUpdateAt");
          final DailyUsageEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpDayKey;
            _tmpDayKey = _cursor.getString(_cursorIndexOfDayKey);
            final long _tmpTotalMs;
            _tmpTotalMs = _cursor.getLong(_cursorIndexOfTotalMs);
            final long _tmpLastUpdateAt;
            _tmpLastUpdateAt = _cursor.getLong(_cursorIndexOfLastUpdateAt);
            _result = new DailyUsageEntity(_tmpDayKey,_tmpTotalMs,_tmpLastUpdateAt);
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

  @Override
  public Flow<DailyUsageEntity> observe(final String dayKey) {
    final String _sql = "SELECT * FROM daily_usage WHERE dayKey = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, dayKey);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"daily_usage"}, new Callable<DailyUsageEntity>() {
      @Override
      @Nullable
      public DailyUsageEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfDayKey = CursorUtil.getColumnIndexOrThrow(_cursor, "dayKey");
          final int _cursorIndexOfTotalMs = CursorUtil.getColumnIndexOrThrow(_cursor, "totalMs");
          final int _cursorIndexOfLastUpdateAt = CursorUtil.getColumnIndexOrThrow(_cursor, "lastUpdateAt");
          final DailyUsageEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpDayKey;
            _tmpDayKey = _cursor.getString(_cursorIndexOfDayKey);
            final long _tmpTotalMs;
            _tmpTotalMs = _cursor.getLong(_cursorIndexOfTotalMs);
            final long _tmpLastUpdateAt;
            _tmpLastUpdateAt = _cursor.getLong(_cursorIndexOfLastUpdateAt);
            _result = new DailyUsageEntity(_tmpDayKey,_tmpTotalMs,_tmpLastUpdateAt);
          } else {
            _result = null;
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

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
