package com.padguard.core.data.di;

import com.padguard.core.data.db.PadGuardDatabase;
import com.padguard.core.data.db.RiskEventDao;
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
public final class DatabaseModule_ProvideRiskEventDaoFactory implements Factory<RiskEventDao> {
  private final Provider<PadGuardDatabase> dbProvider;

  public DatabaseModule_ProvideRiskEventDaoFactory(Provider<PadGuardDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public RiskEventDao get() {
    return provideRiskEventDao(dbProvider.get());
  }

  public static DatabaseModule_ProvideRiskEventDaoFactory create(
      Provider<PadGuardDatabase> dbProvider) {
    return new DatabaseModule_ProvideRiskEventDaoFactory(dbProvider);
  }

  public static RiskEventDao provideRiskEventDao(PadGuardDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideRiskEventDao(db));
  }
}
