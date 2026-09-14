package com.padguard.presentation.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.padguard.domain.model.*
import com.padguard.presentation.ui.components.FeatureGridItem
import com.padguard.presentation.ui.components.FeatureItem
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.util.TimeFormat
import com.padguard.presentation.viewmodel.DeviceAdminSheet

/**
 * 设备管理功能网格 + 五个管理面板
 * （权限管理 / 功能管理 / 应用分发 / 远程升级维护 / 数据统计）
 *
 * 功能对标综合主流 MDM：Microsoft Intune、Scalefusion、Hexnode、
 * 希沃集控、向日葵、AirDroid 的设备管理控制台能力。
 */

// ==================== 功能入口网格 ====================

private val adminFeatures = listOf(
    FeatureItem("权限管理", Icons.Default.AdminPanelSettings),
    FeatureItem("功能管理", Icons.Default.ToggleOn),
    FeatureItem("应用分发", Icons.Default.CloudDownload),
    FeatureItem("远程升级", Icons.Default.SystemUpdateAlt),
    FeatureItem("数据统计", Icons.Default.BarChart)
)

private fun adminSheetFor(name: String): DeviceAdminSheet? = when (name) {
    "权限管理" -> DeviceAdminSheet.PERMISSIONS
    "功能管理" -> DeviceAdminSheet.FUNCTIONS
    "应用分发" -> DeviceAdminSheet.APP_DISPATCH
    "远程升级" -> DeviceAdminSheet.UPGRADE
    "数据统计" -> DeviceAdminSheet.STATS
    else -> null
}

/**
 * 设备管理入口卡片：内嵌于设备详情页，5 宫格入口
 */
@Composable
fun DeviceAdminEntryCard(onOpen: (DeviceAdminSheet) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("设备管理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false
            ) {
                items(adminFeatures, key = { it.name }) { feature ->
                    FeatureGridItem(
                        feature = feature,
                        onClick = { adminSheetFor(feature.name)?.let(onOpen) }
                    )
                }
            }
        }
    }
}

// ==================== 面板调度 ====================

@Composable
fun DeviceAdminSheetHost(
    uiState: com.padguard.presentation.viewmodel.DeviceDetailUiState,
    onClose: () -> Unit,
    onUpdatePermissions: (DevicePermissionConfig) -> Unit,
    onUpdateFunctions: (DeviceFunctionConfig) -> Unit,
    onDispatchApp: (String, AppDispatchAction) -> Unit,
    onStartUpgrade: () -> Unit
) {
    when (uiState.adminSheet) {
        DeviceAdminSheet.PERMISSIONS -> PermissionsSheet(uiState.permissions, onClose, onUpdatePermissions)
        DeviceAdminSheet.FUNCTIONS -> FunctionsSheet(uiState.functionConfig, onClose, onUpdateFunctions)
        DeviceAdminSheet.APP_DISPATCH -> AppDispatchSheet(uiState.distributableApps, onClose, onDispatchApp)
        DeviceAdminSheet.UPGRADE -> UpgradeSheet(uiState, onClose, onStartUpgrade)
        DeviceAdminSheet.STATS -> StatsSheet(uiState, onClose)
        null -> Unit
    }
}

// ==================== 权限管理 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionsSheet(
    config: DevicePermissionConfig?,
    onClose: () -> Unit,
    onUpdate: (DevicePermissionConfig) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        if (config == null) {
            SheetLoading("正在读取权限配置…")
            return@ModalBottomSheet
        }
        var draft by remember(config) { mutableStateOf(config) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SheetTitle("权限管理", "开关实时下发至被控平板")
            SwitchRow("摄像头", "允许平板使用相机拍照", draft.cameraAllowed) { draft = draft.copy(cameraAllowed = it) }
            SwitchRow("麦克风", "允许平板使用麦克风录音", draft.microphoneAllowed) { draft = draft.copy(microphoneAllowed = it) }
            SwitchRow("定位", "允许采集位置信息（定位 / 电子围栏依赖此项）", draft.locationAllowed) { draft = draft.copy(locationAllowed = it) }
            SwitchRow("USB / 外接存储", "允许通过 USB 连接外部设备", draft.usbAllowed) { draft = draft.copy(usbAllowed = it) }
            SwitchRow("未知来源安装", "允许安装非应用商店来源的应用", draft.unknownSourceInstallAllowed) { draft = draft.copy(unknownSourceInstallAllowed = it) }
            SwitchRow("截屏", "允许平板端截屏（关闭可防考试泄题）", draft.screenshotAllowed) { draft = draft.copy(screenshotAllowed = it) }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onUpdate(draft) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) { Text("保存并下发") }
        }
    }
}

