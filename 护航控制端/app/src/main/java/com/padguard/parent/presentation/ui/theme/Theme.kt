package com.padguard.presentation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 护航管控 - 主题定义
 *
 * 配色参考截图：蓝色主色调（类似腾讯/微信家长管控风格）
 * - 主色: #4A90D9 (护眼蓝)
 * - 强调色: #FF6B6B (警告红)
 * - 成功色: #52C41A (在线绿)
 */

private val PrimaryBlue = Color(0xFF4A90D9)
private val PrimaryLight = Color(0xFFE8F0FE)
private val WarningRed = Color(0xFFFF6B6B)
private val SuccessGreen = Color(0xFF52C41A)
private val OnlineGreen = Color(0xFF07C160)   // 微信绿
private val OfflineGray = Color(0xFF999999)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = PrimaryLight,
    secondary = Color(0xFF5C6BC0),
    secondaryContainer = Color(0xFFE8EAF6),
    background = Color(0xFFF5F7FA),
    surface = Color.White,
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF333333),
    error = WarningRed,
    onError = Color.White,
    outline = Color(0xFFE0E0E0)
)

@Composable
fun PadGuardTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}

// 语义色扩展
object PadGuardColors {
    val OnlineGreen = com.padguard.presentation.ui.theme.OnlineGreen
    val OfflineGray = com.padguard.presentation.ui.theme.OfflineGray
    val WarningRed = com.padguard.presentation.ui.theme.WarningRed
    val SuccessGreen = com.padguard.presentation.ui.theme.SuccessGreen
    val CardBg = Color.White
    val SectionBg = Color(0xFFF8F9FA)
}
