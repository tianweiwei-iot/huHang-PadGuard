package com.padguard.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padguard.domain.model.Device
import com.padguard.domain.model.DeviceOnlineStatus
import com.padguard.presentation.ui.theme.PadGuardColors

/**
 * 设备状态卡片（首页顶部）
 * 显示设备名、在线状态、版本号、电量等信息
 */
@Composable
fun DeviceStatusCard(
    device: Device,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 设备图标
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.TabletAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StatusChip(label = when (device.onlineStatus) {
                            DeviceOnlineStatus.ONLINE -> "在线"
                            DeviceOnlineStatus.OFFLINE -> "离线"
                            else -> "未知"
                        }, isActive = device.onlineStatus == DeviceOnlineStatus.ONLINE)
                        if (device.appVersion != null) {
                            StatusChip(label = "v${device.appVersion}", isActive = false)
                        }
                    }
                }
                if (device.batteryLevel != null) {
                    BatteryIndicator(level = device.batteryLevel)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, isActive: Boolean) {
    Surface(
        color = if (isActive) Color(0xFFE8F5E9) else PadGuardColors.SectionBg,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) PadGuardColors.SuccessGreen else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun BatteryIndicator(level: Int) {
    val color = when {
        level > 60 -> PadGuardColors.SuccessGreen
        level > 20 -> Color(0xFFFFA000)
        else -> PadGuardColors.WarningRed
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.BatteryFull, contentDescription = "电量", tint = color, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(2.dp))
        Text(text = "$level%", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/**
 * 功能网格项（用于"设备和应用限制"、"远程监控"等网格）
 */
@Composable
fun FeatureGridItem(
    feature: FeatureItem,
    onClick: () -> Unit,
    isLargeIcon: Boolean = false
) {
    val iconSize = if (isLargeIcon) 32.dp else 24.dp
    val containerSize = if (isLargeIcon) 56.dp else 48.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(4.dp)
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(containerSize)
                    .background(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(feature.icon, contentDescription = feature.name, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(iconSize))
            }
            if (feature.badge != null) {
                Surface(color = PadGuardColors.WarningRed, shape = RoundedCornerShape(4.dp), modifier = Modifier.offset(x = (-4).dp, y = 4.dp)) {
                    Text(text = feature.badge!!, style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp), color = Color.White, modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = feature.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 区块标题组件
 */
@Composable
fun SectionHeader(
    title: String,
    showManageButton: Boolean = false,
    onManageClick: () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.weight(1f))
        if (showManageButton) {
            TextButton(onClick = onManageClick, contentPadding = PaddingValues(0.dp)) {
                Text("管理孩子", color = MaterialTheme.colorScheme.primary)
            }
        }
        trailing?.invoke()
    }
}

/** 功能项数据类 */
data class FeatureItem(val name: String, val icon: ImageVector, val badge: String? = null)
