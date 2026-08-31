package com.padguard.child;

import android.app.Activity;
import android.app.Service;
import android.view.View;
import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.padguard.child.di.AppModule_ProvideApplicationScopeFactory;
import com.padguard.child.monitor.DeviceSnapshotCollector;
import com.padguard.child.monitor.ForegroundAppMonitor;
import com.padguard.child.receiver.PadGuardDeviceAdminReceiver;
import com.padguard.child.receiver.PadGuardDeviceAdminReceiver_MembersInjector;
import com.padguard.child.service.GuardService;
import com.padguard.child.service.GuardService_MembersInjector;
import com.padguard.core.common.TimeProvider;
import com.padguard.core.data.crypto.KeystoreManager;
import com.padguard.core.data.db.AppUsageDao;
import com.padguard.core.data.db.BehaviorLogDao;
import com.padguard.core.data.db.CommandRecordDao;
import com.padguard.core.data.db.DailyUsageDao;
import com.padguard.core.data.db.PadGuardDatabase;
import com.padguard.core.data.db.PolicyDao;
import com.padguard.core.data.db.RiskEventDao;
import com.padguard.core.data.di.DataStoreModule_ProvideAuthDataStoreFactory;
import com.padguard.core.data.di.DatabaseModule_ProvideAppUsageDaoFactory;
import com.padguard.core.data.di.DatabaseModule_ProvideBehaviorLogDaoFactory;
import com.padguard.core.data.di.DatabaseModule_ProvideCommandRecordDaoFactory;
import com.padguard.core.data.di.DatabaseModule_ProvideDailyUsageDaoFactory;
import com.padguard.core.data.di.DatabaseModule_ProvideDatabaseFactory;
import com.padguard.core.data.di.DatabaseModule_ProvidePolicyDaoFactory;
import com.padguard.core.data.di.DatabaseModule_ProvideRiskEventDaoFactory;
import com.padguard.core.data.repository.AuthRepository;
import com.padguard.core.data.repository.CommandRepository;
import com.padguard.core.data.repository.LogRepository;
import com.padguard.core.data.repository.PolicyRepository;
import com.padguard.core.data.repository.UsageRepository;
import com.padguard.core.engine.PolicyEngine;
import com.padguard.core.engine.admin.DeviceAdminBridge;
import com.padguard.core.engine.command.CommandExecutor;
import com.padguard.core.engine.enforcer.AppLimitEnforcer;
import com.padguard.core.engine.enforcer.AppPolicyEnforcer;
import com.padguard.core.engine.enforcer.EyeCareEnforcer;
import com.padguard.core.engine.enforcer.KioskEnforcer;
import com.padguard.core.engine.enforcer.MonitoringEnforcer;
import com.padguard.core.engine.enforcer.PeripheralEnforcer;
import com.padguard.core.engine.enforcer.SecurityEnforcer;
import com.padguard.core.engine.enforcer.SystemLockEnforcer;
import com.padguard.core.engine.enforcer.WebEnforcer;
import com.padguard.core.engine.guard.TamperDetector;
import com.padguard.core.engine.lock.LockController;
import com.padguard.core.engine.schedule.DefaultHolidayProvider;
import com.padguard.core.engine.schedule.ScheduleEvaluator;
import com.padguard.core.transport.CommandGate;
import com.padguard.core.transport.RealRemoteDataSource;
import com.padguard.core.transport.RemoteDataSource;
import com.padguard.core.transport.TransportSettings;
import com.padguard.core.transport.di.TransportModule_ProvideJsonFactory;
import com.padguard.core.transport.di.TransportModule_ProvideOkHttpClientFactory;
import com.padguard.core.transport.di.TransportModule_ProvidePadGuardApiFactory;
import com.padguard.core.transport.di.TransportModule_ProvideRemoteDataSourceFactory;
import com.padguard.core.transport.di.TransportModule_ProvideRetrofitFactory;
import com.padguard.core.transport.http.ApiCaller;
import com.padguard.core.transport.http.AuthInterceptor;
import com.padguard.core.transport.http.CredentialStore;
import com.padguard.core.transport.http.HostSelectionInterceptor;
import com.padguard.core.transport.http.PadGuardApi;
import com.padguard.core.transport.http.RetryInterceptor;
import com.padguard.core.transport.mock.MockRemoteDataSource;
import com.padguard.core.transport.mqtt.MqttTransport;
import dagger.hilt.android.ActivityRetainedLifecycle;
import dagger.hilt.android.ViewModelLifecycle;
import dagger.hilt.android.internal.builders.ActivityComponentBuilder;
import dagger.hilt.android.internal.builders.ActivityRetainedComponentBuilder;
import dagger.hilt.android.internal.builders.FragmentComponentBuilder;
import dagger.hilt.android.internal.builders.ServiceComponentBuilder;
import dagger.hilt.android.internal.builders.ViewComponentBuilder;
import dagger.hilt.android.internal.builders.ViewModelComponentBuilder;
import dagger.hilt.android.internal.builders.ViewWithFragmentComponentBuilder;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories_InternalFactoryFactory_Factory;
import dagger.hilt.android.internal.managers.ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory;
import dagger.hilt.android.internal.managers.SavedStateHandleHolder;
import dagger.hilt.android.internal.modules.ApplicationContextModule;
import dagger.hilt.android.internal.modules.ApplicationContextModule_ProvideContextFactory;
import dagger.internal.DaggerGenerated;
import dagger.internal.DoubleCheck;
import dagger.internal.Preconditions;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;
import javax.inject.Provider;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.serialization.json.Json;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;

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
public final class DaggerPadGuardApplication_HiltComponents_SingletonC {
  private DaggerPadGuardApplication_HiltComponents_SingletonC() {
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private ApplicationContextModule applicationContextModule;

    private Builder() {
    }

    public Builder applicationContextModule(ApplicationContextModule applicationContextModule) {
      this.applicationContextModule = Preconditions.checkNotNull(applicationContextModule);
      return this;
    }

    public PadGuardApplication_HiltComponents.SingletonC build() {
      Preconditions.checkBuilderRequirement(applicationContextModule, ApplicationContextModule.class);
      return new SingletonCImpl(applicationContextModule);
    }
  }

