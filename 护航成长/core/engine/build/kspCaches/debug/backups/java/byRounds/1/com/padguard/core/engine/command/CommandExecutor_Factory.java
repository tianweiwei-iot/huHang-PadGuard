package com.padguard.core.engine.command;

import com.padguard.core.common.TimeProvider;
import com.padguard.core.data.repository.PolicyRepository;
import com.padguard.core.engine.admin.DeviceAdminBridge;
import com.padguard.core.engine.enforcer.AppLimitEnforcer;
import com.padguard.core.engine.enforcer.EyeCareEnforcer;
import com.padguard.core.engine.enforcer.PeripheralEnforcer;
import com.padguard.core.engine.lock.LockController;
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
public final class CommandExecutor_Factory implements Factory<CommandExecutor> {
  private final Provider<DeviceAdminBridge> adminProvider;

  private final Provider<LockController> lockControllerProvider;

  private final Provider<PolicyRepository> policyRepositoryProvider;

  private final Provider<PeripheralEnforcer> peripheralEnforcerProvider;

  private final Provider<AppLimitEnforcer> appLimitEnforcerProvider;

  private final Provider<EyeCareEnforcer> eyeCareEnforcerProvider;

  private final Provider<TimeProvider> timeProvider;

  public CommandExecutor_Factory(Provider<DeviceAdminBridge> adminProvider,
      Provider<LockController> lockControllerProvider,
      Provider<PolicyRepository> policyRepositoryProvider,
      Provider<PeripheralEnforcer> peripheralEnforcerProvider,
      Provider<AppLimitEnforcer> appLimitEnforcerProvider,
      Provider<EyeCareEnforcer> eyeCareEnforcerProvider, Provider<TimeProvider> timeProvider) {
    this.adminProvider = adminProvider;
    this.lockControllerProvider = lockControllerProvider;
    this.policyRepositoryProvider = policyRepositoryProvider;
    this.peripheralEnforcerProvider = peripheralEnforcerProvider;
    this.appLimitEnforcerProvider = appLimitEnforcerProvider;
    this.eyeCareEnforcerProvider = eyeCareEnforcerProvider;
    this.timeProvider = timeProvider;
  }

  @Override
  public CommandExecutor get() {
    return newInstance(adminProvider.get(), lockControllerProvider.get(), policyRepositoryProvider.get(), peripheralEnforcerProvider.get(), appLimitEnforcerProvider.get(), eyeCareEnforcerProvider.get(), timeProvider.get());
  }

  public static CommandExecutor_Factory create(Provider<DeviceAdminBridge> adminProvider,
      Provider<LockController> lockControllerProvider,
      Provider<PolicyRepository> policyRepositoryProvider,
      Provider<PeripheralEnforcer> peripheralEnforcerProvider,
      Provider<AppLimitEnforcer> appLimitEnforcerProvider,
      Provider<EyeCareEnforcer> eyeCareEnforcerProvider, Provider<TimeProvider> timeProvider) {
    return new CommandExecutor_Factory(adminProvider, lockControllerProvider, policyRepositoryProvider, peripheralEnforcerProvider, appLimitEnforcerProvider, eyeCareEnforcerProvider, timeProvider);
  }

  public static CommandExecutor newInstance(DeviceAdminBridge admin, LockController lockController,
      PolicyRepository policyRepository, PeripheralEnforcer peripheralEnforcer,
      AppLimitEnforcer appLimitEnforcer, EyeCareEnforcer eyeCareEnforcer,
      TimeProvider timeProvider) {
    return new CommandExecutor(admin, lockController, policyRepository, peripheralEnforcer, appLimitEnforcer, eyeCareEnforcer, timeProvider);
  }
}
