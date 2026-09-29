package com.padguard.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.padguard.domain.model.ReportPeriod
import com.padguard.parent.presentation.viewmodel.SessionViewModel
import com.padguard.presentation.ui.alert.AlertCenterScreen
import com.padguard.presentation.ui.auth.LoginScreen
import com.padguard.presentation.ui.components.AppSoftBackground
import com.padguard.presentation.ui.components.UnlockRequestDialog
import com.padguard.presentation.ui.control.ControlPolicyScreen
import com.padguard.presentation.ui.device.DeviceDetailScreen
import com.padguard.presentation.ui.main.MainScreen
import com.padguard.presentation.ui.realtime.LocationScreen
import com.padguard.presentation.ui.realtime.MessagePublishScreen
import com.padguard.presentation.ui.realtime.ScreenMonitorScreen
import com.padguard.presentation.viewmodel.UnlockRequestCenterViewModel
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
 * - 其余路由（device_list / statistics / alert_list / profile）预留
 *
 * 约定：带 `{deviceId}` 的路由，其参数由 NavBackStackEntry 自动注入
 * ViewModel 的 SavedStateHandle，因此页面 Composable 无需再接收 deviceId。
 */
sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Home : Screen("home")
    object Main : Screen("main")
    object DeviceList : Screen("device_list")
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
    object Statistics : Screen("statistics/{deviceId}") {
        fun create(deviceId: String) = "statistics/$deviceId"
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
    object Profile : Screen("profile")
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
    val snackbarHostState = remember { SnackbarHostState() }

    // 操作反馈：处理完弹窗即退出，只留一条轻提示，不打断当前所在页面
    LaunchedEffect(unlockState.actionMessage) {
        val msg = unlockState.actionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        unlockVm.clearActionMessage()
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
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Main.route) {
            MainScreen(
                rootNavController = navController,
                unreadAlertCount = unlockState.unreadCount,
                onNavigateToAlerts = { navController.navigate(Screen.AlertList.route) }
            )
        }
        composable(Screen.AlertList.route) {
            AlertCenterScreen(
                state = unlockState,
                onBack = { navController.popBackStack() },
                onApprove = { entry, minutes -> unlockVm.approve(entry.ticket, minutes) },
                onReject = { entry -> unlockVm.reject(entry.ticket) },
                onRefresh = unlockVm::refresh
            )
        }
        composable(Screen.DeviceDetail.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            DeviceDetailScreen(
                deviceId = deviceId,
                onBack = { navController.popBackStack() },
                onNavigateToControl = { id -> navController.navigate(Screen.Control.create(id)) },
                onViewScreen = { id -> navController.navigate(Screen.ScreenMonitor.create(id)) },
                onNavigateToAppManage = { id -> navController.navigate(Screen.AppManage.create(id)) }
            )
        }
        composable(Screen.Control.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            ControlPolicyScreen(
                deviceId = deviceId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.ScreenMonitor.route) {
            ScreenMonitorScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.MessagePublish.route) {
            MessagePublishScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Location.route) {
            LocationScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.TabletUsageDetail.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            com.padguard.presentation.ui.usage.TabletUsageDetailScreen(
                deviceId = deviceId,
                onBack = { navController.popBackStack() },
                onAppClick = { pkg, _, period ->
                    navController.navigate(Screen.AppUsageHistory.create(deviceId, pkg, period))
                }
            )
        }
        composable(Screen.TabletUsageSettings.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            com.padguard.presentation.ui.usage.TabletUsageSettingsScreen(
                deviceId = deviceId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.AppManage.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            com.padguard.presentation.ui.apps.AppManageScreen(
                deviceId = deviceId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.AppUsageHistory.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            val pkgEncoded = backStackEntry.arguments?.getString("packageName").orEmpty()
            val packageName = URLDecoder.decode(pkgEncoded, "UTF-8")
            val periodName = backStackEntry.arguments?.getString("period") ?: ReportPeriod.DAILY.name
            val period = runCatching { ReportPeriod.valueOf(periodName) }.getOrDefault(ReportPeriod.DAILY)
            com.padguard.presentation.ui.usage.AppUsageHistoryScreen(
                deviceId = deviceId,
                packageName = packageName,
                appName = "",
                period = period,
                onBack = { navController.popBackStack() }
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

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            SnackbarHost(hostState = snackbarHostState)
        }
    }
}
