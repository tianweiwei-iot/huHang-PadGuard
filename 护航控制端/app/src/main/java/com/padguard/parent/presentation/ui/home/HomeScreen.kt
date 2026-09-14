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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.padguard.domain.model.*
import com.padguard.presentation.ui.components.SoftCard
import com.padguard.presentation.ui.components.SoftIconBadge
import com.padguard.presentation.ui.theme.OutlineSoft
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.util.TimeFormat
import com.padguard.presentation.viewmodel.HomeUiState

/**
 * 首页仪表盘（清爽卡片风，参考 WPS「我的」页版式；视觉基准：docs/UI设计提示词.md）
 *
 * 结构：
 * 1. 顶部标题栏
 * 2. 孩子/设备切换 pill（多设备横滑）
 * 3. 离线告警置顶（异常预警）
 * 4. 今日平板使用情况环卡（已用 / 剩余 / 限额 + 应用记录）— 深紫渐变 Hero 卡
 * 5. 实时管控三卡（屏幕监控 / 信息发布 / 定位）— 白卡 + 淡彩圆底图标
 * 6. 管控策略四大卡（时长 / 应用 / 上网 / 系统护眼）— 白卡 + 淡彩圆底图标
 * 7. 最新动态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onSelectDevice: (String) -> Unit,
    onDeviceClick: (String) -> Unit,
    onNavigateToControl: (String) -> Unit,
    onNavigateToScreenMonitor: (String) -> Unit,
    onNavigateToMessagePublish: (String) -> Unit,
                    onNavigateToLocation: (String) -> Unit,
                    onNavigateToUsageDetail: (String) -> Unit = {},
    onNavigateToUsageSettings: (String) -> Unit = {},
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
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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

            // === 4. 今日平板使用情况环卡 + 应用记录 ===
            item {
                if (selectedDevice != null) {
                    TodayUsageModule(
                        usedMinutes = uiState.todayUsage?.totalUsageMinutes ?: 0,
                        dailyLimitMinutes = uiState.dailyLimitMinutes,
                        appUsages = uiState.todayUsage?.appUsages ?: emptyList(),
                        onModuleClick = { if (deviceId.isNotEmpty()) onNavigateToUsageDetail(deviceId) },
                        onLimitClick = { if (deviceId.isNotEmpty()) onNavigateToUsageSettings(deviceId) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // === 5. 实时管控三卡（屏幕监控 / 信息发布 / 定位） ===
            item { SectionTitle(text = "实时管控") }
            item {
                RealtimeControlRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    onScreenMonitorClick = { if (deviceId.isNotEmpty()) onNavigateToScreenMonitor(deviceId) },
                    onMessagePublishClick = { if (deviceId.isNotEmpty()) onNavigateToMessagePublish(deviceId) },
                    onLocationClick = { if (deviceId.isNotEmpty()) onNavigateToLocation(deviceId) }
                )
            }

            // === 6. 管控策略四大卡 ===
            item { SectionTitle(text = "管控策略", withTopPadding = true) }
            item {
                ControlCategoryGrid(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    onTimeControlClick = { if (deviceId.isNotEmpty()) onNavigateToUsageSettings(deviceId) },
                    onOtherCategoryClick = { if (deviceId.isNotEmpty()) onNavigateToControl(deviceId) }
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
                color = PadGuardColors.TextPrimary
            )
            Text(
                text = "家庭守护 · $userName",
                style = MaterialTheme.typography.bodySmall,
                color = PadGuardColors.TextSecondary
            )
        }
        // 通知按钮：白底圆形轻投影
        Box(
            modifier = Modifier
                .size(40.dp)
                .shadow(elevation = 2.dp, shape = CircleShape, spotColor = PadGuardColors.CardShadow, ambientColor = PadGuardColors.CardShadow)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = "通知",
                tint = PadGuardColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
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
            // 选中：深紫渐变实底白字（参考会员横幅）；未选中：白底 + 柔和描边
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .then(
                        if (selected) {
                            Modifier.background(brush = PadGuardColors.HeroGradient)
                        } else {
                            Modifier
                                .background(Color.White)
                                .border(1.dp, OutlineSoft, RoundedCornerShape(20.dp))
                        }
                    )
                    .clickable { onSelect(device.id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                        color = if (selected) Color.White else PadGuardColors.TextPrimary,
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
        shape = RoundedCornerShape(16.dp),
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

/**
 * 今日使用看板（Hero 卡：深紫渐变实底，参考会员横幅气质；紧凑版布局规则保持不变）：
 * - 环形进度 60dp，环内仅展示百分比，时长明细移至右侧列
 * - 卡片内边距 12dp，应用记录行高约 32dp，最多展示 3 条
 */
