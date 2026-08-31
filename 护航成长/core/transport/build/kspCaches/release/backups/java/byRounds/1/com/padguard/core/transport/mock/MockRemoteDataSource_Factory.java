package com.padguard.core.transport.mock;

import com.padguard.core.common.TimeProvider;
import com.padguard.core.transport.CommandGate;
import com.padguard.core.transport.TransportSettings;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;
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
public final class MockRemoteDataSource_Factory implements Factory<MockRemoteDataSource> {
  private final Provider<CommandGate> commandGateProvider;

  private final Provider<TimeProvider> timeProvider;

  private final Provider<TransportSettings> settingsProvider;

  private final Provider<Json> jsonProvider;

  public MockRemoteDataSource_Factory(Provider<CommandGate> commandGateProvider,
      Provider<TimeProvider> timeProvider, Provider<TransportSettings> settingsProvider,
      Provider<Json> jsonProvider) {
    this.commandGateProvider = commandGateProvider;
    this.timeProvider = timeProvider;
    this.settingsProvider = settingsProvider;
    this.jsonProvider = jsonProvider;
  }

  @Override
  public MockRemoteDataSource get() {
    return newInstance(commandGateProvider.get(), timeProvider.get(), settingsProvider.get(), jsonProvider.get());
  }

  public static MockRemoteDataSource_Factory create(Provider<CommandGate> commandGateProvider,
      Provider<TimeProvider> timeProvider, Provider<TransportSettings> settingsProvider,
      Provider<Json> jsonProvider) {
    return new MockRemoteDataSource_Factory(commandGateProvider, timeProvider, settingsProvider, jsonProvider);
  }

  public static MockRemoteDataSource newInstance(CommandGate commandGate, TimeProvider timeProvider,
      TransportSettings settings, Json json) {
    return new MockRemoteDataSource(commandGate, timeProvider, settings, json);
  }
}
