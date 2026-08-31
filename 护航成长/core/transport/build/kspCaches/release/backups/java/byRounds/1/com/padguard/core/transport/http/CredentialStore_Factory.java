package com.padguard.core.transport.http;

import com.padguard.core.data.repository.AuthRepository;
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
public final class CredentialStore_Factory implements Factory<CredentialStore> {
  private final Provider<AuthRepository> authRepositoryProvider;

  public CredentialStore_Factory(Provider<AuthRepository> authRepositoryProvider) {
    this.authRepositoryProvider = authRepositoryProvider;
  }

  @Override
  public CredentialStore get() {
    return newInstance(authRepositoryProvider.get());
  }

  public static CredentialStore_Factory create(Provider<AuthRepository> authRepositoryProvider) {
    return new CredentialStore_Factory(authRepositoryProvider);
  }

  public static CredentialStore newInstance(AuthRepository authRepository) {
    return new CredentialStore(authRepository);
  }
}
