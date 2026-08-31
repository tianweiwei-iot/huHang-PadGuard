package com.padguard.core.engine.lock;

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
public final class LockController_Factory implements Factory<LockController> {
  private final Provider<TimeProvider> timeProvider;

  public LockController_Factory(Provider<TimeProvider> timeProvider) {
    this.timeProvider = timeProvider;
  }

  @Override
  public LockController get() {
    return newInstance(timeProvider.get());
  }

  public static LockController_Factory create(Provider<TimeProvider> timeProvider) {
    return new LockController_Factory(timeProvider);
  }

  public static LockController newInstance(TimeProvider timeProvider) {
    return new LockController(timeProvider);
  }
}
