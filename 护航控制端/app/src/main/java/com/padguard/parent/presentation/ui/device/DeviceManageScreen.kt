package com.padguard.parent.presentation.ui.device

import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.padguard.parent.domain.model.Device
import com.padguard.parent.domain.model.DeviceImportRow
import com.padguard.parent.domain.model.DeviceOnlineStatus
import com.padguard.parent.presentation.ui.theme.PadGuardColors
import com.padguard.parent.presentation.ui.components.SoftCard
import com.padguard.parent.presentation.util.QrCodeGenerator
import com.padguard.parent.domain.model.Enrollment
import com.padguard.parent.presentation.viewmodel.DeviceManageViewModel
import com.padguard.parent.presentation.viewmodel.EnrollmentUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * 设备管理页（底部导航「设备」Tab 的二级管理页）
 *
 * 功能（对标主流 MDM 设备管理控制台）：
 * 1. 设备台账列表：在线状态 / 型号 / 版本 / 电量，支持搜索过滤
 * 2. 设备接入：扫码接入 / 配对码接入 / 局域网发现 / Excel 批量导入
 * 3. 增删改查：接入即增、解绑即删、重命名即改、点击进详情即查
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceManageScreen(
    onDeviceClick: (String) -> Unit,
    /** 首页空状态入口直达接入新设备：落地即拉起接入浮层，跳过设备管理主页面 */
    autoOpenAccess: Boolean = false,
    viewModel: DeviceManageViewModel = hiltViewModel()
) {
    val devices by viewModel.devices.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiState.collectLatest {
            if (it.toast != null) {
                snackbarHostState.showSnackbar(it.toast)
                viewModel.clearToast()
            }
        }
    }

    // 直达接入：从首页「还没有绑定的孩子设备」进入时，立即拉起接入新设备浮层。
    // 仅首次组合触发一次，避免重组重复拉起；设备管理 Tab 普通进入时 autoOpenAccess=false 不受影响。
    LaunchedEffect(autoOpenAccess) {
        if (autoOpenAccess) viewModel.openAccessSheet()
    }

    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("设备管理") },
                actions = {
                    IconButton(onClick = viewModel::openAccessSheet) {
                        Icon(Icons.Default.Add, contentDescription = "接入新设备")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 汇总概览
            DeviceSummaryRow(devices = devices)

            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索设备名称 / 型号") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // 设备台账列表
            val filtered = devices.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    (it.model?.contains(searchQuery, ignoreCase = true) ?: false)
            }
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) "暂无接入设备，点击右上角 + 接入平板" else "未找到匹配设备",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filtered, key = { it.id }) { device ->
                        ManagedDeviceCard(
                            device = device,
                            onClick = { onDeviceClick(device.id) },
                            onEdit = { viewModel.openEdit(device) },
                            onDelete = { viewModel.openDelete(device) }
                        )
                    }
                }
            }
        }
    }

    // 接入新设备（全屏覆盖，替代原底部弹层）
    if (uiState.accessSheetVisible) {
        AccessMethodScreen(
            uiState = uiState,
            onClose = viewModel::closeAccessSheet,
            onReloadEnrollment = viewModel::reloadEnrollment,
            onRefreshEnrollmentCode = viewModel::refreshEnrollmentCode,
            onScanLan = viewModel::scanLanDevices,
            onBindLan = viewModel::bindLanDevice,
            onFilePicked = viewModel::onImportFilePicked,
            onConfirmImport = viewModel::confirmImport
        )
    }

    // 重命名弹窗
    uiState.editingDevice?.let { device ->
        RenameDeviceDialog(
            device = device,
            onConfirm = viewModel::renameDevice,
            onDismiss = viewModel::dismissEdit
        )
    }

    // 解绑确认弹窗
    uiState.deletingDevice?.let { device ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("解绑设备") },
            text = { Text("确定解绑「${device.name}」吗？解绑后该平板将退出管控，历史数据保留。") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteDevice(device.id) }) {
                    Text("解绑", color = PadGuardColors.WarningRed)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) { Text("取消") }
            }
        )
    }

    // S11：绑定成功后的操作密码补设弹窗
    uiState.postBindDevice?.let { device ->
        PostBindPasswordDialog(
            device = device,
            busy = uiState.postBindBusy,
            onConfirm = { pw -> viewModel.confirmPostBindPassword(device, pw) },
            onSkip = viewModel::dismissPostBindPassword
        )
    }
}

/**
 * 绑定后强制引导：为新接入的设备设置操作密码（S11）。
 *
 * 校验口径与设备详情页 GuardPasswordCard 一致（4~32 位）。
 * 「跳过」保留但不隐匿：文案直接说明跳过的后果，避免家长误以为绑定即管控完成。
 */
