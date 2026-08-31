package com.padguard.core.data.repository;

import com.padguard.core.data.db.BehaviorLogDao;
import com.padguard.core.data.db.RiskEventDao;
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
public final class LogRepository_Factory implements Factory<LogRepository> {
  private final Provider<BehaviorLogDao> logDaoProvider;

  private final Provider<RiskEventDao> riskDaoProvider;

  public LogRepository_Factory(Provider<BehaviorLogDao> logDaoProvider,
      Provider<RiskEventDao> riskDaoProvider) {
    this.logDaoProvider = logDaoProvider;
    this.riskDaoProvider = riskDaoProvider;
  }

  @Override
  public LogRepository get() {
    return newInstance(logDaoProvider.get(), riskDaoProvider.get());
  }

  public static LogRepository_Factory create(Provider<BehaviorLogDao> logDaoProvider,
      Provider<RiskEventDao> riskDaoProvider) {
    return new LogRepository_Factory(logDaoProvider, riskDaoProvider);
  }

  public static LogRepository newInstance(BehaviorLogDao logDao, RiskEventDao riskDao) {
    return new LogRepository(logDao, riskDao);
  }
}
