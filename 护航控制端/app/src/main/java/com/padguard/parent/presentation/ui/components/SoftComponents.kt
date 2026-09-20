package com.padguard.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.padguard.presentation.ui.theme.PadGuardColors

/**
 * 清爽卡片风共享组件（视觉基准：docs/UI设计提示词.md v2.1，参考 WPS「我的」页版式）。
 *
 * 全 App 的白卡、淡彩图标圆底、页面背景统一在此实现，
 * 页面禁止自行拼装投影/圆角/容器色参数，保证风格一致。
 *
 * 风格要点：浅灰页面底上浮纯白大圆角卡片，无描边、极轻投影；
 * 图标用淡彩圆底 + 同系彩色图标，不使用渐变填充。
 */

/** 卡片圆角（全局统一 16dp）。 */
val SoftCardShape: Shape = RoundedCornerShape(16.dp)

/**
 * 白色柔和卡片：
 * - 纯白填充、16dp 圆角、无描边
 * - 深蓝黑 8% 极轻投影（分离层次但不抢眼）
 *
 * [onClick] 为 null 时渲染为不可点击容器；点击态在内层 Box 处理，
 * 波纹被卡片形状裁切，且不依赖实验性 Card(onClick) API。
 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    shape: Shape = SoftCardShape,
    elevation: Dp = 4.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Card(
        modifier = modifier.shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = PadGuardColors.CardShadow,
            spotColor = PadGuardColors.CardShadow
        ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier.let { m ->
                if (onClick != null) m.clickable(onClick = onClick) else m
            },
            content = content
        )
    }
}

/**
 * Hero 深紫渐变卡（每屏至多一张）：
 * - 深紫三段渐变实底（`PadGuardColors.HeroGradient`）
 * - 深紫 28% 投影（`PadGuardColors.HeroShadow`）
 * - 其上内容一律白字（由调用方保证）
 *
 * 全 App 的 Hero 卡（今日使用卡、周期汇总卡）统一走本组件，
 * 禁止在页面自行拼装渐变/投影参数。
 */
@Composable
fun SoftHeroCard(
    modifier: Modifier = Modifier,
    shape: Shape = SoftCardShape,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = PadGuardColors.HeroShadow,
                spotColor = PadGuardColors.HeroShadow
            )
            .clip(shape)
            .background(brush = PadGuardColors.HeroGradient)
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
        content = content
    )
}

/**
 * 淡彩圆底图标徽章：白卡内的功能入口图标。
 * [tintIndex] 在 蓝/紫/粉 三色组中轮换（负数安全），同屏有节奏不单调。
 */
@Composable
fun SoftIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tintIndex: Int = 0,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    contentDescription: String? = null
) {
    val n = PadGuardColors.TintContainers.size
    val idx = ((tintIndex % n) + n) % n
    Box(
        modifier = modifier
            .size(size)
            .background(color = PadGuardColors.TintContainers[idx], shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = PadGuardColors.TintIconColors[idx],
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 页面背景：浅灰纯色（#F5F6F8）。铺在根导航，全页共享；
 * 各页 Scaffold/顶层 Surface 透明化让底色透出。
 */
@Composable
fun AppSoftBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.background(PadGuardColors.AppBackground)) {
        content()
    }
}
