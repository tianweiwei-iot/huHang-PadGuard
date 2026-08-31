package com.padguard.child.receiver;

import com.padguard.child.di.ApplicationScope;
import com.padguard.core.data.repository.AuthRepository;
import com.padguard.core.data.repository.LogRepository;
import com.padguard.core.engine.admin.DeviceAdminBridge;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;
import kotlinx.coroutines.CoroutineScope;

@QualifierMetadata("com.padguard.child.di.ApplicationScope")
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
public final class PadGuardDeviceAdminReceiver_MembersInjector implements MembersInjector<PadGuardDeviceAdminReceiver> {
  private final Provider<DeviceAdminBridge> adminProvider;

  private final Provider<LogRepository> logRepositoryProvider;

  private final Provider<AuthRepository> authRepositoryProvider;

  private final Provider<CoroutineScope> scopeProvider;

  public PadGuardDeviceAdminReceiver_MembersInjector(Provider<DeviceAdminBridge> adminProvider,
      Provider<LogRepository> logRepositoryProvider,
      Provider<AuthRepository> authRepositoryProvider, Provider<CoroutineScope> scopeProvider) {
    this.adminProvider = adminProvider;
    this.logRepositoryProvider = logRepositoryProvider;
    this.authRepositoryProvider = authRepositoryProvider;
    this.scopeProvider = scopeProvider;
  }

  public static MembersInjector<PadGuardDeviceAdminReceiver> create(
      Provider<DeviceAdminBridge> adminProvider, Provider<LogRepository> logRepositoryProvider,
      Provider<AuthRepository> authRepositoryProvider, Provider<CoroutineScope> scopeProvider) {
    return new PadGuardDeviceAdminReceiver_MembersInjector(adminProvider, logRepositoryProvider, authRepositoryProvider, scopeProvider);
  }

  @Override
  public void injectMembers(PadGuardDeviceAdminReceiver instance) {
    injectAdmin(instance, adminProvider.get());
    injectLogRepository(instance, logRepositoryProvider.get());
    injectAuthRepository(instance, authRepositoryProvider.get());
    injectScope(instance, scopeProvider.get());
  }

  @InjectedFieldSignature("com.padguard.child.receiver.PadGuardDeviceAdminReceiver.admin")
  public static void injectAdmin(PadGuardDeviceAdminReceiver instance, DeviceAdminBridge admin) {
    instance.admin = admin;
  }

  @InjectedFieldSignature("com.padguard.child.receiver.PadGuardDeviceAdminReceiver.logRepository")
  public static void injectLogRepository(PadGuardDeviceAdminReceiver instance,
      LogRepository logRepository) {
    instance.logRepository = logRepository;
  }

  @InjectedFieldSignature("com.padguard.child.receiver.PadGuardDeviceAdminReceiver.authRepository")
  public static void injectAuthRepository(PadGuardDeviceAdminReceiver instance,
      AuthRepository authRepository) {
    instance.authRepository = authRepository;
  }

  @InjectedFieldSignature("com.padguard.child.receiver.PadGuardDeviceAdminReceiver.scope")
  @ApplicationScope
  public static void injectScope(PadGuardDeviceAdminReceiver instance, CoroutineScope scope) {
    instance.scope = scope;
  }
}
