package com.padguard.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.padguard.domain.model.ReportPeriod
import com.padguard.presentation.ui.auth.LoginScreen
import com.padguard.presentation.ui.components.AppSoftBackground
import com.padguard.presentation.ui.control.ControlPolicyScreen
import com.padguard.presentation.ui.device.DeviceDetailScreen
import com.padguard.presentation.ui.main.MainScreen
import com.padguard.presentation.ui.realtime.LocationScreen
import com.padguard.presentation.ui.realtime.MessagePublishScreen
import com.padguard.presentation.ui.realtime.ScreenMonitorScreen
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
    navController: NavHostController = rememberNavController()
) {
    // 全局浅灰背景铺在导航根部：登录页、主框架与全部二级页共享同一底色，
    // 各页 Scaffold 透明化让底色透出（白卡层次依赖该背景）。
    AppSoftBackground(modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Screen.Login.route
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
            MainScreen(rootNavController = navController)
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
    }
}
