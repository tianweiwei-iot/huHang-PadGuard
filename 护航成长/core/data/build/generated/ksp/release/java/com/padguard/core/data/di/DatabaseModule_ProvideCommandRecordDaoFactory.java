package com.padguard.core.data.di;

import com.padguard.core.data.db.CommandRecordDao;
import com.padguard.core.data.db.PadGuardDatabase;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation"
})
public final class DatabaseModule_ProvideCommandRecordDaoFactory implements Factory<CommandRecordDao> {
  private final Provider<PadGuardDatabase> dbProvider;

  public DatabaseModule_ProvideCommandRecordDaoFactory(Provider<PadGuardDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public CommandRecordDao get() {
    return provideCommandRecordDao(dbProvider.get());
  }

  public static DatabaseModule_ProvideCommandRecordDaoFactory create(
      Provider<PadGuardDatabase> dbProvider) {
    return new DatabaseModule_ProvideCommandRecordDaoFactory(dbProvider);
  }

  public static CommandRecordDao provideCommandRecordDao(PadGuardDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideCommandRecordDao(db));
  }
}
