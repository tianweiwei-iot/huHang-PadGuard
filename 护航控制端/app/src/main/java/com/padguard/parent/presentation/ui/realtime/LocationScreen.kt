package com.padguard.parent.presentation.ui.realtime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.parent.domain.model.GeofenceConfig
import com.padguard.parent.domain.model.LocationInfo
import com.padguard.parent.domain.model.MapProvider
import com.padguard.parent.domain.model.MapViewMode
import com.padguard.parent.presentation.ui.theme.PadGuardColors
import com.padguard.parent.presentation.util.TimeFormat
import com.padguard.parent.presentation.viewmodel.LocationViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val GEOFENCE_RADIUS_RANGE = 100f..5000f
private const val METERS_PER_LAT_DEGREE = 111_320.0
private const val DEFAULT_VIEW_RADIUS_METERS = 800.0

/**
 * 实时管控 · 定位
 *
 * 实时获取被管控平板位置并在地图上展示，支持高德地图与 2D / 3D 视图；
 * 支持多电子围栏（一对多）；设备越界 / 进入安全区均记录轨迹。
 * deviceId 由导航路由参数经 SavedStateHandle 注入 ViewModel。
 */
@Composable
fun LocationScreen(
    onBack: () -> Unit,
    viewModel: LocationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val toast = uiState.toast

    LaunchedEffect(toast) {
        if (toast != null) {
            snackbarHostState.showSnackbar(toast)
            viewModel.clearToast()
        }
    }

    val context = LocalContext.current
    // 仅在「已配置高德 Key 且用户选中高德」时使用真实底图，否则回退等距投影占位图。
    val useRealMap = remember(uiState.provider) {
        readAmapKey(context).isNotBlank() && uiState.provider == MapProvider.AMAP
    }
    // 真实底图加载失败时回退占位图；切换地图源时重置。
    var amapFailed by remember(uiState.provider) { mutableStateOf(false) }
    var amapDiag by remember(uiState.provider) { mutableStateOf<AmapDiag?>(null) }
    val showRealMap = useRealMap && !amapFailed

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { RealtimeTopBar(title = "定位", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 全屏地图主区域：占满状态栏以下剩余空间（权重更高）。「立即定位」已移至地图下方固定区域，不再遮挡画面。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.4f)
                    .heightIn(min = 220.dp)
            ) {
                if (showRealMap) {
                    AmapMapView(
                        location = uiState.location,
                        geofences = uiState.geofences,
                        track = emptyList(),
                        viewMode = uiState.viewMode,
                        modifier = Modifier.fillMaxSize(),
                        onMapFailed = { diag ->
                            amapFailed = true
                            amapDiag = diag
                        }
                    )
                } else {
                    GeofenceMapCanvas(
                        location = uiState.location,
                        geofences = uiState.geofences,
                        track = emptyList(),
                        provider = uiState.provider,
                        viewMode = uiState.viewMode,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // 左上角仅保留当前视图模式标识（不再展示地图服务商名称）。
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    color = Color.White.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = uiState.viewMode.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF333333),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            // 「立即定位」固定区域：位于地图正下方，独立于地图画面，不遮挡。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                SoftCandyButton(
                    text = if (uiState.isLocating) "定位中…" else "立即定位",
                    icon = Icons.Default.MyLocation,
                    enabled = !uiState.isLocating,
                    onClick = viewModel::locateNow,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 下方内容区：地址/电量、地图与视图切换、围栏管理、轨迹时间线等其余功能保持不变。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DeviceLocationInfoCard(
                    location = uiState.location,
                    geofences = uiState.geofences,
                    viewMode = uiState.viewMode,
                    batteryLevel = uiState.batteryLevel,
                    useRealMap = useRealMap,
                    amapFailed = amapFailed,
                    amapDiag = amapDiag,
                    onViewModeChange = viewModel::selectViewMode,
                    onRefresh = viewModel::refreshLocation
                )

                GeofenceStatusBanner(
                    inside = uiState.insideGeofence,
                    hasEnabled = uiState.geofences.any { it.enabled },
                    distanceMeters = uiState.distanceMeters,
                    nearestRadiusMeters = uiState.nearestRadiusMeters
                )

                GeofenceListCard(
                    geofences = uiState.geofences,
                    onAdd = viewModel::addGeofence,
                    onUpdateDraft = viewModel::updateZoneDraft,
                    onSetCenter = viewModel::setZoneCenterToDevice,
                    onSave = viewModel::saveZone,
                    onDelete = viewModel::deleteZone
                )

                TrackHistoryCard(track = uiState.track, geofences = uiState.geofences)
            }
        }
    }
}

