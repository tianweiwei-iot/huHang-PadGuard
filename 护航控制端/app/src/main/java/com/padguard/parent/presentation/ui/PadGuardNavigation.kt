package com.padguard.parent.presentation.ui

import com.padguard.parent.presentation.ui.profile.AboutScreen
import com.padguard.parent.presentation.ui.profile.SubscriptionScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.padguard.parent.domain.model.ReportPeriod
import com.padguard.parent.notification.NotificationHelper
import com.padguard.parent.presentation.MainActivity
import com.padguard.parent.presentation.viewmodel.SessionViewModel
import com.padguard.parent.presentation.ui.alert.AlertCenterScreen
import com.padguard.parent.presentation.ui.auth.LoginScreen
import com.padguard.parent.presentation.ui.components.AppSoftBackground
import com.padguard.parent.presentation.ui.components.UnlockRequestDialog
import com.padguard.parent.presentation.ui.components.UsageDelayRequestDialog
import com.padguard.parent.presentation.ui.control.ControlPolicyScreen
import com.padguard.parent.presentation.ui.device.DeviceDetailScreen
import com.padguard.parent.presentation.ui.main.MainScreen
import com.padguard.parent.presentation.ui.realtime.LocationScreen
import com.padguard.parent.presentation.ui.realtime.MessagePublishScreen
import com.padguard.parent.presentation.ui.realtime.ScreenMonitorScreen
import com.padguard.parent.presentation.viewmodel.UnlockRequestCenterViewModel
import com.padguard.parent.presentation.viewmodel.UsageDelayCenterViewModel
import com.padguard.parent.presentation.viewmodel.RiskAlertCenterViewModel
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 导航图定义
 *
 * 路由结构：
 * - login -> 登录页
 * - home  -> 首页仪表盘（主页面，位于 MainScreen 内部）
 * - device_detail/{deviceId} -> 设备详情
 * - control/{deviceId} -> 管控策略
 * - screen_monitor/{deviceId} -> 实时管控：屏幕监控
 * - message_publish/{deviceId} -> 实时管控：信息发布
 * - location/{deviceId} -> 实时管控：定位
 * - tablet_usage_detail/{deviceId} -> 平板使用详情（按天/周/月切换）
 * - tablet_usage_settings/{deviceId} -> 平板使用时间设置
 * - app_usage_history/{deviceId}/{packageName}/{period} -> 应用使用记录子页
 * - alert_list / about / subscription -> 独立页面
 *
 * **两张导航图的分工（重要）**：
 * 本文件是**根导航图**；首页 / 设备 / 统计 / 我的 四个底部 Tab 由 [MainScreen]
 * 内部的嵌套 NavHost 管理（见 `MainTab` 常量），**不属于**根图。
 * 早期这里同时声明了 `home` / `device_list` / `statistics` / `profile` 四条根路由却从不注册，
 * 而 Tab 切换又各写各的裸字符串 —— 两套并存之下一旦有人调用 `navigate(Screen.Home.route)`，
 * 得到的不是跳转而是 `IllegalArgumentException` 崩溃。现已删除这组死声明。
 *
 * 约定：带 `{deviceId}` 的路由，其参数由 NavBackStackEntry 自动注入
 * ViewModel 的 SavedStateHandle，因此页面 Composable 无需再接收 deviceId。
 */
sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Subscription : Screen("subscription")
    object Main : Screen("main")
    object DeviceDetail : Screen("device_detail/{deviceId}") {
        fun create(deviceId: String) = "device_detail/$deviceId"
    }
    object Control : Screen("control/{deviceId}") {
        fun create(deviceId: String) = "control/$deviceId"
    }
    object ScreenMonitor : Screen("screen_monitor/{deviceId}") {
        fun create(deviceId: String) = "screen_monitor/$deviceId"
    }
    object MessagePublish : Screen("message_publish/{deviceId}") {
        fun create(deviceId: String) = "message_publish/$deviceId"
    }
    object Location : Screen("location/{deviceId}") {
        fun create(deviceId: String) = "location/$deviceId"
    }
    object TabletUsageDetail : Screen("tablet_usage_detail/{deviceId}") {
        fun create(deviceId: String) = "tablet_usage_detail/$deviceId"
    }
    object TabletUsageSettings : Screen("tablet_usage_settings/{deviceId}") {
        fun create(deviceId: String) = "tablet_usage_settings/$deviceId"
    }
    object AppUsageHistory : Screen("app_usage_history/{deviceId}/{packageName}/{period}") {
        fun create(deviceId: String, packageName: String, period: ReportPeriod): String {
            val safePkg = URLEncoder.encode(packageName, "UTF-8")
            return "app_usage_history/$deviceId/$safePkg/${period.name}"
        }
    }
    object AppManage : Screen("app_manage/{deviceId}") {
        fun create(deviceId: String) = "app_manage/$deviceId"
    }
    object AlertList : Screen("alert_list")
    // 关于页
    object About : Screen("about")
}