  private static final class ActivityRetainedCBuilder implements PadGuardApplication_HiltComponents.ActivityRetainedC.Builder {
    private final SingletonCImpl singletonCImpl;

    private SavedStateHandleHolder savedStateHandleHolder;

    private ActivityRetainedCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ActivityRetainedCBuilder savedStateHandleHolder(
        SavedStateHandleHolder savedStateHandleHolder) {
      this.savedStateHandleHolder = Preconditions.checkNotNull(savedStateHandleHolder);
      return this;
    }

    @Override
    public PadGuardApplication_HiltComponents.ActivityRetainedC build() {
      Preconditions.checkBuilderRequirement(savedStateHandleHolder, SavedStateHandleHolder.class);
      return new ActivityRetainedCImpl(singletonCImpl, savedStateHandleHolder);
    }
  }

  private static final class ActivityCBuilder implements PadGuardApplication_HiltComponents.ActivityC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private Activity activity;

    private ActivityCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ActivityCBuilder activity(Activity activity) {
      this.activity = Preconditions.checkNotNull(activity);
      return this;
    }

    @Override
    public PadGuardApplication_HiltComponents.ActivityC build() {
      Preconditions.checkBuilderRequirement(activity, Activity.class);
      return new ActivityCImpl(singletonCImpl, activityRetainedCImpl, activity);
    }
  }

  private static final class FragmentCBuilder implements PadGuardApplication_HiltComponents.FragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private Fragment fragment;

    private FragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public FragmentCBuilder fragment(Fragment fragment) {
      this.fragment = Preconditions.checkNotNull(fragment);
      return this;
    }

    @Override
    public PadGuardApplication_HiltComponents.FragmentC build() {
      Preconditions.checkBuilderRequirement(fragment, Fragment.class);
      return new FragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragment);
    }
  }

  private static final class ViewWithFragmentCBuilder implements PadGuardApplication_HiltComponents.ViewWithFragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private View view;

    private ViewWithFragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;
    }

    @Override
    public ViewWithFragmentCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public PadGuardApplication_HiltComponents.ViewWithFragmentC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewWithFragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl, view);
    }
  }

  private static final class ViewCBuilder implements PadGuardApplication_HiltComponents.ViewC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private View view;

    private ViewCBuilder(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public ViewCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public PadGuardApplication_HiltComponents.ViewC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, view);
    }
  }

  private static final class ViewModelCBuilder implements PadGuardApplication_HiltComponents.ViewModelC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private SavedStateHandle savedStateHandle;

    private ViewModelLifecycle viewModelLifecycle;

    private ViewModelCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ViewModelCBuilder savedStateHandle(SavedStateHandle handle) {
      this.savedStateHandle = Preconditions.checkNotNull(handle);
      return this;
    }

    @Override
    public ViewModelCBuilder viewModelLifecycle(ViewModelLifecycle viewModelLifecycle) {
      this.viewModelLifecycle = Preconditions.checkNotNull(viewModelLifecycle);
      return this;
    }

    @Override
    public PadGuardApplication_HiltComponents.ViewModelC build() {
      Preconditions.checkBuilderRequirement(savedStateHandle, SavedStateHandle.class);
      Preconditions.checkBuilderRequirement(viewModelLifecycle, ViewModelLifecycle.class);
      return new ViewModelCImpl(singletonCImpl, activityRetainedCImpl, savedStateHandle, viewModelLifecycle);
    }
  }

  private static final class ServiceCBuilder implements PadGuardApplication_HiltComponents.ServiceC.Builder {
    private final SingletonCImpl singletonCImpl;

    private Service service;

    private ServiceCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ServiceCBuilder service(Service service) {
      this.service = Preconditions.checkNotNull(service);
      return this;
    }

    @Override
    public PadGuardApplication_HiltComponents.ServiceC build() {
      Preconditions.checkBuilderRequirement(service, Service.class);
      return new ServiceCImpl(singletonCImpl, service);
    }
  }

  private static final class ViewWithFragmentCImpl extends PadGuardApplication_HiltComponents.ViewWithFragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private final ViewWithFragmentCImpl viewWithFragmentCImpl = this;

    private ViewWithFragmentCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;


    }
  }

  private static final class FragmentCImpl extends PadGuardApplication_HiltComponents.FragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl = this;

    private FragmentCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        Fragment fragmentParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return activityCImpl.getHiltInternalFactoryFactory();
    }

    @Override
    public ViewWithFragmentComponentBuilder viewWithFragmentComponentBuilder() {
      return new ViewWithFragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl);
    }
  }

  private static final class ViewCImpl extends PadGuardApplication_HiltComponents.ViewC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final ViewCImpl viewCImpl = this;

    private ViewCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }
  }

  private static final class ActivityCImpl extends PadGuardApplication_HiltComponents.ActivityC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl = this;

    private ActivityCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, Activity activityParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;


    }

    @Override
    public void injectMainActivity(MainActivity arg0) {
    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return DefaultViewModelFactories_InternalFactoryFactory_Factory.newInstance(Collections.<Class<?>, Boolean>emptyMap(), new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl));
    }

    @Override
    public Map<Class<?>, Boolean> getViewModelKeys() {
      return Collections.<Class<?>, Boolean>emptyMap();
    }

    @Override
    public ViewModelComponentBuilder getViewModelComponentBuilder() {
      return new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public FragmentComponentBuilder fragmentComponentBuilder() {
      return new FragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }

    @Override
    public ViewComponentBuilder viewComponentBuilder() {
      return new ViewCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }
  }

  private static final class ViewModelCImpl extends PadGuardApplication_HiltComponents.ViewModelC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ViewModelCImpl viewModelCImpl = this;

    private ViewModelCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, SavedStateHandle savedStateHandleParam,
        ViewModelLifecycle viewModelLifecycleParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;


    }

    @Override
    public Map<Class<?>, Provider<ViewModel>> getHiltViewModelMap() {
      return Collections.<Class<?>, Provider<ViewModel>>emptyMap();
    }

    @Override
    public Map<Class<?>, Object> getHiltViewModelAssistedMap() {
      return Collections.<Class<?>, Object>emptyMap();
    }
  }

  private static final class ActivityRetainedCImpl extends PadGuardApplication_HiltComponents.ActivityRetainedC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl = this;

    private dagger.internal.Provider<ActivityRetainedLifecycle> provideActivityRetainedLifecycleProvider;

    private ActivityRetainedCImpl(SingletonCImpl singletonCImpl,
        SavedStateHandleHolder savedStateHandleHolderParam) {
      this.singletonCImpl = singletonCImpl;

      initialize(savedStateHandleHolderParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandleHolder savedStateHandleHolderParam) {
      this.provideActivityRetainedLifecycleProvider = DoubleCheck.provider(new SwitchingProvider<ActivityRetainedLifecycle>(singletonCImpl, activityRetainedCImpl, 0));
    }

    @Override
    public ActivityComponentBuilder activityComponentBuilder() {
      return new ActivityCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public ActivityRetainedLifecycle getActivityRetainedLifecycle() {
      return provideActivityRetainedLifecycleProvider.get();
    }

    private static final class SwitchingProvider<T> implements dagger.internal.Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.id = id;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T get() {
        switch (id) {
          case 0: // dagger.hilt.android.ActivityRetainedLifecycle 
          return (T) ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory.provideActivityRetainedLifecycle();

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ServiceCImpl extends PadGuardApplication_HiltComponents.ServiceC {
    private final SingletonCImpl singletonCImpl;

    private final ServiceCImpl serviceCImpl = this;

    private ServiceCImpl(SingletonCImpl singletonCImpl, Service serviceParam) {
      this.singletonCImpl = singletonCImpl;


    }

    @Override
    public void injectGuardService(GuardService arg0) {
      injectGuardService2(arg0);
    }

    private GuardService injectGuardService2(GuardService instance) {
      GuardService_MembersInjector.injectPolicyEngine(instance, singletonCImpl.policyEngineProvider.get());
      GuardService_MembersInjector.injectRemote(instance, singletonCImpl.provideRemoteDataSourceProvider.get());
      GuardService_MembersInjector.injectTransportSettings(instance, singletonCImpl.transportSettingsProvider.get());
      GuardService_MembersInjector.injectForegroundApp(instance, singletonCImpl.foregroundAppMonitorProvider.get());
      GuardService_MembersInjector.injectSnapshot(instance, singletonCImpl.deviceSnapshotCollectorProvider.get());
      GuardService_MembersInjector.injectAuthRepository(instance, singletonCImpl.authRepositoryProvider.get());
      GuardService_MembersInjector.injectPolicyRepository(instance, singletonCImpl.policyRepositoryProvider.get());
      GuardService_MembersInjector.injectLogRepository(instance, singletonCImpl.logRepositoryProvider.get());
      GuardService_MembersInjector.injectAdmin(instance, singletonCImpl.deviceAdminBridgeProvider.get());
      GuardService_MembersInjector.injectTimeProvider(instance, singletonCImpl.timeProvider.get());
      return instance;
    }
  }

  private static final class SingletonCImpl extends PadGuardApplication_HiltComponents.SingletonC {
    private final ApplicationContextModule applicationContextModule;

    private final SingletonCImpl singletonCImpl = this;

    private dagger.internal.Provider<DeviceAdminBridge> deviceAdminBridgeProvider;

    private dagger.internal.Provider<PadGuardDatabase> provideDatabaseProvider;

    private dagger.internal.Provider<BehaviorLogDao> provideBehaviorLogDaoProvider;

    private dagger.internal.Provider<RiskEventDao> provideRiskEventDaoProvider;

    private dagger.internal.Provider<LogRepository> logRepositoryProvider;

    private dagger.internal.Provider<DataStore<Preferences>> provideAuthDataStoreProvider;

    private dagger.internal.Provider<AuthRepository> authRepositoryProvider;

    private dagger.internal.Provider<CoroutineScope> provideApplicationScopeProvider;

    private dagger.internal.Provider<PeripheralEnforcer> peripheralEnforcerProvider;

    private dagger.internal.Provider<SystemLockEnforcer> systemLockEnforcerProvider;

    private dagger.internal.Provider<AppPolicyEnforcer> appPolicyEnforcerProvider;

    private dagger.internal.Provider<AppUsageDao> provideAppUsageDaoProvider;

    private dagger.internal.Provider<DailyUsageDao> provideDailyUsageDaoProvider;

    private dagger.internal.Provider<UsageRepository> usageRepositoryProvider;

    private dagger.internal.Provider<TimeProvider> timeProvider;

    private dagger.internal.Provider<DefaultHolidayProvider> defaultHolidayProvider;

    private dagger.internal.Provider<ScheduleEvaluator> scheduleEvaluatorProvider;

    private dagger.internal.Provider<AppLimitEnforcer> appLimitEnforcerProvider;

    private dagger.internal.Provider<EyeCareEnforcer> eyeCareEnforcerProvider;

    private dagger.internal.Provider<WebEnforcer> webEnforcerProvider;

    private dagger.internal.Provider<KioskEnforcer> kioskEnforcerProvider;

    private dagger.internal.Provider<SecurityEnforcer> securityEnforcerProvider;

    private dagger.internal.Provider<MonitoringEnforcer> monitoringEnforcerProvider;

    private dagger.internal.Provider<TamperDetector> tamperDetectorProvider;

    private dagger.internal.Provider<LockController> lockControllerProvider;

    private dagger.internal.Provider<PolicyDao> providePolicyDaoProvider;

    private dagger.internal.Provider<KeystoreManager> keystoreManagerProvider;

    private dagger.internal.Provider<PolicyRepository> policyRepositoryProvider;

    private dagger.internal.Provider<CommandExecutor> commandExecutorProvider;

    private dagger.internal.Provider<PolicyEngine> policyEngineProvider;

    private dagger.internal.Provider<CredentialStore> credentialStoreProvider;

    private dagger.internal.Provider<AuthInterceptor> authInterceptorProvider;

    private dagger.internal.Provider<TransportSettings> transportSettingsProvider;

    private dagger.internal.Provider<HostSelectionInterceptor> hostSelectionInterceptorProvider;

    private dagger.internal.Provider<RetryInterceptor> retryInterceptorProvider;

    private dagger.internal.Provider<OkHttpClient> provideOkHttpClientProvider;

    private dagger.internal.Provider<Json> provideJsonProvider;

    private dagger.internal.Provider<Retrofit> provideRetrofitProvider;

    private dagger.internal.Provider<PadGuardApi> providePadGuardApiProvider;

    private dagger.internal.Provider<ApiCaller> apiCallerProvider;

    private dagger.internal.Provider<MqttTransport> mqttTransportProvider;

    private dagger.internal.Provider<CommandRecordDao> provideCommandRecordDaoProvider;

    private dagger.internal.Provider<CommandRepository> commandRepositoryProvider;

    private dagger.internal.Provider<CommandGate> commandGateProvider;

    private dagger.internal.Provider<RealRemoteDataSource> realRemoteDataSourceProvider;

    private dagger.internal.Provider<MockRemoteDataSource> mockRemoteDataSourceProvider;

    private dagger.internal.Provider<RemoteDataSource> provideRemoteDataSourceProvider;

    private dagger.internal.Provider<ForegroundAppMonitor> foregroundAppMonitorProvider;

    private dagger.internal.Provider<DeviceSnapshotCollector> deviceSnapshotCollectorProvider;

    private SingletonCImpl(ApplicationContextModule applicationContextModuleParam) {
      this.applicationContextModule = applicationContextModuleParam;
      initialize(applicationContextModuleParam);
      initialize2(applicationContextModuleParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final ApplicationContextModule applicationContextModuleParam) {
      this.deviceAdminBridgeProvider = DoubleCheck.provider(new SwitchingProvider<DeviceAdminBridge>(singletonCImpl, 0));
      this.provideDatabaseProvider = DoubleCheck.provider(new SwitchingProvider<PadGuardDatabase>(singletonCImpl, 3));
      this.provideBehaviorLogDaoProvider = DoubleCheck.provider(new SwitchingProvider<BehaviorLogDao>(singletonCImpl, 2));
      this.provideRiskEventDaoProvider = DoubleCheck.provider(new SwitchingProvider<RiskEventDao>(singletonCImpl, 4));
      this.logRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<LogRepository>(singletonCImpl, 1));
      this.provideAuthDataStoreProvider = DoubleCheck.provider(new SwitchingProvider<DataStore<Preferences>>(singletonCImpl, 6));
      this.authRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<AuthRepository>(singletonCImpl, 5));
      this.provideApplicationScopeProvider = DoubleCheck.provider(new SwitchingProvider<CoroutineScope>(singletonCImpl, 7));
      this.peripheralEnforcerProvider = DoubleCheck.provider(new SwitchingProvider<PeripheralEnforcer>(singletonCImpl, 9));
      this.systemLockEnforcerProvider = DoubleCheck.provider(new SwitchingProvider<SystemLockEnforcer>(singletonCImpl, 10));
      this.appPolicyEnforcerProvider = DoubleCheck.provider(new SwitchingProvider<AppPolicyEnforcer>(singletonCImpl, 11));
      this.provideAppUsageDaoProvider = DoubleCheck.provider(new SwitchingProvider<AppUsageDao>(singletonCImpl, 14));
      this.provideDailyUsageDaoProvider = DoubleCheck.provider(new SwitchingProvider<DailyUsageDao>(singletonCImpl, 15));
      this.usageRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<UsageRepository>(singletonCImpl, 13));
      this.timeProvider = DoubleCheck.provider(new SwitchingProvider<TimeProvider>(singletonCImpl, 16));
      this.defaultHolidayProvider = DoubleCheck.provider(new SwitchingProvider<DefaultHolidayProvider>(singletonCImpl, 18));
      this.scheduleEvaluatorProvider = DoubleCheck.provider(new SwitchingProvider<ScheduleEvaluator>(singletonCImpl, 17));
      this.appLimitEnforcerProvider = DoubleCheck.provider(new SwitchingProvider<AppLimitEnforcer>(singletonCImpl, 12));
      this.eyeCareEnforcerProvider = DoubleCheck.provider(new SwitchingProvider<EyeCareEnforcer>(singletonCImpl, 19));
      this.webEnforcerProvider = DoubleCheck.provider(new SwitchingProvider<WebEnforcer>(singletonCImpl, 20));
      this.kioskEnforcerProvider = DoubleCheck.provider(new SwitchingProvider<KioskEnforcer>(singletonCImpl, 21));
      this.securityEnforcerProvider = DoubleCheck.provider(new SwitchingProvider<SecurityEnforcer>(singletonCImpl, 22));
      this.monitoringEnforcerProvider = DoubleCheck.provider(new SwitchingProvider<MonitoringEnforcer>(singletonCImpl, 23));
      this.tamperDetectorProvider = DoubleCheck.provider(new SwitchingProvider<TamperDetector>(singletonCImpl, 24));
      this.lockControllerProvider = DoubleCheck.provider(new SwitchingProvider<LockController>(singletonCImpl, 25));
    }

    @SuppressWarnings("unchecked")
    private void initialize2(final ApplicationContextModule applicationContextModuleParam) {
      this.providePolicyDaoProvider = DoubleCheck.provider(new SwitchingProvider<PolicyDao>(singletonCImpl, 28));
      this.keystoreManagerProvider = DoubleCheck.provider(new SwitchingProvider<KeystoreManager>(singletonCImpl, 29));
      this.policyRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<PolicyRepository>(singletonCImpl, 27));
      this.commandExecutorProvider = DoubleCheck.provider(new SwitchingProvider<CommandExecutor>(singletonCImpl, 26));
      this.policyEngineProvider = DoubleCheck.provider(new SwitchingProvider<PolicyEngine>(singletonCImpl, 8));
      this.credentialStoreProvider = DoubleCheck.provider(new SwitchingProvider<CredentialStore>(singletonCImpl, 36));
      this.authInterceptorProvider = DoubleCheck.provider(new SwitchingProvider<AuthInterceptor>(singletonCImpl, 35));
      this.transportSettingsProvider = DoubleCheck.provider(new SwitchingProvider<TransportSettings>(singletonCImpl, 38));
      this.hostSelectionInterceptorProvider = DoubleCheck.provider(new SwitchingProvider<HostSelectionInterceptor>(singletonCImpl, 37));
      this.retryInterceptorProvider = DoubleCheck.provider(new SwitchingProvider<RetryInterceptor>(singletonCImpl, 39));
      this.provideOkHttpClientProvider = DoubleCheck.provider(new SwitchingProvider<OkHttpClient>(singletonCImpl, 34));
      this.provideJsonProvider = DoubleCheck.provider(new SwitchingProvider<Json>(singletonCImpl, 40));
      this.provideRetrofitProvider = DoubleCheck.provider(new SwitchingProvider<Retrofit>(singletonCImpl, 33));
      this.providePadGuardApiProvider = DoubleCheck.provider(new SwitchingProvider<PadGuardApi>(singletonCImpl, 32));
      this.apiCallerProvider = DoubleCheck.provider(new SwitchingProvider<ApiCaller>(singletonCImpl, 41));
      this.mqttTransportProvider = DoubleCheck.provider(new SwitchingProvider<MqttTransport>(singletonCImpl, 42));
      this.provideCommandRecordDaoProvider = DoubleCheck.provider(new SwitchingProvider<CommandRecordDao>(singletonCImpl, 45));
      this.commandRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<CommandRepository>(singletonCImpl, 44));
      this.commandGateProvider = DoubleCheck.provider(new SwitchingProvider<CommandGate>(singletonCImpl, 43));
      this.realRemoteDataSourceProvider = DoubleCheck.provider(new SwitchingProvider<RealRemoteDataSource>(singletonCImpl, 31));
      this.mockRemoteDataSourceProvider = DoubleCheck.provider(new SwitchingProvider<MockRemoteDataSource>(singletonCImpl, 46));
      this.provideRemoteDataSourceProvider = DoubleCheck.provider(new SwitchingProvider<RemoteDataSource>(singletonCImpl, 30));
      this.foregroundAppMonitorProvider = DoubleCheck.provider(new SwitchingProvider<ForegroundAppMonitor>(singletonCImpl, 47));
      this.deviceSnapshotCollectorProvider = DoubleCheck.provider(new SwitchingProvider<DeviceSnapshotCollector>(singletonCImpl, 48));
    }

    @Override
    public void injectPadGuardApplication(PadGuardApplication arg0) {
    }

    @Override
    public void injectPadGuardDeviceAdminReceiver(PadGuardDeviceAdminReceiver arg0) {
      injectPadGuardDeviceAdminReceiver2(arg0);
    }

    @Override
    public Set<Boolean> getDisableFragmentGetContextFix() {
      return Collections.<Boolean>emptySet();
    }

    @Override
    public ActivityRetainedComponentBuilder retainedComponentBuilder() {
      return new ActivityRetainedCBuilder(singletonCImpl);
    }

    @Override
    public ServiceComponentBuilder serviceComponentBuilder() {
      return new ServiceCBuilder(singletonCImpl);
    }

    private PadGuardDeviceAdminReceiver injectPadGuardDeviceAdminReceiver2(
        PadGuardDeviceAdminReceiver instance) {
      PadGuardDeviceAdminReceiver_MembersInjector.injectAdmin(instance, deviceAdminBridgeProvider.get());
      PadGuardDeviceAdminReceiver_MembersInjector.injectLogRepository(instance, logRepositoryProvider.get());
      PadGuardDeviceAdminReceiver_MembersInjector.injectAuthRepository(instance, authRepositoryProvider.get());
      PadGuardDeviceAdminReceiver_MembersInjector.injectScope(instance, provideApplicationScopeProvider.get());
      return instance;
    }

    private static final class SwitchingProvider<T> implements dagger.internal.Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.id = id;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T get() {
        switch (id) {
          case 0: // com.padguard.core.engine.admin.DeviceAdminBridge 
          return (T) new DeviceAdminBridge(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 1: // com.padguard.core.data.repository.LogRepository 
          return (T) new LogRepository(singletonCImpl.provideBehaviorLogDaoProvider.get(), singletonCImpl.provideRiskEventDaoProvider.get());

          case 2: // com.padguard.core.data.db.BehaviorLogDao 
          return (T) DatabaseModule_ProvideBehaviorLogDaoFactory.provideBehaviorLogDao(singletonCImpl.provideDatabaseProvider.get());

          case 3: // com.padguard.core.data.db.PadGuardDatabase 
          return (T) DatabaseModule_ProvideDatabaseFactory.provideDatabase(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 4: // com.padguard.core.data.db.RiskEventDao 
          return (T) DatabaseModule_ProvideRiskEventDaoFactory.provideRiskEventDao(singletonCImpl.provideDatabaseProvider.get());

          case 5: // com.padguard.core.data.repository.AuthRepository 
          return (T) new AuthRepository(singletonCImpl.provideAuthDataStoreProvider.get());

          case 6: // @com.padguard.core.data.di.AuthDataStore androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> 
          return (T) DataStoreModule_ProvideAuthDataStoreFactory.provideAuthDataStore(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 7: // @com.padguard.child.di.ApplicationScope kotlinx.coroutines.CoroutineScope 
          return (T) AppModule_ProvideApplicationScopeFactory.provideApplicationScope();

          case 8: // com.padguard.core.engine.PolicyEngine 
          return (T) new PolicyEngine(singletonCImpl.deviceAdminBridgeProvider.get(), singletonCImpl.peripheralEnforcerProvider.get(), singletonCImpl.systemLockEnforcerProvider.get(), singletonCImpl.appPolicyEnforcerProvider.get(), singletonCImpl.appLimitEnforcerProvider.get(), singletonCImpl.eyeCareEnforcerProvider.get(), singletonCImpl.webEnforcerProvider.get(), singletonCImpl.kioskEnforcerProvider.get(), singletonCImpl.securityEnforcerProvider.get(), singletonCImpl.monitoringEnforcerProvider.get(), singletonCImpl.scheduleEvaluatorProvider.get(), singletonCImpl.tamperDetectorProvider.get(), singletonCImpl.lockControllerProvider.get(), singletonCImpl.commandExecutorProvider.get(), singletonCImpl.policyRepositoryProvider.get(), singletonCImpl.logRepositoryProvider.get(), singletonCImpl.authRepositoryProvider.get(), singletonCImpl.timeProvider.get());

          case 9: // com.padguard.core.engine.enforcer.PeripheralEnforcer 
          return (T) new PeripheralEnforcer(singletonCImpl.deviceAdminBridgeProvider.get());

          case 10: // com.padguard.core.engine.enforcer.SystemLockEnforcer 
          return (T) new SystemLockEnforcer(singletonCImpl.deviceAdminBridgeProvider.get());

          case 11: // com.padguard.core.engine.enforcer.AppPolicyEnforcer 
          return (T) new AppPolicyEnforcer(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.deviceAdminBridgeProvider.get());

          case 12: // com.padguard.core.engine.enforcer.AppLimitEnforcer 
          return (T) new AppLimitEnforcer(singletonCImpl.usageRepositoryProvider.get(), singletonCImpl.timeProvider.get(), singletonCImpl.scheduleEvaluatorProvider.get());

          case 13: // com.padguard.core.data.repository.UsageRepository 
          return (T) new UsageRepository(singletonCImpl.provideAppUsageDaoProvider.get(), singletonCImpl.provideDailyUsageDaoProvider.get());

          case 14: // com.padguard.core.data.db.AppUsageDao 
          return (T) DatabaseModule_ProvideAppUsageDaoFactory.provideAppUsageDao(singletonCImpl.provideDatabaseProvider.get());

          case 15: // com.padguard.core.data.db.DailyUsageDao 
          return (T) DatabaseModule_ProvideDailyUsageDaoFactory.provideDailyUsageDao(singletonCImpl.provideDatabaseProvider.get());

          case 16: // com.padguard.core.common.TimeProvider 
          return (T) new TimeProvider();

          case 17: // com.padguard.core.engine.schedule.ScheduleEvaluator 
          return (T) new ScheduleEvaluator(singletonCImpl.timeProvider.get(), singletonCImpl.defaultHolidayProvider.get());

          case 18: // com.padguard.core.engine.schedule.DefaultHolidayProvider 
          return (T) new DefaultHolidayProvider();

          case 19: // com.padguard.core.engine.enforcer.EyeCareEnforcer 
          return (T) new EyeCareEnforcer(singletonCImpl.timeProvider.get());

          case 20: // com.padguard.core.engine.enforcer.WebEnforcer 
          return (T) new WebEnforcer(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.deviceAdminBridgeProvider.get());

          case 21: // com.padguard.core.engine.enforcer.KioskEnforcer 
          return (T) new KioskEnforcer(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.deviceAdminBridgeProvider.get());

          case 22: // com.padguard.core.engine.enforcer.SecurityEnforcer 
          return (T) new SecurityEnforcer(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.deviceAdminBridgeProvider.get());

          case 23: // com.padguard.core.engine.enforcer.MonitoringEnforcer 
          return (T) new MonitoringEnforcer();

          case 24: // com.padguard.core.engine.guard.TamperDetector 
          return (T) new TamperDetector(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.deviceAdminBridgeProvider.get(), singletonCImpl.timeProvider.get());

          case 25: // com.padguard.core.engine.lock.LockController 
          return (T) new LockController(singletonCImpl.timeProvider.get());

          case 26: // com.padguard.core.engine.command.CommandExecutor 
          return (T) new CommandExecutor(singletonCImpl.deviceAdminBridgeProvider.get(), singletonCImpl.lockControllerProvider.get(), singletonCImpl.policyRepositoryProvider.get(), singletonCImpl.peripheralEnforcerProvider.get(), singletonCImpl.appLimitEnforcerProvider.get(), singletonCImpl.eyeCareEnforcerProvider.get(), singletonCImpl.timeProvider.get());

          case 27: // com.padguard.core.data.repository.PolicyRepository 
          return (T) new PolicyRepository(singletonCImpl.providePolicyDaoProvider.get(), singletonCImpl.keystoreManagerProvider.get());

          case 28: // com.padguard.core.data.db.PolicyDao 
          return (T) DatabaseModule_ProvidePolicyDaoFactory.providePolicyDao(singletonCImpl.provideDatabaseProvider.get());

          case 29: // com.padguard.core.data.crypto.KeystoreManager 
          return (T) new KeystoreManager();

          case 30: // com.padguard.core.transport.RemoteDataSource 
          return (T) TransportModule_ProvideRemoteDataSourceFactory.provideRemoteDataSource(singletonCImpl.realRemoteDataSourceProvider, singletonCImpl.mockRemoteDataSourceProvider);

          case 31: // com.padguard.core.transport.RealRemoteDataSource 
          return (T) new RealRemoteDataSource(singletonCImpl.providePadGuardApiProvider.get(), singletonCImpl.apiCallerProvider.get(), singletonCImpl.mqttTransportProvider.get(), singletonCImpl.commandGateProvider.get(), singletonCImpl.credentialStoreProvider.get(), singletonCImpl.transportSettingsProvider.get(), singletonCImpl.timeProvider.get(), singletonCImpl.provideJsonProvider.get());

          case 32: // com.padguard.core.transport.http.PadGuardApi 
          return (T) TransportModule_ProvidePadGuardApiFactory.providePadGuardApi(singletonCImpl.provideRetrofitProvider.get());

          case 33: // retrofit2.Retrofit 
          return (T) TransportModule_ProvideRetrofitFactory.provideRetrofit(singletonCImpl.provideOkHttpClientProvider.get(), singletonCImpl.provideJsonProvider.get());

          case 34: // okhttp3.OkHttpClient 
          return (T) TransportModule_ProvideOkHttpClientFactory.provideOkHttpClient(singletonCImpl.authInterceptorProvider.get(), singletonCImpl.hostSelectionInterceptorProvider.get(), singletonCImpl.retryInterceptorProvider.get());

          case 35: // com.padguard.core.transport.http.AuthInterceptor 
          return (T) new AuthInterceptor(singletonCImpl.credentialStoreProvider.get(), singletonCImpl.timeProvider.get());

          case 36: // com.padguard.core.transport.http.CredentialStore 
          return (T) new CredentialStore(singletonCImpl.authRepositoryProvider.get());

          case 37: // com.padguard.core.transport.http.HostSelectionInterceptor 
          return (T) new HostSelectionInterceptor(singletonCImpl.transportSettingsProvider.get());

          case 38: // com.padguard.core.transport.TransportSettings 
          return (T) new TransportSettings();

          case 39: // com.padguard.core.transport.http.RetryInterceptor 
          return (T) new RetryInterceptor();

          case 40: // kotlinx.serialization.json.Json 
          return (T) TransportModule_ProvideJsonFactory.provideJson();

          case 41: // com.padguard.core.transport.http.ApiCaller 
          return (T) new ApiCaller(singletonCImpl.timeProvider.get());

          case 42: // com.padguard.core.transport.mqtt.MqttTransport 
          return (T) new MqttTransport(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.transportSettingsProvider.get(), singletonCImpl.credentialStoreProvider.get());

          case 43: // com.padguard.core.transport.CommandGate 
          return (T) new CommandGate(singletonCImpl.credentialStoreProvider.get(), singletonCImpl.timeProvider.get(), singletonCImpl.commandRepositoryProvider.get(), singletonCImpl.logRepositoryProvider.get(), singletonCImpl.transportSettingsProvider.get());

          case 44: // com.padguard.core.data.repository.CommandRepository 
          return (T) new CommandRepository(singletonCImpl.provideCommandRecordDaoProvider.get());

          case 45: // com.padguard.core.data.db.CommandRecordDao 
          return (T) DatabaseModule_ProvideCommandRecordDaoFactory.provideCommandRecordDao(singletonCImpl.provideDatabaseProvider.get());

          case 46: // com.padguard.core.transport.mock.MockRemoteDataSource 
          return (T) new MockRemoteDataSource(singletonCImpl.commandGateProvider.get(), singletonCImpl.timeProvider.get(), singletonCImpl.transportSettingsProvider.get(), singletonCImpl.provideJsonProvider.get());

          case 47: // com.padguard.child.monitor.ForegroundAppMonitor 
          return (T) new ForegroundAppMonitor(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 48: // com.padguard.child.monitor.DeviceSnapshotCollector 
          return (T) new DeviceSnapshotCollector(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          default: throw new AssertionError(id);
        }
      }
    }
  }
}
