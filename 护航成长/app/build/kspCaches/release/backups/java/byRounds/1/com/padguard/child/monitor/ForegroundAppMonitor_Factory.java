package com.padguard.child.monitor;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class ForegroundAppMonitor_Factory implements Factory<ForegroundAppMonitor> {
  private final Provider<Context> contextProvider;

  public ForegroundAppMonitor_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public ForegroundAppMonitor get() {
    return newInstance(contextProvider.get());
  }

  public static ForegroundAppMonitor_Factory create(Provider<Context> contextProvider) {
    return new ForegroundAppMonitor_Factory(contextProvider);
  }

  public static ForegroundAppMonitor newInstance(Context context) {
    return new ForegroundAppMonitor(context);
  }
}
