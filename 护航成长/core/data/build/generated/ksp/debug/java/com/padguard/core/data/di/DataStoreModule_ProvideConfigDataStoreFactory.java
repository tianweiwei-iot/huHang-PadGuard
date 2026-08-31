package com.padguard.core.data.di;

import android.content.Context;
import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata({
    "com.padguard.core.data.di.ConfigDataStore",
    "dagger.hilt.android.qualifiers.ApplicationContext"
})
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
public final class DataStoreModule_ProvideConfigDataStoreFactory implements Factory<DataStore<Preferences>> {
  private final Provider<Context> contextProvider;

  public DataStoreModule_ProvideConfigDataStoreFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public DataStore<Preferences> get() {
    return provideConfigDataStore(contextProvider.get());
  }

  public static DataStoreModule_ProvideConfigDataStoreFactory create(
      Provider<Context> contextProvider) {
    return new DataStoreModule_ProvideConfigDataStoreFactory(contextProvider);
  }

  public static DataStore<Preferences> provideConfigDataStore(Context context) {
    return Preconditions.checkNotNullFromProvides(DataStoreModule.INSTANCE.provideConfigDataStore(context));
  }
}
