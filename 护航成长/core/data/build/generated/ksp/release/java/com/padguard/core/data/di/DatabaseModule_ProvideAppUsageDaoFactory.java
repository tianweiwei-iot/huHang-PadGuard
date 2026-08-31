package com.padguard.core.data.di;

import com.padguard.core.data.db.AppUsageDao;
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
public final class DatabaseModule_ProvideAppUsageDaoFactory implements Factory<AppUsageDao> {
  private final Provider<PadGuardDatabase> dbProvider;

  public DatabaseModule_ProvideAppUsageDaoFactory(Provider<PadGuardDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public AppUsageDao get() {
    return provideAppUsageDao(dbProvider.get());
  }

  public static DatabaseModule_ProvideAppUsageDaoFactory create(
      Provider<PadGuardDatabase> dbProvider) {
    return new DatabaseModule_ProvideAppUsageDaoFactory(dbProvider);
  }

  public static AppUsageDao provideAppUsageDao(PadGuardDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideAppUsageDao(db));
  }
}
