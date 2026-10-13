package com.padguard.child.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.child.ui.components.GlyphKind
import com.padguard.child.ui.settings.ServerConfigDialog
import android.graphics.BitmapFactory
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri

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
    var showStudentNameDialog by remember { mutableStateOf(false) }
    var studentNameInput by remember { mutableStateOf("") }
    var showNicknameDialog by remember { mutableStateOf(false) }
    var nicknameInput by remember { mutableStateOf("") }
    var showLogoutDialog by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.saveAvatar(uri)
    }

    val context = LocalContext.current
    val openGuardA11ySettings = {
        // 操作闸门无障碍服务的开启属于"改动系统设置"，必须先过家长密码闸门。
        // 闸门放行（密码未启用 / 处于解锁窗口）才跳转；否则 Gate 已拉起解锁界面，原动作中止。
        if (viewModel.ensureGuardUnlocked(context)) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    ProfileContent(
        state = state,
        onPermissionGuide = onPermissionGuide,
        onOpenGuardA11y = openGuardA11ySettings,
        onSetServer = {
            // 服务器地址可被孩子无密码修改——改成不可达 IP 即让家长端"看不到"设备、
            // 实时锁屏/消息/放行全失效。现统一过家长密码闸门（同页「去设置」入口同一范式）。
            if (viewModel.ensureGuardUnlocked(context)) {
                serverInput = serverUrl
                showServerDialog = true
            }
        },
        onEditDeviceName = {
            if (viewModel.ensureGuardUnlocked(context)) {
                nameInput = state.deviceName.ifBlank { state.deviceSn }
                showNameDialog = true
            }
        },
        onEditStudentName = {
            if (viewModel.ensureGuardUnlocked(context)) {
                studentNameInput = state.studentName
                showStudentNameDialog = true
            }
        },
        onEditNickname = {
            nicknameInput = state.childNickname
            showNicknameDialog = true
        },
        onSetAvatar = { avatarPicker.launch("image/*") },
        onClearCredentials = viewModel::clearRememberedCredentials,
        onLogout = { showLogoutDialog = true }
    )

    if (showLogoutDialog) {
        // 二次确认：点错一下就登出、又要输一遍密码，代价不对称。
        // 同时把"会发生什么"讲清楚 —— 尤其说明设备受控不会被解除，避免家长误以为登出=解绑。
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出家庭账号？") },
            text = {
                Text(
                    "退出后需要重新输入家长账号密码登录。\n\n" +
                        "**设备受控关系会保留**：家长仍能看到这台平板，" +
                        "若要彻底解除管控，请在家长端「我的孩子 → 解绑设备」。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.logout()
                }) { Text("退出登录") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            }
        )
    }

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

    if (showStudentNameDialog) {
        TextFieldDialog(
            title = "修改孩子姓名",
            placeholder = "例如：小明",
            initial = studentNameInput,
            onDismiss = { showStudentNameDialog = false },
            onSave = { name ->
                viewModel.saveStudentName(name)
                showStudentNameDialog = false
            }
        )
    }

    if (showNicknameDialog) {
        TextFieldDialog(
            title = "修改昵称",
            placeholder = "例如：小可爱",
            initial = nicknameInput,
            onDismiss = { showNicknameDialog = false },
            onSave = { nickname ->
                viewModel.saveNickname(nickname)
                showNicknameDialog = false
            }
        )
    }
}