@Composable
private fun DeviceLocationInfoCard(
    location: LocationInfo?,
    geofences: List<GeofenceConfig>,
    viewMode: MapViewMode,
    batteryLevel: Int?,
    useRealMap: Boolean,
    amapFailed: Boolean,
    amapDiag: AmapDiag?,
    onViewModeChange: (MapViewMode) -> Unit,
    onRefresh: () -> Unit
) {
    RealtimeCard(
        title = "设备位置",
        trailing = {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新位置")
            }
        }
    ) {
        // 视图模式：标签与 2D / 3D 选项同一行显示（已移除「地图服务」服务商选择）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("视图模式", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.width(12.dp))
            SingleChoiceChips(
                options = MapViewMode.values().toList(),
                selected = viewMode,
                labelOf = { it.label },
                onSelect = onViewModeChange
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = when {
                useRealMap && amapFailed -> amapFailText(amapDiag)
                useRealMap ->
                    "地图底图采用国测局 GCJ-02 坐标；设备 GPS（WGS-84）已做偏移转换后绘制；绿色圆圈为各安全区域。"
                else ->
                    "未配置地图 Key，当前为示意占位图；配置 Key 后将显示真实底图。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (useRealMap && amapFailed) PadGuardColors.WarningRed.copy(alpha = 0.8f)
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))
        if (location == null) {
            Text(
                text = "正在获取设备位置…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            Text(
                text = location.address ?: "未知地址",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "坐标 ${formatCoordinate(location.latitude)}, ${formatCoordinate(location.longitude)}" +
                    " · 精度 ${location.accuracy?.roundToInt() ?: 0}米",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = "更新于 ${TimeFormat.formatClockFromMillis(location.timestamp)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            // 新鲜度提示：坐标采集时间距现在过久，或本就是缓存降级点，明确告知家长"可能已过时/不准"。
            val ageMinutes = ((System.currentTimeMillis() - location.timestamp) / 60_000).toInt()
            val stale = ageMinutes > 2 || location.fresh == false
            if (stale) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        if (ageMinutes > 2) append("位置可能已变化（${ageMinutes} 分钟前采集）")
                        if (location.fresh == false) {
                            if (ageMinutes > 2) append(" · ")
                            append("缓存位置，可能不准确")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = PadGuardColors.WarningRed.copy(alpha = 0.85f)
                )
            }
        }

        // 设备电量（与位置并列，低电/关机能解释"为何位置有滞后"）
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "设备电量",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (batteryLevel == null) {
                Text(
                    text = "未知",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                val battColor = when {
                    batteryLevel <= 15 -> PadGuardColors.WarningRed
                    batteryLevel <= 30 -> Color(0xFFE6A23C)
                    else -> PadGuardColors.SuccessGreen
                }
                Surface(
                    color = battColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "$batteryLevel%",
                        style = MaterialTheme.typography.labelMedium,
                        color = battColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "点击「立即定位」后平板会当场采集一次坐标并上报。孩子此刻未使用平板时受系统后台定位限制，" +
                "将在孩子解锁平板后自动补传。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/**
 * 毛琉璃水晶质感高级按钮。
 *
 * 以半透明蓝绿渐变 + 顶部高光 + 玻璃描边模拟磨砂水晶质感；按下时整体透明度降低并轻微收缩，释放后回弹。
 * 颜色采用品牌蓝绿渐变（与地图主题一致），白色文字保证对比度；禁用态整体降透明度。
 */
@Composable
/**
 * 「立即定位」按钮——软糖拟态（Soft Candy Neumorphism）风格。
 *
 * 设计要点：
 * - 主体为柔和薄荷绿竖向渐变 + 顶部柔光，呈现「软糖」般饱满通透质感；
 * - 通过右下暗影 + 左上亮影两层偏移阴影构成拟态浮雕，按钮整体「鼓起」而非扁平；
 * - 按下时阴影收拢、整体略下沉，提供明确的物理回弹反馈；
 * - 禁用态整体降透明度，保留形状与文案，不破坏布局。
 */
private fun SoftCandyButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val baseAlpha = if (enabled) 1f else 0.5f

    // 软糖配色：薄荷绿渐变，柔和护眼，与高德地图主题色呼应。
    val candyTop = Color(0xFF7FE3D4)
    val candyBottom = Color(0xFF3FC9B8)
    val shadowDark = Color(0xFF1F9E8E).copy(alpha = 0.40f)
    val shadowLight = Color.White.copy(alpha = 0.92f)

    // 按下时阴影收拢、按钮下沉，模拟被「按进去」的软糖回弹。
    val press = if (isPressed) 1 else 0
    val elevation = (4 - press).dp
    val offset = (4 - press).dp

    Box(
        modifier = modifier
            .height(54.dp)
            .padding(6.dp) // 为拟态双层阴影预留呼吸空间，避免裁切
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // 暗色阴影层（右下偏移），构成软糖底部厚度感。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = offset, y = offset)
                .shadow(
                    elevation = elevation,
                    shape = RoundedCornerShape(18.dp),
                    ambientColor = shadowDark,
                    spotColor = shadowDark
                )
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
        )
        // 亮色高光阴影层（左上偏移），构成软糖顶部受光面。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = -offset, y = -offset)
                .shadow(
                    elevation = elevation,
                    shape = RoundedCornerShape(18.dp),
                    ambientColor = shadowLight,
                    spotColor = shadowLight
                )
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Transparent)
        )
        // 主体：软糖渐变 + 描边 + 顶部柔光。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to candyTop.copy(alpha = baseAlpha),
                            1f to candyBottom.copy(alpha = baseAlpha)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.55f * baseAlpha),
                    shape = RoundedCornerShape(18.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            // 顶部柔光：模拟软糖表面反光，增强果冻通透感。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0f to Color.White.copy(alpha = 0.42f * baseAlpha),
                                0.5f to Color.Transparent
                            )
                        )
                    )
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = baseAlpha)
                )
            }
        }
    }
}

