package com.padguard.parent.di;

import com.padguard.data.api.PolicyApi;
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
public final class NetworkModule_ProvidePolicyApiFactory implements Factory<PolicyApi> {
  private final Provider<Retrofit> retrofitProvider;

  public NetworkModule_ProvidePolicyApiFactory(Provider<Retrofit> retrofitProvider) {
    this.retrofitProvider = retrofitProvider;
  }

  @Override
  public PolicyApi get() {
    return providePolicyApi(retrofitProvider.get());
  }

  public static NetworkModule_ProvidePolicyApiFactory create(Provider<Retrofit> retrofitProvider) {
    return new NetworkModule_ProvidePolicyApiFactory(retrofitProvider);
  }

  public static PolicyApi providePolicyApi(Retrofit retrofit) {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.providePolicyApi(retrofit));
  }
}
