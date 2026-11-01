package com.xzledger.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ColorOS 17 风格「液态玻璃」设计语言（界面基底）。
 *
 * 真实的背景模糊在 Compose 里做不了：RenderEffect 只作用于自身内容，无法采样兄弟节点的像素，
 * 所以这里的玻璃由四层叠加模拟：
 *  1) 半透明基底（深色 / 浅色自适应，跟随 appearance.seed 的明暗）；
 *  2) 左上方向的斜向主高光 + 底部主题色环境反光（让面板像一块有厚度的玻璃，而不是一张灰片）；
 *  3) 1dp 渐变描边：上沿亮、下沿暗，模拟玻璃厚边的折射；
 *  4) 柔和投影（关闭玻璃时只剩 2dp，退回纯色卡片）。
 * 关闭 appearance.glass 时退回不透明纯色 + 发丝描边，功能与布局完全一致。
 */
@Composable
fun Modifier.liquidGlass(
    corner: Dp = 28.dp,
    strong: Boolean = false,
    enabled: Boolean = LocalAppearance.current.glass,
    elevation: Dp = 14.dp,
    tint: Color = Color.Transparent
): Modifier {
    val apple = LocalAppearance.current.liquidApple && enabled
    val dark = glassDark()
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    // Apple Liquid Glass 的圆角更「饱满」：同一份调用代码下自动放大一档。
    val radius = if (apple) corner * 1.18f else corner
    val shape = RoundedCornerShape(radius)
    val base = when {
        tint != Color.Transparent -> tint
        dark -> Color(0xFF1A1E26)
        else -> Color(0xFFFFFFFF)
    }
    val topAlpha = when {
        !enabled -> 1f
        apple -> if (strong) 0.66f else 0.44f
        strong -> 0.95f
        else -> 0.78f
    }
    val bottomAlpha = when {
        !enabled -> 1f
        apple -> if (strong) 0.50f else 0.28f
        strong -> 0.88f
        else -> 0.62f
    }
    // Apple 风格的阴影更大更柔，靠模糊半径而不是深色撑起层次。
    val shadowDp = when {
        !enabled -> 2.dp
        apple -> elevation * 1.7f
        else -> elevation
    }
    return this
        .shadow(
            shadowDp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = if (apple) (if (dark) 0.55f else 0.20f) else 0.16f),
            spotColor = Color.Black.copy(alpha = if (apple) (if (dark) 0.45f else 0.22f) else 0.20f)
        )
        .clip(shape)
        .background(
            Brush.verticalGradient(listOf(base.copy(alpha = topAlpha), base.copy(alpha = bottomAlpha))),
            shape
        )
        .drawBehind {
            if (!enabled) {
                // 关闭玻璃：保留一条发丝描边，避免纯色卡片糊在背景上。
                drawRoundRect(
                    color = outline.copy(alpha = 0.35f),
                    cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
                return@drawBehind
            }
            val w = size.width
            val h = size.height
            if (apple) {
                // —— Apple Liquid Glass：薄、透、边缘强高光 ——
                // 1) 镜面高光：一条从左上斜下的窄光带，比 ColorOS 风格更集中、更亮。
                drawRect(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = if (dark) 0.16f else 0.62f),
                        0.26f to Color.White.copy(alpha = if (dark) 0.05f else 0.20f),
                        0.55f to Color.Transparent,
                        start = Offset(0f, 0f),
                        end = Offset(w * 0.55f, h * 0.9f)
                    )
                )
                // 2) 底缘内阴影：玻璃有厚度，下沿略暗，上沿才不会「飘」。
                drawRect(
                    Brush.verticalGradient(
                        0.62f to Color.Transparent,
                        1f to Color.Black.copy(alpha = if (dark) 0.14f else 0.055f)
                    )
                )
                // 3) 底缘彩色折射：环境色从玻璃下方透上来。
                drawRect(
                    Brush.verticalGradient(
                        0.80f to Color.Transparent,
                        1f to primary.copy(alpha = if (dark) 0.16f else 0.09f)
                    )
                )
                // 4) 1.4dp 亮边：上沿几乎纯白，向下迅速衰减。
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.62f else 0.98f),
                            Color.White.copy(alpha = if (dark) 0.16f else 0.42f),
                            Color.White.copy(alpha = if (dark) 0.10f else 0.30f)
                        )
                    ),
                    cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
                    style = Stroke(width = 1.4.dp.toPx())
                )
                return@drawBehind
            }
            // 斜向主高光：光从左上打到玻璃面上
            drawRect(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.11f else 0.55f),
                    0.38f to Color.White.copy(alpha = if (dark) 0.035f else 0.16f),
                    0.72f to Color.Transparent,
                    start = Offset(0f, 0f),
                    end = Offset(w * 0.85f, h)
                )
            )
            // 底部环境反光：与主题色呼应，玻璃才有「液态」的通透感
            drawRect(
                Brush.verticalGradient(
                    0.55f to Color.Transparent,
                    1f to primary.copy(alpha = if (dark) 0.16f else 0.10f)
                )
            )
            // 玻璃厚边：上亮下暗
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (dark) 0.42f else 0.95f),
                        Color.White.copy(alpha = if (dark) 0.09f else 0.28f),
                        primary.copy(alpha = if (dark) 0.32f else 0.22f)
                    )
                ),
                cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )
        }
}

/** 当前主题是否偏暗（用来决定玻璃基底与高光的强度）。 */
@Composable
fun glassDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * 全局彩色氛围底：ColorOS 17 的液态玻璃都浮在柔和的光晕上。
 * 左上主色、右侧辅色、底部暖色回光；关闭玻璃时只保留纯背景色。
 */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier) {
    val appearance = LocalAppearance.current
    val dark = glassDark()
    val bg = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    Box(
        modifier
            .fillMaxSize()
            .background(bg)
            .drawBehind {
                if (!appearance.glass) return@drawBehind
                val w = size.width
                val h = size.height
                if (appearance.liquidApple) {
                    // Apple 风格：低饱和、低对比的柔光底，让玻璃自己承担层次，背景不抢戏。
                    drawRect(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (dark) 0.05f else 0.55f),
                                Color.Transparent,
                                Color.Black.copy(alpha = if (dark) 0.12f else 0.035f)
                            )
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(primary.copy(alpha = if (dark) 0.16f else 0.13f), Color.Transparent),
                            center = Offset(w * 0.18f, h * 0.06f),
                            radius = w * 1.05f
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(secondary.copy(alpha = if (dark) 0.12f else 0.10f), Color.Transparent),
                            center = Offset(w * 0.92f, h * 0.88f),
                            radius = w * 0.95f
                        )
                    )
                    return@drawBehind
                }
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(primary.copy(alpha = if (dark) 0.34f else 0.26f), Color.Transparent),
                        center = Offset(w * 0.12f, h * 0.02f),
                        radius = w * 0.95f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(tertiary.copy(alpha = if (dark) 0.26f else 0.16f), Color.Transparent),
                        center = Offset(w * 0.98f, h * 0.40f),
                        radius = w * 0.78f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(secondary.copy(alpha = if (dark) 0.28f else 0.18f), Color.Transparent),
                        center = Offset(w * 0.48f, h * 1.02f),
                        radius = w * 0.85f
                    )
                )
            }
    )
}
