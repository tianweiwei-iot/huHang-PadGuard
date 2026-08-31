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
public final class PolicyDao_Impl implements PolicyDao {
  private final RoomDatabase __db;

  private final SharedSQLiteStatement __preparedStmtOfClear;

  private final EntityUpsertionAdapter<PolicyEntity> __upsertionAdapterOfPolicyEntity;

  public PolicyDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__preparedStmtOfClear = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM policy";
        return _query;
      }
    };
    this.__upsertionAdapterOfPolicyEntity = new EntityUpsertionAdapter<PolicyEntity>(new EntityInsertionAdapter<PolicyEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT INTO `policy` (`id`,`version`,`encryptedJson`,`updatedAt`,`source`) VALUES (?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final PolicyEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getVersion());
        statement.bindString(3, entity.getEncryptedJson());
        statement.bindLong(4, entity.getUpdatedAt());
        statement.bindString(5, entity.getSource());
      }
    }, new EntityDeletionOrUpdateAdapter<PolicyEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE `policy` SET `id` = ?,`version` = ?,`encryptedJson` = ?,`updatedAt` = ?,`source` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final PolicyEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getVersion());
        statement.bindString(3, entity.getEncryptedJson());
        statement.bindLong(4, entity.getUpdatedAt());
        statement.bindString(5, entity.getSource());
        statement.bindLong(6, entity.getId());
      }
    });
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
  public Object upsert(final PolicyEntity entity, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __upsertionAdapterOfPolicyEntity.upsert(entity);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object get(final int id, final Continuation<? super PolicyEntity> $completion) {
    final String _sql = "SELECT * FROM policy WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<PolicyEntity>() {
      @Override
      @Nullable
      public PolicyEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfVersion = CursorUtil.getColumnIndexOrThrow(_cursor, "version");
          final int _cursorIndexOfEncryptedJson = CursorUtil.getColumnIndexOrThrow(_cursor, "encryptedJson");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final int _cursorIndexOfSource = CursorUtil.getColumnIndexOrThrow(_cursor, "source");
          final PolicyEntity _result;
          if (_cursor.moveToFirst()) {
            final int _tmpId;
            _tmpId = _cursor.getInt(_cursorIndexOfId);
            final int _tmpVersion;
            _tmpVersion = _cursor.getInt(_cursorIndexOfVersion);
            final String _tmpEncryptedJson;
            _tmpEncryptedJson = _cursor.getString(_cursorIndexOfEncryptedJson);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            final String _tmpSource;
            _tmpSource = _cursor.getString(_cursorIndexOfSource);
            _result = new PolicyEntity(_tmpId,_tmpVersion,_tmpEncryptedJson,_tmpUpdatedAt,_tmpSource);
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
  public Flow<PolicyEntity> observe(final int id) {
    final String _sql = "SELECT * FROM policy WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"policy"}, new Callable<PolicyEntity>() {
      @Override
      @Nullable
      public PolicyEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfVersion = CursorUtil.getColumnIndexOrThrow(_cursor, "version");
          final int _cursorIndexOfEncryptedJson = CursorUtil.getColumnIndexOrThrow(_cursor, "encryptedJson");
          final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updatedAt");
          final int _cursorIndexOfSource = CursorUtil.getColumnIndexOrThrow(_cursor, "source");
          final PolicyEntity _result;
          if (_cursor.moveToFirst()) {
            final int _tmpId;
            _tmpId = _cursor.getInt(_cursorIndexOfId);
            final int _tmpVersion;
            _tmpVersion = _cursor.getInt(_cursorIndexOfVersion);
            final String _tmpEncryptedJson;
            _tmpEncryptedJson = _cursor.getString(_cursorIndexOfEncryptedJson);
            final long _tmpUpdatedAt;
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
            final String _tmpSource;
            _tmpSource = _cursor.getString(_cursorIndexOfSource);
            _result = new PolicyEntity(_tmpId,_tmpVersion,_tmpEncryptedJson,_tmpUpdatedAt,_tmpSource);
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
