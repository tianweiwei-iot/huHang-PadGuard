package com.padguard.presentation.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.padguard.domain.model.InstalledApp
import com.padguard.presentation.ui.components.AppIcon
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.viewmodel.AppManageUiState
import com.padguard.presentation.viewmodel.AppManageViewModel
import kotlinx.coroutines.flow.collectLatest

/**
 * 应用管理页：应用监控 + 远程安装维护。
 *
 * 页面刻意把"挂起"和"卸载"分成两个动作、且卸载要二次确认：
 * 卸载是**不可逆**的（被管控端不会备份应用数据），
 * 而挂起只是让应用暂时消失、随时可恢复。
 * 把两者做成同一个开关，家长很容易在点错时造成孩子数据丢失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManageScreen(
    deviceId: String,
    onBack: () -> Unit,
    viewModel: AppManageViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingUninstall by remember { mutableStateOf<InstalledApp?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // 本地 APK 选取：家长手里的安装包在手机上，必须先选出来上传，孩子端才下载得到
    val apkPicker = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            ApkFileReader.read(context, uri)
                .onSuccess { apk ->
                    viewModel.installLocalApk(
                        bytes = apk.bytes,
                        fileName = apk.fileName,
                        packageName = apk.packageName,
                        appName = apk.appName
                    )
                }
                .onFailure { e ->
                    snackbarHostState.showSnackbar("读取安装包失败：${e.message}")
                }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiState.collectLatest {
            if (it.toast != null) { snackbarHostState.showSnackbar(it.toast); viewModel.clearToast() }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("应用管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { apkPicker.launch("application/vnd.android.package-archive") }) {
                        Icon(Icons.Default.Add, contentDescription = "安装应用到平板")
                    }
                    IconButton(onClick = { viewModel.toggleSelectionMode() }) {
                        Icon(
                            if (uiState.selectionMode) Icons.Default.Close else Icons.Default.Checklist,
                            contentDescription = "批量操作"
                        )
                    }
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (uiState.selectionMode) {
                BatchActionBar(
                    selectedCount = uiState.selected.size,
                    onSuspend = { viewModel.suspendSelected(true) },
                    onRestore = { viewModel.suspendSelected(false) },
                    onCancel = { viewModel.toggleSelectionMode() }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchBarRow(
                query = uiState.query,
                onQueryChange = viewModel::setQuery,
                showSystemApps = uiState.showSystemApps,
                onShowSystemAppsChange = viewModel::setShowSystemApps
            )

            BatchAllowBar(
                onGrantAll = { viewModel.setAllAllowed(true) },
                onRevokeAll = { viewModel.setAllAllowed(false) }
            )

            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(uiState.error!!, color = PadGuardColors.WarningRed)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.load() }) { Text("重试") }
                    }
                }
                uiState.visibleApps.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (uiState.apps.isEmpty()) "暂无应用台账，等待被管控端上报" else "没有匹配的应用",
                        color = PadGuardColors.TextSecondary
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(uiState.visibleApps, key = { it.packageName }) { app ->
                        InstalledAppItem(
                            app = app,
                            selectionMode = uiState.selectionMode,
                            selected = app.packageName in uiState.selected,
                            onToggleSelected = { viewModel.toggleSelected(app.packageName) },
                            onToggleSuspended = { viewModel.setSuspended(app, !app.suspended) },
                            onToggleAllowed = { viewModel.setAllowed(app, !app.allowed) },
                            onDecreaseLimit = { viewModel.setAppLimit(app, (app.dailyLimitMinutes ?: 0) - 15) },
                            onIncreaseLimit = { viewModel.setAppLimit(app, (app.dailyLimitMinutes ?: 0) + 15) },
                            onUninstall = { pendingUninstall = app }
                        )
                    }
                }
            }
        }
    }

    pendingUninstall?.let { app ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("卸载「${app.appName}」？") },
            text = {
                Text("卸载后应用与其数据将不可恢复。若只是暂时不想让孩子使用，建议改用「挂起」——可随时恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstall(app)
                    pendingUninstall = null
                }) { Text("确认卸载", color = PadGuardColors.WarningRed) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("取消") }
            }
        )
    }
}

// ==================== 组件 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBarRow(
    query: String,
    onQueryChange: (String) -> Unit,
    showSystemApps: Boolean,
    onShowSystemAppsChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("搜索应用名称或包名") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("系统", style = MaterialTheme.typography.bodySmall)
            Checkbox(checked = showSystemApps, onCheckedChange = onShowSystemAppsChange)
        }
    }
}

@Composable
private fun InstalledAppItem(
    app: InstalledApp,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onToggleSuspended: () -> Unit,
    onToggleAllowed: () -> Unit,
    onDecreaseLimit: () -> Unit,
    onIncreaseLimit: () -> Unit,
    onUninstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.White
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
                }
                // 真实图标（孩子端采集上报）。加载失败/还没采集到时由 AppIcon 回退成字母徽章，
                // 不会出现一排一模一样的机器人占位图 —— 那种列表家长根本没法扫。
                AppIcon(
                    packageName = app.packageName,
                    appName = app.appName,
                    iconUrl = app.iconUrl,
                    size = 40.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.appName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        statusText(app),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(app)
                    )
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = PadGuardColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!selectionMode) {
                    // 使用权限开关：关闭即在孩子端隐藏，这是最常用、也最需要"能反复拨"的操作，
                    // 所以做成显式开关而不是藏进菜单。
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(
                            checked = app.allowed,
                            onCheckedChange = { onToggleAllowed() }
                        )
                        Text(
                            if (app.allowed) "允许" else "隐藏",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (app.allowed) PadGuardColors.SuccessGreen else PadGuardColors.TextSecondary
                        )
                    }
                    IconButton(onClick = onToggleSuspended) {
                        Icon(
                            if (app.suspended) Icons.Default.PlayArrow else Icons.Default.Block,
                            contentDescription = if (app.suspended) "恢复" else "挂起",
                            tint = if (app.suspended) PadGuardColors.SuccessGreen else PadGuardColors.TextSecondary
                        )
                    }
                    IconButton(onClick = onUninstall) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "卸载", tint = PadGuardColors.WarningRed)
                    }
                }
            }

            // 每应用使用时长控制：+/- 步进 15 分钟；减到 0 即视为不限制。
            // 仅在非批量模式显示，避免与批量操作互相干扰。
            if (!selectionMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "每日时长",
                        style = MaterialTheme.typography.labelSmall,
                        color = PadGuardColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onDecreaseLimit,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "减少时长", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        text = if (app.dailyLimitMinutes == null || app.dailyLimitMinutes == 0) "不限制" else "${app.dailyLimitMinutes} 分钟",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(min = 64.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    IconButton(
                        onClick = onIncreaseLimit,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "增加时长", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchActionBar(
    selectedCount: Int,
    onSuspend: () -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("已选 $selectedCount 个", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onRestore, enabled = selectedCount > 0) { Text("恢复") }
            Button(
                onClick = onSuspend,
                enabled = selectedCount > 0,
                colors = ButtonDefaults.buttonColors(containerColor = PadGuardColors.WarningRed)
            ) { Text("挂起") }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onCancel) { Text("退出") }
        }
    }
}

/**
 * 一键授权全部 / 一键取消全部。
 * 对应需求：开启管控后所有应用默认隐藏，家长可一键把全部应用开放给孩子，或一键全部收回。
 */
@Composable
private fun BatchAllowBar(
    onGrantAll: () -> Unit,
    onRevokeAll: () -> Unit
) {
    Surface(tonalElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "使用权限",
                style = MaterialTheme.typography.labelMedium,
                color = PadGuardColors.TextSecondary
            )
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRevokeAll) { Text("一键取消全部") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onGrantAll) { Text("一键授权全部") }
        }
    }
}

private fun statusText(app: InstalledApp): String = when {
    !app.installed -> "已卸载（历史记录）"
    app.hidden -> "权限已关闭（孩子端不显示）"
    app.suspended -> "已挂起（打开即拦截）"
    app.blocked -> "已禁用（打开即拦截）"
    app.dailyLimitMinutes != null -> "限时 ${app.dailyLimitMinutes} 分钟 / 天"
    app.isSystem -> "系统应用"
    else -> "正常使用"
}

@Composable
private fun statusColor(app: InstalledApp): Color = when {
    !app.installed -> PadGuardColors.TextSecondary
    app.suspended || app.blocked -> PadGuardColors.WarningRed
    app.dailyLimitMinutes != null -> MaterialTheme.colorScheme.primary
    else -> PadGuardColors.SuccessGreen
}
