package com.padguard.parent.di;

import com.padguard.data.api.MonitorApi;
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
public final class NetworkModule_ProvideMonitorApiFactory implements Factory<MonitorApi> {
  private final Provider<Retrofit> retrofitProvider;

  public NetworkModule_ProvideMonitorApiFactory(Provider<Retrofit> retrofitProvider) {
    this.retrofitProvider = retrofitProvider;
  }

  @Override
  public MonitorApi get() {
    return provideMonitorApi(retrofitProvider.get());
  }

  public static NetworkModule_ProvideMonitorApiFactory create(Provider<Retrofit> retrofitProvider) {
    return new NetworkModule_ProvideMonitorApiFactory(retrofitProvider);
  }

  public static MonitorApi provideMonitorApi(Retrofit retrofit) {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideMonitorApi(retrofit));
  }
}
