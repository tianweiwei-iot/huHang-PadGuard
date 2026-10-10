package com.padguard.presentation.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.padguard.domain.model.TabletUsageSettings
import com.padguard.domain.model.TimeRange
import com.padguard.domain.model.UsageSession
import com.padguard.presentation.util.TimeFormat
import com.padguard.presentation.viewmodel.TabletUsageSettingsUiState
import com.padguard.presentation.viewmodel.TabletUsageSettingsViewModel

/**
 * 平板使用时间设置页（清爽浅色 · 舒适紧凑）
 *
 * 视觉体系（仅本页生效，不影响全局 v2.1 清爽卡片风）：
 * - 浅色渐变底（极浅冷灰白 → 淡蓝灰）+ 极淡蓝/薰衣草氛围光，干净通透
 * - 白色卡片：浅灰描边 + 柔和浅投影 + 16dp 圆角，清爽不刺眼
 * - 强调色：蓝 → 紫 渐变贯穿数值、滑条与主按钮，克制点缀
 * - 布局紧凑：卡片内边距 14dp、区块间距 10dp，信息密度适中、一眼看清
 *
 * 功能逻辑与原版一致：
 * - 「是否启用时间管控」总开关：关闭时页面所有设置项灰化、不可更改
 * - 可用时间段（可增删）/ 工作日 / 休息日使用时长 / 休息提醒 / 超时提示文案
 */

// ==================== 浅色视觉令牌（清爽 · 舒适 · 紧凑） ====================
private val BgTop = Color(0xFFF6F8FB)        // 极浅冷灰白
private val BgBottom = Color(0xFFE9EFF7)     // 淡蓝灰
private val GlowCyan = Color(0xFFA9CBF2)     // 柔和蓝（仅作极淡氛围）
private val GlowViolet = Color(0xFFCBBEF2)   // 柔和薰衣草（仅作极淡氛围）
private val AccentCyan = Color(0xFF2F8FE0)   // 主强调蓝（浅底可读）
private val AccentViolet = Color(0xFF7C6CF0) // 辅助紫（浅底可读）
private val AccentGradient = Brush.linearGradient(listOf(AccentCyan, AccentViolet))
private val GlassText = Color(0xFF1F2A3D)    // 主文字（深岩蓝）
private val GlassTextDim = Color(0xFF6B7787) // 次文字（中灰）
private val GlassSurface = Color(0xFFFFFFFF) // 卡片表面白
private val GlassSurfaceAlt = Color(0xFFF2F6FB) // 卡片渐变下沿
private val GlassBorder = Color(0xFFE2E8F1)  // 卡片描边浅灰
private val GlassShape = RoundedCornerShape(16.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletUsageSettingsScreen(
    deviceId: String,
    onBack: () -> Unit,
    viewModel: TabletUsageSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val enabled = uiState.settings.enabled

    LaunchedEffect(deviceId) {
        if (deviceId.isNotEmpty()) {
            viewModel.load(deviceId)
            viewModel.loadSession(deviceId)
        }
    }

    // 每 15 秒刷新一次剩余时长，与服务端/孩子端保持同源一致（服务端锚定 startEpoch + serverNow）
    LaunchedEffect(deviceId) {
        if (deviceId.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(15_000)
            viewModel.loadSession(deviceId)
        }
    }

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) {
            snackbarHostState.showSnackbar("设置已保存并同步至成长端")
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        // 极光辉光：青（右上）+ 紫（左下），压低透明度只做氛围
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.width * 0.75f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(GlowCyan.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(size.width * 0.92f, -size.height * 0.02f),
                    radius = r
                ),
                radius = r,
                center = Offset(size.width * 0.92f, -size.height * 0.02f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(GlowViolet.copy(alpha = 0.32f), Color.Transparent),
                    center = Offset(size.width * 0.05f, size.height * 0.88f),
                    radius = r * 1.1f
                ),
                radius = r * 1.1f,
                center = Offset(size.width * 0.05f, size.height * 0.88f)
            )
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("平板使用时间设置", color = GlassText, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        GlassIconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = GlassText
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = GlassText,
                        navigationIconContentColor = GlassText
                    )
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                GlassSaveButton(isLoading = uiState.isLoading, onClick = viewModel::save)
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { EnableSwitch(enabled = enabled, onToggle = viewModel::updateEnabled) }
                item { TimeRangeSection(uiState, viewModel, enabled) }
                item {
                    DurationControl(
                        title = "工作日使用时长",
                        subtitle = "周一至周五",
                        icon = Icons.Default.WorkOutline,
                        minutes = uiState.settings.weekdayLimitMinutes,
                        enabled = enabled,
                        onUpdate = viewModel::updateWeekdayLimit
                    )
                }
                item {
                    DurationControl(
                        title = "休息日使用时长",
                        subtitle = "周六至周日",
                        icon = Icons.Default.Weekend,
                        minutes = uiState.settings.weekendLimitMinutes,
                        enabled = enabled,
                        onUpdate = viewModel::updateWeekendLimit
                    )
                }
                item { RestSection(settings = uiState.settings, viewModel = viewModel, enabled = enabled) }
                item {
                    UsageSessionCard(session = uiState.usageSession)
                }
            }
        }
    }
}

