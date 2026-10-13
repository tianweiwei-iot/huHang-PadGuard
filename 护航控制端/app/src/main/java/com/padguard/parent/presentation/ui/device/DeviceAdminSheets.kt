package com.padguard.parent.presentation.ui.device

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
import com.padguard.parent.domain.model.*
import com.padguard.parent.presentation.ui.components.FeatureGridItem
import com.padguard.parent.presentation.ui.components.FeatureItem
import com.padguard.parent.presentation.ui.components.SoftCard
import com.padguard.parent.presentation.ui.theme.PadGuardColors
import com.padguard.parent.presentation.util.TimeFormat
import com.padguard.parent.presentation.viewmodel.DeviceAdminSheet
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import java.io.File

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
    SoftCard(modifier = Modifier.fillMaxWidth()) {
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
    uiState: com.padguard.parent.presentation.viewmodel.DeviceDetailUiState,
    onClose: () -> Unit,
    onUpdatePermissions: (DevicePermissionConfig) -> Unit,
    onUpdateFunctions: (DeviceFunctionConfig) -> Unit,
    onDispatchApp: (String) -> Unit,
    onForceDispatchApp: (String) -> Unit,
    onStartUpgrade: (String) -> Unit,
    onUploadApp: (ByteArray, AppUploadMeta, String) -> Unit,
    onDeleteApp: (String) -> Unit,
    onUninstallApp: (String) -> Unit
) {
    when (uiState.adminSheet) {
        DeviceAdminSheet.PERMISSIONS -> PermissionsSheet(uiState.permissions, onClose, onUpdatePermissions)
        DeviceAdminSheet.FUNCTIONS -> FunctionsSheet(uiState.functionConfig, onClose, onUpdateFunctions)
        DeviceAdminSheet.APP_DISPATCH -> AppDispatchSheet(
            apps = uiState.distributableApps,
            uploading = uiState.uploading,
            onClose = onClose,
            onDispatch = onDispatchApp,
            onForceDispatch = onForceDispatchApp,
            onUpload = onUploadApp,
            onDelete = onDeleteApp,
            onUninstall = onUninstallApp
        )
        DeviceAdminSheet.UPGRADE -> UpgradeSheet(uiState, onClose, onStartUpgrade)
        DeviceAdminSheet.STATS -> StatsSheet(uiState, onClose)
        null -> Unit
    }
}

// ==================== 权限管理 ====================

private data class PermItem(val perm: String, val label: String, val desc: String)

/** 可被家长授予 / 收回的运行时权限（对应 Android 危险权限） */
private val PERMISSION_ITEMS = listOf(
    PermItem("android.permission.CAMERA", "摄像头", "允许被控应用使用相机拍照"),
    PermItem("android.permission.RECORD_AUDIO", "麦克风", "允许被控应用使用麦克风录音"),
    PermItem("android.permission.ACCESS_FINE_LOCATION", "定位", "允许采集位置（定位 / 电子围栏依赖此项）"),
    PermItem("android.permission.READ_PHONE_STATE", "电话", "读取手机状态（如设备标识）"),
    PermItem("android.permission.READ_CONTACTS", "通讯录", "读取联系人信息"),
    PermItem("android.permission.READ_EXTERNAL_STORAGE", "存储", "读取外部存储空间的文件")
)

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
            SheetTitle("权限管理", "授予 / 收回被控应用的运行时权限，保存后实时下发")
            PERMISSION_ITEMS.forEach { item ->
                val granted = draft.permissions.contains(item.perm)
                SwitchRow(item.label, item.desc, granted) { checked ->
                    draft = draft.copy(
                        permissions = if (checked) draft.permissions + item.perm else draft.permissions - item.perm
                    )
                }
            }
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

private data class FuncItem(val key: String, val label: String, val desc: String)

/** 设备功能开关（featureKey 与服务端 CommandKey.FEATURE_* 逐字符一致；value=true=施加限制/关闭能力） */
private val FUNCTION_ITEMS = listOf(
    FuncItem("camera_disabled", "禁用摄像头", "远程关闭相机，防偷拍"),
    FuncItem("screenshot_disabled", "禁止截屏", "防考试泄题、信息外泄"),
    FuncItem("factory_reset_disabled", "禁止恢复出厂", "防止孩子清空管控"),
    FuncItem("safe_boot_disabled", "禁止安全模式", "防绕过管控"),
    FuncItem("unknown_sources_disabled", "禁止未知来源安装", "仅允许应用商店安装"),
    FuncItem("config_datetime_disabled", "禁止修改系统时间", "防绕过时段管控"),
    FuncItem("usb_debug_disabled", "禁止 USB 调试", "防 adb 绕过"),
    FuncItem("usb_data_disabled", "禁止 USB 数据传输", "防拷走数据"),
    FuncItem("status_bar_disabled", "禁用状态栏", "防下拉快捷开关绕过"),
    FuncItem("auto_time_enforced", "强制自动对时", "锁定系统时间，防篡改")
)

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
            SheetTitle("功能管理", "设备能力开关，保存后立即生效（需孩子端具备 Device Owner 权限）")
            FUNCTION_ITEMS.forEach { item ->
                val enabled = draft.features[item.key] == true
                SwitchRow(item.label, item.desc, enabled) { checked ->
                    draft = draft.copy(features = draft.features + (item.key to checked))
                }
            }
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

