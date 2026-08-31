package com.padguard.core.transport;

import com.padguard.core.common.TimeProvider;
import com.padguard.core.transport.http.ApiCaller;
import com.padguard.core.transport.http.CredentialStore;
import com.padguard.core.transport.http.PadGuardApi;
import com.padguard.core.transport.mqtt.MqttTransport;
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
public final class RealRemoteDataSource_Factory implements Factory<RealRemoteDataSource> {
  private final Provider<PadGuardApi> apiProvider;

  private final Provider<ApiCaller> callerProvider;

  private final Provider<MqttTransport> mqttProvider;

  private final Provider<CommandGate> gateProvider;

  private final Provider<CredentialStore> credentialsProvider;

  private final Provider<TransportSettings> settingsProvider;

  private final Provider<TimeProvider> timeProvider;

  private final Provider<Json> jsonProvider;

  public RealRemoteDataSource_Factory(Provider<PadGuardApi> apiProvider,
      Provider<ApiCaller> callerProvider, Provider<MqttTransport> mqttProvider,
      Provider<CommandGate> gateProvider, Provider<CredentialStore> credentialsProvider,
      Provider<TransportSettings> settingsProvider, Provider<TimeProvider> timeProvider,
      Provider<Json> jsonProvider) {
    this.apiProvider = apiProvider;
    this.callerProvider = callerProvider;
    this.mqttProvider = mqttProvider;
    this.gateProvider = gateProvider;
    this.credentialsProvider = credentialsProvider;
    this.settingsProvider = settingsProvider;
    this.timeProvider = timeProvider;
    this.jsonProvider = jsonProvider;
  }

  @Override
  public RealRemoteDataSource get() {
    return newInstance(apiProvider.get(), callerProvider.get(), mqttProvider.get(), gateProvider.get(), credentialsProvider.get(), settingsProvider.get(), timeProvider.get(), jsonProvider.get());
  }

  public static RealRemoteDataSource_Factory create(Provider<PadGuardApi> apiProvider,
      Provider<ApiCaller> callerProvider, Provider<MqttTransport> mqttProvider,
      Provider<CommandGate> gateProvider, Provider<CredentialStore> credentialsProvider,
      Provider<TransportSettings> settingsProvider, Provider<TimeProvider> timeProvider,
      Provider<Json> jsonProvider) {
    return new RealRemoteDataSource_Factory(apiProvider, callerProvider, mqttProvider, gateProvider, credentialsProvider, settingsProvider, timeProvider, jsonProvider);
  }

  public static RealRemoteDataSource newInstance(PadGuardApi api, ApiCaller caller,
      MqttTransport mqtt, CommandGate gate, CredentialStore credentials, TransportSettings settings,
      TimeProvider timeProvider, Json json) {
    return new RealRemoteDataSource(api, caller, mqtt, gate, credentials, settings, timeProvider, json);
  }
}
