package com.padguard.core.engine.enforcer;

import com.padguard.core.engine.admin.DeviceAdminBridge;
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
public final class PeripheralEnforcer_Factory implements Factory<PeripheralEnforcer> {
  private final Provider<DeviceAdminBridge> adminProvider;

  public PeripheralEnforcer_Factory(Provider<DeviceAdminBridge> adminProvider) {
    this.adminProvider = adminProvider;
  }

  @Override
  public PeripheralEnforcer get() {
    return newInstance(adminProvider.get());
  }

  public static PeripheralEnforcer_Factory create(Provider<DeviceAdminBridge> adminProvider) {
    return new PeripheralEnforcer_Factory(adminProvider);
  }

  public static PeripheralEnforcer newInstance(DeviceAdminBridge admin) {
    return new PeripheralEnforcer(admin);
  }
}
