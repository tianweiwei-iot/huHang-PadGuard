package com.padguard.presentation.viewmodel;

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
public final class StatisticsViewModel_Factory implements Factory<StatisticsViewModel> {
  private final Provider<StatisticsRepository> statisticsRepositoryProvider;

  public StatisticsViewModel_Factory(Provider<StatisticsRepository> statisticsRepositoryProvider) {
    this.statisticsRepositoryProvider = statisticsRepositoryProvider;
  }

  @Override
  public StatisticsViewModel get() {
    return newInstance(statisticsRepositoryProvider.get());
  }

  public static StatisticsViewModel_Factory create(
      Provider<StatisticsRepository> statisticsRepositoryProvider) {
    return new StatisticsViewModel_Factory(statisticsRepositoryProvider);
  }

  public static StatisticsViewModel newInstance(StatisticsRepository statisticsRepository) {
    return new StatisticsViewModel(statisticsRepository);
  }
}
