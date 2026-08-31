package com.padguard.core.engine.schedule;

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
public final class DefaultHolidayProvider_Factory implements Factory<DefaultHolidayProvider> {
  @Override
  public DefaultHolidayProvider get() {
    return newInstance();
  }

  public static DefaultHolidayProvider_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static DefaultHolidayProvider newInstance() {
    return new DefaultHolidayProvider();
  }

  private static final class InstanceHolder {
    private static final DefaultHolidayProvider_Factory INSTANCE = new DefaultHolidayProvider_Factory();
  }
}
