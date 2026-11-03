package com.padguard.parent.presentation.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.padguard.parent.presentation.ui.theme.DarkMode
import com.padguard.parent.presentation.ui.theme.PadGuardColors
import com.padguard.parent.presentation.ui.components.SoftCard
import com.padguard.parent.presentation.ui.auth.ServerConfigDialog
import com.padguard.parent.presentation.ui.theme.ThemeMode
import com.padguard.parent.presentation.ui.theme.tokensFor
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

/**
 * 我的页（底部导航「我的」Tab）
 *
 * 条目点击统一通过 [onAction] 分发，避免在列表项内部散落静默空操作；
 * 各条目的真实目标（如「我的孩子」→设备 Tab、「护眼设置」→管控策略）由调用方集中处理。
 */
sealed interface ProfileAction {
    /** 订阅与续费：放在第一位，因为"到期没续费"会直接导致所有管控失效 */
    data object Subscription : ProfileAction
    data object Children : ProfileAction
    data object Members : ProfileAction
    data object EyeCare : ProfileAction
    data object Settings : ProfileAction
    data object About : ProfileAction
    /** 服务器地址：在「我的」页即可修改，不必退出登录 */
    data object ServerAddress : ProfileAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onLogout: () -> Unit,
    onAction: (ProfileAction) -> Unit,
    /** 当前服务器地址，作为「服务器地址」条目的副标题展示 */
    serverUrl: String = "",
    /** 保存服务器地址（改完立即生效） */
    onSaveServerUrl: (String) -> Unit = {},
    /** 当前主题模式 */
    themeMode: ThemeMode = ThemeMode.MINT,
    /** 切换主题（写入持久化并触发整树重组） */
    onThemeChange: (ThemeMode) -> Unit = {},
    /** 深色模式三态（跟随系统/浅色/深色） */
    darkMode: DarkMode = DarkMode.SYSTEM,
    /** 切换深色模式（写入持久化并触发整树重组） */
    onDarkModeChange: (DarkMode) -> Unit = {}
) {
    val context = LocalContext.current
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showServerConfig by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { TopAppBar(title = { Text("我的") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 必须可滚动：功能项变多后（比如新增"应用管控"入口），
                // 不可滚动会把底部内容整块裁掉，用户根本看不到「退出登录」。
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 头部
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("家长用户", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("家庭守护 · 已登录", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // 外观（主题）切换：默认「青空薄荷」，另提供「静谧蓝灰」「晨雾奶油」两款浅色主题。
            // 切换仅改变视觉令牌，不触碰任何业务逻辑；选择落盘后重启保持。
            ThemeSelectorCard(
                themeMode = themeMode,
                onThemeChange = onThemeChange,
                darkMode = darkMode,
                onDarkModeChange = onDarkModeChange
            )

            val items = listOf(
                ProfileItem(
                    "订阅与续费", Icons.Default.CardMembership, "查看套餐与有效期",
                    ProfileAction.Subscription
                ),
                ProfileItem("我的孩子", Icons.Default.ChildCare, "管理被守护设备", ProfileAction.Children),
                // 家庭成员 / 设置目标页尚未实现，置灰并标注"敬请期待"，避免死入口误导。
                ProfileItem("家庭成员", Icons.Default.People, "敬请期待", ProfileAction.Members, enabled = false),
                ProfileItem("护眼设置", Icons.Default.RemoveRedEye, "蓝光 / 距离 / 姿势", ProfileAction.EyeCare),
                ProfileItem("设置", Icons.Default.Settings, "敬请期待", ProfileAction.Settings, enabled = false),
                // 版本号取 BuildConfig，避免每次发版都漏改这里的硬编码字符串
                ProfileItem(
                    "关于", Icons.Default.Info,
                    "版本 ${com.padguard.parent.BuildConfig.VERSION_NAME}",
                    ProfileAction.About
                ),
                ProfileItem(
                    "服务器地址", Icons.Default.Dns,
                    if (serverUrl.isBlank()) "未设置" else serverUrl,
                    ProfileAction.ServerAddress
                )
            )
            items.forEach { item ->
                ProfileListItem(item = item, onClick = {
                    // 服务器地址走本地弹窗（登录后也能改，不依赖 onAction 导航）
                    if (item.action == ProfileAction.ServerAddress) showServerConfig = true
                    else onAction(item.action)
                })
            }

            // 退出登录紧跟功能项，不再用 Spacer(weight(1f)) 把它压到屏幕最底部。
            // 之前那样做：小屏机型上按钮被挤出可视区完全看不见，而父容器又不可滚动，
            // 用户会以为"这个 App 根本没有退出功能"。
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
            OutlinedButton(
                onClick = { showLogoutConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("退出登录")
            }
            Text(
                // 服务端在登出时会把该账号的令牌版本 +1，**所有**已签发令牌（含其他手机）同时失效。
                // 说明文字必须把这一点讲清楚：家长往往在多台手机上登录过，
                // 不提前说明就会在另一台手机上遇到"莫名其妙要重新登录"，进而怀疑账号被盗。
                text = "退出后本机需重新登录，其他设备上的登录状态也会一并失效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }

    if (showLogoutConfirm) {
        // 二次确认：登出后要重新输密码，误点代价不对称。
        // 同时说清后果，避免家长以为登出=解绑设备。
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出登录？") },
            text = {
                Text(
                    "退出后需要重新输入账号密码才能查看被控设备。" +
                        "该账号在其他手机上也会退出登录，但不会解绑任何设备，" +
                        "孩子的管控规则照常生效。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    onLogout()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showServerConfig) {
        ServerConfigDialog(
            initial = serverUrl,
            onDismiss = { showServerConfig = false },
            onSave = { url ->
                onSaveServerUrl(url)
                Toast.makeText(context, "已保存，立即生效", Toast.LENGTH_SHORT).show()
                showServerConfig = false
            }
        )
    }
}

private data class ProfileItem(
    val title: String,
    val icon: ImageVector,
    val subtitle: String,
    val action: ProfileAction,
    // 未实现的目标页（家庭成员 / 设置）置灰禁用，避免"点了没反应"误导用户。
    val enabled: Boolean = true
)

@Composable
private fun ProfileListItem(item: ProfileItem, onClick: () -> Unit) {
    val alpha = if (item.enabled) 1f else 0.4f
    SoftCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (item.enabled) onClick else ({})
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = item.enabled, onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                )
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.enabled) 0.6f else 0.3f)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.enabled) 0.3f else 0.15f)
            )
        }
    }
}

/**
 * 外观（主题）切换卡片：三款浅色主题三选一，含色板预览。
 *
 * 设计要点：
 * - 色板预览直接取自 [tokensFor]，与页面运行期令牌同源，避免重复硬编码导致漂移。
 * - 选中态用 MaterialTheme.colorScheme.primary 描边 + 对勾，未选中弱化，视觉反馈明确。
 * - 仅改变视觉令牌，不触发任何业务逻辑；[onThemeChange] 写入持久化后由根层触发整树重组。
 */
@Composable
private fun ThemeSelectorCard(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    darkMode: DarkMode = DarkMode.SYSTEM,
    onDarkModeChange: (DarkMode) -> Unit = {}
) {
    val options = listOf(
        ThemeMode.MINT to "青空薄荷",
        ThemeMode.BLUEGRAY to "静谧蓝灰",
        ThemeMode.CREAM to "晨雾奶油"
    )
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "外观",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                options.forEach { (mode, label) ->
                    val selected = themeMode == mode
                    val tokens = tokensFor(mode)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onThemeChange(mode) },
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.pageBg,
                        border = BorderStroke(
                            width = 2.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                        // 色板预览：页面底色 + 主色块，直观表达主题气质
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(tokens.primary)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        if (selected) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        }
                    }
                }
            }

            // 深色模式三态：与色板正交。深色启用时统一使用深色令牌（忽略上方色板）。
            DarkModeRow(darkMode = darkMode, onDarkModeChange = onDarkModeChange)
        }
    }
}

/**
 * 深色模式选择行（跟随系统 / 浅色 / 深色）。
 *
 * 默认「跟随系统」：尊重系统级深色与省电设置，也符合 Android 设计规范；
 * 深色启用时 [com.padguard.parent.presentation.ui.theme.tokensFor] 的浅色令牌被
 * 深色令牌整体替换，页面零改动。
 */
@Composable
private fun DarkModeRow(
    darkMode: DarkMode,
    onDarkModeChange: (DarkMode) -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    val options = listOf(
        DarkMode.SYSTEM to "跟随系统",
        DarkMode.LIGHT to "浅色",
        DarkMode.DARK to "深色"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "深色模式",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(modifier = Modifier.padding(3.dp)) {
                options.forEach { (mode, label) ->
                    val selected = darkMode == mode
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDarkModeChange(mode) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
