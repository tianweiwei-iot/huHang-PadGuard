package com.padguard.core.transport;

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
public final class TransportSettings_Factory implements Factory<TransportSettings> {
  @Override
  public TransportSettings get() {
    return newInstance();
  }

  public static TransportSettings_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static TransportSettings newInstance() {
    return new TransportSettings();
  }

  private static final class InstanceHolder {
    private static final TransportSettings_Factory INSTANCE = new TransportSettings_Factory();
  }
}
