package com.padguard.parent.di;

import com.padguard.data.api.DeviceApi;
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
public final class NetworkModule_ProvideDeviceApiFactory implements Factory<DeviceApi> {
  private final Provider<Retrofit> retrofitProvider;

  public NetworkModule_ProvideDeviceApiFactory(Provider<Retrofit> retrofitProvider) {
    this.retrofitProvider = retrofitProvider;
  }

  @Override
  public DeviceApi get() {
    return provideDeviceApi(retrofitProvider.get());
  }

  public static NetworkModule_ProvideDeviceApiFactory create(Provider<Retrofit> retrofitProvider) {
    return new NetworkModule_ProvideDeviceApiFactory(retrofitProvider);
  }

  public static DeviceApi provideDeviceApi(Retrofit retrofit) {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideDeviceApi(retrofit));
  }
}
