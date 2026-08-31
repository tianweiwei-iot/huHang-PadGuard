package com.padguard.core.engine.enforcer;

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
public final class EyeCareEnforcer_Factory implements Factory<EyeCareEnforcer> {
  private final Provider<TimeProvider> timeProvider;

  public EyeCareEnforcer_Factory(Provider<TimeProvider> timeProvider) {
    this.timeProvider = timeProvider;
  }

  @Override
  public EyeCareEnforcer get() {
    return newInstance(timeProvider.get());
  }

  public static EyeCareEnforcer_Factory create(Provider<TimeProvider> timeProvider) {
    return new EyeCareEnforcer_Factory(timeProvider);
  }

  public static EyeCareEnforcer newInstance(TimeProvider timeProvider) {
    return new EyeCareEnforcer(timeProvider);
  }
}
