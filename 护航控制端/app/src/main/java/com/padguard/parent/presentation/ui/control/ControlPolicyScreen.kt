package com.padguard.parent.presentation.ui.control

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.padguard.parent.domain.model.ControlMode
import com.padguard.parent.presentation.ui.components.SoftCard
import com.padguard.parent.presentation.ui.theme.PadGuardColors
import com.padguard.parent.presentation.util.TimeFormat
import com.padguard.parent.presentation.viewmodel.ControlPolicyUiState
import com.padguard.parent.presentation.viewmodel.ControlPolicyViewModel
import com.padguard.parent.presentation.viewmodel.FamilyPreset
import kotlinx.coroutines.flow.collect

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
        // 用 collect 而非 collectLatest：collectLatest 会在下一次 state 变化（哪怕只是
        // isLoading / 计时变化）时取消正在显示的 showSnackbar —— "保存失败"可能刚弹出就被吞掉。
        viewModel.uiState.collect { st ->
            // 失败必须可见：此前只消费 toast，而所有写操作失败都落在 error ——
            // 家长以为已下发、孩子端毫无变化，是最不能接受的形态。
            val msg = st.toast ?: st.error ?: return@collect
            snackbarHostState.showSnackbar(msg)
            // 只清除"刚刚展示的那一条"：展示期间若有新错误到达，不能连它一起清掉
            val now = viewModel.uiState.value
            if (now.toast == msg) viewModel.clearToast() else if (now.error == msg) viewModel.clearError()
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
    // 编辑态必须提到 LazyColumn 之外：LazyListScope 里不能直接放可组合项（Dialog），
    // 且条目滚出屏幕被回收时，正在编辑的草稿会一起丢掉。
    var editing by remember { mutableStateOf<TimeRestrictionDraft?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 从首页「去处理」进来时首先看到的就是这块：解锁 / 限时解锁 / 改每日时长
        item {
            SectionTitle("解锁与放行")
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "孩子申请解锁后，可在此一键放行；也可只改每日时长，让他按新额度继续使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.unlockNow() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) { Text("一键解锁") }
                        OutlinedButton(
                            onClick = { viewModel.lockScreen() },
                            modifier = Modifier.weight(1f)
                        ) { Text("立即锁屏") }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("限时放行", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60).forEach { m ->
                            OutlinedButton(onClick = { viewModel.tempUnlock(m) }) { Text("${m}分钟") }
                        }
                    }
                }
            }
        }

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
                    if (uiState.minorMode?.enabled == true) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "未成年人模式开启时，仍以你在此设置的使用时长为准（家长可自由设置，含高于分龄上限）；仅当你未设置时回落分龄合规基线作为兜底。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
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

        // 服务端 POST/PUT/DELETE 早已齐备，而新设备没有任何种子数据 ——
        // 原先只有列表 + 开关（无新增/编辑/删除），这块区域对绝大多数家长永远是空的，
        // "分时段锁机"实际等于不存在。现补出完整 CRUD 入口（编辑态见函数头部）。

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { SectionTitle("分时段限制") }
                TextButton(onClick = { editing = TimeRestrictionDraft() }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新增时段")
                }
            }
        }

        when {
            // 三态：读取中 / 读取失败 / 读到（可能为空）
            !uiState.timeRestrictionsLoaded -> item {
                Text(
                    "正在读取时段限制…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
            uiState.timeRestrictionsError != null -> item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "读取失败：${uiState.timeRestrictionsError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.load() }) { Text("重试") }
                }
            }
            uiState.timeRestrictions.isEmpty() -> item {
                SoftCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "还没有任何时段限制",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "例如「周一至周五 21:00-22:00 只能玩 30 分钟」，未添加时该时段不受额外限制。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { editing = TimeRestrictionDraft() }) { Text("添加第一条") }
                    }
                }
            }
            else -> items(uiState.timeRestrictions) { tr ->
                TimeRestrictionItem(
                    dayLabel = dayLabel(tr.dayOfWeek),
                    timeRange = "${TimeFormat.formatClock(tr.startTime)} - ${TimeFormat.formatClock(tr.endTime)}",
                    maxMinutes = tr.maxMinutes,
                    enabled = tr.isEnabled,
                    onToggle = { viewModel.toggleTimeRestriction(tr) },
                    onEdit = {
                        editing = TimeRestrictionDraft(
                            id = tr.id,
                            dayOfWeek = tr.dayOfWeek,
                            start = tr.startTime,
                            end = tr.endTime,
                            limitEnabled = tr.maxMinutes != null,
                            limitMinutes = (tr.maxMinutes ?: 60).toString(),
                            enabled = tr.isEnabled
                        )
                    }
                )
            }
        }
    }

    editing?.let { draft ->
        TimeRestrictionEditor(
            draft = draft,
            onDraftChange = { editing = it },
            onDismiss = { editing = null },
            onSave = {
                val err = viewModel.saveTimeRestriction(
                    id = draft.id,
                    dayOfWeek = draft.dayOfWeek,
                    startTime = draft.start,
                    endTime = draft.end,
                    maxMinutes = if (draft.limitEnabled) draft.limitMinutes.toIntOrNull() else null,
                    isEnabled = draft.enabled
                )
                if (err == null) editing = null else editing = draft.copy(error = err)
            },
            onDelete = draft.id?.let { id ->
                {
                    viewModel.deleteTimeRestriction(id)
                    editing = null
                }
            }
        )
    }
    }
}

