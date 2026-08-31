package com.padguard.core.transport.di;

import com.padguard.core.transport.http.PadGuardApi;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;
import retrofit2.Retrofit;

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
public final class TransportModule_ProvidePadGuardApiFactory implements Factory<PadGuardApi> {
  private final Provider<Retrofit> retrofitProvider;

  public TransportModule_ProvidePadGuardApiFactory(Provider<Retrofit> retrofitProvider) {
    this.retrofitProvider = retrofitProvider;
  }

  @Override
  public PadGuardApi get() {
    return providePadGuardApi(retrofitProvider.get());
  }

  public static TransportModule_ProvidePadGuardApiFactory create(
      Provider<Retrofit> retrofitProvider) {
    return new TransportModule_ProvidePadGuardApiFactory(retrofitProvider);
  }

  public static PadGuardApi providePadGuardApi(Retrofit retrofit) {
    return Preconditions.checkNotNullFromProvides(TransportModule.INSTANCE.providePadGuardApi(retrofit));
  }
}
