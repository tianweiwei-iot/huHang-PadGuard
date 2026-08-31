package com.padguard.core.transport.di;

import com.padguard.core.transport.RealRemoteDataSource;
import com.padguard.core.transport.RemoteDataSource;
import com.padguard.core.transport.mock.MockRemoteDataSource;
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
public final class TransportModule_ProvideRemoteDataSourceFactory implements Factory<RemoteDataSource> {
  private final Provider<RealRemoteDataSource> realProvider;

  private final Provider<MockRemoteDataSource> mockProvider;

  public TransportModule_ProvideRemoteDataSourceFactory(Provider<RealRemoteDataSource> realProvider,
      Provider<MockRemoteDataSource> mockProvider) {
    this.realProvider = realProvider;
    this.mockProvider = mockProvider;
  }

  @Override
  public RemoteDataSource get() {
    return provideRemoteDataSource(realProvider, mockProvider);
  }

  public static TransportModule_ProvideRemoteDataSourceFactory create(
      Provider<RealRemoteDataSource> realProvider, Provider<MockRemoteDataSource> mockProvider) {
    return new TransportModule_ProvideRemoteDataSourceFactory(realProvider, mockProvider);
  }

  public static RemoteDataSource provideRemoteDataSource(
      Provider<RealRemoteDataSource> realProvider, Provider<MockRemoteDataSource> mockProvider) {
    return Preconditions.checkNotNullFromProvides(TransportModule.INSTANCE.provideRemoteDataSource(realProvider, mockProvider));
  }
}
