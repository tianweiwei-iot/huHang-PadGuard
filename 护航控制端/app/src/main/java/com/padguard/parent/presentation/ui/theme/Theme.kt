package com.padguard.presentation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 护航管控 - 主题定义（v2.1 清爽卡片风格，参考 WPS「我的」页版式）
 *
 * 视觉基准见 docs/UI设计提示词.md，要点：
 * - 页面背景：浅灰 #F5F6F8，干净通透，卡片靠底色区分而非描边
 * - 卡片：纯白、16dp 大圆角、无描边、极轻投影（SoftCard 统一实现）
 * - 品牌色：科技蓝 #4D7CFF（选中/图标/链接）、梦幻紫 #8B5CF6、活力粉 #FF6FB5（点缀胶囊）
 * - Hero 横幅：深紫渐变（会员卡同款气质），其上内容白字
 * - 图标：淡彩圆底（蓝/紫/粉三色轮换）+ 同系彩色描边图标
 * - 语义状态色（成功/警告/在线/离线）保持行业通用，不参与风格化
 *
 * 所有页面只允许引用本文件的 token，禁止硬编码装饰色值。
 */

// ---------- 品牌三色 ----------
val PrimaryBlue = Color(0xFF4D7CFF)     // 科技蓝：选中态、图标、链接
val PrimaryBlueDeep = Color(0xFF3B63E8)
val AccentPurple = Color(0xFF8B5CF6)    // 梦幻紫：辅色、Hero 渐变
val AccentPink = Color(0xFFFF6FB5)      // 活力粉：高亮点缀

// ---------- 中性与状态色 ----------
val TextPrimary = Color(0xFF1E293B)     // 深蓝黑主文字
val TextSecondary = Color(0xFF64748B)   // 次级说明文字
val OutlineSoft = Color(0xFFE4E8F5)     // 柔和描边 / 分隔线
val PageBg = Color(0xFFF5F6F8)          // 页面浅灰底
val CardWhite = Color.White             // 卡片纯白

// 淡彩圆底（图标徽章容器色）
val TintBlue = Color(0xFFE8EEFF)
val TintPurple = Color(0xFFF0E9FF)
val TintPink = Color(0xFFFFEBF4)

// ---------- 语义状态色 ----------
val WarningRed = Color(0xFFFF6B6B)
val SuccessGreen = Color(0xFF52C41A)
val OnlineGreen = Color(0xFF07C160)
val OfflineGray = Color(0xFF999999)

// ---------- 语义色板（Material 3） ----------
private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = TintBlue,
    onPrimaryContainer = PrimaryBlueDeep,
    secondary = AccentPurple,
    onSecondary = Color.White,
    secondaryContainer = TintPurple,
    onSecondaryContainer = Color(0xFF6D3FD8),
    tertiary = AccentPink,
    onTertiary = Color.White,
    tertiaryContainer = TintPink,
    onTertiaryContainer = Color(0xFFD6488F),
    background = PageBg,
    onBackground = TextPrimary,
    surface = CardWhite,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFFEEF1F6),
    onSurfaceVariant = TextSecondary,
    error = WarningRed,
    onError = Color.White,
    outline = OutlineSoft
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

/**
 * 语义色与品牌装饰 token（全局唯一装饰色来源）。
 * 卡片组件（SoftComponents.kt）从这里取参，页面不得自行拼装视觉参数。
 */
object PadGuardColors {
    // 语义状态色
    val OnlineGreen = com.padguard.presentation.ui.theme.OnlineGreen
    val OfflineGray = com.padguard.presentation.ui.theme.OfflineGray
    val WarningRed = com.padguard.presentation.ui.theme.WarningRed
    val SuccessGreen = com.padguard.presentation.ui.theme.SuccessGreen
    val Amber = Color(0xFFFFA000)

    // 品牌三色
    val PrimaryBlue = com.padguard.presentation.ui.theme.PrimaryBlue
    val AccentPurple = com.padguard.presentation.ui.theme.AccentPurple
    val AccentPink = com.padguard.presentation.ui.theme.AccentPink

    // 文字
    val TextPrimary = com.padguard.presentation.ui.theme.TextPrimary
    val TextSecondary = com.padguard.presentation.ui.theme.TextSecondary

    // 兼容旧引用：卡片底 / 区块底
    val CardBg = Color.White
    val SectionBg = Color(0xFFF5F6F8)

    // ---------- 淡彩图标圆底（容器 + 图标色，三色轮换） ----------
    val TintContainers = listOf(
        com.padguard.presentation.ui.theme.TintBlue,
        com.padguard.presentation.ui.theme.TintPurple,
        com.padguard.presentation.ui.theme.TintPink,
        com.padguard.presentation.ui.theme.TintBlue
    )
    val TintIconColors = listOf(
        PrimaryBlue,
        Color(0xFF7C4DE0),
        Color(0xFFE85D9E),
        PrimaryBlue
    )

    // ---------- 渐变 ----------
    /** Hero 深紫渐变（会员横幅气质）：今日使用卡、选中胶囊 */
    val HeroGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF2F2470), Color(0xFF5B3EC8), Color(0xFF8B5CF6))
    )

    /** 页面背景：浅灰纯色（组件 AppSoftBackground 使用） */
    val AppBackground = PageBg

    /** 卡片投影色（极轻，深蓝黑 8%） */
    val CardShadow = Color(0xFF1E293B).copy(alpha = 0.08f)

    /** Hero 卡投影色（深紫 28%，只压底不描边） */
    val HeroShadow = Color(0xFF2F2470).copy(alpha = 0.28f)
}
