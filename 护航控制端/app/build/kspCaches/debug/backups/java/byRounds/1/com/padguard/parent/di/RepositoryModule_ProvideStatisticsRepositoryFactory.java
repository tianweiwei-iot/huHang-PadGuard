package com.padguard.parent.di;

import com.padguard.data.local.LocalDataSource;
import com.padguard.domain.repository.StatisticsRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
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
public final class RepositoryModule_ProvideStatisticsRepositoryFactory implements Factory<StatisticsRepository> {
  private final Provider<LocalDataSource> localDataSourceProvider;

  public RepositoryModule_ProvideStatisticsRepositoryFactory(
      Provider<LocalDataSource> localDataSourceProvider) {
    this.localDataSourceProvider = localDataSourceProvider;
  }

  @Override
  public StatisticsRepository get() {
    return provideStatisticsRepository(localDataSourceProvider.get());
  }

  public static RepositoryModule_ProvideStatisticsRepositoryFactory create(
      Provider<LocalDataSource> localDataSourceProvider) {
    return new RepositoryModule_ProvideStatisticsRepositoryFactory(localDataSourceProvider);
  }

  public static StatisticsRepository provideStatisticsRepository(LocalDataSource localDataSource) {
    return Preconditions.checkNotNullFromProvides(RepositoryModule.INSTANCE.provideStatisticsRepository(localDataSource));
  }
}
