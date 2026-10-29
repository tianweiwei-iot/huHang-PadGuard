package com.padguard.parent.presentation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 护航管控 - 主题令牌体系（v2.2 多主题模式）
 *
 * 设计来源：主页 UI 方案合集中的三套浅色方案，用户可在「我的」页切换：
 * - [ThemeMode.MINT]    方案 05 青空薄荷（默认）：浅绿白调，清新护眼、健康感
 * - [ThemeMode.BLUEGRAY] 方案 07 静谧蓝灰：蓝灰软阴影，沉稳专业
 * - [ThemeMode.CREAM]    方案 10 晨雾奶油：奶油米色 + 珊瑚橘，柔和舒适
 *
 * 架构要点（保证切换主题不破坏任何已有功能逻辑）：
 * 1. 所有页面只引用本文件的令牌（`PadGuardColors.*` 或顶层 `val`），令牌取值随当前
 *    [ThemeMode] 动态返回，调用点零改动。
 * 2. [PadGuardTheme] 在重组时同步重建 Material3 [lightColorScheme]，使
 *    `MaterialTheme.colorScheme.primary/secondary/background` 等也随主题变化，
 *    避免出现"部分页面切换、部分页面不变"的视觉不一致。
 * 3. 语义状态色（成功/警告/在线/离线）保持行业通用、跨主题一致，不参与风格化。
 * 4. 当前主题由 [com.padguard.parent.di.ThemePrefs] 统一持有并持久化，进程内通过
 *    模块级 [activeTokens] 透传给所有 getter，无需逐页注入。
 *
 * 风险与规避：
 * - 切换主题本质是一次性重建 ColorScheme 并触发整树重组，开销极小；若个别 Composable
 *   在主题切换后未刷新，根因必为未走 PadGuardTheme/未读取令牌，本体系已覆盖全部入口。
 * - 预览（@Preview）若未包裹 PadGuardTheme，将回落到默认 MINT 令牌，不影响运行期。
 */

// ---------- 主题模式 ----------
/**
 * 可选主题。新增主题只需在此追加枚举 + 在 [tokensFor] 中补一套令牌，页面无需改动。
 * [id] 用于持久化存储，禁止改动已上线的取值（MINT/BLUEGRAY/CREAM），否则旧版本已存值无法映射。
 */
enum class ThemeMode(val id: String) {
    MINT("mint"),
    BLUEGRAY("bluegray"),
    CREAM("cream");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: MINT
    }
}

// ---------- 单套主题的完整令牌 ----------
/**
 * 一套主题所需的全部视觉令牌。三套方案共用同一结构，仅取值不同。
 * 这里只描述"颜色与渐变"，语义职责（哪些令牌被谁使用）由 [PadGuardColors] 统一约束。
 */
data class ThemeTokens(
    // 品牌三色：主色 / 主色深（按压缩影、容器文字）/ 辅色 / 点缀色
    val primary: Color,
    val primaryDeep: Color,
    val secondary: Color,
    val tertiary: Color,
    // 文字
    val textPrimary: Color,
    val textSecondary: Color,
    // 描边 / 页面底 / 卡片白
    val outline: Color,
    val pageBg: Color,
    val cardWhite: Color,
    // 淡彩图标圆底（容器 + 图标色，三色轮换）
    val tintContainers: List<Color>,
    val tintIconColors: List<Color>,
    // 渐变与投影
    val heroGradient: Brush,
    val cardShadow: Color,
    val heroShadow: Color,
    // 语义状态色（跨主题一致）
    val warningRed: Color,
    val successGreen: Color,
    val onlineGreen: Color,
    val offlineGray: Color,
    val amber: Color
) {
    /** 由本套令牌构建 Material3 浅色 ColorScheme，保证 colorScheme.* 与自定义令牌一致。 */
    val colorScheme
        get() = lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primary.copy(alpha = 0.12f),
            onPrimaryContainer = primaryDeep,
            secondary = secondary,
            onSecondary = Color.White,
            secondaryContainer = secondary.copy(alpha = 0.12f),
            onSecondaryContainer = secondary,
            tertiary = tertiary,
            onTertiary = Color.White,
            tertiaryContainer = tertiary.copy(alpha = 0.12f),
            onTertiaryContainer = tertiary,
            background = pageBg,
            onBackground = textPrimary,
            surface = cardWhite,
            onSurface = textPrimary,
            surfaceVariant = pageBg,
            onSurfaceVariant = textSecondary,
            error = warningRed,
            onError = Color.White,
            outline = outline
        )
}

