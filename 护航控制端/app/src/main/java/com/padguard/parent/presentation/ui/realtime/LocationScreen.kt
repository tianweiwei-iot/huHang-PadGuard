package com.padguard.presentation.ui.realtime

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.padguard.domain.model.GeofenceConfig
import com.padguard.domain.model.LocationInfo
import com.padguard.domain.model.MapProvider
import com.padguard.domain.model.MapViewMode
import com.padguard.presentation.ui.theme.PadGuardColors
import com.padguard.presentation.util.TimeFormat
import com.padguard.presentation.viewmodel.LocationViewModel
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt

private val GEOFENCE_RADIUS_RANGE = 100f..5000f
private const val METERS_PER_LAT_DEGREE = 111_320.0
private const val DEFAULT_VIEW_RADIUS_METERS = 800.0

/**
 * 实时管控 · 定位
 *
 * 实时获取被管控平板位置并在地图上展示，支持百度 / 高德地图与 2D / 3D 视图，
 * 支持设置电子围栏；设备越界后记录移动轨迹。
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
                geofence = uiState.geofence,
                track = uiState.track,
                provider = uiState.provider,
                viewMode = uiState.viewMode,
                onProviderChange = viewModel::selectProvider,
                onViewModeChange = viewModel::selectViewMode,
                onRefresh = viewModel::refreshLocation
            )

            GeofenceStatusBanner(
                inside = uiState.insideGeofence,
                enabled = uiState.geofence.enabled,
                distanceMeters = uiState.distanceMeters,
                radiusMeters = uiState.geofence.radiusMeters
            )

            GeofenceConfigCard(
                geofence = uiState.geofence,
                dirty = uiState.geofenceDirty,
                onEdit = viewModel::editGeofence,
                onUseCurrentLocation = viewModel::setGeofenceCenterToDevice,
                onSave = viewModel::saveGeofence
            )

            TrackHistoryCard(track = uiState.track)
        }
    }
}

@Composable
private fun DeviceLocationCard(
    location: LocationInfo?,
    geofence: GeofenceConfig,
    track: List<LocationInfo>,
    provider: MapProvider,
    viewMode: MapViewMode,
    onProviderChange: (MapProvider) -> Unit,
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            GeofenceMapCanvas(
                location = location,
                geofence = geofence,
                track = track,
                provider = provider,
                viewMode = viewMode,
                modifier = Modifier.fillMaxSize()
            )
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
            text = "地图为占位渲染（Mock），接入百度 / 高德地图 SDK 后显示真实底图。",
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
    }
}

@Composable
private fun GeofenceStatusBanner(
    inside: Boolean,
    enabled: Boolean,
    distanceMeters: Int,
    radiusMeters: Int
) {
    val style = when {
        !enabled -> GeofenceBannerStyle(
            background = Color(0xFFF0F2F5),
            foreground = Color(0xFF666666),
            icon = Icons.Default.Shield,
            message = "电子围栏未启用，设备位置不受范围限制"
        )
        inside -> GeofenceBannerStyle(
            background = Color(0xFFE8F5E9),
            foreground = PadGuardColors.SuccessGreen,
            icon = Icons.Default.CheckCircle,
            message = "设备在安全区域内（距中心 ${distanceMeters}米 / 半径 ${radiusMeters}米）"
        )
        else -> GeofenceBannerStyle(
            background = PadGuardColors.WarningRed.copy(alpha = 0.1f),
            foreground = PadGuardColors.WarningRed,
            icon = Icons.Default.Warning,
            message = "设备已越出安全区域（距中心 ${distanceMeters}米 / 超出 ${(distanceMeters - radiusMeters).coerceAtLeast(0)}米），已记录移动轨迹"
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
private fun GeofenceConfigCard(
    geofence: GeofenceConfig,
    dirty: Boolean,
    onEdit: ((GeofenceConfig) -> GeofenceConfig) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onSave: () -> Unit
) {
    RealtimeCard(title = "电子围栏") {
        SettingSwitchRow(
            title = "启用电子围栏",
            subtitle = "设备越界后自动记录移动轨迹并告警",
            checked = geofence.enabled,
            onCheckedChange = { value -> onEdit { it.copy(enabled = value) } }
        )

        OutlinedTextField(
            value = geofence.name,
            onValueChange = { value -> onEdit { it.copy(name = value) } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            singleLine = true,
            enabled = geofence.enabled,
            label = { Text("围栏名称") }
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingSliderRow(
            title = "围栏半径",
            valueText = "${geofence.radiusMeters}米",
            value = geofence.radiusMeters.toFloat(),
            valueRange = GEOFENCE_RADIUS_RANGE,
            steps = 48,
            enabled = geofence.enabled,
            onValueChange = { value -> onEdit { it.copy(radiusMeters = value.roundToInt()) } }
        )

        SettingSwitchRow(
            title = "越界后告警",
            checked = geofence.alertOnExit,
            enabled = geofence.enabled,
            onCheckedChange = { value -> onEdit { it.copy(alertOnExit = value) } }
        )

        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(
            onClick = onUseCurrentLocation,
            enabled = geofence.enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("以设备当前位置为中心")
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dirty) {
                Text(
                    text = "围栏已修改，待下发",
                    style = MaterialTheme.typography.labelSmall,
                    color = PadGuardColors.WarningRed
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onSave, enabled = dirty, shape = RoundedCornerShape(12.dp)) {
                Text("保存并下发")
            }
        }
    }
}

@Composable
private fun TrackHistoryCard(track: List<LocationInfo>) {
    RealtimeCard(title = "移动轨迹") {
        if (track.isEmpty()) {
            Text(
                text = "暂无越界轨迹记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            return@RealtimeCard
        }
        Text(
            text = "设备越界后共记录 ${track.size} 个轨迹点",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        track.forEachIndexed { index, point ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
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
                Text(
                    text = TimeFormat.formatClockFromMillis(point.timestamp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (index != track.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
        }
    }
}

/**
 * 电子围栏地图占位渲染。
 *
 * 以「围栏中心（或设备当前位置）」为参考点做等距投影，绘制围栏范围、设备标记与移动轨迹。
 * 3D 模式对纵向做透视压扁，模拟俯角视角。接入真实地图 SDK 时仅需替换本组件。
 */
@Composable
private fun GeofenceMapCanvas(
    location: LocationInfo?,
    geofence: GeofenceConfig,
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

        val refLat = if (geofence.enabled) geofence.centerLatitude else location?.latitude ?: 0.0
        val refLng = if (geofence.enabled) geofence.centerLongitude else location?.longitude ?: 0.0

        val radiusMeters = if (geofence.enabled) geofence.radiusMeters.toDouble() else DEFAULT_VIEW_RADIUS_METERS
        val radiusPx = (minOf(size.width, size.height) / 2f) * 0.7f
        val metersPerPx = (radiusMeters / radiusPx).coerceAtLeast(0.01)

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

        // 电子围栏范围
        if (geofence.enabled) {
            drawCircle(color = accent.copy(alpha = 0.10f), radius = radiusPx, center = center)
            drawCircle(
                color = accent.copy(alpha = 0.65f),
                radius = radiusPx,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // 移动轨迹
        if (track.size >= 2) {
            val points = track.map { toOffset(it.latitude, it.longitude) }
            points.zipWithNext { start, end ->
                drawLine(accent, start, end, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        track.forEach { point ->
            drawCircle(
                color = accent.copy(alpha = 0.55f),
                radius = 3.dp.toPx(),
                center = toOffset(point.latitude, point.longitude)
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