/** 时段编辑草稿：只在本地保存，点保存才写服务端（避免每个按键一次网络写）。 */
private data class TimeRestrictionDraft(
    val id: String? = null,
    val dayOfWeek: Int = 1,
    val start: String = "21:00",
    val end: String = "22:00",
    val limitEnabled: Boolean = true,
    val limitMinutes: String = "60",
    val enabled: Boolean = true,
    val error: String? = null
)

@Composable
private fun TimeRestrictionEditor(
    draft: TimeRestrictionDraft,
    onDraftChange: (TimeRestrictionDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "新增时段限制" else "编辑时段限制") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("重复日", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (1..7).forEach { day ->
                        FilterChip(
                            selected = draft.dayOfWeek == day,
                            onClick = { onDraftChange(draft.copy(dayOfWeek = day, error = null)) },
                            label = { Text(dayLabel(day).removePrefix("周")) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.start,
                        onValueChange = { onDraftChange(draft.copy(start = it, error = null)) },
                        label = { Text("开始") },
                        placeholder = { Text("21:00") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = draft.end,
                        onValueChange = { onDraftChange(draft.copy(end = it, error = null)) },
                        label = { Text("结束") },
                        placeholder = { Text("22:00") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("该时段限时", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "开启后，该时段最多可用指定分钟数；关闭则只限制在时段内使用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = draft.limitEnabled,
                        onCheckedChange = { onDraftChange(draft.copy(limitEnabled = it, error = null)) }
                    )
                }
                if (draft.limitEnabled) {
                    OutlinedTextField(
                        value = draft.limitMinutes,
                        onValueChange = { onDraftChange(draft.copy(limitMinutes = it.filter(Char::isDigit), error = null)) },
                        label = { Text("分钟数（1~1440）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用该时段", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { onDraftChange(draft.copy(enabled = it, error = null)) }
                    )
                }
                draft.error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
        dismissButton = {
            Row {
                onDelete?.let { del ->
                    TextButton(onClick = del) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
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
    onToggle: () -> Unit,
    onEdit: () -> Unit
) {
    // 整卡可点进入编辑：原先只有开关，家长想改时间/时长/星期几都无从下手。
    SoftCard(modifier = Modifier.fillMaxWidth().clickable { onEdit() }) {
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
        item {
            val apps = uiState.installedApps
            val allBlocked = apps.isNotEmpty() && apps.all { it.isBlocked }
            AllAppsControlCard(
                appCount = apps.size,
                allBlocked = allBlocked,
                onToggle = { viewModel.setAllAppsBlocked(it) }
            )
        }
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
private fun AllAppsControlCard(
    appCount: Int,
    allBlocked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "一键管控全部应用",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (allBlocked) "已管控 $appCount 个应用，孩子端不再显示"
                    else "开启后，全部 $appCount 个应用在孩子端隐藏；系统必要应用自动保留",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = allBlocked, onCheckedChange = onToggle)
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
        // 生效前提如实说明：上网过滤走孩子端「上网防护」VPN 通道（域名级拦截）。
        // 该通道已落地，但非设备所有者（无 DO）的机器需要孩子端完成一次系统 VPN 授权，
        // 否则家长配了规则也不会拦 —— 必须把"为什么没生效、去哪儿补授权"讲清楚，
        // 而不是像旧文案那样一句"本版本未启用"（通道上线后已不成立，会让家长以为功能不存在）。
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = PadGuardColors.WarningRed.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = PadGuardColors.WarningRed,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "上网过滤需孩子端授权后生效",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PadGuardColors.WarningRed
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "网址黑名单、禁用浏览器由孩子端「上网防护」通道（VPN）执行域名级拦截。" +
                                "已部署为设备所有者的平板会自动授权；其余设备需孩子在「我的 → 权限引导 → 上网防护」完成一次系统授权，" +
                                "未授权时这些规则不会拦截。定时锁机、应用管控、使用时长不依赖该通道，照常生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

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
                    // 时间输入必须是本地草稿：此前 value 直接绑服务端回写状态 + 每个按键都发一次网络写，
                    // 保存成功前 value 被旧状态拉回，连续输入 "22:00" 会逐字丢失/乱序，弱网下每字符一次 POST。
                    SmartShutdownTimeEditor(
                        startTime = web?.smartShutdownStartTime,
                        endTime = web?.smartShutdownEndTime,
                        onSubmit = { s, e ->
                            viewModel.setSmartShutdown(web?.smartShutdownEnabled ?: true, s, e)
                        }
                    )
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
                        // 只在松手时提交：此前用 LaunchedEffect(level) 提交，切回本 Tab 首组合必触发一次，
                        // 护眼开着就每进一次页面多发一条指令 + 弹一次"已开启"提示。
                        onValueChangeFinished = {
                            if (uiState.eyeProtectionEnabled && level != uiState.eyeProtectionLevel) {
                                viewModel.setEyeProtection(true, level)
                            }
                        },
                        valueRange = 1f..5f,
                        steps = 3,
                        enabled = uiState.eyeProtectionEnabled
                    )
                    Text("强度：$level 级", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
            }
        }

        item {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("管控模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    val current = uiState.device?.sceneMode ?: ControlMode.NORMAL
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

/** HH:mm 合法格式（00:00 ~ 23:59）。非法值下发后孩子端行为未定义，必须在输入层拦掉。 */
private val HHMM_REGEX = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")

/**
 * 定时锁机时段编辑器：本地草稿 + 失焦/回车提交。
 *
 * 为什么不直接绑服务端状态：输入框 value 绑回写状态时，保存成功前每次重组都会把用户
 * 正在输入的内容拉回旧值（丢字/乱序）；而 onValueChange 直接调接口会变成"每键一次 POST"。
 */
@Composable
private fun SmartShutdownTimeEditor(
    startTime: String?,
    endTime: String?,
    onSubmit: (String, String) -> Unit
) {
    var start by remember(startTime) { mutableStateOf(startTime ?: "") }
    var end by remember(endTime) { mutableStateOf(endTime ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val s = start.trim()
        val e = end.trim()
        if (s == (startTime ?: "") && e == (endTime ?: "")) return // 无改动不发请求
        if (!HHMM_REGEX.matches(s) || !HHMM_REGEX.matches(e)) {
            error = "时间格式应为 HH:mm（如 22:00）"
            return
        }
        error = null
        onSubmit(s, e)
    }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = start,
                onValueChange = { start = it; error = null },
                modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) submit() },
                label = { Text("开始 HH:mm") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = error != null,
                singleLine = true
            )
            OutlinedTextField(
                value = end,
                onValueChange = { end = it; error = null },
                modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) submit() },
                label = { Text("结束 HH:mm") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = error != null,
                singleLine = true
            )
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
