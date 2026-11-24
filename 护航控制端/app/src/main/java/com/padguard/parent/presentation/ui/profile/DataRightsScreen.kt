package com.padguard.parent.presentation.ui.profile

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.parent.presentation.viewmodel.DataRightsViewModel

/**
 * 数据权利与合规页。
 *
 * 对应服务端 ParentDataRightsController：查阅复制（导出）、撤回/恢复同意、注销账号、申诉。
 * 危险操作（注销确认、撤回全部设备采集）一律二次确认；所有结果经 Snackbar 呈现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataRightsScreen(
    onBack: () -> Unit,
    viewModel: DataRightsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showWithdrawDialog by remember { mutableStateOf(false) }
    var showDeletionDialog by remember { mutableStateOf(false) }
    var showConfirmDeletionDialog by remember { mutableStateOf(false) }
    var showAppealDialog by remember { mutableStateOf(false) }
    var appealSubject by remember { mutableStateOf("") }
    var appealDetail by remember { mutableStateOf("") }
    var appealContact by remember { mutableStateOf("") }
    var deletionReason by remember { mutableStateOf("") }
    var guardianDeviceId by remember { mutableStateOf("") }
    var guardianMenuExpanded by remember { mutableStateOf(false) }
    var childBirthDate by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(state.export?.devices) {
        val first = state.export?.devices?.firstOrNull()?.deviceId
        if (guardianDeviceId.isBlank() && first != null) guardianDeviceId = first
    }
    LaunchedEffect(guardianDeviceId) {
        if (guardianDeviceId.isNotBlank()) viewModel.loadGuardianConsent(guardianDeviceId)
    }
    LaunchedEffect(state.message, state.error) {
        val text = state.error ?: state.message
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据权利与合规") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "依据《个人信息保护法》，你可查阅复制个人数据、撤回采集同意、注销账号或提交申诉。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Spacer(Modifier.padding(horizontal = 8.dp))
                    Text("正在加载…", style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---- 查阅复制：导出 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("查阅与复制")
                    val export = state.export
                    Text(
                        if (export == null) "尚未加载导出数据"
                        else "账号：${export.account?.phone ?: "-"}　设备 ${export.devices.size} 台　" +
                            "用量 ${export.usageLogs.size} / 位置 ${export.locationTracks.size} / 截图 ${export.screenshots.size} 条" +
                            "（每台上限 ${export.detailLimitPerDevice}）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "导出内容包括账号信息、设备清单、用量日志、位置轨迹、截图记录、申诉与注销记录；明细按设备限流，上限以服务端返回为准。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        val e = state.export
                        if (e == null) Toast.makeText(context, "数据尚未加载完成", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(
                            context,
                            "账号 ${e.account?.phone ?: "-"}｜设备 ${e.devices.size} 台｜导出时间 ${e.exportedAt}",
                            Toast.LENGTH_LONG
                        ).show()
                    }) {
                        Text("查看导出摘要")
                    }
                }
            }

            // ---- 撤回 / 恢复同意 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("采集授权")
                    Text(
                        "撤回后服务端停止落库，并通过策略包让孩子端立即/离线重连后停止本地采集；恢复同意走同一通道。撤回名下全部设备需二次确认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showWithdrawDialog = true },
                            enabled = state.acting == null
                        ) { Text("撤回同意") }
                        Button(
                            onClick = { viewModel.reinstateConsent() },
                            enabled = state.acting == null
                        ) { Text("恢复同意") }
                    }
                }
            }

            // ---- 不满十四周岁监护人单独同意（PIPL 第 29/31 条） ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("不满十四周岁监护人单独同意")
                    Text(
                        "请如实填写孩子出生日期（yyyy-MM-dd）并勾选单独同意；服务端只接受未满 14 周岁。" +
                            "此处为家长申报留痕，**不是实名年龄核验**，法务要求实名核验时需另走核验通道。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    val devices = state.export?.devices.orEmpty()
                    val selectedDevice = devices.firstOrNull { it.deviceId == guardianDeviceId } ?: devices.firstOrNull()
                    Box {
                        TextButton(
                            onClick = { guardianMenuExpanded = true },
                            enabled = devices.isNotEmpty() && state.acting == null
                        ) {
                            Text("设备：${selectedDevice?.name ?: selectedDevice?.deviceId ?: "暂无设备"}")
                        }
                        DropdownMenu(
                            expanded = guardianMenuExpanded,
                            onDismissRequest = { guardianMenuExpanded = false }
                        ) {
                            devices.forEach { d ->
                                DropdownMenuItem(
                                    text = { Text(d.name ?: d.deviceId) },
                                    onClick = {
                                        guardianDeviceId = d.deviceId
                                        guardianMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = childBirthDate,
                        onValueChange = { childBirthDate = it },
                        label = { Text("孩子出生日期（yyyy-MM-dd）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    state.guardianConsent?.let { c ->
                        Text(
                            if (c.consented) "已记录单独同意：${c.childBirthDate ?: "-"}｜文案版本 ${c.consentTextVersion ?: "-"}" +
                                "（按申报年龄判定，未实名核验）"
                            else "该设备尚未提交有效单独同意",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = {
                            viewModel.submitUnder14GuardianConsent(
                                guardianDeviceId, childBirthDate.trim(), "2026-11-23-v1"
                            )
                        },
                        enabled = guardianDeviceId.isNotBlank() && childBirthDate.isNotBlank() && state.acting == null
                    ) { Text("确认已阅读并提交单独同意") }
                }
            }

            // ---- 账号注销 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("注销账号")
                    Text(
                        "提交后进入冷静期，期内可取消；确认后设备数据与账号将被不可逆清除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    state.deletionRequest?.let { req ->
                        Text(
                            "注销凭证：${req.ticket}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.cancelDeletion(req.ticket) }) {
                                Text("取消注销")
                            }
                            Button(onClick = { showConfirmDeletionDialog = true }) {
                                Text("确认注销")
                            }
                        }
                    } ?: run {
                        OutlinedButton(
                            onClick = { showDeletionDialog = true },
                            enabled = state.acting == null
                        ) { Text("申请注销") }
                    }
                }
            }

            // ---- 申诉 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("申诉")
                    Text(
                        "如对管控或数据处理有异议，可提交申诉。已有 ${state.appeals.size} 条记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.appeals.take(3).forEach { a ->
                        Spacer(Modifier.height(4.dp))
                        Text("· ${a.subject}（${a.status}）", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showAppealDialog = true },
                        enabled = state.acting == null
                    ) { Text("提交申诉") }
                }
            }
        }
    }

    // ---- 撤回确认 ----
    if (showWithdrawDialog) {
        AlertDialog(
            onDismissRequest = { showWithdrawDialog = false },
            title = { Text("撤回全部采集授权？") },
            text = { Text("撤回后服务端将停止采集与保存孩子端数据，恢复前家长端看不到新的用量与告警。") },
            confirmButton = {
                TextButton(onClick = {
                    showWithdrawDialog = false
                    viewModel.withdrawConsent()
                }) { Text("确认撤回") }
            },
            dismissButton = { TextButton(onClick = { showWithdrawDialog = false }) { Text("取消") } }
        )
    }

    // ---- 注销申请（填原因）----
    if (showDeletionDialog) {
        AlertDialog(
            onDismissRequest = { showDeletionDialog = false },
            title = { Text("申请注销账号") },
            text = {
                Column {
                    Text("可填写原因（选填），提交后进入冷静期。")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deletionReason,
                        onValueChange = { deletionReason = it },
                        label = { Text("注销原因") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeletionDialog = false
                    viewModel.requestDeletion(deletionReason.ifBlank { null })
                }) { Text("提交申请") }
            },
            dismissButton = { TextButton(onClick = { showDeletionDialog = false }) { Text("取消") } }
        )
    }

    // ---- 注销确认（不可撤销）----
    if (showConfirmDeletionDialog) {
        val ticket = state.deletionRequest?.ticket.orEmpty()
        AlertDialog(
            onDismissRequest = { showConfirmDeletionDialog = false },
            title = { Text("确认注销？") },
            text = { Text("此操作不可撤销：设备个人数据将被清除，账号将被匿名化。请在冷静期确认无误后继续。") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDeletionDialog = false
                    viewModel.confirmDeletion(ticket)
                }) { Text("确认注销", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showConfirmDeletionDialog = false }) { Text("再想想") } }
        )
    }

    // ---- 申诉表单 ----
    if (showAppealDialog) {
        AlertDialog(
            onDismissRequest = { showAppealDialog = false },
            title = { Text("提交申诉") },
            text = {
                Column {
                    OutlinedTextField(
                        value = appealSubject, onValueChange = { appealSubject = it },
                        label = { Text("主题（必填）") }, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = appealDetail, onValueChange = { appealDetail = it },
                        label = { Text("详情") }, modifier = Modifier.fillMaxWidth(), maxLines = 3
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = appealContact, onValueChange = { appealContact = it },
                        label = { Text("联系方式（选填）") }, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = appealSubject.isNotBlank(),
                    onClick = {
                        showAppealDialog = false
                        viewModel.submitAppeal(null, appealSubject.trim(), appealDetail.ifBlank { null }, appealContact.ifBlank { null })
                        appealSubject = ""; appealDetail = ""; appealContact = ""
                    }
                ) { Text("提交") }
            },
            dismissButton = { TextButton(onClick = { showAppealDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}
