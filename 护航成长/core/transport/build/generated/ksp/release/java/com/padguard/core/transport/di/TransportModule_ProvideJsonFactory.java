package com.padguard.core.transport.di;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import kotlinx.serialization.json.Json;

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
public final class TransportModule_ProvideJsonFactory implements Factory<Json> {
  @Override
  public Json get() {
    return provideJson();
  }

  public static TransportModule_ProvideJsonFactory create() {
    return InstanceHolder.INSTANCE;
  }

  public static Json provideJson() {
    return Preconditions.checkNotNullFromProvides(TransportModule.INSTANCE.provideJson());
  }

  private static final class InstanceHolder {
    private static final TransportModule_ProvideJsonFactory INSTANCE = new TransportModule_ProvideJsonFactory();
  }
}