@Composable
private fun GeofenceStatusBanner(
    inside: Boolean,
    hasEnabled: Boolean,
    distanceMeters: Int,
    nearestRadiusMeters: Int
) {
    val style = when {
        !hasEnabled -> GeofenceBannerStyle(
            background = Color(0xFFF0F2F5),
            foreground = Color(0xFF666666),
            icon = Icons.Default.Shield,
            message = "未启用任何安全区域，设备位置不受范围限制"
        )
        inside -> GeofenceBannerStyle(
            background = Color(0xFFE8F5E9),
            foreground = PadGuardColors.SuccessGreen,
            icon = Icons.Default.CheckCircle,
            message = "设备在安全区域内（距最近安全区中心 ${distanceMeters}米）"
        )
        else -> GeofenceBannerStyle(
            background = PadGuardColors.WarningRed.copy(alpha = 0.1f),
            foreground = PadGuardColors.WarningRed,
            icon = Icons.Default.Warning,
            message = "设备已越出安全区域（距最近中心 ${distanceMeters}米 / 超出 " +
                "${(distanceMeters - nearestRadiusMeters).coerceAtLeast(0)}米）"
        )
    }
    Surface(color = style.background, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(style.icon, contentDescription = null, tint = style.foreground, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = style.message,
                style = MaterialTheme.typography.bodyMedium,
                color = style.foreground
            )
        }
    }
}

