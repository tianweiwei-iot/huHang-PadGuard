package com.padguard.core.common;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

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
public final class TimeProvider_Factory implements Factory<TimeProvider> {
  @Override
  public TimeProvider get() {
    return newInstance();
  }

  public static TimeProvider_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static TimeProvider newInstance() {
    return new TimeProvider();
  }

  private static final class InstanceHolder {
    private static final TimeProvider_Factory INSTANCE = new TimeProvider_Factory();
  }
}
