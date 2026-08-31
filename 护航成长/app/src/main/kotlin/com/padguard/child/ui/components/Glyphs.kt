package com.padguard.child.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 管控覆盖层用的单字符徽标。
 *
 * 不依赖 `material-icons` 额外依赖（避免引入不确定传递依赖导致编译失败），
 * 用一个圆形底色 + 中文单字表达语义：
 * - 锁屏页用「锁」
 * - 应用拦截用「禁」
 * - 消息通知用「告」
 * 字符即图标，零图标库、零资源，渲染稳定。
 */
@Composable
fun GlyphBadge(char: String, tint: Color) {
    Box(
        modifier = Modifier.size(72.dp).clip(CircleShape).background(tint),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = char,
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** 列表项里用的小尺寸 Glyph 类型。 */
enum class GlyphKind { BLOCK, LOCK, NOTICE, CHECK }

/**
 * 列表项里用的小尺寸 Glyph（行内图标位）。
 *
 * 区别于 [GlyphBadge]：尺寸更小（默认 24dp）、颜色根据 [kind] 决定，
 * 适合放在列表项左侧作为类型指示。
 */
@Composable
fun Glyph(
    kind: GlyphKind,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    val (char, tint) = when (kind) {
        GlyphKind.BLOCK -> "禁" to Color(0xFFE57373)
        GlyphKind.LOCK -> "锁" to Color(0xFF64B5F6)
        GlyphKind.NOTICE -> "告" to Color(0xFFFFB74D)
        GlyphKind.CHECK -> "可" to Color(0xFF81C784)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = char,
            color = Color.White,
            fontSize = (size.value * 0.5f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}
