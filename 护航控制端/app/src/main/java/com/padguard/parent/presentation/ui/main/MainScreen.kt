package com.padguard.presentation.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.padguard.presentation.ui.Screen
import com.padguard.presentation.ui.device.DeviceManageScreen
import com.padguard.presentation.ui.home.HomeScreen
import com.padguard.presentation.ui.profile.ProfileAction
import com.padguard.presentation.ui.profile.ProfileScreen
import com.padguard.presentation.ui.statistics.StatisticsScreen
import com.padguard.presentation.viewmodel.HomeViewModel
import com.padguard.presentation.viewmodel.ProfileViewModel

/**
 * 主框架：底部导航承载「首页 / 设备 / 统计 / 我的」四个顶级页面。
 * 设备详情 / 管控策略 / 实时监控 以根导航栈（rootNavController）全屏压入，不显示底部栏。
 *
 * 视觉（docs/UI设计提示词.md）：全局渐变背景由根导航（PadGuardNavHost）统一铺设，
 * 此处 Scaffold 透明化让渐变透出；底部导航栏为玻璃质感。
 */
@Composable
fun MainScreen(rootNavController: NavHostController) {
    val homeVm: HomeViewModel = hiltViewModel()
    val homeUiState by homeVm.uiState.collectAsState()
    val navController = rememberNavController()

    val items = listOf(
        BottomNavItem("home", "首页", Icons.Default.Home),
        BottomNavItem("devices", "设备", Icons.Default.Devices),
        BottomNavItem("statistics", "统计", Icons.Default.BarChart),
        BottomNavItem("profile", "我的", Icons.Default.Person)
    )
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                tonalElevation = 0.dp
            ) {
                items.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    uiState = homeUiState,
                    onSelectDevice = homeVm::selectDevice,
                    onDeviceClick = { rootNavController.navigate(Screen.DeviceDetail.create(it)) },
                    onNavigateToControl = { rootNavController.navigate(Screen.Control.create(it)) },
                    onNavigateToScreenMonitor = { rootNavController.navigate(Screen.ScreenMonitor.create(it)) },
                    onNavigateToMessagePublish = { rootNavController.navigate(Screen.MessagePublish.create(it)) },
                    onNavigateToLocation = { rootNavController.navigate(Screen.Location.create(it)) },
                    onNavigateToUsageDetail = { rootNavController.navigate(Screen.TabletUsageDetail.create(it)) },
                    onNavigateToUsageSettings = { rootNavController.navigate(Screen.TabletUsageSettings.create(it)) },
                    onRefresh = homeVm::refreshData
                )
            }
            composable("devices") {
                DeviceManageScreen(
                    onDeviceClick = { rootNavController.navigate(Screen.DeviceDetail.create(it)) }
                )
            }
            composable("statistics") {
                val deviceId = homeUiState.selectedDevice?.id
                    ?: homeUiState.devices.firstOrNull()?.id.orEmpty()
                StatisticsScreen(deviceId = deviceId, rootNavController = rootNavController)
            }
            composable("profile") {
                val profileVm: ProfileViewModel = hiltViewModel()
                val profileState by profileVm.uiState.collectAsState()
                LaunchedEffect(profileState.loggedOut) {
                    if (profileState.loggedOut) {
                        rootNavController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Main.route) { inclusive = true }
                        }
                    }
                }
                ProfileScreen(
                    onLogout = profileVm::logout,
                    onAction = { action ->
                        when (action) {
                            ProfileAction.Children -> navController.navigate("devices") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                            ProfileAction.EyeCare -> {
                                val id = homeUiState.selectedDevice?.id
                                    ?: homeUiState.devices.firstOrNull()?.id
                                if (id != null) rootNavController.navigate(Screen.Control.create(id))
                            }
                            ProfileAction.Members,
                            ProfileAction.Settings,
                            ProfileAction.About -> {
                                // 目标页尚未实现，预留；集中在此处理避免散落静默空操作
                            }
                        }
                    }
                )
            }
        }
    }
}

private data class BottomNavItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
