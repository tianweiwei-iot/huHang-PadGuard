package com.padguard.core.transport.http;

import com.padguard.core.transport.TransportSettings;
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
public final class HostSelectionInterceptor_Factory implements Factory<HostSelectionInterceptor> {
  private final Provider<TransportSettings> settingsProvider;

  public HostSelectionInterceptor_Factory(Provider<TransportSettings> settingsProvider) {
    this.settingsProvider = settingsProvider;
  }

  @Override
  public HostSelectionInterceptor get() {
    return newInstance(settingsProvider.get());
  }

  public static HostSelectionInterceptor_Factory create(
      Provider<TransportSettings> settingsProvider) {
    return new HostSelectionInterceptor_Factory(settingsProvider);
  }

  public static HostSelectionInterceptor newInstance(TransportSettings settings) {
    return new HostSelectionInterceptor(settings);
  }
}
