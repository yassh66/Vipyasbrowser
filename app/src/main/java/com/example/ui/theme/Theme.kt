package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

data class ExtendedThemeColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val cardBackground: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val inputBackground: Color,
    val inputBorder: Color,
    val accent: Color
)

val LocalAccentColor = compositionLocalOf { YasCyan }

val LocalThemeColors = compositionLocalOf {
    ExtendedThemeColors(
        isDark = true,
        background = Color(0xFF090D16),
        surface = Color(0xFF131B2E),
        surfaceVariant = Color(0xFF1E293B),
        cardBackground = Color(0xFF182238),
        cardBorder = Color(0xFF2A3756),
        textPrimary = Color(0xFFF1F5F9),
        textSecondary = Color(0xFF94A3B8),
        textMuted = Color(0xFF64748B),
        inputBackground = Color(0xFF0B101D),
        inputBorder = Color(0xFF2A3756),
        accent = YasCyan
    )
}

val MaterialTheme.extendedColors: ExtendedThemeColors
    @Composable
    get() = LocalThemeColors.current

private fun getAdaptiveAccent(baseAccent: Color, isDark: Boolean): Color {
    if (isDark) return baseAccent
    return when (baseAccent) {
        Color(0xFF00E5FF) -> Color(0xFF0097A7) // Cyber Cyan -> Deep Rich Cyan for light mode contrast
        Color(0xFF8B5CF6) -> Color(0xFF6D28D9) // Purple
        Color(0xFFF59E0B) -> Color(0xFFD97706) // Amber
        Color(0xFF10B981) -> Color(0xFF059669) // Emerald
        Color(0xFFFF2A85) -> Color(0xFFE11D48) // Neon Pink -> Deep Rose Pink
        Color(0xFF3B82F6) -> Color(0xFF1D4ED8) // Blue
        else -> baseAccent
    }
}

private fun createDarkExtendedColors(accentColor: Color) = ExtendedThemeColors(
    isDark = true,
    background = Color(0xFF090D16),
    surface = Color(0xFF131B2E),
    surfaceVariant = Color(0xFF1E293B),
    cardBackground = Color(0xFF182238),
    cardBorder = Color(0xFF2A3756),
    textPrimary = Color(0xFFF1F5F9),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    inputBackground = Color(0xFF0B101D),
    inputBorder = Color(0xFF2A3756),
    accent = accentColor
)

private fun createLightExtendedColors(accentColor: Color): ExtendedThemeColors {
    val adaptiveAccent = getAdaptiveAccent(accentColor, false)
    return ExtendedThemeColors(
        isDark = false,
        background = Color(0xFFF8FAFC),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF1F5F9),
        cardBackground = Color(0xFFFFFFFF),
        cardBorder = Color(0xFFE2E8F0),
        textPrimary = Color(0xFF0F172A),
        textSecondary = Color(0xFF475569),
        textMuted = Color(0xFF64748B),
        inputBackground = Color(0xFFF8FAFC),
        inputBorder = Color(0xFFCBD5E1),
        accent = adaptiveAccent
    )
}

private fun createDarkColorScheme(accentColor: Color) = darkColorScheme(
    primary = accentColor,
    onPrimary = Color(0xFF090D16),
    primaryContainer = YasPurple,
    onPrimaryContainer = Color(0xFFF1F5F9),
    secondary = YasPurple,
    onSecondary = Color(0xFFF1F5F9),
    background = Color(0xFF090D16),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF131B2E),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2A3756),
    error = ErrorRed,
    onError = Color(0xFFF1F5F9)
)

private fun createLightColorScheme(accentColor: Color): androidx.compose.material3.ColorScheme {
    val adaptiveAccent = getAdaptiveAccent(accentColor, false)
    return lightColorScheme(
        primary = adaptiveAccent,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEDF2F7),
        onPrimaryContainer = Color(0xFF0F172A),
        secondary = YasPurple,
        onSecondary = Color.White,
        background = Color(0xFFF8FAFC),
        onBackground = Color(0xFF0F172A),
        surface = Color.White,
        onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFF1F5F9),
        onSurfaceVariant = Color(0xFF475569),
        outline = Color(0xFFCBD5E1),
        error = ErrorRed,
        onError = Color.White
    )
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun YASBrowserTheme(
    darkTheme: Boolean = true,
    accentColor: Color = YasCyan,
    content: @Composable () -> Unit
) {
    val effectiveAccent = getAdaptiveAccent(accentColor, darkTheme)
    val colorScheme = if (darkTheme) createDarkColorScheme(accentColor) else createLightColorScheme(accentColor)
    val extendedColors = if (darkTheme) createDarkExtendedColors(accentColor) else createLightExtendedColors(accentColor)
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalAccentColor provides effectiveAccent,
        LocalThemeColors provides extendedColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
