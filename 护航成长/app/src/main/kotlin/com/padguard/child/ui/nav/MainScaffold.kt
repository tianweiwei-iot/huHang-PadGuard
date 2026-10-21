package com.padguard.child.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.padguard.child.ui.apps.AppInventoryScreen
import com.padguard.child.ui.home.HomeScreen
import com.padguard.child.ui.message.MessageDetailScreen
import com.padguard.child.ui.message.MessageListScreen
import com.padguard.child.ui.profile.PermissionGuideScreen
import com.padguard.child.ui.profile.ProfileScreen
import com.padguard.child.ui.unlock.UnlockRequestScreen
import com.padguard.child.usagesession.UsageSessionHost

/**
 * App 顶层骨架：3 Tab + 嵌套子页面。
 *
 * 设计要点：
 * 1. 三个 Tab 用 [MainTab] 枚举声明，新增 Tab 只需改 Routes.kt 里的列表。
 * 2. 子页面（消息详情 / 权限引导）共用同一个 NavHost，但点击底栏会回到对应 Tab 的 startDestination，
 *    不会在子页面残留返回栈。
 * 3. 底栏在子页面（消息详情 / 权限引导）自动隐藏，避免视觉杂乱。
 */
@Composable
fun PadGuardApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            // 消息详情 / 权限引导等子页面隐藏底栏，避免视觉杂乱
            if (currentRoute in TAB_ROUTES) {
                BottomBar(
                    navController = navController,
                    currentRoute = currentRoute
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = Route.Home.path,
                modifier = Modifier.fillMaxSize()
            ) {
            composable(Route.Home.path) {
                HomeScreen(
                    onRequestUnlock = { navController.navigate(Route.UnlockRequest.path) }
                )
            }
            composable(Route.UnlockRequest.path) {
                UnlockRequestScreen(onBack = { navController.safePopBackStack() })
            }
            composable(Route.MessageList.path) {
                MessageListScreen(
                    onItemClick = { id -> navController.navigate(Route.MessageDetail.build(id)) }
                )
            }
            composable(
                route = Route.MessageDetail.path,
                arguments = listOf(navArgument(Route.MessageDetail.ARG) { type = NavType.StringType })
            ) { entry ->
                val id = entry.arguments?.getString(Route.MessageDetail.ARG).orEmpty()
                MessageDetailScreen(
                    messageId = id,
                    onBack = { navController.safePopBackStack() }
                )
            }
            composable(Route.Profile.path) {
                ProfileScreen(
                    onPermissionGuide = { kind ->
                        navController.navigate(Route.PermissionGuide.build(kind))
                    },
                    onAppInventory = { navController.navigate(Route.AppInventory.path) }
                )
            }
            composable(Route.AppInventory.path) {
                AppInventoryScreen(onBack = { navController.safePopBackStack() })
            }
            composable(
                route = Route.PermissionGuide.path,
                arguments = listOf(navArgument(Route.PermissionGuide.ARG) { type = NavType.StringType })
            ) { entry ->
                val kind = entry.arguments?.getString(Route.PermissionGuide.ARG).orEmpty()
                PermissionGuideScreen(
                    kind = kind,
                    onBack = { navController.safePopBackStack() }
                )
            }
        }
        // 全局会话浮层：覆盖在 Tab 之上，承载“计时开始确认”对话框与“剩余10分钟倒计时”横幅。
        // 放置在 NavHost 之后，确保其在所有路由页面之上渲染，且不受底栏显隐影响。
        UsageSessionHost()
        }
    }
}

private val TAB_ROUTES: Set<String> = MainTab.entries.map { it.route.path }.toSet()

@Composable
private fun BottomBar(
    navController: NavHostController,
    currentRoute: String?
) {
    NavigationBar {
        MainTab.entries.forEach { tab ->
            val isSelected = currentRoute == tab.route.path
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    navController.navigate(tab.route.path) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (isSelected) tab.filled else tab.outlined,
                        contentDescription = null
                    )
                },
                label = { Text(text = tab.labelResName) }
            )
        }
    }
}
