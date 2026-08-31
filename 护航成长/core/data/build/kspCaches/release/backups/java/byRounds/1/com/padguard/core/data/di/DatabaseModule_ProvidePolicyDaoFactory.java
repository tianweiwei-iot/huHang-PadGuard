package com.padguard.core.data.di;

import com.padguard.core.data.db.PadGuardDatabase;
import com.padguard.core.data.db.PolicyDao;
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
public final class DatabaseModule_ProvidePolicyDaoFactory implements Factory<PolicyDao> {
  private final Provider<PadGuardDatabase> dbProvider;

  public DatabaseModule_ProvidePolicyDaoFactory(Provider<PadGuardDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public PolicyDao get() {
    return providePolicyDao(dbProvider.get());
  }

  public static DatabaseModule_ProvidePolicyDaoFactory create(
      Provider<PadGuardDatabase> dbProvider) {
    return new DatabaseModule_ProvidePolicyDaoFactory(dbProvider);
  }

  public static PolicyDao providePolicyDao(PadGuardDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.providePolicyDao(db));
  }
}
