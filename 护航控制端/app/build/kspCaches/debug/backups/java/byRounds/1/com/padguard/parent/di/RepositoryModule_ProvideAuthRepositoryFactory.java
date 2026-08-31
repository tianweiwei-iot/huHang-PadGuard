package com.padguard.parent.di;

import com.padguard.data.local.LocalDataSource;
import com.padguard.domain.repository.AuthRepository;
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
public final class RepositoryModule_ProvideAuthRepositoryFactory implements Factory<AuthRepository> {
  private final Provider<LocalDataSource> localDataSourceProvider;

  public RepositoryModule_ProvideAuthRepositoryFactory(
      Provider<LocalDataSource> localDataSourceProvider) {
    this.localDataSourceProvider = localDataSourceProvider;
  }

  @Override
  public AuthRepository get() {
    return provideAuthRepository(localDataSourceProvider.get());
  }

  public static RepositoryModule_ProvideAuthRepositoryFactory create(
      Provider<LocalDataSource> localDataSourceProvider) {
    return new RepositoryModule_ProvideAuthRepositoryFactory(localDataSourceProvider);
  }

  public static AuthRepository provideAuthRepository(LocalDataSource localDataSource) {
    return Preconditions.checkNotNullFromProvides(RepositoryModule.INSTANCE.provideAuthRepository(localDataSource));
  }
}
