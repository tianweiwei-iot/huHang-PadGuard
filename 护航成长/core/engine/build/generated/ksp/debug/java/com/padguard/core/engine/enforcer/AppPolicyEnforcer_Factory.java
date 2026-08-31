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
public final class AppPolicyEnforcer_Factory implements Factory<AppPolicyEnforcer> {
  private final Provider<Context> contextProvider;

  private final Provider<DeviceAdminBridge> adminProvider;

  public AppPolicyEnforcer_Factory(Provider<Context> contextProvider,
      Provider<DeviceAdminBridge> adminProvider) {
    this.contextProvider = contextProvider;
    this.adminProvider = adminProvider;
  }

  @Override
  public AppPolicyEnforcer get() {
    return newInstance(contextProvider.get(), adminProvider.get());
  }

  public static AppPolicyEnforcer_Factory create(Provider<Context> contextProvider,
      Provider<DeviceAdminBridge> adminProvider) {
    return new AppPolicyEnforcer_Factory(contextProvider, adminProvider);
  }

  public static AppPolicyEnforcer newInstance(Context context, DeviceAdminBridge admin) {
    return new AppPolicyEnforcer(context, admin);
  }
}