@Composable
private fun ProfileContent(
    state: ProfileUiState,
    onPermissionGuide: (String) -> Unit,
    onOpenGuardA11y: () -> Unit,
    onSetServer: () -> Unit,
    onEditDeviceName: () -> Unit,
    onEditStudentName: () -> Unit,
    onEditNickname: () -> Unit,
    onSetAvatar: () -> Unit,
    onClearCredentials: () -> Unit,
    /** 退出家庭账号：回调交给 ProfileScreen 弹二次确认，本函数只负责入口 */
    onLogout: () -> Unit
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
            // 操作密码闸门基础设施告警：密码已启用但无障碍服务未开 → 拦截失效，必须引导开启
            if (state.guardInfraWarning) {
                item {
                    GuardInfraWarningBanner(onOpen = onOpenGuardA11y)
                }
            }
            item {
                DeviceCard(
                    state = state,
                    onEditDeviceName = onEditDeviceName,
                    onEditStudentName = onEditStudentName,
                    onEditNickname = onEditNickname,
                    onSetAvatar = onSetAvatar
                )
            }
            item {
                CredentialsCard(state = state, onClear = onClearCredentials)
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
                    batteryOptimizationWhitelisted = state.batteryOptimizationWhitelisted,
                    onClickDeviceAdmin = { onPermissionGuide("device_admin") },
                    onClickUsageStats = { onPermissionGuide("usage_stats") },
                    onClickBatteryOptimization = { onPermissionGuide("battery_optimization") }
                )
            }
            item {
                AppControlDiagnosticsCard(
                    state = state,
                    onClickUsageStats = { onPermissionGuide("usage_stats") },
                    onClickDeviceOwner = { onPermissionGuide("device_admin") }
                )
            }
            // 设备所有者(DO/PO)专属能力矩阵：如实标注本机可用 / 需 DO，让家长知道降级边界
            item {
                OwnerCapabilityCard(state = state)
            }
            item {
                Text(
                    text = "更多",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            item {
                MoreCard(
                    onSetServer = onSetServer,
                    canLogout = state.isChildLoggedIn,
                    onLogout = onLogout
                )
            }
        }
    }
}

@Composable
/**
 * 已记录的家长端账号密码。
 *
 * 密码默认掩码显示，点「显示」才明文；一键「清除记录」会立刻从本机删除。
 * 说明文案明确告知"仅保存在本机"，避免误解为上传到服务器。
 */
