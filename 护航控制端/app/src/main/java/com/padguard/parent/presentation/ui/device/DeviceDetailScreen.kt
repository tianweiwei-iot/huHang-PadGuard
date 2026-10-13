package com.padguard.parent.presentation.ui.device

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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import android.graphics.BitmapFactory
import android.net.Uri
import com.padguard.parent.domain.model.Device
import com.padguard.parent.domain.model.DeviceOnlineStatus
import com.padguard.parent.presentation.ui.components.FeatureGridItem
import com.padguard.parent.presentation.ui.components.FeatureItem
import com.padguard.parent.presentation.ui.components.SoftCard
import com.padguard.parent.presentation.ui.theme.PadGuardColors
import com.padguard.parent.presentation.util.TimeFormat
import com.padguard.parent.presentation.viewmodel.DeviceDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

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
    onNavigateToAppManage: (String) -> Unit = {},
    viewModel: DeviceDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiState.collectLatest { if (it.toast != null) { snackbarHostState.showSnackbar(it.toast); viewModel.clearToast() } }
    }

    Scaffold(
        containerColor = Color.Transparent,
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
            uiState.device?.let { device ->
                DeviceHeaderCard(device)
                ChildProfileCard(device)
            }

            // 原网格中「重启」「授权」与「定位」一样只弹假 toast（一个请求都不发），
            // 而同页「一键锁屏」是真链路——同页真假并存最伤信任。现：
            // - 定位：接真实指令链路（viewModel.locateNow → /v1/monitor/{id}/locate → LOCATE 指令）；
            // - 重启 / 授权：移出网格，改为下方置灰说明（服务端无 REBOOT 指令；
            //   临时授权需选时长，正确入口在「管控策略」页与解锁工单中心）。
            QuickCommandGrid(
                locating = uiState.locating,
                onCommand = { cmd ->
                    when (cmd) {
                        "一键锁屏" -> viewModel.lockScreen()
                        "定位" -> viewModel.locateNow()
                        "看屏" -> onViewScreen(deviceId)
                    }
                }
            )

            // 未成年人模式（分龄合规底座）：一键开启即套用分龄时长 / 护眼 / 宵禁基线
            MinorModeCard(
                minorMode = uiState.minorMode,
                ageBandDefaults = uiState.ageBandDefaults,
                effectiveDailyLimitMinutes = uiState.effectiveDailyLimitMinutes,
                busy = uiState.minorModeBusy,
                onToggle = viewModel::setMinorModeEnabled,
                onSelectBand = viewModel::selectAgeBand,
                onToggleCurfew = viewModel::setCurfewEnabled,
                onGrantExemption = viewModel::grantExemption
            )

            // 受管控水印：替代"关不掉的系统录屏红点"的可控提示手段，
            // 卡片内已写明系统限制，避免家长把两者混淆。
            WatermarkCard(
                enabled = uiState.watermarkEnabled,
                content = uiState.watermarkContent,
                busy = uiState.watermarkBusy,
                onApply = viewModel::setWatermark
            )

            // 操作密码闸门（C 端软拦截方案）：开启后孩子改动设备须输入家长密码。
            // 见 GuardPasswordCard 注释中的能力边界说明。
            GuardPasswordCard(
                set = uiState.guardPasswordSet,
                busy = uiState.guardPasswordBusy,
                onSet = { viewModel.setGuardPassword(it) },
                onClear = { viewModel.setGuardPassword("", clear = true) }
            )

            // 引导：设好操作密码后，若还希望"打开受管控应用也要密码"，
            // 必须再到「应用管理」开启『打开应用也要密码』开关——两步分散在两页，极易漏掉第二步。
            // 这里把两步显式串成引导，降低遗漏率。
            if (uiState.guardPasswordSet) {
                LaunchGuardGuideCard(onOpenAppManage = { onNavigateToAppManage(deviceId) })
            }

            TodayUsageDetailCard(uiState.todayUsage?.totalUsageMinutes ?: 0)

            val detailLocation = uiState.location
            if (detailLocation != null) {
                LocationCard(address = detailLocation.address, lat = detailLocation.latitude, lng = detailLocation.longitude)
            }

            // 设备管理五面板入口（权限 / 功能 / 应用分发 / 远程升级 / 数据统计）
            DeviceAdminEntryCard(onOpen = viewModel::openAdminSheet)

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

            // 应用监控 + 远程安装维护（已安装台账 / 挂起 / 卸载）
            OutlinedButton(
                onClick = { onNavigateToAppManage(deviceId) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Apps, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("应用管理（已安装 / 挂起 / 卸载）")
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // 设备管理面板（权限管理 / 功能管理 / 应用分发 / 远程升级 / 数据统计）
    DeviceAdminSheetHost(
        uiState = uiState,
        onClose = viewModel::closeAdminSheet,
        onUpdatePermissions = viewModel::updatePermissions,
        onUpdateFunctions = viewModel::updateFunctionConfig,
        onDispatchApp = { id -> viewModel.dispatchApp(id) },
        onForceDispatchApp = { id -> viewModel.dispatchApp(id, force = true) },
        onStartUpgrade = { id -> viewModel.startUpgrade(id) },
        onUploadApp = { bytes, meta, name -> viewModel.uploadApp(bytes, meta, name) },
        onDeleteApp = { id -> viewModel.deleteApp(id) },
        onUninstallApp = { pkg -> viewModel.uninstallDistributedApp(pkg) }
    )
}

/**
 * 引导卡：家长在「设备操作密码」里设好密码后，若还希望"打开受管控应用也要密码"，
 * 必须再到「应用管理」开启『打开应用也要密码』开关（两步分散在两页，极易漏掉第二步）。
 * 这里把两步显式串成引导，降低遗漏率。
 */
@Composable
private fun LaunchGuardGuideCard(onOpenAppManage: () -> Unit) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = PadGuardColors.PrimaryBlue,
                    modifier = Modifier.size(20.dp)
                )
                Text("还想让打开受管控应用也要密码？", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Text(
                "你已开启「设备操作密码」。若希望孩子打开受管控应用时也需输入此密码，" +
                    "请到「应用管理」开启『打开应用也要密码』开关——仅此开关开启后才生效" +
                    "（安装 / 卸载始终需要密码，无需开启此开关）。",
                fontSize = 12.sp,
                color = PadGuardColors.TextSecondary
            )
            Button(
                onClick = onOpenAppManage,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PadGuardColors.PrimaryBlue)
            ) {
                Icon(Icons.Default.Apps, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("去应用管理开启")
            }
        }
    }
}

