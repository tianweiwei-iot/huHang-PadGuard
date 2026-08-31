package com.padguard.core.data.di;

import com.padguard.core.data.db.DailyUsageDao;
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
public final class DatabaseModule_ProvideDailyUsageDaoFactory implements Factory<DailyUsageDao> {
  private final Provider<PadGuardDatabase> dbProvider;

  public DatabaseModule_ProvideDailyUsageDaoFactory(Provider<PadGuardDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public DailyUsageDao get() {
    return provideDailyUsageDao(dbProvider.get());
  }

  public static DatabaseModule_ProvideDailyUsageDaoFactory create(
      Provider<PadGuardDatabase> dbProvider) {
    return new DatabaseModule_ProvideDailyUsageDaoFactory(dbProvider);
  }

  public static DailyUsageDao provideDailyUsageDao(PadGuardDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideDailyUsageDao(db));
  }
}
