package com.padguard.core.transport.di;

import com.padguard.core.transport.http.AuthInterceptor;
import com.padguard.core.transport.http.HostSelectionInterceptor;
import com.padguard.core.transport.http.RetryInterceptor;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;
import okhttp3.OkHttpClient;

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
public final class TransportModule_ProvideOkHttpClientFactory implements Factory<OkHttpClient> {
  private final Provider<AuthInterceptor> authInterceptorProvider;

  private final Provider<HostSelectionInterceptor> hostSelectionInterceptorProvider;

  private final Provider<RetryInterceptor> retryInterceptorProvider;

  public TransportModule_ProvideOkHttpClientFactory(
      Provider<AuthInterceptor> authInterceptorProvider,
      Provider<HostSelectionInterceptor> hostSelectionInterceptorProvider,
      Provider<RetryInterceptor> retryInterceptorProvider) {
    this.authInterceptorProvider = authInterceptorProvider;
    this.hostSelectionInterceptorProvider = hostSelectionInterceptorProvider;
    this.retryInterceptorProvider = retryInterceptorProvider;
  }

  @Override
  public OkHttpClient get() {
    return provideOkHttpClient(authInterceptorProvider.get(), hostSelectionInterceptorProvider.get(), retryInterceptorProvider.get());
  }

  public static TransportModule_ProvideOkHttpClientFactory create(
      Provider<AuthInterceptor> authInterceptorProvider,
      Provider<HostSelectionInterceptor> hostSelectionInterceptorProvider,
      Provider<RetryInterceptor> retryInterceptorProvider) {
    return new TransportModule_ProvideOkHttpClientFactory(authInterceptorProvider, hostSelectionInterceptorProvider, retryInterceptorProvider);
  }

  public static OkHttpClient provideOkHttpClient(AuthInterceptor authInterceptor,
      HostSelectionInterceptor hostSelectionInterceptor, RetryInterceptor retryInterceptor) {
    return Preconditions.checkNotNullFromProvides(TransportModule.INSTANCE.provideOkHttpClient(authInterceptor, hostSelectionInterceptor, retryInterceptor));
  }
}
