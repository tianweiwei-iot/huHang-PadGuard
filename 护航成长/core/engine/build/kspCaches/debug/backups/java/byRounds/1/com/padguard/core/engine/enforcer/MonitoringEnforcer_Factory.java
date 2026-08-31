package com.padguard.core.engine.enforcer;

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
public final class MonitoringEnforcer_Factory implements Factory<MonitoringEnforcer> {
  @Override
  public MonitoringEnforcer get() {
    return newInstance();
  }

  public static MonitoringEnforcer_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static MonitoringEnforcer newInstance() {
    return new MonitoringEnforcer();
  }

  private static final class InstanceHolder {
    private static final MonitoringEnforcer_Factory INSTANCE = new MonitoringEnforcer_Factory();
  }
}
