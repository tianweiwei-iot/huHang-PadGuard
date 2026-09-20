package com.padguard.presentation.ui.control

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.padguard.domain.model.ControlMode
import com.padguard.presentation.ui.components.SoftCard
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.util.TimeFormat
import com.padguard.presentation.viewmodel.ControlPolicyUiState
import com.padguard.presentation.viewmodel.ControlPolicyViewModel
import com.padguard.presentation.viewmodel.FamilyPreset
import kotlinx.coroutines.flow.collectLatest

/**
 * 管控策略页（纯家庭场景，四栏：时长 / 应用 / 上网 / 系统）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlPolicyScreen(
    deviceId: String,
    onBack: () -> Unit,
    viewModel: ControlPolicyViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("时长", "应用", "上网", "系统")

    LaunchedEffect(Unit) {
        viewModel.uiState.collectLatest {
            if (it.toast != null) { snackbarHostState.showSnackbar(it.toast); viewModel.clearToast() }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(uiState.device?.name ?: "管控策略") },
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            when (selectedTab) {
                0 -> TimePolicyTab(uiState, viewModel)
                1 -> AppPolicyTab(uiState, viewModel)
                2 -> WebPolicyTab(uiState, viewModel)
                3 -> SystemPolicyTab(uiState, viewModel)
            }
        }
    }
}

// ==================== 时长 ====================

@Composable
private fun TimePolicyTab(uiState: ControlPolicyUiState, viewModel: ControlPolicyViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle("家庭模板（一键应用）")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PresetChip("上学日", Icons.Default.School) { viewModel.applyPreset(FamilyPreset.SCHOOL_DAY) }
                PresetChip("周末", Icons.Default.Weekend) { viewModel.applyPreset(FamilyPreset.WEEKEND) }
                PresetChip("假期", Icons.Default.Celebration) { viewModel.applyPreset(FamilyPreset.HOLIDAY) }
                PresetChip("夜间锁机", Icons.Default.Bedtime) { viewModel.applyPreset(FamilyPreset.NIGHT_LOCK) }
            }
        }

        item {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("每日总使用时长", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    var minutes by remember(uiState.dailyLimitMinutes) { mutableIntStateOf(uiState.dailyLimitMinutes) }
                    Text("${TimeFormat.formatDurationFromMinutes(minutes)} / 天", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Slider(
                        value = minutes.toFloat(),
                        onValueChange = { minutes = it.toInt() },
                        valueRange = 30f..480f,
                        steps = 14
                    )
                    Button(
                        onClick = { viewModel.setDailyLimit(minutes) },
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("保存") }
                }
            }
        }

        item { SectionTitle("分时段限制") }

        items(uiState.timeRestrictions) { tr ->
            TimeRestrictionItem(
                dayLabel = dayLabel(tr.dayOfWeek),
                timeRange = "${TimeFormat.formatClock(tr.startTime)} - ${TimeFormat.formatClock(tr.endTime)}",
                maxMinutes = tr.maxMinutes,
                enabled = tr.isEnabled
            ) { viewModel.toggleTimeRestriction(tr) }
        }
    }
}

@Composable
private fun PresetChip(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(name) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
    )
}

@Composable
private fun TimeRestrictionItem(
    dayLabel: String,
    timeRange: String,
    maxMinutes: Int?,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(dayLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "$timeRange · 限 ${maxMinutes?.let { TimeFormat.formatDurationFromMinutes(it) } ?: "不限"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            Switch(checked = enabled, onCheckedChange = { onToggle() })
        }
    }
}

// ==================== 应用 ====================

@Composable
private fun AppPolicyTab(uiState: ControlPolicyUiState, viewModel: ControlPolicyViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SectionTitle("应用黑名单与时长限制") }
        items(uiState.installedApps) { app ->
            AppPolicyItem(
                appName = app.appName,
                blocked = app.isBlocked,
                dailyLimit = app.dailyLimitMinutes,
                onToggleBlock = { viewModel.toggleAppBlock(app, it) },
                onCycleLimit = {
                    val next = when (app.dailyLimitMinutes) {
                        null -> 30
                        30 -> 60
                        60 -> 90
                        90 -> null
                        else -> null
                    }
                    viewModel.setAppTimeLimit(app, next)
                }
            )
        }
    }
}

@Composable
private fun AppPolicyItem(
    appName: String,
    blocked: Boolean,
    dailyLimit: Int?,
    onToggleBlock: (Boolean) -> Unit,
    onCycleLimit: () -> Unit
) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(appName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (blocked) "已禁用" else "已允许",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (blocked) PadGuardColors.WarningRed else PadGuardColors.SuccessGreen
                )
            }
            AssistChip(
                onClick = onCycleLimit,
                label = { Text(if (dailyLimit == null) "不限时" else "限时 ${TimeFormat.formatDurationFromMinutes(dailyLimit)}") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = blocked, onCheckedChange = onToggleBlock)
        }
    }
}

// ==================== 上网 ====================

@Composable
private fun WebPolicyTab(uiState: ControlPolicyUiState, viewModel: ControlPolicyViewModel) {
    var urlInput by remember { mutableStateOf("") }
    val web = uiState.webPolicy
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("禁用浏览器", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text("关闭后孩子无法打开网页浏览器", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    }
                    Switch(
                        checked = web?.browserDisabled ?: false,
                        onCheckedChange = { viewModel.setBrowserDisabled(it) }
                    )
                }
            }
        }

        item {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("网址黑名单", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("输入要拦截的网址") },
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = { viewModel.addUrlBlock(urlInput); urlInput = "" }) {
                            Icon(Icons.Default.Add, contentDescription = "添加", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    (web?.blockedUrls ?: emptyList()).forEach { url ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Block, contentDescription = null, tint = PadGuardColors.WarningRed, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(url, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.removeUrlBlock(url) }) {
                                Icon(Icons.Default.Close, contentDescription = "移除", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                            }
                        }
                    }
                    if ((web?.blockedUrls ?: emptyList()).isEmpty()) {
                        Text("暂无拦截网址", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
                    }
                }
            }
        }

        item {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("定时锁机（夜间锁机）", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("在设定时段内自动锁屏", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        }
                        Switch(
                            checked = web?.smartShutdownEnabled ?: false,
                            onCheckedChange = { viewModel.setSmartShutdown(it, web?.smartShutdownStartTime, web?.smartShutdownEndTime) }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = web?.smartShutdownStartTime ?: "",
                            onValueChange = { viewModel.setSmartShutdown(web?.smartShutdownEnabled ?: true, it, web?.smartShutdownEndTime) },
                            modifier = Modifier.weight(1f),
                            label = { Text("开始 HH:mm") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = web?.smartShutdownEndTime ?: "",
                            onValueChange = { viewModel.setSmartShutdown(web?.smartShutdownEnabled ?: true, web?.smartShutdownStartTime, it) },
                            modifier = Modifier.weight(1f),
                            label = { Text("结束 HH:mm") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                }
            }
        }
    }
}

// ==================== 系统 ====================

@Composable
private fun SystemPolicyTab(uiState: ControlPolicyUiState, viewModel: ControlPolicyViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Button(
                onClick = { viewModel.lockScreen() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PadGuardColors.WarningRed)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("一键锁屏")
            }
        }

        item {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("护眼模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("滤蓝光", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = uiState.eyeProtectionEnabled,
                            onCheckedChange = { viewModel.setEyeProtection(it, uiState.eyeProtectionLevel) }
                        )
                    }
                    var level by remember(uiState.eyeProtectionLevel) { mutableIntStateOf(uiState.eyeProtectionLevel) }
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { level = it.toInt() },
                        valueRange = 1f..5f,
                        steps = 3,
                        enabled = uiState.eyeProtectionEnabled
                    )
                    Text("强度：$level 级", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    LaunchedEffect(level) {
                        if (uiState.eyeProtectionEnabled) viewModel.setEyeProtection(true, level)
                    }
                }
            }
        }

        item {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("管控模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    val current = uiState.device?.controlMode ?: ControlMode.NORMAL
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ControlModeChip("正常", current == ControlMode.NORMAL) { viewModel.setControlMode(ControlMode.NORMAL) }
                        ControlModeChip("学习", current == ControlMode.LEARNING) { viewModel.setControlMode(ControlMode.LEARNING) }
                        ControlModeChip("专注", current == ControlMode.FOCUS) { viewModel.setControlMode(ControlMode.FOCUS) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlModeChip(name: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(name) }
    )
}

// ==================== 通用 ====================

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
}

private fun dayLabel(dayOfWeek: Int): String {
    return when (dayOfWeek) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        7 -> "周日"
        else -> "第${dayOfWeek}天"
    }
}
