package com.padguard.core.engine.enforcer;

import android.content.Context;
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
public final class SecurityEnforcer_Factory implements Factory<SecurityEnforcer> {
  private final Provider<Context> contextProvider;

  private final Provider<DeviceAdminBridge> adminProvider;

  public SecurityEnforcer_Factory(Provider<Context> contextProvider,
      Provider<DeviceAdminBridge> adminProvider) {
    this.contextProvider = contextProvider;
    this.adminProvider = adminProvider;
  }

  @Override
  public SecurityEnforcer get() {
    return newInstance(contextProvider.get(), adminProvider.get());
  }

  public static SecurityEnforcer_Factory create(Provider<Context> contextProvider,
      Provider<DeviceAdminBridge> adminProvider) {
    return new SecurityEnforcer_Factory(contextProvider, adminProvider);
  }

  public static SecurityEnforcer newInstance(Context context, DeviceAdminBridge admin) {
    return new SecurityEnforcer(context, admin);
  }
}
