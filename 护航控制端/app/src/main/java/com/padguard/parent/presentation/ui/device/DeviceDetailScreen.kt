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
import com.padguard.parent.domain.model.AgreementStatus
import com.padguard.parent.domain.model.Device
import com.padguard.parent.domain.model.DeviceOnlineStatus
import com.padguard.parent.domain.model.ProtectionLevel
import com.padguard.parent.presentation.ui.components.FeatureGridItem
import com.padguard.parent.presentation.ui.components.FeatureItem
import com.padguard.parent.presentation.ui.components.HighRiskAction
import com.padguard.parent.presentation.ui.components.HighRiskActions
import com.padguard.parent.presentation.ui.components.HighRiskConfirmDialog
import com.padguard.parent.presentation.ui.components.SoftCard
import com.padguard.parent.presentation.ui.components.StaleDataHint
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
    onNavigateToNotificationRecord: (String) -> Unit = {},
    viewModel: DeviceDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // 高风险指令的待确认项（R-P3-2）：锁屏 / 暂停整机管控这类"一触即发"的操作先弹确认，
    // 确认前不下发任何指令 —— 详情页的快捷网格里它们紧挨着定位、看屏等常用按钮，易误触。
    var pendingRisk by remember { mutableStateOf<HighRiskAction?>(null) }
    val deviceLabel = uiState.device?.name

    LaunchedEffect(Unit) {
        // 必须用 collect 而不是 collectLatest：showSnackbar 会挂起约 4 秒，
        // collectLatest 会在任意一次无关的状态刷新（设备上下线、开关翻转）时把它取消掉。
        // 而这里展示的「设备执行失败：…」「超时未执行」正是家长判断要不要重试的唯一线索。
        // 只清除"刚展示的那一条"：展示期间若来了新提示，不能被上一轮的 clearToast 吞掉。
        viewModel.uiState.collect { s ->
            val msg = s.toast ?: return@collect
            snackbarHostState.showSnackbar(msg)
            if (viewModel.uiState.value.toast == msg) viewModel.clearToast()
        }
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

        // 加载失败（非 loading 且没有设备）：此前直接落到下方 Column，
        // 设备卡片全被 `?.let` 跳过，只剩指令网格与统计——家长看到半张空页，
        // 误以为"这设备没有信息"，而真正原因是接口失败。
        if (uiState.device == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "设备信息加载失败",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = uiState.error ?: "网络异常或设备不存在，请重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.loadDeviceDetail() }) { Text("重试") }
                }
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
                GuardianHealthCard(device)
                ProtectionLevelCard(device)
                AgreementStatusRow(uiState.agreement)
            }

            // 设备对象拿到了、但本次刷新失败：下面的守护状态 / 权限 chip 全是上一次的快照。
            // 这页是家长判断"管控还有没有效"的入口，绝不能让它看起来像实时状态。
            if (uiState.error != null) {
                StaleDataHint(
                    message = uiState.error,
                    lastSuccessAt = uiState.lastSuccessAt,
                    onRetry = { viewModel.loadDeviceDetail() }
                )
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
                        // 锁屏会让孩子的平板当场不可用，先二次确认再下发
                        "一键锁屏" -> pendingRisk = HighRiskActions.lockScreen(deviceLabel) { viewModel.lockScreen() }
                        "定位" -> viewModel.locateNow()
                        "看屏" -> onViewScreen(deviceId)
                        "显示管理台" -> viewModel.revealConsole()
                    }
                }
            )

            // 整机管控 暂停/恢复 主开关：真正释放 / 恢复对平板的全部管控（跨页面共享开关态）。
            // 只有「暂停」是高风险方向（管控全面失效）；恢复管控是收紧防护，无需确认。
            ManagementActiveCard(
                active = uiState.managementActive,
                busy = uiState.managementBusy,
                onToggle = { wantActive ->
                    if (wantActive) {
                        viewModel.setManagementActive(true)
                    } else {
                        pendingRisk = HighRiskActions.pauseManagement(deviceLabel) {
                            viewModel.setManagementActive(false)
                        }
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

            // A2：通知记录入口（孩子端代收的系统通知，仅包名 + 标题，不含正文）
            SoftCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToNotificationRecord(deviceId) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("通知记录", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "查看孩子端代收的系统通知（仅包名 + 标题）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                    Icon(
                        Icons.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    )
                }
            }

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

    HighRiskConfirmDialog(action = pendingRisk, onDismiss = { pendingRisk = null })

    // 设备管理面板（权限管理 / 功能管理 / 应用分发 / 远程升级 / 数据统计）
    DeviceAdminSheetHost(
        uiState = uiState,
        onClose = viewModel::closeAdminSheet,
        onUpdatePermissions = viewModel::updatePermissions,
        onUpdateFunctions = viewModel::updateFunctionConfig,
        onDispatchApp = { id -> viewModel.dispatchApp(id) },
        onForceDispatchApp = { id -> viewModel.dispatchApp(id, force = true) },
        onStartUpgrade = { id -> viewModel.startUpgrade(id) },
        onUploadApp = { apk, meta -> viewModel.uploadApp(apk, meta) },
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

/**
 * 使用授权协议签署凭证（说明书 §5.4 合规留存的管控端回显）。
 *
 * 措辞是刻意保守的：`signed=false` 只表示服务端**没有查到凭证**（旧版本孩子端未上报、
 * 日志尚未上行、设备易主清档），绝不能写成「未同意」——那会把"查不到"伪造成一个
 * 关于孩子行为的确定结论。查询失败时 [status] 为 null，整行不显示，
 * 不让一个次要合规接口的抖动在详情页里制造焦虑。
 */
@Composable
private fun AgreementStatusRow(status: AgreementStatus?) {
    if (status == null) return
    val detail = if (status.signed) {
        val at = status.signedAt?.let { TimeFormat.describeStamp(it) }
        "已阅已同意 · 协议 v${status.version.orEmpty()}" + (at?.let { " · $it" } ?: "")
    } else {
        "暂未查询到签署记录（旧版本设备或日志尚未同步）"
    }
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (status.signed) Icons.Default.CheckCircle else Icons.Default.Info,
                contentDescription = null,
                tint = if (status.signed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "使用授权协议",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * 守护健康度卡（P3 可观测性）：展示孩子端守护进程 / 无障碍服务存活与链路状态。
 * 数据来自服务端 devices 守护快照（孩子端随心跳上报）。守护失联（多为被强制停止）时给出醒目告警，
 * 引导家长重新打开孩子端。设备尚未上报（旧孩子端）时温和提示，不报错。
 */
@Composable
private fun GuardianHealthCard(device: Device) {
    val daemonAlive = device.daemonAlive
    val a11yAlive = device.a11yAlive
    val lost = device.daemonLost == true || daemonAlive == false
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = PadGuardColors.PrimaryBlue, modifier = Modifier.size(20.dp))
                Text("守护健康度", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            if (daemonAlive == null && a11yAlive == null) {
                Text(
                    "设备尚未上报守护状态（请将孩子端升级到最新版本）",
                    fontSize = 12.sp,
                    color = PadGuardColors.TextSecondary
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(label = "守护进程", isActive = daemonAlive == true)
                    StatusChip(label = "无障碍服务", isActive = a11yAlive == true)
                    device.daemonOnlineState?.let { state ->
                        val text = when (state) {
                            "ONLINE" -> "链路在线"
                            "MQTT_DOWN" -> "链路断开"
                            "MQTT_DOWN_HTTP_OK" -> "链路降级·监管中"
                            "HTTP_DOWN" -> "HTTPS异常·推送中"
                            "OFFLINE" -> "已离线"
                            else -> state
                        }
                        // 只要 HTTPS 骨干或传输通道任一存活，监管就未中断，chip 仍为"正常"色；
                        // 只有双通道皆断（OFFLINE）或传输通道彻底断（MQTT_DOWN 旧值）才标红。
                        val active = state == "ONLINE" || state == "MQTT_DOWN_HTTP_OK" || state == "HTTP_DOWN"
                        StatusChip(label = text, isActive = active)
                    }
                }
                if (lost) {
                    Text(
                        "⚠ 守护进程疑似失联（多为被强制停止）。管控可能在停止后被解除，请重新打开孩子端或重启设备。",
                        fontSize = 12.sp,
                        color = PadGuardColors.WarningRed
                    )
                }
                // 无障碍单独告警：它与守护进程是两条独立链路 —— 进程活着但无障碍被关/没连上时，
                // 看板若只显示一个红点，家长既不知道后果也不知道该做什么。
                // 注意 a11yAlive 为 null 是"孩子端版本过旧未上报"，不能当成 false 报警。
                if (a11yAlive == false) {
                    Text(
                        "⚠ 操作闸门无障碍服务未在运行：孩子可从系统设置绕过操作密码、卸载本应用。" +
                            "请在孩子端「我的」页按提示重新开启；若已开启仍显示异常，在该页点「重新连接」。",
                        fontSize = 12.sp,
                        color = PadGuardColors.WarningRed
                    )
                }
            }
        }
    }
}

/**
 * 防护等级卡：展示孩子端实际管控强度（软管控 / 设备所有者硬管控）。
 * 数据由服务端 devices.control_mode（孩子端随心跳上报的权限等级）安全推导，
 * 与场景模式(NORMAL/LEARNING/FOCUS)相互独立。
 */
@Composable
private fun ProtectionLevelCard(device: Device) {
    val hard = device.protectionLevel == ProtectionLevel.HARD
    val bg = if (hard) Color(0xFFE8F5E9) else Color(0xFFFFF8E1)
    val fg = if (hard) Color(0xFF1B5E20) else Color(0xFF8D6E00)
    val title = if (hard) "防护等级：设备所有者硬管控" else "防护等级：软管控（非设备所有者）"
    val subtitle = if (hard) {
        "防卸载 / 防停止 / 防改时间 已由系统锁定"
    } else {
        "功能正常；孩子进「设置」可卸载 / 停止 / 改时间，可在孩子端「我的」页升级为设备所有者"
    }
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (hard) Icons.Default.VerifiedUser else Icons.Default.Warning,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = fg, fontWeight = FontWeight.Bold)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.85f))
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
        FeatureItem("看屏", Icons.AutoMirrored.Filled.ScreenShare),
        FeatureItem("显示管理台", Icons.Default.Visibility)
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
