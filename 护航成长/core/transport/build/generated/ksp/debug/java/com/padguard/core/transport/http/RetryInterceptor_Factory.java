package com.padguard.core.transport.http;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

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
public final class RetryInterceptor_Factory implements Factory<RetryInterceptor> {
  @Override
  public RetryInterceptor get() {
    return newInstance();
  }

  public static RetryInterceptor_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static RetryInterceptor newInstance() {
    return new RetryInterceptor();
  }

  private static final class InstanceHolder {
    private static final RetryInterceptor_Factory INSTANCE = new RetryInterceptor_Factory();
  }
}
