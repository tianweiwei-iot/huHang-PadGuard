package com.padguard.presentation.viewmodel;

import androidx.lifecycle.SavedStateHandle;
import com.padguard.domain.repository.DeviceRepository;
import com.padguard.domain.repository.PolicyRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
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
public final class ControlPolicyViewModel_Factory implements Factory<ControlPolicyViewModel> {
  private final Provider<DeviceRepository> deviceRepositoryProvider;

  private final Provider<PolicyRepository> policyRepositoryProvider;

  private final Provider<SavedStateHandle> savedStateHandleProvider;

  public ControlPolicyViewModel_Factory(Provider<DeviceRepository> deviceRepositoryProvider,
      Provider<PolicyRepository> policyRepositoryProvider,
      Provider<SavedStateHandle> savedStateHandleProvider) {
    this.deviceRepositoryProvider = deviceRepositoryProvider;
    this.policyRepositoryProvider = policyRepositoryProvider;
    this.savedStateHandleProvider = savedStateHandleProvider;
  }

  @Override
  public ControlPolicyViewModel get() {
    return newInstance(deviceRepositoryProvider.get(), policyRepositoryProvider.get(), savedStateHandleProvider.get());
  }

  public static ControlPolicyViewModel_Factory create(
      Provider<DeviceRepository> deviceRepositoryProvider,
      Provider<PolicyRepository> policyRepositoryProvider,
      Provider<SavedStateHandle> savedStateHandleProvider) {
    return new ControlPolicyViewModel_Factory(deviceRepositoryProvider, policyRepositoryProvider, savedStateHandleProvider);
  }

  public static ControlPolicyViewModel newInstance(DeviceRepository deviceRepository,
      PolicyRepository policyRepository, SavedStateHandle savedStateHandle) {
    return new ControlPolicyViewModel(deviceRepository, policyRepository, savedStateHandle);
  }
}
