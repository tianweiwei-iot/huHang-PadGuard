package com.padguard.parent.presentation.ui.main

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
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.padguard.parent.presentation.ui.MainTab
import com.padguard.parent.presentation.ui.Screen
import com.padguard.parent.presentation.ui.device.DeviceManageScreen
import com.padguard.parent.presentation.ui.home.HomeScreen
import com.padguard.parent.presentation.ui.profile.ProfileAction
import com.padguard.parent.presentation.ui.profile.ProfileScreen
import com.padguard.parent.presentation.ui.statistics.StatisticsScreen
import com.padguard.parent.presentation.ui.theme.ThemeMode
import com.padguard.parent.presentation.viewmodel.HomeViewModel
import com.padguard.parent.presentation.viewmodel.ProfileViewModel

/**
 * 主框架：底部导航承载「首页 / 设备 / 统计 / 我的」四个顶级页面。
 * 设备详情 / 管控策略 / 实时监控 以根导航栈（rootNavController）全屏压入，不显示底部栏。
 *
 * 视觉（docs/UI设计提示词.md v2.1）：全局浅灰底由根导航（PadGuardNavHost）统一铺设，
 * 此处 Scaffold 透明化让底色透出；底部导航栏为纯白实底。
 */
@Composable
fun MainScreen(
    rootNavController: NavHostController,
    /** 未读消息数（解锁申请），驱动首页铃铛红点 */
    unreadAlertCount: Int = 0,
    /** 点击铃铛进入消息中心 */
    onNavigateToAlerts: () -> Unit = {},
    /** 当前主题模式（透传给「我的」页切换入口） */
    themeMode: ThemeMode = ThemeMode.MINT,
    /** 切换主题（透传给「我的」页切换入口） */
    onThemeChange: (ThemeMode) -> Unit = {}
) {
    val homeVm: HomeViewModel = hiltViewModel()
    val homeUiState by homeVm.uiState.collectAsState()
    val navController = rememberNavController()

    val items = listOf(
        BottomNavItem(MainTab.HOME, "首页", Icons.Default.Home),
        BottomNavItem(MainTab.DEVICES, "设备", Icons.Default.Devices),
        BottomNavItem(MainTab.STATISTICS, "统计", Icons.Default.BarChart),
        BottomNavItem(MainTab.PROFILE, "我的", Icons.Default.Person)
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
                            // 起始页（首页）不能走 saveState/restoreState：
                            // 实测经过「接入新设备」流程后，restoreState 会在起始目的地上
                            // 恢复出被污染的回退栈 —— 点「首页」永远落在「设备管理」。
                            // 起始页永远存在于回退栈底，直接 popBackStack 即可确定性回到首页；
                            // 万一真不在栈里（理论不会），再退化为普通 navigate。
                            val isStart = item.route == navController.graph.startDestinationRoute
                            if (isStart) {
                                if (!navController.popBackStack(item.route, inclusive = false)) {
                                    navController.navigate(item.route) { launchSingleTop = true }
                                }
                            } else {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
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
            startDestination = MainTab.HOME,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(MainTab.HOME) {
                HomeScreen(
                    uiState = homeUiState,
                    unreadAlertCount = unreadAlertCount,
                    onNavigateToAlerts = onNavigateToAlerts,
                    onSelectDevice = homeVm::selectDevice,
                    onDeviceClick = { rootNavController.navigate(Screen.DeviceDetail.create(it)) },
                    onNavigateToControl = { rootNavController.navigate(Screen.Control.create(it)) },
                    onNavigateToAppManage = { rootNavController.navigate(Screen.AppManage.create(it)) },
                    onNavigateToScreenMonitor = { rootNavController.navigate(Screen.ScreenMonitor.create(it)) },
                    onNavigateToMessagePublish = { rootNavController.navigate(Screen.MessagePublish.create(it)) },
                    onNavigateToLocation = { rootNavController.navigate(Screen.Location.create(it)) },
                    onNavigateToUsageDetail = { rootNavController.navigate(Screen.TabletUsageDetail.create(it)) },
                    onNavigateToUsageSettings = { rootNavController.navigate(Screen.TabletUsageSettings.create(it)) },
                    onIgnoreUnlockTicket = homeVm::ignoreUnlockTicket,
                    onRefresh = homeVm::refreshData,
                    onSetManagementActive = homeVm::setManagementActive,
                    // 首页空状态「还没有绑定的孩子设备」：直接进入接入新设备流程，
                    // 跳过设备管理主页面。openAccess=true 让 DeviceManageScreen 落地即拉起接入浮层。
                    onAddDevice = { navController.navigate(MainTab.devices(openAccess = true)) { launchSingleTop = true } }
                )
            }
            composable(
                route = MainTab.DEVICES_PATTERN,
                arguments = listOf(
                    navArgument(MainTab.OPEN_ACCESS) {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) { backStackEntry ->
                val openAccess = backStackEntry.arguments?.getBoolean("openAccess") ?: false
                DeviceManageScreen(
                    onDeviceClick = { rootNavController.navigate(Screen.DeviceDetail.create(it)) },
                    autoOpenAccess = openAccess
                )
            }
            composable(MainTab.STATISTICS) {
                val deviceId = homeUiState.selectedDevice?.id
                    ?: homeUiState.devices.firstOrNull()?.id.orEmpty()
                StatisticsScreen(deviceId = deviceId, rootNavController = rootNavController)
            }
            composable(MainTab.PROFILE) {
                val profileVm: ProfileViewModel = hiltViewModel()
                val profileState by profileVm.uiState.collectAsState()
                val serverUrl by profileVm.serverUrl.collectAsState()
                LaunchedEffect(profileState.loggedOut) {
                    if (profileState.loggedOut) {
                        rootNavController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Main.route) { inclusive = true }
                        }
                    }
                }
                ProfileScreen(
                    onLogout = profileVm::logout,
                    serverUrl = serverUrl,
                    onSaveServerUrl = profileVm::saveServerUrl,
                    themeMode = themeMode,
                    onThemeChange = onThemeChange,
                    onAction = { action ->
                        when (action) {
                            ProfileAction.Subscription ->
                                rootNavController.navigate(Screen.Subscription.route)
                            ProfileAction.Children -> navController.navigate(MainTab.DEVICES) {
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
                            ProfileAction.ServerAddress -> {
                                // 服务器地址弹窗由 ProfileScreen 本地处理，无需在此导航
                            }
                            // 家庭成员 / 设置尚未实现，ProfileScreen 内已置灰禁用，
                            // 这里不再响应（避免"点了没反应"误导）。
                            ProfileAction.Members,
                            ProfileAction.Settings -> {
                                // 目标页尚未实现，预留；集中在此处理避免散落静默空操作
                            }
                            ProfileAction.About -> {
                                // 关于页已落地为真实路由，导航进入。
                                rootNavController.navigate(Screen.About.route)
                            }
                        }
                    }
                )
            }
        }
    }
}

private data class BottomNavItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
