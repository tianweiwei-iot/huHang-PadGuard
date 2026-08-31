package com.padguard.presentation.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.padguard.domain.model.*
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.viewmodel.HomeUiState

/**
 * 首页仪表盘（现代 Material 3 风格，对标 Family Link / MS Family）
 *
 * 结构：
 * 1. 顶部标题栏
 * 2. 孩子/设备切换 pill（多设备横滑）
 * 3. 离线告警置顶（异常预警）
 * 4. 今日使用时长环卡（已用 / 剩余）
 * 5. 管控策略四大卡（时长 / 应用 / 上网 / 系统护眼）
 * 6. 远程监控快捷（实时画面 / 截屏 / 定位）
 * 7. 最新动态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onSelectDevice: (String) -> Unit,
    onDeviceClick: (String) -> Unit,
    onNavigateToControl: (String) -> Unit,
    onNavigateToMonitor: (String) -> Unit,
    onRefresh: () -> Unit = {}
) {
    val devices = uiState.devices
    val selectedDevice = uiState.selectedDevice
    val deviceId = selectedDevice?.id.orEmpty()

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // === 1. 顶部标题栏 ===
            item { HomeTopBar(userName = uiState.userName) }

            // === 2. 孩子/设备切换 pill ===
            if (devices.isNotEmpty()) {
                item {
                    DeviceSwitchPill(
                        devices = devices,
                        selectedId = uiState.selectedDeviceId,
                        onSelect = onSelectDevice
                    )
                }
            }

            // === 3. 离线告警置顶 ===
            if (selectedDevice?.onlineStatus == DeviceOnlineStatus.OFFLINE) {
                item { OfflineAlertBanner(deviceName = selectedDevice.name) }
            }

            // === 4. 今日使用时长环卡 ===
            item {
                if (selectedDevice != null) {
                TodayUsageRingCard(
                    usedMinutes = uiState.todayUsage?.totalUsageMinutes ?: 0,
                    dailyLimitMinutes = uiState.dailyLimitMinutes,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                }
            }

            // === 5. 管控策略四大卡 ===
            item { SectionTitle(text = "管控策略") }
            item {
                ControlCategoryGrid(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    onCategoryClick = { if (deviceId.isNotEmpty()) onNavigateToControl(deviceId) }
                )
            }

            // === 6. 远程监控快捷 ===
            item { SectionTitle(text = "远程监控", withTopPadding = true) }
            item {
                MonitorQuickRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    onMonitorClick = { if (deviceId.isNotEmpty()) onNavigateToMonitor(deviceId) }
                )
            }

            // === 7. 最新动态 ===
            item { SectionTitle(text = "最新动态", withTopPadding = true) }
            item {
                LatestActivityCard(
                    activityText = uiState.latestActivity ?: "暂无新动态",
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

// ==================== 子组件 ====================

@Composable
private fun HomeTopBar(userName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "护航管控",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "家庭守护 · $userName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
        IconButton(onClick = { }) {
            Icon(Icons.Default.Notifications, contentDescription = "通知", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun DeviceSwitchPill(
    devices: List<Device>,
    selectedId: String?,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        devices.forEach { device ->
            val selected = device.id == selectedId
            val online = device.onlineStatus == DeviceOnlineStatus.ONLINE
            Surface(
                onClick = { onSelect(device.id) },
                shape = RoundedCornerShape(20.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                border = if (!selected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)) else null
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (online) PadGuardColors.OnlineGreen else PadGuardColors.OfflineGray,
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = device.name,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineAlertBanner(deviceName: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = PadGuardColors.WarningRed.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.WifiOff, contentDescription = null, tint = PadGuardColors.WarningRed)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$deviceName 当前离线，部分功能暂不可用",
                color = PadGuardColors.WarningRed,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun TodayUsageRingCard(
    usedMinutes: Int,
    dailyLimitMinutes: Int,
    modifier: Modifier = Modifier
) {
    val safeLimit = dailyLimitMinutes.coerceAtLeast(1)
    val remaining = (safeLimit - usedMinutes).coerceAtLeast(0)
    val fraction = (usedMinutes.toFloat() / safeLimit.toFloat()).coerceIn(0f, 1f)
    val overLimit = usedMinutes > safeLimit
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(96.dp),
                    strokeWidth = 10.dp,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${usedMinutes / 60}h${usedMinutes % 60}m",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("已用", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(modifier = Modifier.width(20.dp))
            Column {
                Text("今日屏幕使用", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (overLimit) "已超出限额 ${usedMinutes - safeLimit} 分钟" else "剩余 ${remaining / 60}h${remaining % 60}m",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("每日限额 ${safeLimit} 分钟", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ==================== 管控四大卡 ====================

private val controlCategories = listOf(
    ControlCategory("时长管控", "每日时长与时段", Icons.Default.Schedule),
    ControlCategory("应用管控", "限制与黑名单", Icons.Default.AppBlocking),
    ControlCategory("上网管控", "网址过滤与浏览器", Icons.Default.Block),
    ControlCategory("系统护眼", "锁屏 · 护眼 · 模式", Icons.Default.RemoveRedEye)
)

private data class ControlCategory(val title: String, val subtitle: String, val icon: ImageVector)

@Composable
private fun ControlCategoryGrid(modifier: Modifier = Modifier, onCategoryClick: () -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false
    ) {
        items(controlCategories) { cat ->
            ControlCategoryCard(cat = cat, onClick = onCategoryClick)
        }
    }
}

@Composable
private fun ControlCategoryCard(cat: ControlCategory, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(cat.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(cat.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(cat.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

// ==================== 监控快捷 ====================

private val monitorQuickItems = listOf(
    MonitorQuick("实时画面", Icons.AutoMirrored.Filled.ScreenShare),
    MonitorQuick("截屏", Icons.Default.Screenshot),
    MonitorQuick("定位", Icons.Default.LocationOn)
)

private data class MonitorQuick(val title: String, val icon: ImageVector)

@Composable
private fun MonitorQuickRow(modifier: Modifier = Modifier, onMonitorClick: () -> Unit) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        monitorQuickItems.forEach { item ->
            MonitorQuickCard(item = item, modifier = Modifier.weight(1f), onClick = onMonitorClick)
        }
    }
}

@Composable
private fun MonitorQuickCard(item: MonitorQuick, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

// ==================== 通用 ====================

@Composable
private fun SectionTitle(text: String, withTopPadding: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (withTopPadding) 8.dp else 0.dp)
    )
}

@Composable
private fun LatestActivityCard(activityText: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = activityText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