private fun CredentialsCard(state: ProfileUiState, onClear: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "账号与密码", style = MaterialTheme.typography.titleMedium)
            if (state.account.isBlank()) {
                Text(
                    text = "未记录。登录或绑定时勾选「记住账号密码」后显示在此。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = "账号：${state.account}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = "密码：" + if (revealed) state.accountPassword else "••••••",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { revealed = !revealed }) {
                        Text(if (revealed) "隐藏" else "显示")
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = "仅保存在本机，用于免重复输入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClear) { Text("清除记录") }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    state: ProfileUiState,
    onEditDeviceName: () -> Unit,
    onEditStudentName: () -> Unit,
    onEditNickname: () -> Unit,
    onSetAvatar: () -> Unit
) {
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
                // 头像：有自定义头像（本地 URI 或服务端 URL）时加载真实图片，否则回退到姓名首字
                AsyncAvatar(
                    source = state.childAvatar,
                    letter = state.studentName,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onSetAvatar)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.studentName.isBlank()) "未命名孩子" else state.studentName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (state.childNickname.isNotBlank()) {
                        Text(
                            text = "昵称：${state.childNickname}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
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
                TextButton(onClick = onSetAvatar) { Text("设置头像") }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            // 孩子姓名（自定义资料，实时同步至服务端 + 家长端）
            ProfileFieldRow(
                label = "孩子姓名",
                value = if (state.studentName.isBlank()) "未设置" else state.studentName,
                onClick = onEditStudentName
            )
            // 昵称
            ProfileFieldRow(
                label = "昵称",
                value = if (state.childNickname.isBlank()) "未设置" else state.childNickname,
                onClick = onEditNickname
            )
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

/**
 * 头像图片：source 为本地 content URI（选图后预览）或服务端 http(s) URL（上传后回显）。
 * 两者都通过 Bitmap 流式解码，零额外依赖；加载失败 / 为空时回退到姓名首字。
 */
@Composable
private fun AsyncAvatar(
    source: String,
    letter: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(source) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(source) {
        bitmap = null
        if (source.isNotBlank()) {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = if (source.startsWith("content://")) {
                        context.contentResolver.openInputStream(Uri.parse(source))
                    } else {
                        java.net.URL(source).openStream()
                    }
                    stream?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.primary, modifier = modifier) {
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
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

/** 可点击的"字段行"：用于孩子姓名 / 昵称等自定义资料的修改入口 */
@Composable
private fun ProfileFieldRow(label: String, value: String, onClick: () -> Unit) {
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "修改",
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
private fun TextFieldDialog(
    title: String,
    placeholder: String,
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
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
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
    batteryOptimizationWhitelisted: Boolean,
    onClickDeviceAdmin: () -> Unit,
    onClickUsageStats: () -> Unit,
    onClickBatteryOptimization: () -> Unit
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
            HorizontalDivider()
            // 电池优化白名单入口。设备息屏进入 Doze 后若不加豁免，
            // 后台 MQTT 心跳/定位/推送被冻结，每晚即脱管。引导用户手动加入白名单。
            // active 取真实白名单状态（ProfileViewModel.isBatteryOptimizationWhitelisted），
            // 不写死 —— 假状态比没有状态更坏。
            PermissionRow(
                title = "电池优化白名单",
                subtitle = "加入豁免名单，确保息屏后仍能正常管控（锁屏/定位/推送）",
                active = batteryOptimizationWhitelisted,
                onClick = onClickBatteryOptimization
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

/**
 * 应用管控自检。
 *
 * ## 为什么必须有这张卡
 * "家长端禁了应用、孩子端却照常能打开"是本产品最高频的投诉，而它的成因有三条，
 * **任意一条缺失都会静默失效**：
 *   1. 名单根本没收到（家长端没配置 / 策略没下发）；
 *   2. 「使用情况访问」没授权 —— 探不到当前前台是哪个应用，拦截无从谈起，
 *      而这权限连设备所有者都无权代授，必须手动开；
 *   3. 不是设备所有者 —— 隐藏 / 挂起应用只有 DO/PO 能做，只激活设备管理员时
 *      只能做到"打开时弹拦截页"，图标不会消失。
 *
 * 把三件事摊开显示 + 给出结论与补救入口，家长不用猜、也不必再来问客服。
 * 这里刻意**不写"已开启/未开启"这种含糊状态**：每一行的读数是可直接行动的事实。
 */
@Composable
private fun AppControlDiagnosticsCard(
    state: ProfileUiState,
    onClickUsageStats: () -> Unit,
    onClickDeviceOwner: () -> Unit
) {
    val hasList = state.appListCount > 0 || state.appSystemRuleCount > 0
    val verdict = when {
        !hasList ->
            "家长端还没有下发应用名单。请先在家长端「应用管理」勾选要管控的应用并保存，" +
                "保存后最多 30 秒同步到本机。"
        !state.usageStatsGranted ->
            "名单已收到，但「使用情况访问」未授权 —— 本机探不到孩子当前打开的是哪个应用，" +
                "拦截不会发生。点下方按钮开启即可生效（不需要设备所有者权限）。"
        state.canHideApps ->
            "应用管控已完整生效：被管控应用会从桌面隐藏，打开时也会被拦截。"
        else ->
            "名单已收到、拦截可用，但本机只是设备管理员 —— 应用图标不会消失，" +
                "仅在孩子打开时弹出拦截页。若要彻底隐藏，需把平板设为设备所有者（DO）。"
    }
    val verdictOk = hasList && state.usageStatsGranted

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("应用管控自检", style = MaterialTheme.typography.titleMedium)

            DiagnosticRow(
                label = "应用名单",
                value = if (hasList) {
                    "${state.appListModeLabel} ${state.appListCount} 条" +
                        if (state.appSystemRuleCount > 0) " · 系统规则 ${state.appSystemRuleCount} 条" else ""
                } else {
                    "未收到（0 条）"
                },
                ok = hasList
            )
            DiagnosticRow(
                label = "识别前台应用",
                value = if (state.usageStatsGranted) "已授权" else "未授权 · 无法拦截",
                ok = state.usageStatsGranted
            )
            DiagnosticRow(
                label = "隐藏 / 挂起应用",
                value = when {
                    state.canHideApps -> "可用（设备所有者）"
                    state.canSuspendApps -> "仅可挂起"
                    else -> "不可用 · 需设备所有者(DO)"
                },
                ok = state.canHideApps
            )

            Surface(
                color = (if (verdictOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    .copy(alpha = 0.08f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = verdict,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }

            if (!state.usageStatsGranted || !state.canHideApps) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.usageStatsGranted) {
                        TextButton(onClick = onClickUsageStats) { Text("开启使用情况访问") }
                    }
                    if (!state.canHideApps) {
                        TextButton(onClick = onClickDeviceOwner) { Text("查看 DO 权限说明") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Close,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
    }
}

/**
 * 操作密码闸门基础设施告警横幅。
 *
 * 仅在「家长已启用操作密码，但操作闸门无障碍服务未开启」时出现：
 * 此时系统级拦截失效，孩子可从设置/通知栏绕过密码。引导家长（经密码闸门校验后）
 * 跳转无障碍设置开启服务。
 */
@Composable
private fun GuardInfraWarningBanner(onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "操作密码闸门未完全生效",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "已启用操作密码，但「操作闸门无障碍服务」尚未在系统设置中开启。在此期间孩子可从设置 / 通知栏绕过密码，请尽快开启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = onOpen) {
                Text(text = "去开启无障碍服务")
            }
        }
    }
}

/**
 * 设备所有者(DO/PO)专属能力矩阵卡。
 *
 * 逐项如实标注本机可用 / 不可用（不可用显式标注"需设备所有者(DO)"），
 * 让存量 C 端平板（无 DO）的家长清楚知道哪些管控在本机只是"降级事后拦截"，
 * 而不是误以为策略下发即生效。复用 [DiagnosticRow] 保持视觉一致。
 */
@Composable
private fun OwnerCapabilityCard(state: ProfileUiState) {
    val caps = state.ownerCapabilities
    if (caps.isEmpty()) return
    val allOk = caps.all { it.supported }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("设备所有者能力", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (allOk) {
                    "当前为设备所有者(DO)模式，以下能力全部可用。"
                } else {
                    "以下能力需要平板处于「设备所有者(DO)」模式。当前设备未启用 DO，标记项不可用，" +
                        "相关管控将降级为无障碍服务事后拦截。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
            caps.forEach { cap ->
                DiagnosticRow(
                    label = cap.name,
                    value = if (cap.supported) "可用" else cap.note,
                    ok = cap.supported
                )
            }
        }
    }
}

@Composable
private fun MoreCard(
    onSetServer: () -> Unit,
    canLogout: Boolean,
    onLogout: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            SimpleRow(title = "⚙ 高级设置", subtitle = "服务器地址 / 端口等（连不上管控端时修改）", onClick = onSetServer)
            HorizontalDivider()
            SimpleRow(title = "应用管理", subtitle = "查看本机已安装应用与受限情况")
            HorizontalDivider()
            SimpleRow(title = "帮助与反馈", subtitle = "常见问题 + 提交反馈")
            HorizontalDivider()
            SimpleRow(title = "关于护航成长", subtitle = "版本号 / 开源许可")
            // 退出登录：装上 App 后唯一能"换个账号 / 退出家庭账号"的地方。
            // 之前没做，导致装完就退不出去、换手机号只能清数据。
            if (canLogout) {
                HorizontalDivider()
                SimpleRow(
                    title = "退出家庭账号",
                    subtitle = "仅退出家长账号登录，设备受控关系保留",
                    onClick = onLogout,
                    titleColor = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 服务器地址设置弹窗已统一迁移到 [com.padguard.child.ui.settings.ServerConfigDialog]，
 * 「我的 → 更多 → 高级设置」与绑定欢迎页共用，避免重复实现。
 */
@Composable
private fun SimpleRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit = {},
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
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
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = titleColor)
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
