package com.padguard.core.transport.mqtt;

import android.content.Context;
import com.padguard.core.transport.TransportSettings;
import com.padguard.core.transport.http.CredentialStore;
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
public final class MqttTransport_Factory implements Factory<MqttTransport> {
  private final Provider<Context> contextProvider;

  private final Provider<TransportSettings> settingsProvider;

  private final Provider<CredentialStore> credentialsProvider;

  public MqttTransport_Factory(Provider<Context> contextProvider,
      Provider<TransportSettings> settingsProvider, Provider<CredentialStore> credentialsProvider) {
    this.contextProvider = contextProvider;
    this.settingsProvider = settingsProvider;
    this.credentialsProvider = credentialsProvider;
  }

  @Override
  public MqttTransport get() {
    return newInstance(contextProvider.get(), settingsProvider.get(), credentialsProvider.get());
  }

  public static MqttTransport_Factory create(Provider<Context> contextProvider,
      Provider<TransportSettings> settingsProvider, Provider<CredentialStore> credentialsProvider) {
    return new MqttTransport_Factory(contextProvider, settingsProvider, credentialsProvider);
  }

  public static MqttTransport newInstance(Context context, TransportSettings settings,
      CredentialStore credentials) {
    return new MqttTransport(context, settings, credentials);
  }
}
