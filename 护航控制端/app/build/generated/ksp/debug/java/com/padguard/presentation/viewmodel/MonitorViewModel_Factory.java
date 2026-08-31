package com.padguard.presentation.viewmodel;

import androidx.lifecycle.SavedStateHandle;
import com.padguard.domain.repository.MonitorRepository;
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
public final class MonitorViewModel_Factory implements Factory<MonitorViewModel> {
  private final Provider<MonitorRepository> monitorRepositoryProvider;

  private final Provider<SavedStateHandle> savedStateHandleProvider;

  public MonitorViewModel_Factory(Provider<MonitorRepository> monitorRepositoryProvider,
      Provider<SavedStateHandle> savedStateHandleProvider) {
    this.monitorRepositoryProvider = monitorRepositoryProvider;
    this.savedStateHandleProvider = savedStateHandleProvider;
  }

  @Override
  public MonitorViewModel get() {
    return newInstance(monitorRepositoryProvider.get(), savedStateHandleProvider.get());
  }

  public static MonitorViewModel_Factory create(
      Provider<MonitorRepository> monitorRepositoryProvider,
      Provider<SavedStateHandle> savedStateHandleProvider) {
    return new MonitorViewModel_Factory(monitorRepositoryProvider, savedStateHandleProvider);
  }

  public static MonitorViewModel newInstance(MonitorRepository monitorRepository,
      SavedStateHandle savedStateHandle) {
    return new MonitorViewModel(monitorRepository, savedStateHandle);
  }
}
