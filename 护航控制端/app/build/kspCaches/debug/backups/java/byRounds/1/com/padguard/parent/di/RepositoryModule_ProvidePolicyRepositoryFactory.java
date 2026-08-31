package com.padguard.parent.di;

import com.padguard.data.local.LocalDataSource;
import com.padguard.domain.repository.PolicyRepository;
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
public final class RepositoryModule_ProvidePolicyRepositoryFactory implements Factory<PolicyRepository> {
  private final Provider<LocalDataSource> localDataSourceProvider;

  public RepositoryModule_ProvidePolicyRepositoryFactory(
      Provider<LocalDataSource> localDataSourceProvider) {
    this.localDataSourceProvider = localDataSourceProvider;
  }

  @Override
  public PolicyRepository get() {
    return providePolicyRepository(localDataSourceProvider.get());
  }

  public static RepositoryModule_ProvidePolicyRepositoryFactory create(
      Provider<LocalDataSource> localDataSourceProvider) {
    return new RepositoryModule_ProvidePolicyRepositoryFactory(localDataSourceProvider);
  }

  public static PolicyRepository providePolicyRepository(LocalDataSource localDataSource) {
    return Preconditions.checkNotNullFromProvides(RepositoryModule.INSTANCE.providePolicyRepository(localDataSource));
  }
}
