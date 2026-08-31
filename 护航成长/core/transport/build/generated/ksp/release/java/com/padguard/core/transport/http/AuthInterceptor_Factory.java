package com.padguard.core.transport.http;

import com.padguard.core.common.TimeProvider;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class AuthInterceptor_Factory implements Factory<AuthInterceptor> {
  private final Provider<CredentialStore> credentialsProvider;

  private final Provider<TimeProvider> timeProvider;

  public AuthInterceptor_Factory(Provider<CredentialStore> credentialsProvider,
      Provider<TimeProvider> timeProvider) {
    this.credentialsProvider = credentialsProvider;
    this.timeProvider = timeProvider;
  }

  @Override
  public AuthInterceptor get() {
    return newInstance(credentialsProvider.get(), timeProvider.get());
  }

  public static AuthInterceptor_Factory create(Provider<CredentialStore> credentialsProvider,
      Provider<TimeProvider> timeProvider) {
    return new AuthInterceptor_Factory(credentialsProvider, timeProvider);
  }

  public static AuthInterceptor newInstance(CredentialStore credentials,
      TimeProvider timeProvider) {
    return new AuthInterceptor(credentials, timeProvider);
  }
}
