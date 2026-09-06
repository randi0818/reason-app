package me.excuse.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// 原始调色板常量 —— 给 Theme.kt 构造 ColorScheme / AppColors 用，外部不直接引用。
internal val InkLight = Color(0xFF111111)
internal val PaperLight = Color(0xFFFAFAFA)
internal val SurfaceBgLight = Color(0xFFFFFFFF)
internal val MutedLight = Color(0xFF707070)
internal val LineLight = Color(0xFFE5E5E5)
internal val DangerLight = Color(0xFFCC2222)
internal val FaintLight = Color(0xFF888888)
internal val MutedSoftLight = Color(0xFFBBBBBB)
internal val DisabledLight = Color(0xFFCCCCCC)

internal val InkDark = Color(0xFFF2F2F2)
internal val PaperDark = Color(0xFF0E0E0E)
internal val SurfaceBgDark = Color(0xFF1A1A1A)
internal val MutedDark = Color(0xFF8A8A8A)
internal val LineDark = Color(0xFF2A2A2A)
internal val DangerDark = Color(0xFFFF5C5C)
internal val FaintDark = Color(0xFF8A8A8A)
internal val MutedSoftDark = Color(0xFF555555)
internal val DisabledDark = Color(0xFF333333)

data class AppColors(
    val fg: Color,
    val bg: Color,
    val surface: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val danger: Color,
    val mutedSoft: Color,
    val disabled: Color,
    val heatmap: List<Color>, // 5 级，idx 0 = 空，idx 4 = 满
    val isDark: Boolean
)

val LightAppColors = AppColors(
    fg = InkLight,
    bg = PaperLight,
    surface = SurfaceBgLight,
    muted = MutedLight,
    faint = FaintLight,
    line = LineLight,
    danger = DangerLight,
    mutedSoft = MutedSoftLight,
    disabled = DisabledLight,
    heatmap = listOf(
        Color(0xFFEFEFEF),
        Color(0xFFCFCFCF),
        Color(0xFF999999),
        Color(0xFF555555),
        Color(0xFF111111)
    ),
    isDark = false
)

val DarkAppColors = AppColors(
    fg = InkDark,
    bg = PaperDark,
    surface = SurfaceBgDark,
    muted = MutedDark,
    faint = FaintDark,
    line = LineDark,
    danger = DangerDark,
    mutedSoft = MutedSoftDark,
    disabled = DisabledDark,
    heatmap = listOf(
        Color(0xFF1A1A1A),
        Color(0xFF333333),
        Color(0xFF666666),
        Color(0xFFAAAAAA),
        Color(0xFFF2F2F2)
    ),
    isDark = true
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

// Composable-scope 语义访问器 —— 现有 `Ink` / `Paper` / `Muted` / `Line` 调用点不需要改名。
val Ink: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.fg
val Paper: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.bg
val SurfaceBg: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.surface
val Muted: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.muted
/**
 * 比 [Muted] 更退的一级：刻度、占位符这种本来就该躲在数据后面的文字。
 *
 * [Muted] 改成 #707070 是为了让正文尺寸的次要文字过 WCAG AA（浅色下 3.39:1 → 4.74:1），
 * 但刻度和占位符跟着变深之后会跟数据抢注意力，所以单拎出来保持原值。
 * 这是故意不达 AA 的一组颜色，别把正文改成它。
 */
val Faint: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.faint
val Line: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.line
val Danger: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.danger
val MutedSoft: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.mutedSoft
val Disabled: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.disabled
