package com.padguard.parent.di;

import com.padguard.data.local.LocalDataSource;
import com.padguard.domain.repository.DeviceRepository;
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
public final class RepositoryModule_ProvideDeviceRepositoryFactory implements Factory<DeviceRepository> {
  private final Provider<LocalDataSource> localDataSourceProvider;

  public RepositoryModule_ProvideDeviceRepositoryFactory(
      Provider<LocalDataSource> localDataSourceProvider) {
    this.localDataSourceProvider = localDataSourceProvider;
  }

  @Override
  public DeviceRepository get() {
    return provideDeviceRepository(localDataSourceProvider.get());
  }

  public static RepositoryModule_ProvideDeviceRepositoryFactory create(
      Provider<LocalDataSource> localDataSourceProvider) {
    return new RepositoryModule_ProvideDeviceRepositoryFactory(localDataSourceProvider);
  }

  public static DeviceRepository provideDeviceRepository(LocalDataSource localDataSource) {
    return Preconditions.checkNotNullFromProvides(RepositoryModule.INSTANCE.provideDeviceRepository(localDataSource));
  }
}
