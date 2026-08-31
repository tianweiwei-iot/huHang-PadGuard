package com.padguard.core.engine.guard;

import android.content.Context;
import com.padguard.core.common.TimeProvider;
import com.padguard.core.engine.admin.DeviceAdminBridge;
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
public final class TamperDetector_Factory implements Factory<TamperDetector> {
  private final Provider<Context> contextProvider;

  private final Provider<DeviceAdminBridge> adminProvider;

  private final Provider<TimeProvider> timeProvider;

  public TamperDetector_Factory(Provider<Context> contextProvider,
      Provider<DeviceAdminBridge> adminProvider, Provider<TimeProvider> timeProvider) {
    this.contextProvider = contextProvider;
    this.adminProvider = adminProvider;
    this.timeProvider = timeProvider;
  }

  @Override
  public TamperDetector get() {
    return newInstance(contextProvider.get(), adminProvider.get(), timeProvider.get());
  }

  public static TamperDetector_Factory create(Provider<Context> contextProvider,
      Provider<DeviceAdminBridge> adminProvider, Provider<TimeProvider> timeProvider) {
    return new TamperDetector_Factory(contextProvider, adminProvider, timeProvider);
  }

  public static TamperDetector newInstance(Context context, DeviceAdminBridge admin,
      TimeProvider timeProvider) {
    return new TamperDetector(context, admin, timeProvider);
  }
}
