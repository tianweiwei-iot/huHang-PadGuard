package com.padguard.core.data.db;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.room.util.StringUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.StringBuilder;
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
public final class BehaviorLogDao_Impl implements BehaviorLogDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<BehaviorLogEntity> __insertionAdapterOfBehaviorLogEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeleteUploadedBefore;

  private final SharedSQLiteStatement __preparedStmtOfClear;

  public BehaviorLogDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfBehaviorLogEntity = new EntityInsertionAdapter<BehaviorLogEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR IGNORE INTO `behavior_log` (`id`,`logId`,`type`,`timestamp`,`payloadJson`,`uploaded`) VALUES (nullif(?, 0),?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final BehaviorLogEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getLogId());
        statement.bindString(3, entity.getType());
        statement.bindLong(4, entity.getTimestamp());
        statement.bindString(5, entity.getPayloadJson());
        final int _tmp = entity.getUploaded() ? 1 : 0;
        statement.bindLong(6, _tmp);
      }
    };
    this.__preparedStmtOfDeleteUploadedBefore = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM behavior_log WHERE uploaded = 1 AND timestamp < ?";
        return _query;
      }
    };
    this.__preparedStmtOfClear = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM behavior_log";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final BehaviorLogEntity entity,
      final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfBehaviorLogEntity.insertAndReturnId(entity);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object insertAll(final List<BehaviorLogEntity> entities,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfBehaviorLogEntity.insert(entities);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteUploadedBefore(final long before,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteUploadedBefore.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, before);
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
          __preparedStmtOfDeleteUploadedBefore.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object clear(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfClear.acquire();
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
          __preparedStmtOfClear.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object pending(final int limit,
      final Continuation<? super List<BehaviorLogEntity>> $completion) {
    final String _sql = "SELECT * FROM behavior_log WHERE uploaded = 0 ORDER BY timestamp ASC LIMIT ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, limit);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<BehaviorLogEntity>>() {
      @Override
      @NonNull
      public List<BehaviorLogEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfLogId = CursorUtil.getColumnIndexOrThrow(_cursor, "logId");
          final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
          final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
          final int _cursorIndexOfPayloadJson = CursorUtil.getColumnIndexOrThrow(_cursor, "payloadJson");
          final int _cursorIndexOfUploaded = CursorUtil.getColumnIndexOrThrow(_cursor, "uploaded");
          final List<BehaviorLogEntity> _result = new ArrayList<BehaviorLogEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final BehaviorLogEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpLogId;
            _tmpLogId = _cursor.getString(_cursorIndexOfLogId);
            final String _tmpType;
            _tmpType = _cursor.getString(_cursorIndexOfType);
            final long _tmpTimestamp;
            _tmpTimestamp = _cursor.getLong(_cursorIndexOfTimestamp);
            final String _tmpPayloadJson;
            _tmpPayloadJson = _cursor.getString(_cursorIndexOfPayloadJson);
            final boolean _tmpUploaded;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfUploaded);
            _tmpUploaded = _tmp != 0;
            _item = new BehaviorLogEntity(_tmpId,_tmpLogId,_tmpType,_tmpTimestamp,_tmpPayloadJson,_tmpUploaded);
            _result.add(_item);
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
  public Object pendingCount(final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM behavior_log WHERE uploaded = 0";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
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
  public Flow<Integer> observePendingCount() {
    final String _sql = "SELECT COUNT(*) FROM behavior_log WHERE uploaded = 0";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"behavior_log"}, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
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
  public Object markUploaded(final List<Long> ids, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final StringBuilder _stringBuilder = StringUtil.newStringBuilder();
        _stringBuilder.append("UPDATE behavior_log SET uploaded = 1 WHERE id IN (");
        final int _inputSize = ids.size();
        StringUtil.appendPlaceholders(_stringBuilder, _inputSize);
        _stringBuilder.append(")");
        final String _sql = _stringBuilder.toString();
        final SupportSQLiteStatement _stmt = __db.compileStatement(_sql);
        int _argIndex = 1;
        for (long _item : ids) {
          _stmt.bindLong(_argIndex, _item);
          _argIndex++;
        }
        __db.beginTransaction();
        try {
          _stmt.executeUpdateDelete();
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
