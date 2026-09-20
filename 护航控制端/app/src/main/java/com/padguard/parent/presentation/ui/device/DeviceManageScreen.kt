package com.padguard.presentation.ui.device

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.padguard.domain.model.Device
import com.padguard.domain.model.DeviceImportRow
import com.padguard.domain.model.DeviceOnlineStatus
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.ui.components.SoftCard
import com.padguard.presentation.util.QrCodeGenerator
import com.padguard.presentation.viewmodel.DeviceManageViewModel
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

    // 接入新设备底部弹层
    if (uiState.accessSheetVisible) {
        AccessMethodSheet(
            uiState = uiState,
            onClose = viewModel::closeAccessSheet,
            onBindByQr = viewModel::bindByQrPayload,
            onBindByPairingCode = viewModel::bindByPairingCode,
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

// ==================== 接入方式弹层 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccessMethodSheet(
    uiState: com.padguard.presentation.viewmodel.DeviceManageUiState,
    onClose: () -> Unit,
    onBindByQr: (String) -> Unit,
    onBindByPairingCode: (String) -> Unit,
    onScanLan: () -> Unit,
    onBindLan: (com.padguard.domain.model.LanDevice) -> Unit,
    onFilePicked: (android.net.Uri, String) -> Unit,
    onConfirmImport: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("接入新设备", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            TabRow(selectedTabIndex = selectedTab) {
                listOf("扫码", "配对码", "局域网", "Excel").forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label) }
                    )
                }
            }

            when (selectedTab) {
                0 -> QrAccessPanel(uiState, onBindByQr)
                1 -> PairingCodeAccessPanel(uiState, onBindByPairingCode)
                2 -> LanAccessPanel(uiState, onScanLan, onBindLan)
                3 -> ExcelImportPanel(uiState, onFilePicked, onConfirmImport)
            }
        }
    }
}

// ---- 方式一：扫码接入 ----

@Composable
private fun QrAccessPanel(
    uiState: com.padguard.presentation.viewmodel.DeviceManageUiState,
    onBindByQr: (String) -> Unit
) {
    var manualPayload by remember { mutableStateOf("") }
    val qrBitmap = remember(uiState.qrPayload) {
        uiState.qrPayload?.let { QrCodeGenerator.generate(it, 512) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "方式一：在平板「护航学生端 → 设备绑定」打开接入码，用本机扫码；或将平板展示的绑定码粘贴到下方输入框。",
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
                CircularProgressIndicator()
            }
        }
        OutlinedTextField(
            value = manualPayload,
            onValueChange = { manualPayload = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("绑定码 / 二维码内容") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Button(
            onClick = { onBindByQr(manualPayload) },
            modifier = Modifier.fillMaxWidth(),
            enabled = manualPayload.isNotBlank(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("确认接入")
        }
    }
}

// ---- 方式二：配对码接入 ----

@Composable
private fun PairingCodeAccessPanel(
    uiState: com.padguard.presentation.viewmodel.DeviceManageUiState,
    onBindByPairingCode: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "方式二：在平板「护航学生端 → 设备绑定」查看 6 位配对码，输入后即可接入；也可将本机配对码输入到平板完成反向绑定。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        uiState.pairingCode?.let { current ->
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
                    Text("本机配对码", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = current,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp
                    )
                }
            }
        }
        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 6) code = it.filter { c -> c.isDigit() } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("输入平板配对码（6 位数字）") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Button(
            onClick = { onBindByPairingCode(code) },
            modifier = Modifier.fillMaxWidth(),
            enabled = code.length == 6,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Password, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("确认接入")
        }
    }
}

// ---- 方式三：局域网发现 ----

@Composable
private fun LanAccessPanel(
    uiState: com.padguard.presentation.viewmodel.DeviceManageUiState,
    onScanLan: () -> Unit,
    onBindLan: (com.padguard.domain.model.LanDevice) -> Unit
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
    uiState: com.padguard.presentation.viewmodel.DeviceManageUiState,
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
