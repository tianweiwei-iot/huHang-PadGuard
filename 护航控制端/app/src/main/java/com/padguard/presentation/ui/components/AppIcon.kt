package com.padguard.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.padguard.domain.KnownAppIcons

/**
 * 应用图标组件（控制端展示被管控端应用使用记录时复用）。
 *
 * 图标解析优先级：
 * 1. 显式 [iconUrl]（后端返回 / 被管控端上报）；
 * 2. 按 [packageName] 命中 [KnownAppIcons] 已知应用映射；
 * 3. 以上皆无或加载失败时，回退到「字母 / 字符徽章」。
 *
 * 这样无论数据链路是否携带图标，控制端的应用记录列表都能直观呈现对应 App 图标。
 */
@Composable
fun AppIcon(
    packageName: String,
    appName: String,
    iconUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp
) {
    val resolved = iconUrl?.takeIf { it.isNotBlank() } ?: KnownAppIcons.iconFor(packageName)
    val badge = appBadgeStyle(packageName, appName)
    val inner = (size - 2.dp).coerceAtLeast(16.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        if (resolved != null) {
            SubcomposeAsyncImage(
                model = resolved,
                contentDescription = appName,
                modifier = Modifier
                    .size(inner)
                    .clip(CircleShape)
                    .background(Color.White),
                contentScale = ContentScale.Crop,
                loading = { LetterBadge(badge, inner) },
                error = { LetterBadge(badge, inner) }
            )
        } else {
            LetterBadge(badge, inner)
        }
    }
}

@Composable
private fun LetterBadge(badge: AppBadgeStyle, inner: Dp) {
    Box(
        modifier = Modifier
            .size(inner)
            .clip(CircleShape)
            .background(badge.color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = badge.mark,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

private data class AppBadgeStyle(val mark: String, val color: Color)

/** 无图标时的兜底徽章：按包名 / 应用名匹配常见应用，否则取应用名首字符。 */
private fun appBadgeStyle(packageName: String, appName: String): AppBadgeStyle {
    val p = packageName.lowercase()
    return when {
        p.contains("mobileqq") || p.contains("qq") -> AppBadgeStyle("🐧", Color(0xFF12B7F5))
        p.contains("tencent.mm") || p.contains("wechat") || appName.contains("微信") ->
            AppBadgeStyle("微", Color(0xFF07C160))
        p.contains("aweme") || p.contains("douyin") || appName.contains("抖音") ->
            AppBadgeStyle("抖", Color(0xFFFE2C55))
        p.contains("qqlive") || p.contains("tencent.video") || appName.contains("腾讯视频") ->
            AppBadgeStyle("▶", Color(0xFFFF7028))
        p.contains("netease") || appName.contains("荒野") -> AppBadgeStyle("野", Color(0xFFE3344A))
        p.contains("kuaiya") || appName.contains("快影") -> AppBadgeStyle("K", Color(0xFF1E90FF))
        p.contains("tencent") -> AppBadgeStyle("T", Color(0xFF1976FF))
        else -> {
            val ch = appName.firstOrNull()?.toString() ?: "·"
            AppBadgeStyle(ch, Color(0xFF607D8B))
        }
    }
}