// ==================== 功能管理 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FunctionsSheet(
    config: DeviceFunctionConfig?,
    onClose: () -> Unit,
    onUpdate: (DeviceFunctionConfig) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        if (config == null) {
            SheetLoading("正在读取功能配置…")
            return@ModalBottomSheet
        }
        var draft by remember(config) { mutableStateOf(config) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SheetTitle("功能管理", "管控能力开关，保存后立即生效")
            SwitchRow("学习模式", "仅允许使用学习类应用（Kiosk）", draft.learningModeEnabled) { draft = draft.copy(learningModeEnabled = it) }
            SwitchRow("消息推送", "允许家长端下发文字 / 图片 / 视频消息", draft.messagePushEnabled) { draft = draft.copy(messagePushEnabled = it) }
            SwitchRow("时长限制", "启用每日使用时长与时段管控", draft.screenTimeLimitEnabled) { draft = draft.copy(screenTimeLimitEnabled = it) }
            SwitchRow("护眼模式", "降低蓝光并提醒用眼距离", draft.eyeProtectionEnabled) { draft = draft.copy(eyeProtectionEnabled = it) }
            SwitchRow("远程锁屏", "允许家长端远程锁定平板屏幕", draft.remoteLockEnabled) { draft = draft.copy(remoteLockEnabled = it) }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onUpdate(draft) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) { Text("保存并下发") }
        }
    }
}

// ==================== 应用分发 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDispatchSheet(
    apps: List<DistributableApp>,
    onClose: () -> Unit,
    onDispatch: (String, AppDispatchAction) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SheetTitle("应用分发", "从企业应用库向平板静默下发 / 卸载应用")
            if (apps.isEmpty()) {
                SheetLoading("正在加载应用库…")
            } else {
                apps.forEach { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = app.appName.firstOrNull()?.toString() ?: "·",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.appName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "v${app.versionName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            TextButton(onClick = { onDispatch(app.packageName, AppDispatchAction.INSTALL) }) {
                                Text("安装")
                            }
                            TextButton(onClick = { onDispatch(app.packageName, AppDispatchAction.UNINSTALL) }) {
                                Text("卸载", color = PadGuardColors.WarningRed)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 远程升级 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpgradeSheet(
    uiState: com.padguard.presentation.viewmodel.DeviceDetailUiState,
    onClose: () -> Unit,
    onStartUpgrade: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SheetTitle("远程升级维护", "下发新版本并远程升级被控端应用")
            val info = uiState.upgradeInfo
            if (info == null) {
                SheetLoading("正在检查版本…")
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoLine("当前版本", "v${info.currentAppVersion}")
                        InfoLine("最新版本", "v${info.latestAppVersion}")
                        info.releaseNote?.let { InfoLine("更新说明", it) }
                        if (info.hasUpdate) {
                            Text(
                                "发现新版本，可远程升级",
                                color = PadGuardColors.OnlineGreen,
                                style = MaterialTheme.typography.labelMedium
                            )
                        } else {
                            Text(
                                "已是最新版本",
                                color = PadGuardColors.OnlineGreen,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                Button(
                    onClick = onStartUpgrade,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = info.hasUpdate && !uiState.isUpgrading,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.isUpgrading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在升级，请勿关闭平板…")
                    } else {
                        Icon(Icons.Default.SystemUpdateAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (info.hasUpdate) "一键升级到 v${info.latestAppVersion}" else "无需升级")
                    }
                }
            }
        }
    }
}

// ==================== 数据统计 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsSheet(
    uiState: com.padguard.presentation.viewmodel.DeviceDetailUiState,
    onClose: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SheetTitle("数据统计", "设备使用与合规概览")
            val today = uiState.todayUsage
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoLine(
                        "今日使用时长",
                        TimeFormat.formatDurationFromMinutes(today?.totalUsageMinutes ?: 0)
                    )
                    InfoLine("今日解锁次数", "${today?.screenUnlockCount ?: 0} 次")
                    val report = uiState.monthlyReport
                    if (report != null) {
                        InfoLine("近 30 天累计使用", TimeFormat.formatDurationFromMinutes(report.totalUsageMinutes))
                        InfoLine("近 30 天违规次数", "${report.violationCount} 次")
                        InfoLine("近 30 天告警次数", "${report.alertCount} 次")
                    } else {
                        Text(
                            "正在加载月度统计…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

// ==================== 通用小件 ====================

@Composable
private fun SheetTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun SheetLoading(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
    }
}
