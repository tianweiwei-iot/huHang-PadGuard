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
public final class ApiCaller_Factory implements Factory<ApiCaller> {
  private final Provider<TimeProvider> timeProvider;

  public ApiCaller_Factory(Provider<TimeProvider> timeProvider) {
    this.timeProvider = timeProvider;
  }

  @Override
  public ApiCaller get() {
    return newInstance(timeProvider.get());
  }

  public static ApiCaller_Factory create(Provider<TimeProvider> timeProvider) {
    return new ApiCaller_Factory(timeProvider);
  }

  public static ApiCaller newInstance(TimeProvider timeProvider) {
    return new ApiCaller(timeProvider);
  }
}