@Composable
private fun TodayUsageModule(
    usedMinutes: Int,
    dailyLimitMinutes: Int,
    appUsages: List<com.padguard.domain.model.AppUsage>,
    onModuleClick: () -> Unit,
    onLimitClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeLimit = dailyLimitMinutes.coerceAtLeast(1)
    val remaining = (safeLimit - usedMinutes).coerceAtLeast(0)
    val fraction = (usedMinutes.toFloat() / safeLimit.toFloat()).coerceIn(0f, 1f)
    val overLimit = usedMinutes > safeLimit

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color(0xFF2F2470).copy(alpha = 0.28f),
                spotColor = Color(0xFF2F2470).copy(alpha = 0.28f)
            )
            .clip(RoundedCornerShape(16.dp))
            .background(brush = PadGuardColors.HeroGradient)
            .clickable(onClick = onModuleClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.size(60.dp),
                        strokeWidth = 8.dp,
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.25f)
                    )
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text("今日平板使用情况", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "已用 ${TimeFormat.formatDurationFromMinutes(usedMinutes)}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        if (overLimit) "已超出限额 ${TimeFormat.formatDurationFromMinutes(usedMinutes - safeLimit)}" else "剩余 ${TimeFormat.formatDurationFromMinutes(remaining)}",
                        color = Color.White.copy(alpha = 0.95f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "每日限额 ${TimeFormat.formatDurationFromMinutes(safeLimit)} · 点此调整",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clickable(onClick = onLimitClick)
                            .padding(vertical = 1.dp),
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    )
                }
            }

            if (appUsages.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.25f))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "应用使用记录",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(3.dp))

                val sorted = appUsages.sortedByDescending {
                    it.usageSeconds.takeIf { s -> s > 0 } ?: (it.usageMinutes * 60)
                }
                val shown = sorted.take(3)
                shown.forEachIndexed { index, app ->
                    AppUsageLineRow(app = app)
                    if (index != shown.lastIndex) {
                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.15f),
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }

                if (sorted.size > 3) {
                    Text(
                        text = "点击查看全部 · 共 ${appUsages.size} 个应用",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

/**
 * 应用使用记录单行布局（紧凑版：行高约 32dp）：
 *
 *   [●图标] 应用名称 | 开始时间 → 结束时间 | 应用时长
 *
 * - 名称 / 开始时间 / 结束时间 / 时长 四列全部左对齐在同一基线上
 * - 时间统一 XX时YY分ZZ秒；时长统一 X小时Y分钟Z秒
 * - 左侧 26dp 图标：优先用后端返回的 iconUrl（Coil 加载真实 APK 图标），加载失败时回退到字符徽章
 */
@Composable
private fun AppUsageLineRow(app: com.padguard.domain.model.AppUsage) {
    val startClock = TimeFormat.formatClock(app.startTime)
    val endClock = TimeFormat.formatClock(app.endTime)
    val duration = TimeFormat.formatAppUsageDuration(app.usageSeconds, app.usageMinutes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(packageName = app.packageName, appName = app.appName, iconUrl = app.iconUrl)

        Spacer(modifier = Modifier.width(8.dp))

        // 名称（左对齐，固定宽度，所有行基线对齐）
        Text(
            text = app.appName,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(72.dp)
        )

        // 开始时间（左对齐，固定宽度）
        Text(
            text = startClock,
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(84.dp)
        )

        // 箭头（视觉连接）
        Text(
            text = "→",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelSmall
        )

        // 结束时间（左对齐）
        Text(
            text = endClock,
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(84.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        // 时长（右端，但仍在同一基线）
        Text(
            text = duration,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.End
        )
    }
}

/**
 * 应用图标：优先用后端返回的 iconUrl（Coil 加载真实 APK 图标）；
 * 加载失败 / 未提供时回退到根据包名/应用名映射的字符徽章。
 */
@Composable
private fun AppIcon(packageName: String, appName: String, iconUrl: String?) {
    val badge = appBadgeStyle(packageName, appName)
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        if (!iconUrl.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = iconUrl,
                contentDescription = appName,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(badge.color),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badge.mark,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 兜底图标样式（无 iconUrl 时使用）：根据 packageName / appName 匹配，
 * 返回单字符 + 主色的圆形徽章。
 */
private data class AppBadgeStyle(val mark: String, val color: Color)

private fun appBadgeStyle(packageName: String, appName: String): AppBadgeStyle {
    val p = packageName.lowercase()
    return when {
        p.contains("mobileqq") || p.contains("qq") -> AppBadgeStyle("🐧", Color(0xFF12B7F5))
        p.contains("tencent.mm") || p.contains("wechat") || appName.contains("微信") -> AppBadgeStyle("微", Color(0xFF07C160))
        p.contains("aweme") || p.contains("douyin") || appName.contains("抖音") -> AppBadgeStyle("抖", Color(0xFFFE2C55))
        p.contains("qqlive") || p.contains("tencent.video") || appName.contains("腾讯视频") -> AppBadgeStyle("▶", Color(0xFFFF7028))
        p.contains("netease") || appName.contains("荒野") -> AppBadgeStyle("野", Color(0xFFE3344A))
        p.contains("kuaiya") || appName.contains("快影") -> AppBadgeStyle("K", Color(0xFF1E90FF))
        p.contains("tencent") -> AppBadgeStyle("T", Color(0xFF1976FF))
        else -> {
            val ch = appName.firstOrNull()?.toString() ?: "·"
            AppBadgeStyle(ch, Color(0xFF607D8B))
        }
    }
}

// ==================== 管控四大卡 ====================

/**
 * 四大管控卡：第一个是「时间管控」，点击进入平板使用时间设置页（与首页"每日限额"标签同源）。
 * 其余三类仍进入 ControlPolicyScreen。
 */
private val controlCategories = listOf(
    ControlCategory("时间管控", "每日时长与时段", Icons.Default.Schedule),
    ControlCategory("应用管控", "限制与黑名单", Icons.Default.AppBlocking),
    ControlCategory("上网管控", "网址过滤与浏览器", Icons.Default.Block),
    ControlCategory("系统护眼", "锁屏 · 护眼 · 模式", Icons.Default.RemoveRedEye)
)

private data class ControlCategory(val title: String, val subtitle: String, val icon: ImageVector)

@Composable
private fun ControlCategoryGrid(
    modifier: Modifier = Modifier,
    onTimeControlClick: () -> Unit,
    onOtherCategoryClick: () -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = false
    ) {
        items(controlCategories) { cat ->
            // 索引 0 = 时间管控，跳设置页；其他跳管控策略页；图标圆底按索引三色轮换
            val index = controlCategories.indexOf(cat)
            val onClick = if (index == 0) onTimeControlClick else onOtherCategoryClick
            ControlCategoryCard(cat = cat, tintIndex = index, onClick = onClick)
        }
    }
}

@Composable
private fun ControlCategoryCard(cat: ControlCategory, tintIndex: Int, onClick: () -> Unit) {
    SoftCard(onClick = onClick) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            SoftIconBadge(icon = cat.icon, tintIndex = tintIndex)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                cat.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = PadGuardColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                cat.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = PadGuardColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ==================== 实时管控三卡 ====================

private val realtimeControlItems = listOf(
    RealtimeControl("屏幕监控", "实时画面截屏录屏", Icons.AutoMirrored.Filled.ScreenShare),
    RealtimeControl("信息发布", "文字/图片/视频/声音", Icons.Default.Campaign),
    RealtimeControl("定位", "围栏与实时轨迹", Icons.Default.LocationOn)
)

private data class RealtimeControl(val title: String, val subtitle: String, val icon: ImageVector)

@Composable
private fun RealtimeControlRow(
    modifier: Modifier = Modifier,
    onScreenMonitorClick: () -> Unit,
    onMessagePublishClick: () -> Unit,
    onLocationClick: () -> Unit
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        realtimeControlItems.forEachIndexed { index, item ->
            val onClick = when (index) {
                0 -> onScreenMonitorClick
                1 -> onMessagePublishClick
                else -> onLocationClick
            }
            RealtimeControlCard(item = item, tintIndex = index, modifier = Modifier.weight(1f), onClick = onClick)
        }
    }
}

@Composable
private fun RealtimeControlCard(
    item: RealtimeControl,
    tintIndex: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SoftCard(onClick = onClick, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SoftIconBadge(icon = item.icon, tintIndex = tintIndex)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = PadGuardColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                item.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = PadGuardColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ==================== 通用 ====================

/** 区块标题：深蓝黑加粗（参考"切换账号"式平实标题，不用装饰条）。 */
@Composable
private fun SectionTitle(text: String, withTopPadding: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = PadGuardColors.TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (withTopPadding) 4.dp else 0.dp)
    )
}

@Composable
private fun LatestActivityCard(activityText: String, modifier: Modifier = Modifier) {
    SoftCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SoftIconBadge(
                icon = Icons.Default.History,
                tintIndex = 1,
                size = 36.dp,
                iconSize = 18.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = activityText,
                style = MaterialTheme.typography.bodyMedium,
                color = PadGuardColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
