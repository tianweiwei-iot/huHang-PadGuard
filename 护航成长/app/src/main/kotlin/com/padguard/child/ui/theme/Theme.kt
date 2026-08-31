package com.padguard.child.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 被管控端主题。
 * 面向学生/未成年人，采用低饱和、护眼的配色，不使用刺激性高亮色。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D6F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F0EA),
    secondary = Color(0xFF4F6D8C),
    background = Color(0xFFF6F8F7),
    surface = Color.White,
    error = Color(0xFFC0504D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1C1),
    onPrimary = Color(0xFF00352E),
    primaryContainer = Color(0xFF1B4F45),
    secondary = Color(0xFFB3C9DF),
    background = Color(0xFF101413),
    surface = Color(0xFF191E1D),
    error = Color(0xFFE08B88)
)

private val AppTypography = Typography()

@Composable
fun PadGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
