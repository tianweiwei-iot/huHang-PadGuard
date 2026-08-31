package com.padguard.core.data.repository;

import com.padguard.core.data.db.CommandRecordDao;
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
public final class CommandRepository_Factory implements Factory<CommandRepository> {
  private final Provider<CommandRecordDao> daoProvider;

  public CommandRepository_Factory(Provider<CommandRecordDao> daoProvider) {
    this.daoProvider = daoProvider;
  }

  @Override
  public CommandRepository get() {
    return newInstance(daoProvider.get());
  }

  public static CommandRepository_Factory create(Provider<CommandRecordDao> daoProvider) {
    return new CommandRepository_Factory(daoProvider);
  }

  public static CommandRepository newInstance(CommandRecordDao dao) {
    return new CommandRepository(dao);
  }
}
