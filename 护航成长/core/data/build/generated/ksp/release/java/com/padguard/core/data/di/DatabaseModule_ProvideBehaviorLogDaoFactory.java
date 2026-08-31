package com.padguard.core.data.di;

import com.padguard.core.data.db.BehaviorLogDao;
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
public final class DatabaseModule_ProvideBehaviorLogDaoFactory implements Factory<BehaviorLogDao> {
  private final Provider<PadGuardDatabase> dbProvider;

  public DatabaseModule_ProvideBehaviorLogDaoFactory(Provider<PadGuardDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public BehaviorLogDao get() {
    return provideBehaviorLogDao(dbProvider.get());
  }

  public static DatabaseModule_ProvideBehaviorLogDaoFactory create(
      Provider<PadGuardDatabase> dbProvider) {
    return new DatabaseModule_ProvideBehaviorLogDaoFactory(dbProvider);
  }

  public static BehaviorLogDao provideBehaviorLogDao(PadGuardDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideBehaviorLogDao(db));
  }
}
