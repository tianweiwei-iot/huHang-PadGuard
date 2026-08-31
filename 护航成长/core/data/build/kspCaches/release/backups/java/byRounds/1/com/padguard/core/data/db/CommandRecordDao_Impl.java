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
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Long;
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

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class CommandRecordDao_Impl implements CommandRecordDao {
  private final RoomDatabase __db;

  private final SharedSQLiteStatement __preparedStmtOfDeleteBefore;

  private final EntityUpsertionAdapter<CommandRecordEntity> __upsertionAdapterOfCommandRecordEntity;

  public CommandRecordDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__preparedStmtOfDeleteBefore = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM command_record WHERE receivedAt < ?";
        return _query;
      }
    };
    this.__upsertionAdapterOfCommandRecordEntity = new EntityUpsertionAdapter<CommandRecordEntity>(new EntityInsertionAdapter<CommandRecordEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT INTO `command_record` (`msgId`,`type`,`receivedAt`,`executedAt`,`status`,`retryCount`,`errorMessage`) VALUES (?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final CommandRecordEntity entity) {
        statement.bindString(1, entity.getMsgId());
        statement.bindString(2, entity.getType());
        statement.bindLong(3, entity.getReceivedAt());
        if (entity.getExecutedAt() == null) {
          statement.bindNull(4);
        } else {
          statement.bindLong(4, entity.getExecutedAt());
        }
        statement.bindString(5, entity.getStatus());
        statement.bindLong(6, entity.getRetryCount());
        if (entity.getErrorMessage() == null) {
          statement.bindNull(7);
        } else {
          statement.bindString(7, entity.getErrorMessage());
        }
      }
    }, new EntityDeletionOrUpdateAdapter<CommandRecordEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE `command_record` SET `msgId` = ?,`type` = ?,`receivedAt` = ?,`executedAt` = ?,`status` = ?,`retryCount` = ?,`errorMessage` = ? WHERE `msgId` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final CommandRecordEntity entity) {
        statement.bindString(1, entity.getMsgId());
        statement.bindString(2, entity.getType());
        statement.bindLong(3, entity.getReceivedAt());
        if (entity.getExecutedAt() == null) {
          statement.bindNull(4);
        } else {
          statement.bindLong(4, entity.getExecutedAt());
        }
        statement.bindString(5, entity.getStatus());
        statement.bindLong(6, entity.getRetryCount());
        if (entity.getErrorMessage() == null) {
          statement.bindNull(7);
        } else {
          statement.bindString(7, entity.getErrorMessage());
        }
        statement.bindString(8, entity.getMsgId());
      }
    });
  }

  @Override
  public Object deleteBefore(final long before, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteBefore.acquire();
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
          __preparedStmtOfDeleteBefore.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object upsert(final CommandRecordEntity entity,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __upsertionAdapterOfCommandRecordEntity.upsert(entity);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object get(final String msgId,
      final Continuation<? super CommandRecordEntity> $completion) {
    final String _sql = "SELECT * FROM command_record WHERE msgId = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, msgId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<CommandRecordEntity>() {
      @Override
      @Nullable
      public CommandRecordEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfMsgId = CursorUtil.getColumnIndexOrThrow(_cursor, "msgId");
          final int _cursorIndexOfType = CursorUtil.getColumnIndexOrThrow(_cursor, "type");
          final int _cursorIndexOfReceivedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "receivedAt");
          final int _cursorIndexOfExecutedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "executedAt");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfRetryCount = CursorUtil.getColumnIndexOrThrow(_cursor, "retryCount");
          final int _cursorIndexOfErrorMessage = CursorUtil.getColumnIndexOrThrow(_cursor, "errorMessage");
          final CommandRecordEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpMsgId;
            _tmpMsgId = _cursor.getString(_cursorIndexOfMsgId);
            final String _tmpType;
            _tmpType = _cursor.getString(_cursorIndexOfType);
            final long _tmpReceivedAt;
            _tmpReceivedAt = _cursor.getLong(_cursorIndexOfReceivedAt);
            final Long _tmpExecutedAt;
            if (_cursor.isNull(_cursorIndexOfExecutedAt)) {
              _tmpExecutedAt = null;
            } else {
              _tmpExecutedAt = _cursor.getLong(_cursorIndexOfExecutedAt);
            }
            final String _tmpStatus;
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus);
            final int _tmpRetryCount;
            _tmpRetryCount = _cursor.getInt(_cursorIndexOfRetryCount);
            final String _tmpErrorMessage;
            if (_cursor.isNull(_cursorIndexOfErrorMessage)) {
              _tmpErrorMessage = null;
            } else {
              _tmpErrorMessage = _cursor.getString(_cursorIndexOfErrorMessage);
            }
            _result = new CommandRecordEntity(_tmpMsgId,_tmpType,_tmpReceivedAt,_tmpExecutedAt,_tmpStatus,_tmpRetryCount,_tmpErrorMessage);
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
  public Object recentIds(final Continuation<? super List<String>> $completion) {
    final String _sql = "SELECT msgId FROM command_record ORDER BY receivedAt DESC LIMIT 500";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<String>>() {
      @Override
      @NonNull
      public List<String> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final List<String> _result = new ArrayList<String>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final String _item;
            _item = _cursor.getString(0);
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

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
