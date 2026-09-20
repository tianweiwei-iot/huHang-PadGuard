package com.padguard.presentation.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.padguard.domain.model.TabletUsageSettings
import com.padguard.domain.model.TimeRange
import com.padguard.presentation.ui.components.SoftCard
import com.padguard.presentation.util.TimeFormat
import com.padguard.presentation.viewmodel.TabletUsageSettingsUiState
import com.padguard.presentation.viewmodel.TabletUsageSettingsViewModel

/**
 * 平板使用时间设置页
 *
 * - 「是否启用时间管控」总开关：关闭时页面所有设置项灰化、不可更改
 * - 可用时间段（可增删）
 * - 工作日 / 休息日使用时长，步长 30 分钟，最长 24 小时
 * - 使用多久后提醒休息 / 每次休息时长（与使用时长同一控件，步长 30 分钟，最长 24 小时）
 * - 超时提示文案
 * - 确认后同步至成长端
 */
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
        if (deviceId.isNotEmpty()) viewModel.load(deviceId)
    }

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) {
            snackbarHostState.showSnackbar("设置已保存并同步至成长端")
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("平板使用时间设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    enabled = !uiState.isLoading,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("确认")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { EnableSwitch(enabled = enabled, onToggle = viewModel::updateEnabled) }
            item { TimeRangeSection(uiState, viewModel, enabled) }
            item {
                DurationControl(
                    title = "工作日（周一至周五）使用时长",
                    minutes = uiState.settings.weekdayLimitMinutes,
                    enabled = enabled,
                    onUpdate = viewModel::updateWeekdayLimit
                )
            }
            item {
                DurationControl(
                    title = "休息日（周六至周日）使用时长",
                    minutes = uiState.settings.weekendLimitMinutes,
                    enabled = enabled,
                    onUpdate = viewModel::updateWeekendLimit
                )
            }
            item { RestSection(settings = uiState.settings, viewModel = viewModel, enabled = enabled) }
            item { MessageSection(message = uiState.settings.timeUpMessage, onUpdate = viewModel::updateTimeUpMessage, enabled = enabled) }
        }
    }
}

@Composable
private fun EnableSwitch(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "是否启用时间管控",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (!enabled) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "未启用时，以下所有设置不可修改",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun TimeRangeSection(
    uiState: TabletUsageSettingsUiState,
    viewModel: TabletUsageSettingsViewModel,
    enabled: Boolean
) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "可用时间段",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { viewModel.addTimeRange() },
                    enabled = enabled
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("添加")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (uiState.settings.enabledTimeRanges.isEmpty()) {
                Text(
                    text = "未设置可用时段，将全天允许使用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                        Spacer(modifier = Modifier.height(8.dp))
                    }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("时段 ${index + 1}", style = MaterialTheme.typography.bodyMedium)
        TimeInput(
            value = range.startTime,
            enabled = enabled,
            onValueChange = { onUpdate(it, range.endTime) },
            modifier = Modifier.weight(1f)
        )
        Text("至", style = MaterialTheme.typography.bodyMedium)
        TimeInput(
            value = range.endTime,
            enabled = enabled,
            onValueChange = { onUpdate(range.startTime, it) },
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onRemove,
            enabled = enabled
        ) {
            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
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
    OutlinedTextField(
        value = value,
        onValueChange = {
            val filtered = it.filter { c -> c.isDigit() || c == ':' }
            if (filtered.length <= 5) onValueChange(filtered)
        },
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

/**
 * 统一时长控件：步进按钮 + 渐变加深的滑动条。
 * 步长 30 分钟，范围 0 ~ 24 小时（1440 分钟）。
 */
@Composable
private fun DurationControl(
    title: String,
    minutes: Int,
    enabled: Boolean,
    onUpdate: (Int) -> Unit
) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        DurationControlContent(title = title, minutes = minutes, enabled = enabled, onUpdate = onUpdate)
    }
}

/** 时长控件内容体：供独立卡片与「休息提醒」卡内复用，避免卡片嵌套卡片。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DurationControlContent(
    title: String,
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
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = TimeFormat.formatDurationFromMinutes(minutes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { onUpdate((minutes - step).coerceAtLeast(0)) },
                    enabled = enabled
                ) {
                    Icon(Icons.Default.RemoveCircle, contentDescription = "减少")
                }
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
                        thumbColor = MaterialTheme.colorScheme.primary,
                        disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    ),
                    track = { sliderState ->
                        val fraction = (sliderState.value - sliderState.valueRange.start) /
                                (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
                        val baseColor = if (enabled) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        }
                        val fromColor = if (enabled) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        }
                        val toColor = if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
                                        colors = listOf(fromColor, toColor),
                                        startX = 0f,
                                        endX = w
                                    ),
                                    topLeft = Offset.Zero,
                                    size = Size(w * fraction, h),
                                    cornerRadius = CornerRadius(radius, radius)
                                )
                            }
                        }
                    }
                )
                IconButton(
                    onClick = { onUpdate((minutes + step).coerceAtMost(maxMinutes)) },
                    enabled = enabled
                ) {
                    Icon(Icons.Default.AddCircle, contentDescription = "增加")
                }
            }
            Text(
                text = "步长 ${TimeFormat.formatDurationFromMinutes(step)}，最长 ${TimeFormat.formatDurationFromMinutes(maxMinutes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
}

@Composable
private fun RestSection(
    settings: TabletUsageSettings,
    viewModel: TabletUsageSettingsViewModel,
    enabled: Boolean
) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "休息提醒",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
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
}

@Composable
private fun MessageSection(message: String, onUpdate: (String) -> Unit, enabled: Boolean) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "超时提示文案",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = message,
                onValueChange = onUpdate,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                minLines = 2,
                maxLines = 3,
                placeholder = { Text("请输入提示语") }
            )
        }
    }
}