// ==================== 玻璃基础组件 ====================

/**
 * 水晶玻璃卡片。
 *
 * 立体感构成（自下而上）：
 * 1. 深色环境投影（shadow，让卡片"浮"在深空底上）
 * 2. 半透明竖向渐变填充（上亮下暗，模拟玻璃受光）
 * 3. 渐变描边（顶部亮、底部暗，模拟玻璃切面反光）
 */
@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = GlassShape,
                spotColor = Color(0xFF1F2A3D).copy(alpha = 0.10f),
                ambientColor = Color(0xFF1F2A3D).copy(alpha = 0.06f)
            )
            .clip(GlassShape)
            .background(
                Brush.verticalGradient(
                    listOf(GlassSurface, GlassSurfaceAlt)
                )
            )
            .border(
                width = 1.dp,
                color = GlassBorder,
                shape = GlassShape
            )
            .padding(14.dp),
        content = content
    )
}

/** 卡片标题行：渐变图标徽章 + 标题（+ 可选右侧插槽） */
@Composable
private fun GlassSectionHeader(
    icon: ImageVector,
    title: String,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(AccentGradient)
                .shadow(10.dp, CircleShape, spotColor = AccentCyan.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = GlassText,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** 玻璃圆形小按钮（返回键等） */
@Composable
private fun GlassIconButton(onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(GlassSurface)
            .border(1.dp, GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 底部主按钮：青→紫渐变胶囊 + 同色光晕投影 */
@Composable
private fun GlassSaveButton(isLoading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                spotColor = Color(0xFF1F2A3D).copy(alpha = 0.08f),
                ambientColor = Color(0xFF1F2A3D).copy(alpha = 0.05f)
            )
            .background(GlassSurface)
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .shadow(18.dp, RoundedCornerShape(18.dp), spotColor = AccentViolet.copy(alpha = 0.55f))
                .clip(RoundedCornerShape(18.dp))
                .background(AccentGradient)
                .clickable(enabled = !isLoading, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = "确认并同步至成长端",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

/** 玻璃输入框（时间 / 文案通用）：半透明底 + 聚焦渐变描边 */
@Composable
private fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    center: Boolean = false,
    minLines: Int = 1,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        !enabled -> GlassBorder
        focused -> AccentCyan.copy(alpha = 0.8f)
        else -> GlassBorder
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(GlassSurfaceAlt.copy(alpha = if (enabled) 1f else 0.5f))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 3,
        textStyle = TextStyle(
            color = if (enabled) GlassText else GlassTextDim,
            fontSize = 15.sp,
            textAlign = if (center) TextAlign.Center else TextAlign.Start
        ),
        keyboardOptions = keyboardOptions,
        cursorBrush = AccentGradient,
        decorationBox = { inner ->
            Box(contentAlignment = if (center) Alignment.Center else Alignment.CenterStart) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, color = GlassTextDim, fontSize = 15.sp)
                }
                inner()
            }
        }
    )
}

// ==================== 页面区块 ====================

@Composable
private fun EnableSwitch(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassSectionHeader(icon = Icons.Default.AdminPanelSettings, title = "启用时间管控")
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentCyan,
                    checkedBorderColor = AccentCyan,
                    uncheckedThumbColor = GlassTextDim,
                    uncheckedTrackColor = GlassBorder,
                    uncheckedBorderColor = GlassBorder
                )
            )
        }
        if (!enabled) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "未启用时，以下所有设置不可修改",
                style = MaterialTheme.typography.labelSmall,
                color = GlassTextDim
            )
        }
    }
}