@Composable
private fun DeviceHeaderCard(device: com.padguard.parent.domain.model.Device) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
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

/**
 * 孩子端自定义资料卡：展示孩子头像 / 姓名 / 昵称（实时同步自孩子端）。
 * 头像为服务端 URL，用零依赖的 Bitmap 流式解码加载；无头像时回退到姓名首字。
 */
@Composable
private fun ChildProfileCard(device: Device) {
    val hasProfile = !device.childName.isNullOrBlank() ||
            !device.childNickname.isNullOrBlank() || !device.childAvatar.isNullOrBlank()
    if (!hasProfile) return
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AsyncChildAvatar(
                source = device.childAvatar,
                letter = device.childName ?: device.childNickname ?: "",
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.childName ?: device.childNickname ?: "孩子",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (!device.childNickname.isNullOrBlank() && !device.childName.isNullOrBlank()) {
                    Text(
                        text = "昵称：${device.childNickname}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Text(
                    text = "孩子资料（来自孩子端设置）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }
        }
    }
}

/** 零依赖头像加载：source 为 http(s) URL，加载失败 / 为空时回退到姓名首字。 */
@Composable
private fun AsyncChildAvatar(source: String?, letter: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(source) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(source) {
        bitmap = null
        if (!source.isNullOrBlank()) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    java.net.URL(source).openStream()?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = letter.take(1).ifBlank { "孩" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
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
private fun QuickCommandGrid(locating: Boolean = false, onCommand: (String) -> Unit) {
    val commands = listOf(
        FeatureItem("一键锁屏", Icons.Default.Lock),
        FeatureItem("定位", Icons.Default.MyLocation),
        FeatureItem("看屏", Icons.AutoMirrored.Filled.ScreenShare)
    )
    SoftCard(modifier = Modifier.fillMaxWidth()) {
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
                items(commands, key = { it.name }) {
                    FeatureGridItem(feature = it, onClick = { if (it.name != "定位" || !locating) onCommand(it.name) })
                }
            }
            // 做不到的命令不装样子。远程重启无指令支持；临时授权需选时长。
            // 移出网格 + 明示真实情况与正确入口，杜绝"界面说成功、后端什么都没发生"。
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "远程重启：暂不支持（设备端无对应能力）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "临时授权：请在「管控策略」页选择解锁时长，或在解锁工单中心审批",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TodayUsageDetailCard(usageMinutes: Int) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.HourglassBottom, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("今日使用时长", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                Text(
                    text = TimeFormat.formatDurationFromMinutes(usageMinutes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun LocationCard(address: String?, lat: Double, lng: Double) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
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
