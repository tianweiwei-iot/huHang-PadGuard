package com.padguard.child.monitor;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class DeviceSnapshotCollector_Factory implements Factory<DeviceSnapshotCollector> {
  private final Provider<Context> contextProvider;

  public DeviceSnapshotCollector_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public DeviceSnapshotCollector get() {
    return newInstance(contextProvider.get());
  }

  public static DeviceSnapshotCollector_Factory create(Provider<Context> contextProvider) {
    return new DeviceSnapshotCollector_Factory(contextProvider);
  }

  public static DeviceSnapshotCollector newInstance(Context context) {
    return new DeviceSnapshotCollector(context);
  }
}
