package com.padguard.core.transport;

import com.padguard.core.common.TimeProvider;
import com.padguard.core.data.repository.CommandRepository;
import com.padguard.core.data.repository.LogRepository;
import com.padguard.core.transport.http.CredentialStore;
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
public final class CommandGate_Factory implements Factory<CommandGate> {
  private final Provider<CredentialStore> credentialsProvider;

  private final Provider<TimeProvider> timeProvider;

  private final Provider<CommandRepository> commandRepositoryProvider;

  private final Provider<LogRepository> logRepositoryProvider;

  private final Provider<TransportSettings> settingsProvider;

  public CommandGate_Factory(Provider<CredentialStore> credentialsProvider,
      Provider<TimeProvider> timeProvider, Provider<CommandRepository> commandRepositoryProvider,
      Provider<LogRepository> logRepositoryProvider, Provider<TransportSettings> settingsProvider) {
    this.credentialsProvider = credentialsProvider;
    this.timeProvider = timeProvider;
    this.commandRepositoryProvider = commandRepositoryProvider;
    this.logRepositoryProvider = logRepositoryProvider;
    this.settingsProvider = settingsProvider;
  }

  @Override
  public CommandGate get() {
    return newInstance(credentialsProvider.get(), timeProvider.get(), commandRepositoryProvider.get(), logRepositoryProvider.get(), settingsProvider.get());
  }

  public static CommandGate_Factory create(Provider<CredentialStore> credentialsProvider,
      Provider<TimeProvider> timeProvider, Provider<CommandRepository> commandRepositoryProvider,
      Provider<LogRepository> logRepositoryProvider, Provider<TransportSettings> settingsProvider) {
    return new CommandGate_Factory(credentialsProvider, timeProvider, commandRepositoryProvider, logRepositoryProvider, settingsProvider);
  }

  public static CommandGate newInstance(CredentialStore credentials, TimeProvider timeProvider,
      CommandRepository commandRepository, LogRepository logRepository,
      TransportSettings settings) {
    return new CommandGate(credentials, timeProvider, commandRepository, logRepository, settings);
  }
}
