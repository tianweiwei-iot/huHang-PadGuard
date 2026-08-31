package com.padguard.child.service;

import com.padguard.child.monitor.DeviceSnapshotCollector;
import com.padguard.child.monitor.ForegroundAppMonitor;
import com.padguard.core.common.TimeProvider;
import com.padguard.core.data.repository.AuthRepository;
import com.padguard.core.data.repository.LogRepository;
import com.padguard.core.data.repository.PolicyRepository;
import com.padguard.core.engine.PolicyEngine;
import com.padguard.core.engine.admin.DeviceAdminBridge;
import com.padguard.core.transport.RemoteDataSource;
import com.padguard.core.transport.TransportSettings;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class GuardService_MembersInjector implements MembersInjector<GuardService> {
  private final Provider<PolicyEngine> policyEngineProvider;

  private final Provider<RemoteDataSource> remoteProvider;

  private final Provider<TransportSettings> transportSettingsProvider;

  private final Provider<ForegroundAppMonitor> foregroundAppProvider;

  private final Provider<DeviceSnapshotCollector> snapshotProvider;

  private final Provider<AuthRepository> authRepositoryProvider;

  private final Provider<PolicyRepository> policyRepositoryProvider;

  private final Provider<LogRepository> logRepositoryProvider;

  private final Provider<DeviceAdminBridge> adminProvider;

  private final Provider<TimeProvider> timeProvider;

  public GuardService_MembersInjector(Provider<PolicyEngine> policyEngineProvider,
      Provider<RemoteDataSource> remoteProvider,
      Provider<TransportSettings> transportSettingsProvider,
      Provider<ForegroundAppMonitor> foregroundAppProvider,
      Provider<DeviceSnapshotCollector> snapshotProvider,
      Provider<AuthRepository> authRepositoryProvider,
      Provider<PolicyRepository> policyRepositoryProvider,
      Provider<LogRepository> logRepositoryProvider, Provider<DeviceAdminBridge> adminProvider,
      Provider<TimeProvider> timeProvider) {
    this.policyEngineProvider = policyEngineProvider;
    this.remoteProvider = remoteProvider;
    this.transportSettingsProvider = transportSettingsProvider;
    this.foregroundAppProvider = foregroundAppProvider;
    this.snapshotProvider = snapshotProvider;
    this.authRepositoryProvider = authRepositoryProvider;
    this.policyRepositoryProvider = policyRepositoryProvider;
    this.logRepositoryProvider = logRepositoryProvider;
    this.adminProvider = adminProvider;
    this.timeProvider = timeProvider;
  }

  public static MembersInjector<GuardService> create(Provider<PolicyEngine> policyEngineProvider,
      Provider<RemoteDataSource> remoteProvider,
      Provider<TransportSettings> transportSettingsProvider,
      Provider<ForegroundAppMonitor> foregroundAppProvider,
      Provider<DeviceSnapshotCollector> snapshotProvider,
      Provider<AuthRepository> authRepositoryProvider,
      Provider<PolicyRepository> policyRepositoryProvider,
      Provider<LogRepository> logRepositoryProvider, Provider<DeviceAdminBridge> adminProvider,
      Provider<TimeProvider> timeProvider) {
    return new GuardService_MembersInjector(policyEngineProvider, remoteProvider, transportSettingsProvider, foregroundAppProvider, snapshotProvider, authRepositoryProvider, policyRepositoryProvider, logRepositoryProvider, adminProvider, timeProvider);
  }

  @Override
  public void injectMembers(GuardService instance) {
    injectPolicyEngine(instance, policyEngineProvider.get());
    injectRemote(instance, remoteProvider.get());
    injectTransportSettings(instance, transportSettingsProvider.get());
    injectForegroundApp(instance, foregroundAppProvider.get());
    injectSnapshot(instance, snapshotProvider.get());
    injectAuthRepository(instance, authRepositoryProvider.get());
    injectPolicyRepository(instance, policyRepositoryProvider.get());
    injectLogRepository(instance, logRepositoryProvider.get());
    injectAdmin(instance, adminProvider.get());
    injectTimeProvider(instance, timeProvider.get());
  }

  @InjectedFieldSignature("com.padguard.child.service.GuardService.policyEngine")
  public static void injectPolicyEngine(GuardService instance, PolicyEngine policyEngine) {
    instance.policyEngine = policyEngine;
  }

  @InjectedFieldSignature("com.padguard.child.service.GuardService.remote")
  public static void injectRemote(GuardService instance, RemoteDataSource remote) {
    instance.remote = remote;
  }

  @InjectedFieldSignature("com.padguard.child.service.GuardService.transportSettings")
  public static void injectTransportSettings(GuardService instance,
      TransportSettings transportSettings) {
    instance.transportSettings = transportSettings;
  }

  @InjectedFieldSignature("com.padguard.child.service.GuardService.foregroundApp")
  public static void injectForegroundApp(GuardService instance,
      ForegroundAppMonitor foregroundApp) {
    instance.foregroundApp = foregroundApp;
  }

  @InjectedFieldSignature("com.padguard.child.service.GuardService.snapshot")
  public static void injectSnapshot(GuardService instance, DeviceSnapshotCollector snapshot) {
    instance.snapshot = snapshot;
  }

  @InjectedFieldSignature("com.padguard.child.service.GuardService.authRepository")
  public static void injectAuthRepository(GuardService instance, AuthRepository authRepository) {
    instance.authRepository = authRepository;
  }

  @InjectedFieldSignature("com.padguard.child.service.GuardService.policyRepository")
  public static void injectPolicyRepository(GuardService instance,
      PolicyRepository policyRepository) {
    instance.policyRepository = policyRepository;
  }

  @InjectedFieldSignature("com.padguard.child.service.GuardService.logRepository")
  public static void injectLogRepository(GuardService instance, LogRepository logRepository) {
    instance.logRepository = logRepository;
  }

  @InjectedFieldSignature("com.padguard.child.service.GuardService.admin")
  public static void injectAdmin(GuardService instance, DeviceAdminBridge admin) {
    instance.admin = admin;
  }

  @InjectedFieldSignature("com.padguard.child.service.GuardService.timeProvider")
  public static void injectTimeProvider(GuardService instance, TimeProvider timeProvider) {
    instance.timeProvider = timeProvider;
  }
}
