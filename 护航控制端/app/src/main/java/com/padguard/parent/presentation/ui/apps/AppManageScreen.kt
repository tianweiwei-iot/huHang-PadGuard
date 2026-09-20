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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.padguard.domain.model.InstalledApp
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
    onUninstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.White
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
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

private fun statusText(app: InstalledApp): String = when {
    !app.installed -> "已卸载（历史记录）"
    app.suspended -> "已挂起（图标已隐藏）"
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
