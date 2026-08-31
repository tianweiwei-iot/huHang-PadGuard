package com.padguard.presentation.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.padguard.presentation.ui.auth.LoginScreen
import com.padguard.presentation.ui.home.HomeScreen
import com.padguard.presentation.ui.device.DeviceDetailScreen
import com.padguard.presentation.ui.control.ControlPolicyScreen
import com.padguard.presentation.ui.monitor.MonitorScreen
import com.padguard.presentation.ui.main.MainScreen

/**
 * 导航图定义
 *
 * 路由结构：
 * - login -> 登录页
 * - home  -> 首页仪表盘（主页面）
 * - device_detail/{deviceId} -> 设备详情
 * - control/{deviceId} -> 管控策略
 * - monitor/{deviceId} -> 实时监控（查看屏幕）
 * - 其余路由（device_list / statistics / alert_list / profile）预留
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
    object Monitor : Screen("monitor/{deviceId}") {
        fun create(deviceId: String) = "monitor/$deviceId"
    }
    object Statistics : Screen("statistics/{deviceId}") {
        fun create(deviceId: String) = "statistics/$deviceId"
    }
    object AlertList : Screen("alert_list")
    object Profile : Screen("profile")
}

@Composable
fun PadGuardNavHost(
    navController: NavHostController = rememberNavController()
) {
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
                onViewScreen = { id -> navController.navigate(Screen.Monitor.create(id)) }
            )
        }
        composable(Screen.Control.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            ControlPolicyScreen(
                deviceId = deviceId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Monitor.route) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId").orEmpty()
            MonitorScreen(
                deviceId = deviceId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
