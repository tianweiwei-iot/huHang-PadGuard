package com.padguard.presentation.viewmodel;

import androidx.lifecycle.SavedStateHandle;
import com.padguard.domain.repository.DeviceRepository;
import com.padguard.domain.repository.MonitorRepository;
import com.padguard.domain.repository.PolicyRepository;
import com.padguard.domain.repository.StatisticsRepository;
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
public final class DeviceDetailViewModel_Factory implements Factory<DeviceDetailViewModel> {
  private final Provider<DeviceRepository> deviceRepositoryProvider;

  private final Provider<MonitorRepository> monitorRepositoryProvider;

  private final Provider<StatisticsRepository> statisticsRepositoryProvider;

  private final Provider<PolicyRepository> policyRepositoryProvider;

  private final Provider<SavedStateHandle> savedStateHandleProvider;

  public DeviceDetailViewModel_Factory(Provider<DeviceRepository> deviceRepositoryProvider,
      Provider<MonitorRepository> monitorRepositoryProvider,
      Provider<StatisticsRepository> statisticsRepositoryProvider,
      Provider<PolicyRepository> policyRepositoryProvider,
      Provider<SavedStateHandle> savedStateHandleProvider) {
    this.deviceRepositoryProvider = deviceRepositoryProvider;
    this.monitorRepositoryProvider = monitorRepositoryProvider;
    this.statisticsRepositoryProvider = statisticsRepositoryProvider;
    this.policyRepositoryProvider = policyRepositoryProvider;
    this.savedStateHandleProvider = savedStateHandleProvider;
  }

  @Override
  public DeviceDetailViewModel get() {
    return newInstance(deviceRepositoryProvider.get(), monitorRepositoryProvider.get(), statisticsRepositoryProvider.get(), policyRepositoryProvider.get(), savedStateHandleProvider.get());
  }

  public static DeviceDetailViewModel_Factory create(
      Provider<DeviceRepository> deviceRepositoryProvider,
      Provider<MonitorRepository> monitorRepositoryProvider,
      Provider<StatisticsRepository> statisticsRepositoryProvider,
      Provider<PolicyRepository> policyRepositoryProvider,
      Provider<SavedStateHandle> savedStateHandleProvider) {
    return new DeviceDetailViewModel_Factory(deviceRepositoryProvider, monitorRepositoryProvider, statisticsRepositoryProvider, policyRepositoryProvider, savedStateHandleProvider);
  }

  public static DeviceDetailViewModel newInstance(DeviceRepository deviceRepository,
      MonitorRepository monitorRepository, StatisticsRepository statisticsRepository,
      PolicyRepository policyRepository, SavedStateHandle savedStateHandle) {
    return new DeviceDetailViewModel(deviceRepository, monitorRepository, statisticsRepository, policyRepository, savedStateHandle);
  }
}