// ---------- 三套方案令牌 ----------
// 方案 05 青空薄荷（默认）：浅绿白调
private val MintTokens = ThemeTokens(
    primary = Color(0xFF0E9F73),
    primaryDeep = Color(0xFF0B7A57),
    secondary = Color(0xFF0EA5E9),
    tertiary = Color(0xFFF59E0B),
    textPrimary = Color(0xFF14332A),
    textSecondary = Color(0xFF4A6B5E),
    outline = Color(0xFFD6E8E0),
    pageBg = Color(0xFFEFF7F3),
    cardWhite = Color.White,
    tintContainers = listOf(Color(0xFFD8F2E8), Color(0xFFD7ECF6), Color(0xFFFCEFD6)),
    tintIconColors = listOf(Color(0xFF0E9F73), Color(0xFF0EA5E9), Color(0xFFD97706)),
    heroGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF0FBA82), Color(0xFF0DA6E8))
    ),
    cardShadow = Color(0xFF14332A).copy(alpha = 0.08f),
    heroShadow = Color(0xFF0FBA82).copy(alpha = 0.28f),
    warningRed = Color(0xFFFF6B6B),
    successGreen = Color(0xFF52C41A),
    onlineGreen = Color(0xFF07C160),
    offlineGray = Color(0xFF999999),
    amber = Color(0xFFF59E0B)
)

// 方案 07 静谧蓝灰：蓝灰软阴影，沉稳专业
private val BlueGrayTokens = ThemeTokens(
    primary = Color(0xFF3D5BE0),
    primaryDeep = Color(0xFF2A3FB0),
    secondary = Color(0xFF5D49C9),
    tertiary = Color(0xFF0EA5E9),
    textPrimary = Color(0xFF2B3446),
    textSecondary = Color(0xFF5A6678),
    outline = Color(0xFFD9DEEA),
    pageBg = Color(0xFFE9EDF4),
    cardWhite = Color.White,
    tintContainers = listOf(Color(0xFFDDE3F5), Color(0xFFE8EAF2), Color(0xFFE6E0F2)),
    tintIconColors = listOf(Color(0xFF3D5BE0), Color(0xFF5D49C9), Color(0xFF0EA5E9)),
    heroGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF4F6EF5), Color(0xFF8C5CF5))
    ),
    cardShadow = Color(0xFF2B3446).copy(alpha = 0.08f),
    heroShadow = Color(0xFF4F6EF5).copy(alpha = 0.28f),
    warningRed = Color(0xFFFF6B6B),
    successGreen = Color(0xFF52C41A),
    onlineGreen = Color(0xFF07C160),
    offlineGray = Color(0xFF999999),
    amber = Color(0xFFF5A623)
)

// 方案 10 晨雾奶油：奶油米色 + 珊瑚橘，柔和舒适
private val CreamTokens = ThemeTokens(
    primary = Color(0xFFC95B38),
    primaryDeep = Color(0xFFA8482A),
    secondary = Color(0xFFB07A2E),
    tertiary = Color(0xFF3FA37C),
    textPrimary = Color(0xFF43372A),
    textSecondary = Color(0xFF6E5E4C),
    outline = Color(0xFFE8DCCF),
    pageBg = Color(0xFFFAF7F2),
    cardWhite = Color.White,
    tintContainers = listOf(Color(0xFFF6E7DD), Color(0xFFF4ECDD), Color(0xFFE8EFE4)),
    tintIconColors = listOf(Color(0xFFC95B38), Color(0xFFB07A2E), Color(0xFF3FA37C)),
    heroGradient = Brush.linearGradient(
        colors = listOf(Color(0xFFE3704A), Color(0xFFF0B540))
    ),
    cardShadow = Color(0xFF43372A).copy(alpha = 0.08f),
    heroShadow = Color(0xFFE3704A).copy(alpha = 0.28f),
    warningRed = Color(0xFFFF6B6B),
    successGreen = Color(0xFF52C41A),
    onlineGreen = Color(0xFF07C160),
    offlineGray = Color(0xFF999999),
    amber = Color(0xFFD97706)
)

/** 按模式取对应令牌集合。 */
fun tokensFor(mode: ThemeMode): ThemeTokens = when (mode) {
    ThemeMode.MINT -> MintTokens
    ThemeMode.BLUEGRAY -> BlueGrayTokens
    ThemeMode.CREAM -> CreamTokens
}

