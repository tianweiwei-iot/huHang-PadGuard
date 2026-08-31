package com.padguard.core.data.repository;

import com.padguard.core.data.crypto.KeystoreManager;
import com.padguard.core.data.db.PolicyDao;
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
public final class PolicyRepository_Factory implements Factory<PolicyRepository> {
  private final Provider<PolicyDao> daoProvider;

  private final Provider<KeystoreManager> keystoreProvider;

  public PolicyRepository_Factory(Provider<PolicyDao> daoProvider,
      Provider<KeystoreManager> keystoreProvider) {
    this.daoProvider = daoProvider;
    this.keystoreProvider = keystoreProvider;
  }

  @Override
  public PolicyRepository get() {
    return newInstance(daoProvider.get(), keystoreProvider.get());
  }

  public static PolicyRepository_Factory create(Provider<PolicyDao> daoProvider,
      Provider<KeystoreManager> keystoreProvider) {
    return new PolicyRepository_Factory(daoProvider, keystoreProvider);
  }

  public static PolicyRepository newInstance(PolicyDao dao, KeystoreManager keystore) {
    return new PolicyRepository(dao, keystore);
  }
}
