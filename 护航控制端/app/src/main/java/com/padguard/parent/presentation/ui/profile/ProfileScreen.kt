package com.padguard.presentation.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.ui.components.SoftCard

/**
 * 我的页（底部导航「我的」Tab）
 *
 * 条目点击统一通过 [onAction] 分发，避免在列表项内部散落静默空操作；
 * 各条目的真实目标（如「我的孩子」→设备 Tab、「护眼设置」→管控策略）由调用方集中处理。
 */
sealed interface ProfileAction {
    data object Children : ProfileAction
    data object Members : ProfileAction
    data object EyeCare : ProfileAction
    data object Settings : ProfileAction
    data object About : ProfileAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onLogout: () -> Unit,
    onAction: (ProfileAction) -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { TopAppBar(title = { Text("我的") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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

            val items = listOf(
                ProfileItem("我的孩子", Icons.Default.ChildCare, "管理被守护设备", ProfileAction.Children),
                ProfileItem("家庭成员", Icons.Default.People, "多人共管", ProfileAction.Members),
                ProfileItem("护眼设置", Icons.Default.RemoveRedEye, "蓝光 / 距离 / 姿势", ProfileAction.EyeCare),
                ProfileItem("设置", Icons.Default.Settings, "通用设置", ProfileAction.Settings),
                ProfileItem("关于", Icons.Default.Info, "版本 1.0.0", ProfileAction.About)
            )
            items.forEach { item ->
                ProfileListItem(item = item, onClick = { onAction(item.action) })
            }

            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("退出登录")
            }
        }
    }
}

private data class ProfileItem(
    val title: String,
    val icon: ImageVector,
    val subtitle: String,
    val action: ProfileAction
)

@Composable
private fun ProfileListItem(item: ProfileItem, onClick: () -> Unit) {
    SoftCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
        }
    }
}