/**
 * 进程内当前主题令牌。由 [PadGuardTheme] 在每次重组时同步刷新，
 * 所有 getter 读取此变量即可拿到最新主题——无需逐页注入、调用点零改动。
 * 用普通 var（非 State）：刷新由父级 PadGuardTheme 的重组驱动整树重排，避免额外订阅噪声。
 */
private var activeTokens: ThemeTokens = MintTokens

/**
 * 主题顶层入口。在重组时先把 [activeTokens] 对齐到当前 [mode]，再重建 Material3 ColorScheme，
 * 然后下发内容。两者同步发生，子节点读取到的令牌与 colorScheme 必然一致。
 *
 * [mode] 由根层（MainActivity）从持久化读取后传入；默认 MINT，保证未接入前的预览/启动安全。
 */
@Composable
fun PadGuardTheme(
    mode: ThemeMode = ThemeMode.MINT,
    content: @Composable () -> Unit
) {
    activeTokens = tokensFor(mode)
    MaterialTheme(
        colorScheme = activeTokens.colorScheme,
        content = content
    )
}

/**
 * 语义色与品牌装饰 token（全局唯一装饰色来源）。
 * 全部以 getter 形式落到当前主题，卡片组件（SoftComponents.kt）与所有页面从中取参，
 * 页面不得自行拼装视觉参数。
 *
 * 命名为保留历史公开 API：对外表现仍是 `PadGuardColors.PrimaryBlue` 等常量式访问，
 * 但取值随主题动态变化。
 */
object PadGuardColors {
    // ---------- 品牌三色 ----------
    val PrimaryBlue get() = activeTokens.primary
    val PrimaryBlueDeep get() = activeTokens.primaryDeep
    val AccentPurple get() = activeTokens.secondary
    val AccentPink get() = activeTokens.tertiary

    // ---------- 中性与状态色 ----------
    val TextPrimary get() = activeTokens.textPrimary
    val TextSecondary get() = activeTokens.textSecondary
    val OutlineSoft get() = activeTokens.outline
    val PageBg get() = activeTokens.pageBg
    val CardWhite get() = activeTokens.cardWhite

    // 淡彩圆底（图标徽章容器色）
    val TintBlue get() = activeTokens.tintContainers[0]
    val TintPurple get() = activeTokens.tintContainers[1]
    val TintPink get() = activeTokens.tintContainers[2]

    // ---------- 语义状态色 ----------
    val WarningRed get() = activeTokens.warningRed
    val SuccessGreen get() = activeTokens.successGreen
    val OnlineGreen get() = activeTokens.onlineGreen
    val OfflineGray get() = activeTokens.offlineGray
    val Amber get() = activeTokens.amber

    // ---------- 兼容旧引用：卡片底 / 区块底 ----------
    val CardBg get() = activeTokens.cardWhite
    val SectionBg get() = activeTokens.pageBg

    // ---------- 淡彩图标圆底（容器 + 图标色，三色轮换） ----------
    val TintContainers get() = activeTokens.tintContainers
    val TintIconColors get() = activeTokens.tintIconColors

    // ---------- 渐变 ----------
    /** Hero 渐变（会员横幅气质）：今日使用卡、选中胶囊 */
    val HeroGradient get() = activeTokens.heroGradient

    /** 页面背景：浅色纯色（组件 AppSoftBackground 使用） */
    val AppBackground get() = activeTokens.pageBg

    /** 卡片投影色（极轻，主文字色 8%） */
    val CardShadow get() = activeTokens.cardShadow

    /** Hero 卡投影色（主色 28%，只压底不描边） */
    val HeroShadow get() = activeTokens.heroShadow
}

/**
 * 顶层令牌别名（历史公开 API）。
 * 早期部分页面直接 `import ...theme.PrimaryBlue` 等顶层 val；此处保留同名顶层 getter，
 * 继续委托到当前主题，避免改动这些调用点。新增代码请优先使用 [PadGuardColors]。
 */
val PrimaryBlue get() = PadGuardColors.PrimaryBlue
val PrimaryBlueDeep get() = PadGuardColors.PrimaryBlueDeep
val AccentPurple get() = PadGuardColors.AccentPurple
val AccentPink get() = PadGuardColors.AccentPink
val TextPrimary get() = PadGuardColors.TextPrimary
val TextSecondary get() = PadGuardColors.TextSecondary
val OutlineSoft get() = PadGuardColors.OutlineSoft
val PageBg get() = PadGuardColors.PageBg
val CardWhite get() = PadGuardColors.CardWhite
val TintBlue get() = PadGuardColors.TintBlue
val TintPurple get() = PadGuardColors.TintPurple
val TintPink get() = PadGuardColors.TintPink