@Composable
private fun PostBindPasswordDialog(
    device: Device,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onSkip: () -> Unit
) {
    var pw by remember(device.id) { mutableStateOf("") }
    var pw2 by remember(device.id) { mutableStateOf("") }
    val canSubmit = pw.length in 4..32 && pw == pw2

    AlertDialog(
        onDismissRequest = { /* 不点按钮不关闭：这是一次显式的引导步骤 */ },
        title = { Text("设置操作密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "「${device.name}」接入成功。建议立即设置操作密码：设置后，孩子在平板上进入系统设置、" +
                        "卸载/安装应用都必须输入此密码。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = pw,
                    onValueChange = { if (it.length <= 32) pw = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("家长密码（4~32 位）") },
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = pw2,
                    onValueChange = { if (it.length <= 32) pw2 = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("确认密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        if (pw2.isNotEmpty() && pw != pw2) Text("两次输入不一致", color = PadGuardColors.WarningRed)
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pw) },
                enabled = !busy && canSubmit,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(if (busy) "同步中…" else "设置并同步")
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip, enabled = !busy) {
                Text("暂不设置", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    )
}

// ==================== 汇总概览 ====================

@Composable
private fun DeviceSummaryRow(devices: List<Device>) {
    val online = devices.count { it.onlineStatus == DeviceOnlineStatus.ONLINE }
    val offline = devices.count { it.onlineStatus == DeviceOnlineStatus.OFFLINE }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SummaryCard("接入总数", "${devices.size}", "台", Modifier.weight(1f))
        SummaryCard("在线", "$online", "台", Modifier.weight(1f), PadGuardColors.OnlineGreen)
        SummaryCard("离线", "$offline", "台", Modifier.weight(1f), PadGuardColors.OfflineGray)
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.primary
) {
    SoftCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = valueColor
                )
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

// ==================== 设备台账卡片 ====================

@Composable
private fun ManagedDeviceCard(
    device: Device,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val online = device.onlineStatus == DeviceOnlineStatus.ONLINE

    SoftCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.TabletAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                if (online) PadGuardColors.OnlineGreen else PadGuardColors.OfflineGray,
                                androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = listOfNotNull(
                            if (online) "在线" else "离线",
                            device.model,
                            device.appVersion?.let { "v$it" }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            device.batteryLevel?.let {
                Text(
                    text = "$it%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (it > 20) PadGuardColors.OnlineGreen else PadGuardColors.WarningRed
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "设备操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("解绑设备") },
                        leadingIcon = { Icon(Icons.Default.LinkOff, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

// ==================== 接入方式（全屏） ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccessMethodScreen(
    uiState: com.padguard.parent.presentation.viewmodel.DeviceManageUiState,
    onClose: () -> Unit,
    onReloadEnrollment: () -> Unit,
    onRefreshEnrollmentCode: () -> Unit,
    onScanLan: () -> Unit,
    onBindLan: (com.padguard.parent.domain.model.LanDevice) -> Unit,
    onFilePicked: (android.net.Uri, String) -> Unit,
    onConfirmImport: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    // 接入码倒计时的时基：每秒推进一次。用单调时钟而非墙上时钟，
    // 用户在手机上改系统时间也不会让倒计时跳变。
    var nowRealtime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowRealtime = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }

    // 全屏覆盖：直接铺满整个屏幕，承载接入新设备的全部内容（替代原底部弹层）。
    // 自带顶栏与关闭按钮；绑定成功后由 ViewModel 直接 closeAccessSheet 退出，无需二次确认。
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("接入新设备") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    // 前两个 Tab 是同一个接入会话的两种出示方式：
                    // 家长端无相机扫码能力，二维码是"给孩子端扫"，接入码是"给孩子端输入"。
                    listOf("二维码", "接入码", "局域网", "Excel").forEachIndexed { index, label ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(label) }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> EnrollmentQrPanel(
                        uiState.enrollment, nowRealtime, onReloadEnrollment, onRefreshEnrollmentCode
                    )
                    1 -> EnrollmentPairingPanel(
                        uiState.enrollment, nowRealtime, onReloadEnrollment, onRefreshEnrollmentCode
                    )
                    2 -> LanAccessPanel(uiState, onScanLan, onBindLan)
                    3 -> ExcelImportPanel(uiState, onFilePicked, onConfirmImport)
                }
            }
        }
    }
}

// ---- 接入会话：二维码（方式一）与接入码（方式二）同源 ----

/**
 * 接入会话的展示宿主：二维码与接入码共用同一份会话状态。
 *
 * 加载 / 失败 / 过期三种非正常态统一在这里呈现，两个 Tab 因此不可能出现
 * "一个还在转圈、另一个已经显示"的分裂状态，也不可能出现错误被静默吞掉 ——
 * 每条失败都必须显示原因，并给出可执行的下一步（重试 / 换码）。
 */
@Composable
private fun EnrollmentHost(
    state: EnrollmentUi,
    nowRealtime: Long,
    onReload: () -> Unit,
    onRefreshCode: () -> Unit,
    content: @Composable (Enrollment, Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (state) {
            EnrollmentUi.Idle, EnrollmentUi.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "正在生成接入码…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            is EnrollmentUi.Failed -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("未能获取接入信息", style = MaterialTheme.typography.titleSmall)
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(onClick = onReload, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                    }
                }
            }

            is EnrollmentUi.Active -> {
                // 剩余时长按服务端时钟 + 本地单调流逝推算，不受用户改系统时间影响
                val remainingSeconds =
                    (state.enrollment.remainingMillis(nowRealtime) / 1000).coerceAtLeast(0)
                if (state.expired || remainingSeconds <= 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("接入码已失效", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "接入码 10 分钟内有效。换一个后，请让孩子端使用新码重新接入。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Button(onClick = onRefreshCode, modifier = Modifier.fillMaxWidth()) {
                                Text("换一个接入码")
                            }
                        }
                    }
                } else {
                    content(state.enrollment, remainingSeconds)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onRefreshCode) { Text("换一个接入码") }
                    }
                }
            }
        }
    }
}

