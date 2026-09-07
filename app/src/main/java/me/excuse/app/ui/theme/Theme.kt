package me.excuse.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.excuse.app.appServices
import me.excuse.app.data.prefs.ThemeMode

private fun materialScheme(c: AppColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.fg, onPrimary = c.bg,
        secondary = c.fg, onSecondary = c.bg,
        background = c.bg, onBackground = c.fg,
        surface = c.surface, onSurface = c.fg,
        surfaceVariant = c.bg, onSurfaceVariant = c.muted,
        outline = c.line
    )
} else {
    lightColorScheme(
        primary = c.fg, onPrimary = c.bg,
        secondary = c.fg, onSecondary = c.bg,
        background = c.bg, onBackground = c.fg,
        surface = c.surface, onSurface = c.fg,
        surfaceVariant = c.bg, onSurfaceVariant = c.muted,
        outline = c.line
    )
}

// Metro / Windows Phone 风：全直角，无圆角。一处定义，所有组件继承。
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
)

@Composable
fun ExcuseTheme(content: @Composable () -> Unit) {
    val mode by LocalContext.current.appServices.themeModeFlow.collectAsState()
    ExcuseTheme(mode = mode, content = content)
}

@Composable
fun ExcuseTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val useDark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val appColors = if (useDark) DarkAppColors else LightAppColors
    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = materialScheme(appColors),
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
