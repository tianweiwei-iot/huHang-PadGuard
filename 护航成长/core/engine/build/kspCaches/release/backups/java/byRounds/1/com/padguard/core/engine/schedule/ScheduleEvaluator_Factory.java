package com.padguard.core.engine.schedule;

import com.padguard.core.common.TimeProvider;
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
public final class ScheduleEvaluator_Factory implements Factory<ScheduleEvaluator> {
  private final Provider<TimeProvider> timeProvider;

  private final Provider<HolidayProvider> holidayProvider;

  public ScheduleEvaluator_Factory(Provider<TimeProvider> timeProvider,
      Provider<HolidayProvider> holidayProvider) {
    this.timeProvider = timeProvider;
    this.holidayProvider = holidayProvider;
  }

  @Override
  public ScheduleEvaluator get() {
    return newInstance(timeProvider.get(), holidayProvider.get());
  }

  public static ScheduleEvaluator_Factory create(Provider<TimeProvider> timeProvider,
      Provider<HolidayProvider> holidayProvider) {
    return new ScheduleEvaluator_Factory(timeProvider, holidayProvider);
  }

  public static ScheduleEvaluator newInstance(TimeProvider timeProvider,
      HolidayProvider holidayProvider) {
    return new ScheduleEvaluator(timeProvider, holidayProvider);
  }
}