/** 从 APK 文件解析包名 / 版本（用于上传到应用库）；结果以 domain 层 [AppUploadMeta] 表达 */
private fun parseApkMeta(context: Context, uri: Uri): AppUploadMeta? {
    val tmp = File(context.cacheDir, "pg_upload_${System.currentTimeMillis()}.apk")
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out -> input.copyTo(out) }
        }
        val pi = context.packageManager.getPackageArchiveInfo(tmp.absolutePath, 0)
        tmp.delete()
        pi?.let {
            // versionCode 字段已弃用，API 28+ 用 longVersionCode 避免 32 位版本号溢出
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                it.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                it.versionCode.toLong()
            }
            AppUploadMeta(
                packageName = it.packageName,
                versionCode = versionCode,
                versionName = it.versionName ?: "",
                appName = it.applicationInfo?.loadLabel(context.packageManager)?.toString() ?: it.packageName
            )
        }
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDispatchSheet(
    apps: List<DistributableApp>,
    uploading: Boolean,
    onClose: () -> Unit,
    onDispatch: (String) -> Unit,
    onForceDispatch: (String) -> Unit,
    onUpload: (ByteArray, AppUploadMeta, String) -> Unit,
    onDelete: (String) -> Unit,
    onUninstall: (String) -> Unit
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@rememberLauncherForActivityResult
        val meta = parseApkMeta(context, uri) ?: return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex("_display_name")
                if (idx >= 0) c.getString(idx) else null
            } else null
        } ?: "app.apk"
        onUpload(bytes, meta, name)
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SheetTitle(
                "应用分发",
                "上传 APK 到应用库，再一键静默下发 / 卸载到被控平板（「删除」仅移除库条目，不动平板）"
            )
            Button(
                onClick = { picker.launch("application/vnd.android.package-archive") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !uploading
            ) {
                if (uploading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在上传…")
                } else {
                    Text("上传 APK 到应用库")
                }
            }
            if (apps.isEmpty()) {
                SheetLoading("应用库为空，先上传一个 APK")
            } else {
                apps.forEach { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                        text = "v${app.versionName} · ${formatSize(app.sizeBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            // 四个动作语义各不相同，必须分开摆、不可合并：
                            // 上一行作用于平板的「安装」类动作（普通分发会被服务端拦，强制重装才无条件下发），
                            // 下一行是「移除」类动作（卸载作用于平板，删除只移除应用库条目）。
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { onDispatch(app.id) }) {
                                    Text("分发安装")
                                }
                                TextButton(onClick = { onForceDispatch(app.id) }) {
                                    Text("强制重装")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { onUninstall(app.packageName) }) {
                                    Text("卸载")
                                }
                                TextButton(onClick = { onDelete(app.id) }) {
                                    Text("删除", color = PadGuardColors.WarningRed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}

/**
 * 版本展示：优先用 versionName，缺失 / 空串时回落到 versionCode。
 *
 * 不能直接写 `versionName ?: versionCode` —— 两侧类型不同（String? / Long），
 * 表达式会退化成 Any?，既丢类型安全又让空值分支的展示不可控。
 */
private fun displayVersion(versionName: String?, versionCode: Long): String {
    val name = versionName?.takeIf { it.isNotBlank() }
    return if (name != null) "v$name" else "v$versionCode"
}

// ==================== 远程升级 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpgradeSheet(
    uiState: com.padguard.parent.presentation.viewmodel.DeviceDetailUiState,
    onClose: () -> Unit,
    onStartUpgrade: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SheetTitle("远程升级维护", "目录库版本高于设备已装版本时可远程升级")
            val items = uiState.upgradeItems
            if (items.isEmpty()) {
                SheetLoading("正在检查版本…")
            } else {
                items.forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            InfoLine("应用", item.appName)
                            // 未安装时 installedVersionCode=0，此时显示 v0 会让家长误以为"装了个 0 版"
                            InfoLine(
                                "已装版本",
                                if (item.installedVersionCode > 0) {
                                    displayVersion(item.installedVersionName, item.installedVersionCode)
                                } else {
                                    "未安装"
                                }
                            )
                            InfoLine("最新版本", displayVersion(item.catalogVersionName, item.catalogVersionCode))
                            if (item.upgradeAvailable) {
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
                        onClick = { onStartUpgrade(item.catalogId) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = item.upgradeAvailable && !uiState.isUpgrading,
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
                            Text(
                                if (item.upgradeAvailable) {
                                    "一键升级到 ${displayVersion(item.catalogVersionName, item.catalogVersionCode)}"
                                } else {
                                    "无需升级"
                                }
                            )
                        }
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
    uiState: com.padguard.parent.presentation.viewmodel.DeviceDetailUiState,
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