@Composable
private fun GeofenceListCard(
    geofences: List<GeofenceConfig>,
    onAdd: () -> Unit,
    onUpdateDraft: (String, (GeofenceConfig) -> GeofenceConfig) -> Unit,
    onSetCenter: (String) -> Unit,
    onSave: (GeofenceConfig) -> Unit,
    onDelete: (String) -> Unit
) {
    RealtimeCard(title = "电子围栏（安全区域）") {
        if (geofences.isEmpty()) {
            Text(
                text = "尚未设置安全区域，点击下方按钮新增。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        geofences.forEachIndexed { index, zone ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            GeofenceZoneItem(
                zone = zone,
                onUpdateDraft = onUpdateDraft,
                onSetCenter = onSetCenter,
                onSave = onSave,
                onDelete = onDelete
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("新增安全区域")
        }
        Text(
            text = "可设置多个安全区域（家 / 学校 / 补习班等），进入或离开任一区域均可选择是否告警。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun GeofenceZoneItem(
    zone: GeofenceConfig,
    onUpdateDraft: (String, (GeofenceConfig) -> GeofenceConfig) -> Unit,
    onSetCenter: (String) -> Unit,
    onSave: (GeofenceConfig) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "区域 ${zone.name.ifBlank { "未命名" }}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onDelete(zone.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "删除区域", tint = PadGuardColors.WarningRed)
            }
        }

        SettingSwitchRow(
            title = "启用",
            checked = zone.enabled,
            onCheckedChange = { v: Boolean -> onUpdateDraft(zone.id) { it.copy(enabled = v) } }
        )

        OutlinedTextField(
            value = zone.name,
            onValueChange = { v: String -> onUpdateDraft(zone.id) { it.copy(name = v) } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            singleLine = true,
            enabled = zone.enabled,
            label = { Text("区域名称") }
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingSliderRow(
            title = "围栏半径",
            valueText = "${zone.radiusMeters}米",
            value = zone.radiusMeters.toFloat(),
            valueRange = GEOFENCE_RADIUS_RANGE,
            steps = 48,
            enabled = zone.enabled,
            onValueChange = { v: Float -> onUpdateDraft(zone.id) { it.copy(radiusMeters = v.roundToInt()) } }
        )

        SettingSwitchRow(
            title = "离开该区域时告警",
            checked = zone.alertOnExit,
            enabled = zone.enabled,
            onCheckedChange = { v: Boolean -> onUpdateDraft(zone.id) { it.copy(alertOnExit = v) } }
        )

        SettingSwitchRow(
            title = "进入该区域时告警",
            checked = zone.alertOnEnter,
            enabled = zone.enabled,
            onCheckedChange = { v: Boolean -> onUpdateDraft(zone.id) { it.copy(alertOnEnter = v) } }
        )

        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onSetCenter(zone.id) },
                enabled = zone.enabled,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("以当前位置为中心")
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { onSave(zone) }, enabled = zone.enabled, shape = RoundedCornerShape(12.dp)) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun TrackHistoryCard(track: List<LocationInfo>, geofences: List<GeofenceConfig>) {
    RealtimeCard(title = "历史轨迹时间线") {
        if (track.isEmpty()) {
            Text(
                text = "暂无轨迹记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            return@RealtimeCard
        }
        val enabled = geofences.filter { it.enabled }
        Text(
            text = "最近 ${track.size} 个定位点（按日分组，绿色＝处于某安全区内）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))

        val groups = remember(track) {
            track.groupBy { dayKey(it.timestamp) }.toSortedMap(reverseOrder())
        }
        groups.forEach { (key, points) ->
            Text(
                text = dayLabel(key),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            points.forEach { point ->
                val inside = enabled.isEmpty() || enabled.any {
                    haversine(it.centerLatitude, it.centerLongitude, point.latitude, point.longitude) <= it.radiusMeters
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (inside) PadGuardColors.SuccessGreen else PadGuardColors.WarningRed)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = point.address ?: "未知位置",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${formatCoordinate(point.latitude)}, ${formatCoordinate(point.longitude)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = TimeFormat.formatClockFromMillis(point.timestamp),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = if (inside) "安全区" else "区域外",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (inside) PadGuardColors.SuccessGreen else PadGuardColors.WarningRed
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        }
    }
}

/**
 * 电子围栏地图占位渲染（多围栏）。
 *
 * 以「任一启用围栏中心（或设备当前位置）」为参考点做等距投影，绘制各安全区域范围、设备标记。
 * 真实底图由 [AmapMapView] 承载；接入真实地图 SDK 时仅需替换本组件。
 */
@Composable
private fun GeofenceMapCanvas(
    location: LocationInfo?,
    geofences: List<GeofenceConfig>,
    track: List<LocationInfo>,
    provider: MapProvider,
    viewMode: MapViewMode,
    modifier: Modifier = Modifier
) {
    val accent = when (provider) {
        MapProvider.AMAP -> Color(0xFF00A37A)
    }
    val backgroundColor = Color(0xFFF2F5FA)
    val gridColor = Color(0xFFE1E7F0)

    Canvas(modifier = modifier) {
        drawRect(color = backgroundColor)

        // 3D 模式：纵向压扁，模拟俯角视角
        val squash = if (viewMode == MapViewMode.MODE_3D) 0.55f else 1f
        val center = Offset(size.width / 2f, size.height / 2f)

        val enabled = geofences.filter { it.enabled }
        val refLat = enabled.firstOrNull()?.centerLatitude ?: location?.latitude ?: 0.0
        val refLng = enabled.firstOrNull()?.centerLongitude ?: location?.longitude ?: 0.0
        val maxRadius = enabled.maxOfOrNull { it.radiusMeters } ?: 0
        val viewRadius = if (maxRadius > 0) maxRadius.toDouble() else DEFAULT_VIEW_RADIUS_METERS
        val radiusPx = (minOf(size.width, size.height) / 2f) * 0.7f
        val metersPerPx = (viewRadius / radiusPx).coerceAtLeast(0.01)

        fun toOffset(lat: Double, lng: Double): Offset {
            val dxMeters = (lng - refLng) * METERS_PER_LAT_DEGREE * cos(Math.toRadians(refLat))
            val dyMeters = (lat - refLat) * METERS_PER_LAT_DEGREE
            return Offset(
                x = center.x + (dxMeters / metersPerPx).toFloat(),
                y = center.y - (dyMeters / metersPerPx).toFloat() * squash
            )
        }

        // 路网底纹
        val gridStep = 56f
        var x = 0f
        while (x <= size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += gridStep
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += gridStep
        }

        // 各安全区域范围
        enabled.forEach { zone ->
            val rPx = (zone.radiusMeters / metersPerPx).toFloat()
            drawCircle(color = accent.copy(alpha = 0.10f), radius = rPx, center = center)
            drawCircle(
                color = accent.copy(alpha = 0.65f),
                radius = rPx,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // 设备当前位置
        location?.let {
            val marker = toOffset(it.latitude, it.longitude)
            drawCircle(color = Color.White, radius = 9.dp.toPx(), center = marker)
            drawCircle(color = accent, radius = 6.dp.toPx(), center = marker)
        }
    }
}

private data class GeofenceBannerStyle(
    val background: Color,
    val foreground: Color,
    val icon: ImageVector,
    val message: String
)

private fun formatCoordinate(value: Double): String = String.format(Locale.CHINA, "%.5f", value)

/**
 * 底图加载失败时的诚实、差异化提示。
 *
 * 旧实现直接断言"Key 无效 / 包名或 SHA1 绑定不符 / 网络受限"——但看门狗本身无法分辨这三者，
 * 纯属误导。这里只基于看门狗回传的**真实观测数据**给结论：
 *  - 连不上高德服务器 → 明确指向网络受限/被拦截；
 *  - 网络通但底图仍失败 → 几乎可以确定是 Key 类型错误或包名/SHA1 绑定不符，并直接把本机签名
 *    SHA1 与包名打印出来，家长/运维到高德控制台核对时无需再手动 keytool。
 */
private fun amapFailText(diag: AmapDiag?): String = when {
    diag == null ->
        "真实底图加载失败，已回退示意占位图。请检查高德控制台 Key 配置与设备网络。"
    !diag.networkReachable ->
        "真实底图加载失败：设备无法连接高德服务器（网络受限或被拦截），已回退示意占位图。" +
            "请检查设备网络/WiFi 后重试；若在隔离网络环境，底图本就无法加载。"
    else ->
        "真实底图加载失败：高德 Key 类型错误或「包名 / 签名(SHA1)」绑定不符，已回退示意占位图。\n" +
            "本机签名 SHA1：${diag.sha1.ifBlank { "（读取失败）" }}\n" +
            "包名：${diag.packageName}\n" +
            "请到高德开放平台 → 我的应用，确认该 Android Key 已绑定以上包名与 SHA1" +
            "（debug 与 release 签名不同，建议两个 SHA1 都加进同一 Key）。"
}

/**
 * 按"东八区（Asia/Shanghai）自然日"分组轨迹点（键：yyyy-MM-dd，倒序用）。
 * 必须固定时区而非依赖设备默认时区：出国或改过时区的设备若用本地时区，
 * 会出现跨日分组错位（如 23:30 与 00:10 被分到不同"天"），轨迹时间线割裂。
 */
private fun dayKey(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.format(Date(ts))

/** 由 yyyy-MM-dd 生成「M月d日」展示标签。 */
private fun dayLabel(key: String): String {
    val parts = key.split("-")
    if (parts.size < 3) return key
    return "${parts[1].toInt()}月${parts[2].toInt()}日"
}

/** 球面距离（Haversine），返回米。 */
private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return (6_371_000.0 * c).roundToInt()
}
