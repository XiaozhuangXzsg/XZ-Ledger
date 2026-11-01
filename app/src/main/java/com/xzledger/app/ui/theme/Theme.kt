package com.xzledger.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** 主题与个性化设置（第八节：浅色／深色／跟随系统、自定义颜色、字体大小、动画、液态玻璃）。 */
data class Appearance(
    val mode: String = "system",       // system | light | dark
    val seed: String = "sandrone",     // 主色方案（默认：桑多涅风格暖棕/金）
    val fontScale: Float = 1f,
    val animations: Boolean = true,
    val smoothAnimations: Boolean = true, // true=平滑出现，false=淡化
    val glass: Boolean = true,
    val language: String = "zh",
    val anime: Boolean = true,       // 二次元元素（桑多涅看板娘 / 星芒点缀）
    // Apple Liquid Glass（WWDC 2025）风格：更薄更透的玻璃、更强的边缘高光、更柔的大阴影、
    // 更大的圆角与低饱和背景。关闭则保持原来的 ColorOS 17 风格。
    val liquidApple: Boolean = false
)

val LocalAppearance = staticCompositionLocalOf { Appearance() }

private data class Palette(val primary: Color, val secondary: Color, val tertiary: Color, val container: Color)

private val palettes = mapOf(
    "sandrone" to Palette(Color(0xFFA8791F), Color(0xFF8A6E5A), Color(0xFFA63D4A), Color(0xFFF6EEDA)),
    "indigo" to Palette(Color(0xFF4C5BD4), Color(0xFF7C6CF0), Color(0xFF38B2AC), Color(0xFFE6E9FF)),
    "teal" to Palette(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF5C6BC0), Color(0xFFDDF3EF)),
    "amber" to Palette(Color(0xFFE08A00), Color(0xFFF2A93B), Color(0xFF8D6E63), Color(0xFFFFF1DA)),
    "rose" to Palette(Color(0xFFD64570), Color(0xFFEE7CA0), Color(0xFF7E57C2), Color(0xFFFFE4EC)),
    "green" to Palette(Color(0xFF2E7D32), Color(0xFF66BB6A), Color(0xFF00838F), Color(0xFFE2F4E3)),
    "blue" to Palette(Color(0xFF1565C0), Color(0xFF42A5F5), Color(0xFF00897B), Color(0xFFE0EDFB)),
    "purple" to Palette(Color(0xFF6A1B9A), Color(0xFFAB47BC), Color(0xFF3949AB), Color(0xFFF2E4F8)),
    "graphite" to Palette(Color(0xFF455A64), Color(0xFF78909C), Color(0xFF5D4037), Color(0xFFE8ECEF))
)

val seedKeys: List<String> = palettes.keys.toList()

fun seedLabel(key: String): String = when (key) {
    "sandrone", "" -> "金棕（默认）"
    "indigo" -> "靛蓝"
    "teal" -> "青绿"
    "amber" -> "琥珀"
    "rose" -> "玫瑰"
    "green" -> "森绿"
    "blue" -> "天蓝"
    "purple" -> "紫罗兰"
    "graphite" -> "石墨"
    else -> key
}

fun scaledTypography(scale: Float): Typography {
    // 显式给出行高：只写 fontSize 时部分机型在系统字体放大后会出现行与行重叠。
    fun s(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
        TextStyle(fontSize = (size * scale).sp, lineHeight = (size * scale * 1.38f).sp, fontWeight = weight)
    val d = Typography()
    return d.copy(
        displaySmall = s(30f, FontWeight.SemiBold),
        headlineMedium = s(26f, FontWeight.SemiBold),
        headlineSmall = s(22f, FontWeight.SemiBold),
        titleLarge = s(20f, FontWeight.SemiBold),
        titleMedium = s(16f, FontWeight.SemiBold),
        titleSmall = s(14f, FontWeight.Medium),
        bodyLarge = s(16f),
        bodyMedium = s(14f),
        bodySmall = s(12f),
        labelLarge = s(14f, FontWeight.Medium),
        labelMedium = s(12f, FontWeight.Medium),
        labelSmall = s(11f, FontWeight.Medium)
    )
}

@Composable
fun XzTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance.mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    // 未知或空的主色键一律回落到新的默认金棕，避免界面莫名回到旧的靛蓝配色。
    val p = palettes[appearance.seed] ?: palettes.getValue("sandrone")
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.primary,
            onPrimary = Color(0xFF10121A),
            primaryContainer = p.primary.copy(alpha = 0.32f),
            onPrimaryContainer = Color(0xFFF6EEDA),
            secondary = p.secondary,
            tertiary = p.tertiary,
            background = Color(0xFF16130F),
            onBackground = Color(0xFFEFE8DC),
            surface = Color(0xFF1E1A14),
            onSurface = Color(0xFFEFE8DC),
            surfaceVariant = Color(0xFF2A241C),
            onSurfaceVariant = Color(0xFFBFB3A2),
            outline = Color(0xFF453C2F)
        )
    } else {
        lightColorScheme(
            primary = p.primary,
            onPrimary = Color.White,
            primaryContainer = p.container,
            onPrimaryContainer = p.primary,
            secondary = p.secondary,
            tertiary = p.tertiary,
            background = Color(0xFFF8F4EC),
            onBackground = Color(0xFF221E18),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF221E18),
            surfaceVariant = Color(0xFFF0E8DA),
            onSurfaceVariant = Color(0xFF5C544A),
            outline = Color(0xFFDCD2C2)
        )
    }
    CompositionLocalProvider(LocalAppearance provides appearance) {
        MaterialTheme(colorScheme = scheme, typography = scaledTypography(appearance.fontScale), content = content)
    }
}

/** 金额颜色：支出用暖色，收入用绿色，退款用蓝绿色。 */
@Composable
fun expenseColor(): Color = if (isSystemInDarkTheme()) Color(0xFFFF9B85) else Color(0xFFD2543C)
@Composable
fun incomeColor(): Color = if (isSystemInDarkTheme()) Color(0xFF8FD8A4) else Color(0xFF3F8A5C)
@Composable
fun refundColor(): Color = if (isSystemInDarkTheme()) Color(0xFF9FD8E8) else Color(0xFF2E7C93)
