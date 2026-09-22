package com.padguard.child.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.ui.components.GlyphKind
import com.padguard.child.ui.settings.ServerConfigDialog

/**
 * "我的"页。
 *
 * 视觉层次自上而下：
 * 1. 设备卡片：孩子名 + 设备信息
 * 2. 隐私与安全：设备管理员 / 使用情况访问 状态
 * 3. 列表项：应用管理 / 帮助 / 关于 / 退出
 */
@Composable
fun ProfileScreen(
    onPermissionGuide: (String) -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsState()
    var showServerDialog by remember { mutableStateOf(false) }
    var serverInput by remember { mutableStateOf("") }
    var showNameDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }

    ProfileContent(
        state = state,
        onPermissionGuide = onPermissionGuide,
        onSetServer = {
            serverInput = serverUrl
            showServerDialog = true
        },
        onEditDeviceName = {
            nameInput = state.deviceName.ifBlank { state.deviceSn }
            showNameDialog = true
        }
    )

    if (showServerDialog) {
        ServerConfigDialog(
            initial = serverInput,
            onDismiss = { showServerDialog = false },
            onSave = { url ->
                viewModel.setServerUrl(url)
                showServerDialog = false
            }
        )
    }

    if (showNameDialog) {
        DeviceNameDialog(
            initial = nameInput,
            onDismiss = { showNameDialog = false },
            onSave = { name ->
                viewModel.saveDeviceName(name)
                showNameDialog = false
            }
        )
    }
}

@Composable
private fun ProfileContent(
    state: ProfileUiState,
    onPermissionGuide: (String) -> Unit,
    onSetServer: () -> Unit,
    onEditDeviceName: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "我的",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            item {
                DeviceCard(state = state, onEditDeviceName = onEditDeviceName)
            }
            item {
                Text(
                    text = "隐私与安全",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            item {
                SecurityCard(
                    deviceAdminActive = state.deviceAdminActive,
                    usageStatsGranted = state.usageStatsGranted,
                    onClickDeviceAdmin = { onPermissionGuide("device_admin") },
                    onClickUsageStats = { onPermissionGuide("usage_stats") }
                )
            }
            item {
                Text(
                    text = "更多",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            item {
                MoreCard(onSetServer = onSetServer)
            }
        }
    }
}

@Composable
private fun DeviceCard(state: ProfileUiState, onEditDeviceName: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = state.studentName.take(1).ifBlank { "孩" },
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                Column {
                    Text(
                        text = if (state.studentName.isBlank()) "未命名孩子" else state.studentName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (state.isBound) "已绑定" else "未绑定",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isBound) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            DeviceNameRow(
                name = state.deviceName.ifBlank { state.deviceSn },
                onClick = onEditDeviceName
            )
            InfoRow(label = "设备 SN", value = state.deviceSn)
            InfoRow(label = "型号", value = state.deviceModel)
            InfoRow(label = "系统", value = state.androidVersion)
            InfoRow(label = "存储", value = state.storage)
            InfoRow(label = "电量", value = state.battery)
        }
    }
}

@Composable
private fun DeviceNameRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "设备名称",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "改名",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun DeviceNameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onSave(text.trim()) }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("修改设备名称") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("例如：小明的平板") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SecurityCard(
    deviceAdminActive: Boolean,
    usageStatsGranted: Boolean,
    onClickDeviceAdmin: () -> Unit,
    onClickUsageStats: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            PermissionRow(
                title = "设备管理员",
                subtitle = "管控权限已激活后，才能执行锁屏、禁用应用、隐藏状态栏等操作",
                active = deviceAdminActive,
                onClick = onClickDeviceAdmin
            )
            HorizontalDivider()
            PermissionRow(
                title = "使用情况访问",
                subtitle = "授权后才能统计每个 App 的真实使用时长",
                active = usageStatsGranted,
                onClick = onClickUsageStats
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    active: Boolean,
    pillText: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
        StatusPill(active = active, text = pillText ?: if (active) "已开启" else "未开启")
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun StatusPill(active: Boolean, text: String) {
    val bg = if (active) MaterialTheme.colorScheme.primary else Color(0xFFB0BEC5)
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
    }
}

@Composable
private fun MoreCard(onSetServer: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            SimpleRow(title = "⚙ 高级设置", subtitle = "服务器地址 / 端口等（连不上管控端时修改）", onClick = onSetServer)
            HorizontalDivider()
            SimpleRow(title = "应用管理", subtitle = "查看本机已安装应用与受限情况")
            HorizontalDivider()
            SimpleRow(title = "帮助与反馈", subtitle = "常见问题 + 提交反馈")
            HorizontalDivider()
            SimpleRow(title = "关于护航成长", subtitle = "版本号 / 开源许可")
        }
    }
}

/**
 * 服务器地址设置弹窗已统一迁移到 [com.padguard.child.ui.settings.ServerConfigDialog]，
 * 「我的 → 更多 → 高级设置」与绑定欢迎页共用，避免重复实现。
 */
@Composable
private fun SimpleRow(title: String, subtitle: String, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}