/** 方式一：家长出示二维码，由孩子端扫描 */
@Composable
private fun EnrollmentQrPanel(
    state: EnrollmentUi,
    nowRealtime: Long,
    onReload: () -> Unit,
    onRefreshCode: () -> Unit
) {
    EnrollmentHost(state, nowRealtime, onReload, onRefreshCode) { enrollment, remaining ->
        val qrBitmap = remember(enrollment.qrPayload) {
            QrCodeGenerator.generate(enrollment.qrPayload, 512)
        }
        Text(
            "在平板「护航学生端 → 设备绑定」选择扫码，扫描下方二维码即可完成接入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = "设备接入二维码",
                    modifier = Modifier.size(190.dp)
                )
            } else {
                Text(
                    "二维码生成失败，请重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Text(
            "二维码 ${formatRemaining(remaining)}后失效",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/** 方式二：家长出示 6 位接入码，由孩子端输入（与二维码同源同值） */
@Composable
private fun EnrollmentPairingPanel(
    state: EnrollmentUi,
    nowRealtime: Long,
    onReload: () -> Unit,
    onRefreshCode: () -> Unit
) {
    EnrollmentHost(state, nowRealtime, onReload, onRefreshCode) { enrollment, remaining ->
        Text(
            "在平板「护航学生端 → 设备绑定」选择输入绑定码，填入下方 6 位接入码即可完成接入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("本次接入码", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = enrollment.pairingCode,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp
                )
            }
        }
        Text(
            "接入码 ${formatRemaining(remaining)}后失效",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

private fun formatRemaining(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m} 分 ${s} 秒 " else "${s} 秒 "
}

// ---- 方式三：局域网发现 ----

@Composable
private fun LanAccessPanel(
    uiState: com.padguard.parent.presentation.viewmodel.DeviceManageUiState,
    onScanLan: () -> Unit,
    onBindLan: (com.padguard.parent.domain.model.LanDevice) -> Unit
) {
    LaunchedEffect(Unit) { onScanLan() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "方式三：自动扫描与手机处于同一 Wi-Fi 的待接入平板（需平板端开启「允许发现」）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        if (uiState.lanScanning) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text("正在扫描局域网…", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (uiState.lanDevices.isEmpty()) {
            Text(
                "未发现待接入平板，可点击下方按钮重新扫描",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 20.dp)
            )
        } else {
            uiState.lanDevices.forEach { lan ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lan.model ?: lan.deviceId,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${lan.ipAddress} · Android ${lan.osVersion ?: "-"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        TextButton(onClick = { onBindLan(lan) }) { Text("接入") }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onScanLan,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.lanScanning,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Radar, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("重新扫描")
        }
    }
}

// ---- 方式四：Excel 批量导入 ----

private val importFileTypes = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
    "application/vnd.ms-excel",                                          // .xls（页面内提示转存）
    "text/csv",
    "text/comma-separated-values",
    "text/plain"
)

@Composable
private fun ExcelImportPanel(
    uiState: com.padguard.parent.presentation.viewmodel.DeviceManageUiState,
    onFilePicked: (android.net.Uri, String) -> Unit,
    onConfirmImport: () -> Unit
) {
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "import.xlsx"
            onFilePicked(uri, name)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "方式四：使用 Excel / WPS 表格批量导入设备台账。列顺序：设备编号（必填）、设备别名、型号、分组；支持 .xlsx 与 .csv。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        OutlinedButton(
            onClick = { filePicker.launch(importFileTypes) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.UploadFile, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("选择表格文件")
        }

        if (uiState.importError != null) {
            Text(
                text = "解析失败：${uiState.importError}",
                color = PadGuardColors.WarningRed,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (uiState.importPreview.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${uiState.importFileName} · 共 ${uiState.importPreview.size} 条",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    uiState.importPreview.take(3).forEach { row ->
                        Text(
                            text = importRowSummary(row),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (uiState.importPreview.size > 3) {
                        Text(
                            text = "…等 ${uiState.importPreview.size} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
            Button(
                onClick = onConfirmImport,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.DoneAll, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("确认导入 ${uiState.importPreview.size} 台设备")
            }
        }
    }
}

private fun importRowSummary(row: DeviceImportRow): String =
    listOfNotNull(row.hardwareId, row.alias, row.model, row.groupName).joinToString(" / ")

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

// ==================== 重命名弹窗 ====================

@Composable
private fun RenameDeviceDialog(
    device: Device,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(device.id) { mutableStateOf(device.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名设备") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("设备名称") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(device.id, name) },
                enabled = name.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