/**
 * 底部 Tab 的路由常量（**属于 [MainScreen] 的嵌套导航图，不是根图**）。
 *
 * 单独定义在这里而非 MainScreen 内部，是为了让「Tab 路由」与「根路由 [Screen]」
 * 两套体系在同一处对照可见 —— 早期两者混用裸字符串，改一处漏一处，
 * 且根图里那几条同名声明从未注册，调用即崩。
 */
object MainTab {
    const val HOME = "home"
    const val DEVICES = "devices"
    const val STATISTICS = "statistics"
    const val PROFILE = "profile"

    /** 设备 Tab 的可选参数：进入即展开「接入新设备」浮层（首页空状态直达用） */
    const val OPEN_ACCESS = "openAccess"

    /** 带可选参数的完整路由模式，供 `composable(route = ...)` 使用 */
    const val DEVICES_PATTERN = "$DEVICES?$OPEN_ACCESS={$OPEN_ACCESS}"

    /** 构造设备 Tab 的跳转目标；openAccess=true 时落地即拉起接入浮层 */
    fun devices(openAccess: Boolean = false): String =
        if (openAccess) "$DEVICES?$OPEN_ACCESS=true" else DEVICES
}

@Composable
fun PadGuardNavHost(
    navController: NavHostController = rememberNavController(),
    sessionViewModel: SessionViewModel = hiltViewModel()
) {
    // ---- 会话门控 ----
    // 启动时读取持久化会话决定起点；bootResolved=false 期间显示启动占位，
    // 避免先闪一帧登录页再跳主页的视觉抖动。
    val bootResolved by sessionViewModel.bootResolved.collectAsState()
    val user by sessionViewModel.user.collectAsState()

    // 会话在运行中被清空（如令牌刷新失败、退出登录）时，全局退回登录页。
    // 用「目标页不是登录页」做防重入，退出登录自身的导航不会被二次叠加。
    LaunchedEffect(user, bootResolved) {
        val onLogin = navController.currentDestination?.route == Screen.Login.route
        if (bootResolved && user == null && !onLogin) {
            navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // ---- 全局解锁申请 ----
    // 这里取到的 ViewModel 是 Activity 作用域（本 Composable 位于 Activity 的 setContent 根），
    // 因此弹窗与消息中心天然共享同一份轮询数据，且生命周期与整个 App 一致 ——
    // 家长翻到哪个页面都能收到申请，不会被某个页面的销毁打断。
    val unlockVm: UnlockRequestCenterViewModel = hiltViewModel()
    val unlockState by unlockVm.uiState.collectAsState()
    // 延时申请全局轮询（与解锁工单同层级：Activity 作用域，翻到哪个页面都在拉，
    // 孩子提交/家长处理都即时刷新；后台时新申请转本地通知触达家长）
    val delayVm: UsageDelayCenterViewModel = hiltViewModel()
    val delayState by delayVm.uiState.collectAsState()
    // 风险告警全局轮询（与解锁工单同层级：Activity 作用域，翻到哪个页面都在拉，
    // 后台时新 CRITICAL 告警转本地通知触达家长）
    val riskVm: RiskAlertCenterViewModel = hiltViewModel()
    val riskState by riskVm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 通知点击直达消息中心。
    // 覆盖两种入口：①冷启动——onCreate 的 intent 就带着 extra；
    // ②热点击——App 在栈里，SINGLE_TOP 走 MainActivity.onNewIntent → setIntent。
    // 用 ON_START 观测而非 LaunchedEffect(user)：后者在热点击时不会重组（键没变），
    // 而回前台必经 ON_START；LifecycleRegistry 会把当前事件补派给新加的观察者，
    // 冷启动同样不漏。读完立刻移除 extra，防止重复导航。
    val navHostContext = LocalContext.current
    // owner 必须在组合作用域里取：DisposableEffect 的块不是 @Composable 上下文
    val navHostLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(navHostLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    unlockVm.setAppForeground(true)
                    delayVm.setAppForeground(true)
                    // 风险告警共用同一套前台门控 —— 前台不打扰，退到后台才发通知
                    riskVm.setAppForeground(true)
                    // 通知点击直达消息中心。冷启动（onCreate intent 带 extra）与
                    // 热点击（SINGLE_TOP → onNewIntent → setIntent）都必经 ON_START；
                    // LifecycleRegistry 会把当前事件补派给新观察者，冷启动同样不漏。
                    val activity = navHostContext as? MainActivity
                    if (activity?.intent?.getBooleanExtra(NotificationHelper.EXTRA_OPEN_ALERTS, false) == true) {
                        activity.intent.removeExtra(NotificationHelper.EXTRA_OPEN_ALERTS)
                        navController.navigate(Screen.AlertList.route)
                    }
                }
                // 退到后台才允许本地通知触达（前台已有弹窗，双响是噪音）
                Lifecycle.Event.ON_STOP -> {
                    unlockVm.setAppForeground(false)
                    delayVm.setAppForeground(false)
                    riskVm.setAppForeground(false)
                }
                else -> Unit
            }
        }
        navHostLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { navHostLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 操作反馈：处理完弹窗即退出，只留一条轻提示，不打断当前所在页面
    LaunchedEffect(unlockState.actionMessage) {
        val msg = unlockState.actionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        unlockVm.clearActionMessage()
    }

    // 风险告警的确认反馈，与解锁工单共用 snackbar 通道
    LaunchedEffect(riskState.actionMessage) {
        val msg = riskState.actionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        riskVm.clearActionMessage()
    }

    // 延时申请的反馈，与解锁工单共用 snackbar 通道
    LaunchedEffect(delayState.actionMessage) {
        val msg = delayState.actionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        delayVm.clearActionMessage()
    }

    // 全局浅灰背景铺在导航根部：登录页、主框架与全部二级页共享同一底色，
    // 各页 Scaffold 透明化让底色透出（白卡层次依赖该背景）。
    AppSoftBackground(modifier = Modifier) {
        if (!bootResolved) {
            // 会话读取（纯本地 DataStore）通常毫秒级完成；占位仅防御首帧闪屏
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Transparent),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@AppSoftBackground
        }

        val startDestination =
            if (user != null) SessionViewModel.MEMBER_START else SessionViewModel.GUEST_START

        NavHost(
            navController = navController,
            startDestination = startDestination
        ) {
        composable(Screen.Login.route) {
            // 登录成功后申请通知权限，使"孩子求解锁"能本地触达家长
            val activity = LocalContext.current as? MainActivity
            LoginScreen(
                onLoginSuccess = {
                    activity?.requestNotificationPermissionIfNeeded()
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Main.route) {
            MainScreen(
                rootNavController = navController,
                // 铃铛红点合并解锁工单、延时申请与风险告警三类未读
                unreadAlertCount = unlockState.unreadCount + delayState.unreadCount + riskState.unreadCount,
                onNavigateToAlerts = { navController.navigate(Screen.AlertList.route) }
            )
        }
        composable(Screen.Subscription.route) {
            SubscriptionScreen(onBack = { navController.safePopBackStack() })
        }
        composable(Screen.AlertList.route) {
            AlertCenterScreen(
                state = unlockState,
                onBack = { navController.safePopBackStack() },
                onApprove = { entry, minutes -> unlockVm.approve(entry.ticket, minutes) },
                onReject = { entry -> unlockVm.reject(entry.ticket) },
                onRefresh = unlockVm::refresh,
                // 风险告警分区（同一页面承载，根层 VM 已在此处之上取出）
                riskAlerts = riskState.alerts,
                riskUnreadCount = riskState.unreadCount,
                riskProcessing = riskState.processing,
                onAcknowledgeRisk = { alert -> riskVm.acknowledge(alert) },
                // 延时申请分区（同一页面承载，根层 VM 已在此处之上取出）
                delayState = delayState,
                onApproveDelay = { delayVm.approve(it.ticket) },
                onRejectDelay = { delayVm.reject(it.ticket) },
                onDismissDelay = { delayVm.dismiss(it.ticket) }
            )
        }
        // 关于页落地为真实路由
        composable(Screen.About.route) {
            AboutScreen(onBack = { navController.safePopBackStack() })
        }
        composable(Screen.DeviceDetail.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            DeviceDetailScreen(
                deviceId = deviceId,
                onBack = { navController.safePopBackStack() },
                onNavigateToControl = { id -> navController.navigate(Screen.Control.create(id)) },
                onViewScreen = { id -> navController.navigate(Screen.ScreenMonitor.create(id)) },
                onNavigateToAppManage = { id -> navController.navigate(Screen.AppManage.create(id)) }
            )
        }
        composable(Screen.Control.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            ControlPolicyScreen(
                deviceId = deviceId,
                onBack = { navController.safePopBackStack() }
            )
        }
        composable(Screen.ScreenMonitor.route) {
            ScreenMonitorScreen(onBack = { navController.safePopBackStack() })
        }
        composable(Screen.MessagePublish.route) {
            MessagePublishScreen(onBack = { navController.safePopBackStack() })
        }
        composable(Screen.Location.route) {
            LocationScreen(onBack = { navController.safePopBackStack() })
        }
        composable(Screen.TabletUsageDetail.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            com.padguard.parent.presentation.ui.usage.TabletUsageDetailScreen(
                deviceId = deviceId,
                onBack = { navController.safePopBackStack() },
                onAppClick = { pkg, _, period ->
                    navController.navigate(Screen.AppUsageHistory.create(deviceId, pkg, period))
                }
            )
        }
        composable(Screen.TabletUsageSettings.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            com.padguard.parent.presentation.ui.usage.TabletUsageSettingsScreen(
                deviceId = deviceId,
                onBack = { navController.safePopBackStack() }
            )
        }
        composable(Screen.AppManage.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            com.padguard.parent.presentation.ui.apps.AppManageScreen(
                deviceId = deviceId,
                onBack = { navController.safePopBackStack() },
                onNavigateToDeviceDetail = { navController.navigate(Screen.DeviceDetail.create(deviceId)) }
            )
        }
        composable(Screen.AppUsageHistory.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            val pkgEncoded = backStackEntry.arguments?.getString("packageName").orEmpty()
            val packageName = URLDecoder.decode(pkgEncoded, "UTF-8")
            val periodName = backStackEntry.arguments?.getString("period") ?: ReportPeriod.DAILY.name
            val period = runCatching { ReportPeriod.valueOf(periodName) }.getOrDefault(ReportPeriod.DAILY)
            com.padguard.parent.presentation.ui.usage.AppUsageHistoryScreen(
                deviceId = deviceId,
                packageName = packageName,
                appName = "",
                period = period,
                onBack = { navController.safePopBackStack() }
            )
        }
        }

        // ---- 全局弹层：铺在 NavHost 之上，因此覆盖所有页面 ----
        // 放在 NavHost 内部就会被限制在某一个目的地里，失去"无论哪个界面都弹出"的意义。
        unlockState.popup?.let { entry ->
            UnlockRequestDialog(
                entry = entry,
                processing = unlockState.processing,
                // 忽略：归档进消息中心，不下发消息给孩子
                onIgnore = { unlockVm.dismiss(entry.ticket) },
                // 去处理：直接按申请时长同意放行（孩子此刻正等着，多一步跳转就多一分钟干等）
                onHandle = { unlockVm.approve(entry.ticket, entry.ticket.durationMinutes) }
            )
        }

        // ---- 延时申请全局弹层（与解锁弹窗并列，覆盖所有页面） ----
        delayState.popup?.let { entry ->
            UsageDelayRequestDialog(
                entry = entry,
                processing = delayState.processing,
                // 忽略：归档进消息中心，不下发消息给孩子，工单仍可被批准
                onIgnore = { delayVm.dismiss(entry.ticket) },
                // 同意延长：服务端按工单分钟数累加额度，孩子端下一次会话刷新即生效
                onApprove = { delayVm.approve(entry.ticket) },
                // 拒绝：关闭工单并给孩子下发"申请未通过"
                onReject = { delayVm.reject(entry.ticket) }
            )
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SnackbarHost(hostState = snackbarHostState)
        }
    }
}
