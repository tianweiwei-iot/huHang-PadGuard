package com.padguard.presentation.viewmodel;

import com.padguard.domain.repository.DeviceRepository;
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
public final class HomeViewModel_Factory implements Factory<HomeViewModel> {
  private final Provider<DeviceRepository> deviceRepositoryProvider;

  private final Provider<StatisticsRepository> statisticsRepositoryProvider;

  private final Provider<PolicyRepository> policyRepositoryProvider;

  public HomeViewModel_Factory(Provider<DeviceRepository> deviceRepositoryProvider,
      Provider<StatisticsRepository> statisticsRepositoryProvider,
      Provider<PolicyRepository> policyRepositoryProvider) {
    this.deviceRepositoryProvider = deviceRepositoryProvider;
    this.statisticsRepositoryProvider = statisticsRepositoryProvider;
    this.policyRepositoryProvider = policyRepositoryProvider;
  }

  @Override
  public HomeViewModel get() {
    return newInstance(deviceRepositoryProvider.get(), statisticsRepositoryProvider.get(), policyRepositoryProvider.get());
  }

  public static HomeViewModel_Factory create(Provider<DeviceRepository> deviceRepositoryProvider,
      Provider<StatisticsRepository> statisticsRepositoryProvider,
      Provider<PolicyRepository> policyRepositoryProvider) {
    return new HomeViewModel_Factory(deviceRepositoryProvider, statisticsRepositoryProvider, policyRepositoryProvider);
  }

  public static HomeViewModel newInstance(DeviceRepository deviceRepository,
      StatisticsRepository statisticsRepository, PolicyRepository policyRepository) {
    return new HomeViewModel(deviceRepository, statisticsRepository, policyRepository);
  }
}
