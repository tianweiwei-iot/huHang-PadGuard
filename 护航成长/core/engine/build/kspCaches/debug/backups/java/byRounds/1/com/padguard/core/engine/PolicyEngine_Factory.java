package com.padguard.core.engine;

import com.padguard.core.common.TimeProvider;
import com.padguard.core.data.repository.AuthRepository;
import com.padguard.core.data.repository.LogRepository;
import com.padguard.core.data.repository.PolicyRepository;
import com.padguard.core.engine.admin.DeviceAdminBridge;
import com.padguard.core.engine.command.CommandExecutor;
import com.padguard.core.engine.enforcer.AppLimitEnforcer;
import com.padguard.core.engine.enforcer.AppPolicyEnforcer;
import com.padguard.core.engine.enforcer.EyeCareEnforcer;
import com.padguard.core.engine.enforcer.KioskEnforcer;
import com.padguard.core.engine.enforcer.MonitoringEnforcer;
import com.padguard.core.engine.enforcer.PeripheralEnforcer;
import com.padguard.core.engine.enforcer.SecurityEnforcer;
import com.padguard.core.engine.enforcer.SystemLockEnforcer;
import com.padguard.core.engine.enforcer.WebEnforcer;
import com.padguard.core.engine.guard.TamperDetector;
import com.padguard.core.engine.lock.LockController;
import com.padguard.core.engine.schedule.ScheduleEvaluator;
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
public final class PolicyEngine_Factory implements Factory<PolicyEngine> {
  private final Provider<DeviceAdminBridge> adminProvider;

  private final Provider<PeripheralEnforcer> peripheralEnforcerProvider;

  private final Provider<SystemLockEnforcer> systemLockEnforcerProvider;

  private final Provider<AppPolicyEnforcer> appPolicyEnforcerProvider;

  private final Provider<AppLimitEnforcer> appLimitEnforcerProvider;

  private final Provider<EyeCareEnforcer> eyeCareEnforcerProvider;

  private final Provider<WebEnforcer> webEnforcerProvider;

  private final Provider<KioskEnforcer> kioskEnforcerProvider;

  private final Provider<SecurityEnforcer> securityEnforcerProvider;

  private final Provider<MonitoringEnforcer> monitoringEnforcerProvider;

  private final Provider<ScheduleEvaluator> scheduleEvaluatorProvider;

  private final Provider<TamperDetector> tamperDetectorProvider;

  private final Provider<LockController> lockControllerProvider;

  private final Provider<CommandExecutor> commandExecutorProvider;

  private final Provider<PolicyRepository> policyRepositoryProvider;

  private final Provider<LogRepository> logRepositoryProvider;

  private final Provider<AuthRepository> authRepositoryProvider;

  private final Provider<TimeProvider> timeProvider;

  public PolicyEngine_Factory(Provider<DeviceAdminBridge> adminProvider,
      Provider<PeripheralEnforcer> peripheralEnforcerProvider,
      Provider<SystemLockEnforcer> systemLockEnforcerProvider,
      Provider<AppPolicyEnforcer> appPolicyEnforcerProvider,
      Provider<AppLimitEnforcer> appLimitEnforcerProvider,
      Provider<EyeCareEnforcer> eyeCareEnforcerProvider, Provider<WebEnforcer> webEnforcerProvider,
      Provider<KioskEnforcer> kioskEnforcerProvider,
      Provider<SecurityEnforcer> securityEnforcerProvider,
      Provider<MonitoringEnforcer> monitoringEnforcerProvider,
      Provider<ScheduleEvaluator> scheduleEvaluatorProvider,
      Provider<TamperDetector> tamperDetectorProvider,
      Provider<LockController> lockControllerProvider,
      Provider<CommandExecutor> commandExecutorProvider,
      Provider<PolicyRepository> policyRepositoryProvider,
      Provider<LogRepository> logRepositoryProvider,
      Provider<AuthRepository> authRepositoryProvider, Provider<TimeProvider> timeProvider) {
    this.adminProvider = adminProvider;
    this.peripheralEnforcerProvider = peripheralEnforcerProvider;
    this.systemLockEnforcerProvider = systemLockEnforcerProvider;
    this.appPolicyEnforcerProvider = appPolicyEnforcerProvider;
    this.appLimitEnforcerProvider = appLimitEnforcerProvider;
    this.eyeCareEnforcerProvider = eyeCareEnforcerProvider;
    this.webEnforcerProvider = webEnforcerProvider;
    this.kioskEnforcerProvider = kioskEnforcerProvider;
    this.securityEnforcerProvider = securityEnforcerProvider;
    this.monitoringEnforcerProvider = monitoringEnforcerProvider;
    this.scheduleEvaluatorProvider = scheduleEvaluatorProvider;
    this.tamperDetectorProvider = tamperDetectorProvider;
    this.lockControllerProvider = lockControllerProvider;
    this.commandExecutorProvider = commandExecutorProvider;
    this.policyRepositoryProvider = policyRepositoryProvider;
    this.logRepositoryProvider = logRepositoryProvider;
    this.authRepositoryProvider = authRepositoryProvider;
    this.timeProvider = timeProvider;
  }

