package com.padguard.presentation.ui.device

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.padguard.domain.model.DeviceOnlineStatus
import com.padguard.presentation.ui.components.FeatureGridItem
import com.padguard.presentation.ui.components.FeatureItem
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.viewmodel.DeviceDetailViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * 设备详情页（纯家庭场景）
 *
 * 布局：
 * 1. 顶部栏（返回 + 设备名）
 * 2. 设备信息头卡（在线状态 / 电量 / 型号）
 * 3. 即时远程指令（一键锁屏 / 重启 / 定位 / 看屏 / 授权）
 * 4. 今日使用时长
 * 5. 实时位置
 * 6. 管控策略入口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    deviceId: String,
    onBack: () -> Unit,
    onNavigateToControl: (String) -> Unit,
    onViewScreen: (String) -> Unit,
    viewModel: DeviceDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiState.collectLatest { if (it.toast != null) { snackbarHostState.showSnackbar(it.toast); viewModel.clearToast() } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.device?.name ?: "设备详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading && uiState.device == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            uiState.device?.let { device -> DeviceHeaderCard(device) }

            QuickCommandGrid(
                onCommand = { cmd ->
                    when (cmd) {
                        "一键锁屏" -> viewModel.lockScreen()
                        "重启" -> viewModel.runCommand("重启设备")
                        "定位" -> viewModel.runCommand("实时定位")
                        "看屏" -> onViewScreen(deviceId)
                        "授权" -> viewModel.runCommand("临时授权")
                    }
                }
            )

            TodayUsageDetailCard(uiState.todayUsage?.totalUsageMinutes ?: 0)

            val detailLocation = uiState.location
            if (detailLocation != null) {
                LocationCard(address = detailLocation.address, lat = detailLocation.latitude, lng = detailLocation.longitude)
            }

            Button(
                onClick = { onNavigateToControl(deviceId) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("管控策略（时长 / 应用 / 上网 / 系统）")
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DeviceHeaderCard(device: com.padguard.domain.model.Device) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.TabletAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusChip(
                        label = when (device.onlineStatus) {
                            DeviceOnlineStatus.ONLINE -> "在线"
                            DeviceOnlineStatus.OFFLINE -> "离线"
                            else -> "未知"
                        },
                        isActive = device.onlineStatus == DeviceOnlineStatus.ONLINE
                    )
                    device.model?.let { StatusChip(label = it, isActive = false) }
                    device.appVersion?.let { StatusChip(label = "v$it", isActive = false) }
                }
            }
            device.batteryLevel?.let {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val color = when {
                        it > 60 -> PadGuardColors.SuccessGreen
                        it > 20 -> Color(0xFFFFA000)
                        else -> PadGuardColors.WarningRed
                    }
                    Icon(Icons.Default.BatteryFull, contentDescription = "电量", tint = color, modifier = Modifier.size(26.dp))
                    Text("$it%", style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, isActive: Boolean) {
    Surface(color = if (isActive) Color(0xFFE8F5E9) else PadGuardColors.SectionBg, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) PadGuardColors.SuccessGreen else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun QuickCommandGrid(onCommand: (String) -> Unit) {
    val commands = listOf(
        FeatureItem("一键锁屏", Icons.Default.Lock),
        FeatureItem("重启", Icons.Default.RestartAlt),
        FeatureItem("定位", Icons.Default.MyLocation),
        FeatureItem("看屏", Icons.AutoMirrored.Filled.ScreenShare),
        FeatureItem("授权", Icons.Default.VerifiedUser)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("即时远程指令", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false
            ) {
                items(commands, key = { it.name }) { FeatureGridItem(feature = it, onClick = { onCommand(it.name) }) }
            }
        }
    }
}

@Composable
private fun TodayUsageDetailCard(usageMinutes: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.HourglassBottom, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("今日使用时长", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${usageMinutes / 60}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(" 小时 ${usageMinutes % 60} 分", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun LocationCard(address: String?, lat: Double, lng: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).background(PadGuardColors.SectionBg, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = PadGuardColors.OnlineGreen, modifier = Modifier.size(26.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("实时位置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                Text(
                    address ?: "未知地址",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    String.format("%.4f, %.4f", lat, lng),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        }
    }
}
