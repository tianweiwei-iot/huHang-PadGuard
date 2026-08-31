package com.padguard.core.engine.enforcer;

import com.padguard.core.common.TimeProvider;
import com.padguard.core.data.repository.UsageRepository;
import com.padguard.core.engine.schedule.ScheduleEvaluator;
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
public final class AppLimitEnforcer_Factory implements Factory<AppLimitEnforcer> {
  private final Provider<UsageRepository> usageRepositoryProvider;

  private final Provider<TimeProvider> timeProvider;

  private final Provider<ScheduleEvaluator> scheduleEvaluatorProvider;

  public AppLimitEnforcer_Factory(Provider<UsageRepository> usageRepositoryProvider,
      Provider<TimeProvider> timeProvider, Provider<ScheduleEvaluator> scheduleEvaluatorProvider) {
    this.usageRepositoryProvider = usageRepositoryProvider;
    this.timeProvider = timeProvider;
    this.scheduleEvaluatorProvider = scheduleEvaluatorProvider;
  }

  @Override
  public AppLimitEnforcer get() {
    return newInstance(usageRepositoryProvider.get(), timeProvider.get(), scheduleEvaluatorProvider.get());
  }

  public static AppLimitEnforcer_Factory create(Provider<UsageRepository> usageRepositoryProvider,
      Provider<TimeProvider> timeProvider, Provider<ScheduleEvaluator> scheduleEvaluatorProvider) {
    return new AppLimitEnforcer_Factory(usageRepositoryProvider, timeProvider, scheduleEvaluatorProvider);
  }

  public static AppLimitEnforcer newInstance(UsageRepository usageRepository,
      TimeProvider timeProvider, ScheduleEvaluator scheduleEvaluator) {
    return new AppLimitEnforcer(usageRepository, timeProvider, scheduleEvaluator);
  }
}