  @Override
  public PolicyEngine get() {
    return newInstance(adminProvider.get(), peripheralEnforcerProvider.get(), systemLockEnforcerProvider.get(), appPolicyEnforcerProvider.get(), appLimitEnforcerProvider.get(), eyeCareEnforcerProvider.get(), webEnforcerProvider.get(), kioskEnforcerProvider.get(), securityEnforcerProvider.get(), monitoringEnforcerProvider.get(), scheduleEvaluatorProvider.get(), tamperDetectorProvider.get(), lockControllerProvider.get(), commandExecutorProvider.get(), policyRepositoryProvider.get(), logRepositoryProvider.get(), authRepositoryProvider.get(), timeProvider.get());
  }

  public static PolicyEngine_Factory create(Provider<DeviceAdminBridge> adminProvider,
      Provider<PeripheralEnforcer> peripheralEnforcerProvider,
      Provider<SystemLockEnforcer> systemLockEnforcerProvider,
      Provider<AppPolicyEnforcer> appPolicyEnforcerProvider,
      Provider<AppLimitEnforcer> appLimitEnforcerProvider,
      Provider<EyeCareEnforcer> eyeCareEnforcerProvider, Provider<WebEnforcer> webEnforcerProvider,
      Provider<KioskEnforcer> kioskEnforcerProvider,
      Provider<SecurityEnforcer> securityEnforcerProvider,
      Provider<MonitoringEnforcer> monitoringEnforcerProvider,
      Provider<ScheduleEvaluator> scheduleEvaluatorProvider,
      Provider<TamperDetector> tamperDetectorProvider,
      Provider<LockController> lockControllerProvider,
      Provider<CommandExecutor> commandExecutorProvider,
      Provider<PolicyRepository> policyRepositoryProvider,
      Provider<LogRepository> logRepositoryProvider,
      Provider<AuthRepository> authRepositoryProvider, Provider<TimeProvider> timeProvider) {
    return new PolicyEngine_Factory(adminProvider, peripheralEnforcerProvider, systemLockEnforcerProvider, appPolicyEnforcerProvider, appLimitEnforcerProvider, eyeCareEnforcerProvider, webEnforcerProvider, kioskEnforcerProvider, securityEnforcerProvider, monitoringEnforcerProvider, scheduleEvaluatorProvider, tamperDetectorProvider, lockControllerProvider, commandExecutorProvider, policyRepositoryProvider, logRepositoryProvider, authRepositoryProvider, timeProvider);
  }

  public static PolicyEngine newInstance(DeviceAdminBridge admin,
      PeripheralEnforcer peripheralEnforcer, SystemLockEnforcer systemLockEnforcer,
      AppPolicyEnforcer appPolicyEnforcer, AppLimitEnforcer appLimitEnforcer,
      EyeCareEnforcer eyeCareEnforcer, WebEnforcer webEnforcer, KioskEnforcer kioskEnforcer,
      SecurityEnforcer securityEnforcer, MonitoringEnforcer monitoringEnforcer,
      ScheduleEvaluator scheduleEvaluator, TamperDetector tamperDetector,
      LockController lockController, CommandExecutor commandExecutor,
      PolicyRepository policyRepository, LogRepository logRepository, AuthRepository authRepository,
      TimeProvider timeProvider) {
    return new PolicyEngine(admin, peripheralEnforcer, systemLockEnforcer, appPolicyEnforcer, appLimitEnforcer, eyeCareEnforcer, webEnforcer, kioskEnforcer, securityEnforcer, monitoringEnforcer, scheduleEvaluator, tamperDetector, lockController, commandExecutor, policyRepository, logRepository, authRepository, timeProvider);
  }
}
