package com.padguard.parent.di;

import com.padguard.data.local.LocalDataSource;
import com.padguard.domain.repository.MonitorRepository;
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
public final class RepositoryModule_ProvideMonitorRepositoryFactory implements Factory<MonitorRepository> {
  private final Provider<LocalDataSource> localDataSourceProvider;

  public RepositoryModule_ProvideMonitorRepositoryFactory(
      Provider<LocalDataSource> localDataSourceProvider) {
    this.localDataSourceProvider = localDataSourceProvider;
  }

  @Override
  public MonitorRepository get() {
    return provideMonitorRepository(localDataSourceProvider.get());
  }

  public static RepositoryModule_ProvideMonitorRepositoryFactory create(
      Provider<LocalDataSource> localDataSourceProvider) {
    return new RepositoryModule_ProvideMonitorRepositoryFactory(localDataSourceProvider);
  }

  public static MonitorRepository provideMonitorRepository(LocalDataSource localDataSource) {
    return Preconditions.checkNotNullFromProvides(RepositoryModule.INSTANCE.provideMonitorRepository(localDataSource));
  }
}
