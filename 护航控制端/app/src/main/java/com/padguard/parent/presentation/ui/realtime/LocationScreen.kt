package com.padguard.parent.presentation.ui.realtime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
 * 实时获取被管控平板位置并在地图上展示，支持百度 / 高德地图与 2D / 3D 视图；
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

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { RealtimeTopBar(title = "定位", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DeviceLocationCard(
                location = uiState.location,
                geofences = uiState.geofences,
                provider = uiState.provider,
                viewMode = uiState.viewMode,
                batteryLevel = uiState.batteryLevel,
                onProviderChange = viewModel::selectProvider,
                onViewModeChange = viewModel::selectViewMode,
                onRefresh = viewModel::refreshLocation,
                onLocate = viewModel::locateNow,
                isLocating = uiState.isLocating
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

@Composable
private fun DeviceLocationCard(
    location: LocationInfo?,
    geofences: List<GeofenceConfig>,
    provider: MapProvider,
    viewMode: MapViewMode,
    batteryLevel: Int?,
    onProviderChange: (MapProvider) -> Unit,
    onViewModeChange: (MapViewMode) -> Unit,
    onRefresh: () -> Unit,
    onLocate: () -> Unit,
    isLocating: Boolean
) {
    RealtimeCard(
        title = "设备位置",
        trailing = {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新位置")
            }
        }
    ) {
        Text("地图服务", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(4.dp))
        SingleChoiceChips(
            options = MapProvider.values().toList(),
            selected = provider,
            labelOf = { it.label },
            onSelect = onProviderChange
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text("视图模式", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(4.dp))
        SingleChoiceChips(
            options = MapViewMode.values().toList(),
            selected = viewMode,
            labelOf = { it.label },
            onSelect = onViewModeChange
        )

        Spacer(modifier = Modifier.height(12.dp))
        val context = LocalContext.current
        // 仅在「已配置高德 Key 且用户选中高德」时使用真实底图，否则回退等距投影占位图。
        val useRealMap = remember(provider) { readAmapKey(context).isNotBlank() && provider == MapProvider.AMAP }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            if (useRealMap) {
                AmapMapView(
                    location = location,
                    geofences = geofences,
                    track = emptyList(),
                    viewMode = viewMode,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                GeofenceMapCanvas(
                    location = location,
                    geofences = geofences,
                    track = emptyList(),
                    provider = provider,
                    viewMode = viewMode,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                color = Color.White.copy(alpha = 0.92f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "${provider.label} · ${viewMode.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF333333),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            text = if (useRealMap) {
                "地图由高德提供，坐标为 GCJ-02；设备 GPS（WGS-84）已做偏移转换后绘制；绿色圆圈为各安全区域。"
            } else {
                "未配置高德地图 Key，当前为示意占位图；配置 Key 后将显示真实底图。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
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
        Button(
            onClick = onLocate,
            enabled = !isLocating,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (isLocating) "定位中…" else "立即定位")
        }
        Text(
            text = "点击后平板会当场采集一次坐标并上报。孩子此刻未使用平板时受系统后台定位限制，" +
                "将在孩子解锁平板后自动补传。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 6.dp)
        )
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
        MapProvider.BAIDU -> Color(0xFF2F6BFF)
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
