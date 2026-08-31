package com.padguard.parent;

import android.app.Activity;
import android.app.Service;
import android.view.View;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.padguard.data.local.LocalDataSource;
import com.padguard.domain.repository.AuthRepository;
import com.padguard.domain.repository.DeviceRepository;
import com.padguard.domain.repository.MonitorRepository;
import com.padguard.domain.repository.PolicyRepository;
import com.padguard.domain.repository.StatisticsRepository;
import com.padguard.parent.di.RepositoryModule_ProvideAuthRepositoryFactory;
import com.padguard.parent.di.RepositoryModule_ProvideDeviceRepositoryFactory;
import com.padguard.parent.di.RepositoryModule_ProvideMonitorRepositoryFactory;
import com.padguard.parent.di.RepositoryModule_ProvidePolicyRepositoryFactory;
import com.padguard.parent.di.RepositoryModule_ProvideStatisticsRepositoryFactory;
import com.padguard.parent.presentation.MainActivity;
import com.padguard.presentation.viewmodel.ControlPolicyViewModel;
import com.padguard.presentation.viewmodel.ControlPolicyViewModel_HiltModules;
import com.padguard.presentation.viewmodel.DeviceDetailViewModel;
import com.padguard.presentation.viewmodel.DeviceDetailViewModel_HiltModules;
import com.padguard.presentation.viewmodel.HomeViewModel;
import com.padguard.presentation.viewmodel.HomeViewModel_HiltModules;
import com.padguard.presentation.viewmodel.LoginViewModel;
import com.padguard.presentation.viewmodel.LoginViewModel_HiltModules;
import com.padguard.presentation.viewmodel.MonitorViewModel;
import com.padguard.presentation.viewmodel.MonitorViewModel_HiltModules;
import com.padguard.presentation.viewmodel.ProfileViewModel;
import com.padguard.presentation.viewmodel.ProfileViewModel_HiltModules;
import com.padguard.presentation.viewmodel.StatisticsViewModel;
import com.padguard.presentation.viewmodel.StatisticsViewModel_HiltModules;
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
import dagger.internal.IdentifierNameString;
import dagger.internal.KeepFieldType;
import dagger.internal.LazyClassKeyMap;
import dagger.internal.MapBuilder;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

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
      return DefaultViewModelFactories_InternalFactoryFactory_Factory.newInstance(getViewModelKeys(), new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl));
    }

    @Override
    public Map<Class<?>, Boolean> getViewModelKeys() {
      return LazyClassKeyMap.<Boolean>of(MapBuilder.<String, Boolean>newMapBuilder(7).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_ControlPolicyViewModel, ControlPolicyViewModel_HiltModules.KeyModule.provide()).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_DeviceDetailViewModel, DeviceDetailViewModel_HiltModules.KeyModule.provide()).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_HomeViewModel, HomeViewModel_HiltModules.KeyModule.provide()).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_LoginViewModel, LoginViewModel_HiltModules.KeyModule.provide()).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_MonitorViewModel, MonitorViewModel_HiltModules.KeyModule.provide()).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_ProfileViewModel, ProfileViewModel_HiltModules.KeyModule.provide()).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_StatisticsViewModel, StatisticsViewModel_HiltModules.KeyModule.provide()).build());
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

    @IdentifierNameString
    private static final class LazyClassKeyProvider {
      static String com_padguard_presentation_viewmodel_HomeViewModel = "com.padguard.presentation.viewmodel.HomeViewModel";

      static String com_padguard_presentation_viewmodel_StatisticsViewModel = "com.padguard.presentation.viewmodel.StatisticsViewModel";

      static String com_padguard_presentation_viewmodel_ControlPolicyViewModel = "com.padguard.presentation.viewmodel.ControlPolicyViewModel";

      static String com_padguard_presentation_viewmodel_ProfileViewModel = "com.padguard.presentation.viewmodel.ProfileViewModel";

      static String com_padguard_presentation_viewmodel_MonitorViewModel = "com.padguard.presentation.viewmodel.MonitorViewModel";

      static String com_padguard_presentation_viewmodel_LoginViewModel = "com.padguard.presentation.viewmodel.LoginViewModel";

      static String com_padguard_presentation_viewmodel_DeviceDetailViewModel = "com.padguard.presentation.viewmodel.DeviceDetailViewModel";

      @KeepFieldType
      HomeViewModel com_padguard_presentation_viewmodel_HomeViewModel2;

      @KeepFieldType
      StatisticsViewModel com_padguard_presentation_viewmodel_StatisticsViewModel2;

      @KeepFieldType
      ControlPolicyViewModel com_padguard_presentation_viewmodel_ControlPolicyViewModel2;

      @KeepFieldType
      ProfileViewModel com_padguard_presentation_viewmodel_ProfileViewModel2;

      @KeepFieldType
      MonitorViewModel com_padguard_presentation_viewmodel_MonitorViewModel2;

      @KeepFieldType
      LoginViewModel com_padguard_presentation_viewmodel_LoginViewModel2;

      @KeepFieldType
      DeviceDetailViewModel com_padguard_presentation_viewmodel_DeviceDetailViewModel2;
    }
  }

  private static final class ViewModelCImpl extends PadGuardApplication_HiltComponents.ViewModelC {
    private final SavedStateHandle savedStateHandle;

    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ViewModelCImpl viewModelCImpl = this;

    private Provider<ControlPolicyViewModel> controlPolicyViewModelProvider;

    private Provider<DeviceDetailViewModel> deviceDetailViewModelProvider;

    private Provider<HomeViewModel> homeViewModelProvider;

    private Provider<LoginViewModel> loginViewModelProvider;

    private Provider<MonitorViewModel> monitorViewModelProvider;

    private Provider<ProfileViewModel> profileViewModelProvider;

    private Provider<StatisticsViewModel> statisticsViewModelProvider;

    private ViewModelCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, SavedStateHandle savedStateHandleParam,
        ViewModelLifecycle viewModelLifecycleParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.savedStateHandle = savedStateHandleParam;
      initialize(savedStateHandleParam, viewModelLifecycleParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandle savedStateHandleParam,
        final ViewModelLifecycle viewModelLifecycleParam) {
      this.controlPolicyViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 0);
      this.deviceDetailViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 1);
      this.homeViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 2);
      this.loginViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 3);
      this.monitorViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 4);
      this.profileViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 5);
      this.statisticsViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 6);
    }

    @Override
    public Map<Class<?>, javax.inject.Provider<ViewModel>> getHiltViewModelMap() {
      return LazyClassKeyMap.<javax.inject.Provider<ViewModel>>of(MapBuilder.<String, javax.inject.Provider<ViewModel>>newMapBuilder(7).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_ControlPolicyViewModel, ((Provider) controlPolicyViewModelProvider)).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_DeviceDetailViewModel, ((Provider) deviceDetailViewModelProvider)).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_HomeViewModel, ((Provider) homeViewModelProvider)).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_LoginViewModel, ((Provider) loginViewModelProvider)).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_MonitorViewModel, ((Provider) monitorViewModelProvider)).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_ProfileViewModel, ((Provider) profileViewModelProvider)).put(LazyClassKeyProvider.com_padguard_presentation_viewmodel_StatisticsViewModel, ((Provider) statisticsViewModelProvider)).build());
    }

    @Override
    public Map<Class<?>, Object> getHiltViewModelAssistedMap() {
      return Collections.<Class<?>, Object>emptyMap();
    }

    @IdentifierNameString
    private static final class LazyClassKeyProvider {
      static String com_padguard_presentation_viewmodel_DeviceDetailViewModel = "com.padguard.presentation.viewmodel.DeviceDetailViewModel";

      static String com_padguard_presentation_viewmodel_LoginViewModel = "com.padguard.presentation.viewmodel.LoginViewModel";

      static String com_padguard_presentation_viewmodel_ControlPolicyViewModel = "com.padguard.presentation.viewmodel.ControlPolicyViewModel";

      static String com_padguard_presentation_viewmodel_ProfileViewModel = "com.padguard.presentation.viewmodel.ProfileViewModel";

      static String com_padguard_presentation_viewmodel_StatisticsViewModel = "com.padguard.presentation.viewmodel.StatisticsViewModel";

      static String com_padguard_presentation_viewmodel_HomeViewModel = "com.padguard.presentation.viewmodel.HomeViewModel";

      static String com_padguard_presentation_viewmodel_MonitorViewModel = "com.padguard.presentation.viewmodel.MonitorViewModel";

      @KeepFieldType
      DeviceDetailViewModel com_padguard_presentation_viewmodel_DeviceDetailViewModel2;

      @KeepFieldType
      LoginViewModel com_padguard_presentation_viewmodel_LoginViewModel2;

      @KeepFieldType
      ControlPolicyViewModel com_padguard_presentation_viewmodel_ControlPolicyViewModel2;

      @KeepFieldType
      ProfileViewModel com_padguard_presentation_viewmodel_ProfileViewModel2;

      @KeepFieldType
      StatisticsViewModel com_padguard_presentation_viewmodel_StatisticsViewModel2;

      @KeepFieldType
      HomeViewModel com_padguard_presentation_viewmodel_HomeViewModel2;

      @KeepFieldType
      MonitorViewModel com_padguard_presentation_viewmodel_MonitorViewModel2;
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final ViewModelCImpl viewModelCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          ViewModelCImpl viewModelCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.viewModelCImpl = viewModelCImpl;
        this.id = id;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T get() {
        switch (id) {
          case 0: // com.padguard.presentation.viewmodel.ControlPolicyViewModel 
          return (T) new ControlPolicyViewModel(singletonCImpl.provideDeviceRepositoryProvider.get(), singletonCImpl.providePolicyRepositoryProvider.get(), viewModelCImpl.savedStateHandle);

          case 1: // com.padguard.presentation.viewmodel.DeviceDetailViewModel 
          return (T) new DeviceDetailViewModel(singletonCImpl.provideDeviceRepositoryProvider.get(), singletonCImpl.provideMonitorRepositoryProvider.get(), singletonCImpl.provideStatisticsRepositoryProvider.get(), singletonCImpl.providePolicyRepositoryProvider.get(), viewModelCImpl.savedStateHandle);

          case 2: // com.padguard.presentation.viewmodel.HomeViewModel 
          return (T) new HomeViewModel(singletonCImpl.provideDeviceRepositoryProvider.get(), singletonCImpl.provideStatisticsRepositoryProvider.get(), singletonCImpl.providePolicyRepositoryProvider.get());

          case 3: // com.padguard.presentation.viewmodel.LoginViewModel 
          return (T) new LoginViewModel(singletonCImpl.provideAuthRepositoryProvider.get());

          case 4: // com.padguard.presentation.viewmodel.MonitorViewModel 
          return (T) new MonitorViewModel(singletonCImpl.provideMonitorRepositoryProvider.get(), viewModelCImpl.savedStateHandle);

          case 5: // com.padguard.presentation.viewmodel.ProfileViewModel 
          return (T) new ProfileViewModel(singletonCImpl.provideAuthRepositoryProvider.get());

          case 6: // com.padguard.presentation.viewmodel.StatisticsViewModel 
          return (T) new StatisticsViewModel(singletonCImpl.provideStatisticsRepositoryProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ActivityRetainedCImpl extends PadGuardApplication_HiltComponents.ActivityRetainedC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl = this;

    private Provider<ActivityRetainedLifecycle> provideActivityRetainedLifecycleProvider;

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

    private static final class SwitchingProvider<T> implements Provider<T> {
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
  }

  private static final class SingletonCImpl extends PadGuardApplication_HiltComponents.SingletonC {
    private final ApplicationContextModule applicationContextModule;

    private final SingletonCImpl singletonCImpl = this;

    private Provider<LocalDataSource> localDataSourceProvider;

    private Provider<DeviceRepository> provideDeviceRepositoryProvider;

    private Provider<PolicyRepository> providePolicyRepositoryProvider;

    private Provider<MonitorRepository> provideMonitorRepositoryProvider;

    private Provider<StatisticsRepository> provideStatisticsRepositoryProvider;

    private Provider<AuthRepository> provideAuthRepositoryProvider;

    private SingletonCImpl(ApplicationContextModule applicationContextModuleParam) {
      this.applicationContextModule = applicationContextModuleParam;
      initialize(applicationContextModuleParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final ApplicationContextModule applicationContextModuleParam) {
      this.localDataSourceProvider = DoubleCheck.provider(new SwitchingProvider<LocalDataSource>(singletonCImpl, 1));
      this.provideDeviceRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<DeviceRepository>(singletonCImpl, 0));
      this.providePolicyRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<PolicyRepository>(singletonCImpl, 2));
      this.provideMonitorRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<MonitorRepository>(singletonCImpl, 3));
      this.provideStatisticsRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<StatisticsRepository>(singletonCImpl, 4));
      this.provideAuthRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<AuthRepository>(singletonCImpl, 5));
    }

    @Override
    public void injectPadGuardApplication(PadGuardApplication padGuardApplication) {
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

    private static final class SwitchingProvider<T> implements Provider<T> {
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
          case 0: // com.padguard.domain.repository.DeviceRepository 
          return (T) RepositoryModule_ProvideDeviceRepositoryFactory.provideDeviceRepository(singletonCImpl.localDataSourceProvider.get());

          case 1: // com.padguard.data.local.LocalDataSource 
          return (T) new LocalDataSource(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 2: // com.padguard.domain.repository.PolicyRepository 
          return (T) RepositoryModule_ProvidePolicyRepositoryFactory.providePolicyRepository(singletonCImpl.localDataSourceProvider.get());

          case 3: // com.padguard.domain.repository.MonitorRepository 
          return (T) RepositoryModule_ProvideMonitorRepositoryFactory.provideMonitorRepository(singletonCImpl.localDataSourceProvider.get());

          case 4: // com.padguard.domain.repository.StatisticsRepository 
          return (T) RepositoryModule_ProvideStatisticsRepositoryFactory.provideStatisticsRepository(singletonCImpl.localDataSourceProvider.get());

          case 5: // com.padguard.domain.repository.AuthRepository 
          return (T) RepositoryModule_ProvideAuthRepositoryFactory.provideAuthRepository(singletonCImpl.localDataSourceProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }
}
