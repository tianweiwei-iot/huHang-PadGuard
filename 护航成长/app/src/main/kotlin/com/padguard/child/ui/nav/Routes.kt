package com.padguard.child.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 路由常量。
 *
 * 集中定义可以避免散落的硬编码字符串；同时把 Tab 列表也放在这里，
 * 后面 MainScaffold 直接 forEach 渲染，避免"改了 Tab 忘了改导航"的不一致。
 */
sealed class Route(val path: String) {
    data object Home : Route("home")
    data object MessageList : Route("message/list")
    data object MessageDetail : Route("message/detail/{messageId}") {
        const val ARG = "messageId"
        fun build(messageId: String) = "message/detail/$messageId"
    }
    data object Profile : Route("profile")
    data object PermissionGuide : Route("profile/permission/{kind}") {
        const val ARG = "kind"
        fun build(kind: String) = "profile/permission/$kind"
    }

    /** 临时解锁申请。从首页进入时不带包名（申请整机时间） */
    data object UnlockRequest : Route("unlock/request")
}

/**
 * 底部 Tab 定义。3 个 Tab：首页 / 消息 / 我的。
 *
 * 选中/未选中用两套图标 + 颜色由 [MainScaffold] 控制，
 * 这里只声明元数据，避免 UI 与导航耦合。
 */
enum class MainTab(
    val route: Route,
    val labelResName: String,
    val outlined: ImageVector,
    val filled: ImageVector
) {
    Home(Route.Home, "首页", Icons.Outlined.Home, Icons.Rounded.Home),
    Message(Route.MessageList, "消息", Icons.Outlined.Mail, Icons.Rounded.Mail),
    Profile(Route.Profile, "我的", Icons.Outlined.Person, Icons.Rounded.Person)
}
