package com.padguard.parent.di;

import com.padguard.data.api.StatisticsApi;
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
public final class NetworkModule_ProvideStatisticsApiFactory implements Factory<StatisticsApi> {
  private final Provider<Retrofit> retrofitProvider;

  public NetworkModule_ProvideStatisticsApiFactory(Provider<Retrofit> retrofitProvider) {
    this.retrofitProvider = retrofitProvider;
  }

  @Override
  public StatisticsApi get() {
    return provideStatisticsApi(retrofitProvider.get());
  }

  public static NetworkModule_ProvideStatisticsApiFactory create(
      Provider<Retrofit> retrofitProvider) {
    return new NetworkModule_ProvideStatisticsApiFactory(retrofitProvider);
  }

  public static StatisticsApi provideStatisticsApi(Retrofit retrofit) {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideStatisticsApi(retrofit));
  }
}
