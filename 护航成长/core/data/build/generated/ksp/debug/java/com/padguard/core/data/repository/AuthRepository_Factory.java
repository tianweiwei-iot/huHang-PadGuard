package com.padguard.core.data.repository;

import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("com.padguard.core.data.di.AuthDataStore")
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
public final class AuthRepository_Factory implements Factory<AuthRepository> {
  private final Provider<DataStore<Preferences>> storeProvider;

  public AuthRepository_Factory(Provider<DataStore<Preferences>> storeProvider) {
    this.storeProvider = storeProvider;
  }

  @Override
  public AuthRepository get() {
    return newInstance(storeProvider.get());
  }

  public static AuthRepository_Factory create(Provider<DataStore<Preferences>> storeProvider) {
    return new AuthRepository_Factory(storeProvider);
  }

  public static AuthRepository newInstance(DataStore<Preferences> store) {
    return new AuthRepository(store);
  }
}