@Composable
private fun TimeRangeSection(
    uiState: TabletUsageSettingsUiState,
    viewModel: TabletUsageSettingsViewModel,
    enabled: Boolean
) {
    GlassCard {
        GlassSectionHeader(
            icon = Icons.Default.Schedule,
            title = "可用时间段",
            trailing = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (enabled) AccentGradient else Brush.linearGradient(listOf(GlassBorder, GlassBorder)))
                        .clickable(enabled = enabled) { viewModel.addTimeRange() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = if (enabled) Color.White else GlassTextDim, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("添加", color = if (enabled) Color.White else GlassTextDim, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        )
        Spacer(modifier = Modifier.height(14.dp))
        if (uiState.settings.enabledTimeRanges.isEmpty()) {
            Text(
                text = "未设置可用时段，将全天允许使用",
                style = MaterialTheme.typography.bodyMedium,
                color = GlassTextDim
            )
        } else {
            uiState.settings.enabledTimeRanges.forEachIndexed { index, range ->
                TimeRangeRow(
                    range = range,
                    index = index,
                    enabled = enabled,
                    onUpdate = { start, end -> viewModel.updateTimeRange(index, start, end) },
                    onRemove = { viewModel.removeTimeRange(index) }
                )
                if (index != uiState.settings.enabledTimeRanges.lastIndex) {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun TimeRangeRow(
    range: TimeRange,
    index: Int,
    enabled: Boolean,
    onUpdate: (String, String) -> Unit,
    onRemove: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "时段 ${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = GlassTextDim
            )
            TimeInput(
                value = range.startTime,
                enabled = enabled,
                onValueChange = { onUpdate(it, range.endTime) },
                modifier = Modifier.weight(1f)
            )
            Text("至", style = MaterialTheme.typography.labelMedium, color = GlassTextDim)
            TimeInput(
                value = range.endTime,
                enabled = enabled,
                onValueChange = { onUpdate(range.startTime, it) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = if (enabled) Color(0xFFFF7A93) else GlassTextDim.copy(alpha = 0.4f),
                modifier = Modifier
                    .size(20.dp)
                    .clickable(enabled = enabled, onClick = onRemove)
            )
        }
    }
}

@Composable
private fun TimeInput(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassTextField(
        value = value,
        onValueChange = {
            val filtered = it.filter { c -> c.isDigit() || c == ':' }
            if (filtered.length <= 5) onValueChange(filtered)
        },
        modifier = modifier,
        enabled = enabled,
        center = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

/**
 * 统一时长控件：玻璃卡片 + 渐变数值 + 青→紫渐变滑条 + 玻璃圆底步进按钮。
 * 步长 30 分钟，范围 0 ~ 24 小时（1440 分钟）。
 */
@Composable
private fun DurationControl(
    title: String,
    subtitle: String,
    icon: ImageVector,
    minutes: Int,
    enabled: Boolean,
    onUpdate: (Int) -> Unit
) {
    GlassCard {
        DurationControlContent(
            title = title,
            subtitle = subtitle,
            icon = icon,
            minutes = minutes,
            enabled = enabled,
            onUpdate = onUpdate
        )
    }
}

/** 时长控件内容体：供独立卡片与「休息提醒」卡内复用，避免卡片嵌套卡片。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DurationControlContent(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    minutes: Int,
    enabled: Boolean,
    onUpdate: (Int) -> Unit
) {
    val maxMinutes = 1440
    val step = 30

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(AccentGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassText
                    )
                    if (subtitle != null) {
                        Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = GlassTextDim)
                    }
                }
            }
            Text(
                text = TimeFormat.formatDurationFromMinutes(minutes),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AccentCyan
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassStepButton(
                icon = Icons.Default.Remove,
                contentDescription = "减少",
                enabled = enabled
            ) { onUpdate((minutes - step).coerceAtLeast(0)) }
            Slider(
                value = minutes.toFloat(),
                onValueChange = {
                    val snapped = ((it.roundToInt() / step) * step).coerceIn(0, maxMinutes)
                    onUpdate(snapped)
                },
                valueRange = 0f..maxMinutes.toFloat(),
                steps = maxMinutes / step - 1,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = AccentCyan,
                    disabledThumbColor = GlassTextDim
                ),
                thumb = {
                    // 圆形水晶旋钮：白心 + 青/紫渐变描边 + 光晕，贴合玻璃质感
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .shadow(10.dp, CircleShape, spotColor = AccentCyan.copy(alpha = 0.75f))
                            .clip(CircleShape)
                            .background(AccentGradient)
                            .border(2.dp, Color.White, CircleShape)
                    )
                },
                track = { sliderState ->
                    val fraction = (sliderState.value - sliderState.valueRange.start) /
                            (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
                    val baseColor = if (enabled) {
                        Color(0xFFE2E8F1)
                    } else {
                        Color(0xFFEDF1F6)
                    }
                    Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                        val w = size.width
                        val h = size.height
                        val radius = h / 2f
                        drawRoundRect(
                            color = baseColor,
                            topLeft = Offset.Zero,
                            size = size,
                            cornerRadius = CornerRadius(radius, radius)
                        )
                        if (fraction > 0f) {
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(AccentCyan, AccentViolet),
                                    startX = 0f,
                                    endX = w
                                ),
                                topLeft = Offset.Zero,
                                size = Size(w * fraction, h),
                                cornerRadius = CornerRadius(radius, radius)
                            )
                            // 滑条顶部高光线：强化"水晶截面"立体感
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.35f),
                                topLeft = Offset(0f, 0f),
                                size = Size(w * fraction, 2.dp.toPx()),
                                cornerRadius = CornerRadius(radius, radius)
                            )
                        }
                    }
                }
            )
            GlassStepButton(
                icon = Icons.Default.Add,
                contentDescription = "增加",
                enabled = enabled
            ) { onUpdate((minutes + step).coerceAtMost(maxMinutes)) }
        }
        Text(
            text = "步长 ${TimeFormat.formatDurationFromMinutes(step)}，最长 ${TimeFormat.formatDurationFromMinutes(maxMinutes)}",
            style = MaterialTheme.typography.labelSmall,
            color = GlassTextDim
        )
    }
}

/** 玻璃圆底步进按钮 */
@Composable
private fun GlassStepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) GlassSurfaceAlt else Color(0xFFEDF1F6))
            .border(1.dp, GlassBorder, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) AccentCyan else GlassTextDim,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun RestSection(
    settings: TabletUsageSettings,
    viewModel: TabletUsageSettingsViewModel,
    enabled: Boolean
) {
    GlassCard {
        GlassSectionHeader(icon = Icons.Default.FreeBreakfast, title = "休息提醒")
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "连续使用达到设定时长后强制休息，休息期间平板锁定。任一项设为 0 表示不提醒",
            style = TextStyle(color = GlassTextDim, fontSize = 12.sp)
        )
        Spacer(modifier = Modifier.height(14.dp))
        DurationControlContent(
            title = "使用多久后提醒休息",
            minutes = settings.restAfterMinutes,
            enabled = enabled,
            onUpdate = viewModel::updateRestAfter
        )
        Spacer(modifier = Modifier.height(12.dp))
        DurationControlContent(
            title = "每次休息时长",
            minutes = settings.restDurationMinutes,
            enabled = enabled,
            onUpdate = viewModel::updateRestDuration
        )
    }
}

/**
 * 今日剩余时长卡片。
 *
 * 数据来自服务端锚定的使用计时会话（与服务器、孩子端同一份剩余时长），
 * 家长端不再各自按本地时钟计算，三端数字严格一致。
 * 卡片内部每秒本地插值刷新倒计时，服务端拉取由页面每 15 秒轮询一次。
 */
@Composable
private fun UsageSessionCard(session: UsageSession?) {
    GlassCard {
        GlassSectionHeader(icon = Icons.Default.Schedule, title = "今日剩余时长")
        Spacer(modifier = Modifier.height(6.dp))
        if (session == null) {
            Text(
                "正在读取计时状态…",
                style = TextStyle(color = GlassTextDim, fontSize = 12.sp)
            )
            return@GlassCard
        }
        if (!session.active) {
            Text(
                "孩子端尚未确认开始计时，今日额度 ${session.quotaMinutes} 分钟（尚未开始计时）",
                style = TextStyle(color = GlassTextDim, fontSize = 12.sp)
            )
            return@GlassCard
        }
        // 每秒本地墙钟插值刷新，与服务端/孩子端同源派生，避免三端数字漂移
        var tick by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                tick = System.currentTimeMillis()
                delay(1000)
            }
        }
        val remaining = session.liveRemainingMs(tick)
        val mm = (remaining / 60_000L).toInt()
        val ss = ((remaining / 1000L) % 60L).toInt()
        val mmText = if (mm < 10) "0$mm" else "$mm"
        val ssText = if (ss < 10) "0$ss" else "$ss"
        Text(
            text = "$mmText:$ssText",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = AccentCyan
        )
        Spacer(modifier = Modifier.height(6.dp))
        val total = session.quotaMinutes + session.extendedMinutes
        Text(
            text = if (session.extendedMinutes > 0)
                "当日额度 $total 分钟（含延时 ${session.extendedMinutes} 分钟）"
            else "当日额度 $total 分钟",
            style = TextStyle(color = GlassTextDim, fontSize = 12.sp)
        )
    }
}
