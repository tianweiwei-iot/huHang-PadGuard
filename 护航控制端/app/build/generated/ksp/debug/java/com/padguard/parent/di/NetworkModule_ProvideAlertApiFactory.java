package com.padguard.parent.di;

import com.padguard.data.api.AlertApi;
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
public final class NetworkModule_ProvideAlertApiFactory implements Factory<AlertApi> {
  private final Provider<Retrofit> retrofitProvider;

  public NetworkModule_ProvideAlertApiFactory(Provider<Retrofit> retrofitProvider) {
    this.retrofitProvider = retrofitProvider;
  }

  @Override
  public AlertApi get() {
    return provideAlertApi(retrofitProvider.get());
  }

  public static NetworkModule_ProvideAlertApiFactory create(Provider<Retrofit> retrofitProvider) {
    return new NetworkModule_ProvideAlertApiFactory(retrofitProvider);
  }

  public static AlertApi provideAlertApi(Retrofit retrofit) {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideAlertApi(retrofit));
  }
}
