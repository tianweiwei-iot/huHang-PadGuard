package com.padguard.core.data.repository;

import com.padguard.core.data.db.AppUsageDao;
import com.padguard.core.data.db.DailyUsageDao;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class UsageRepository_Factory implements Factory<UsageRepository> {
  private final Provider<AppUsageDao> appUsageDaoProvider;

  private final Provider<DailyUsageDao> dailyUsageDaoProvider;

  public UsageRepository_Factory(Provider<AppUsageDao> appUsageDaoProvider,
      Provider<DailyUsageDao> dailyUsageDaoProvider) {
    this.appUsageDaoProvider = appUsageDaoProvider;
    this.dailyUsageDaoProvider = dailyUsageDaoProvider;
  }

  @Override
  public UsageRepository get() {
    return newInstance(appUsageDaoProvider.get(), dailyUsageDaoProvider.get());
  }

  public static UsageRepository_Factory create(Provider<AppUsageDao> appUsageDaoProvider,
      Provider<DailyUsageDao> dailyUsageDaoProvider) {
    return new UsageRepository_Factory(appUsageDaoProvider, dailyUsageDaoProvider);
  }

  public static UsageRepository newInstance(AppUsageDao appUsageDao, DailyUsageDao dailyUsageDao) {
    return new UsageRepository(appUsageDao, dailyUsageDao);
  }
}
