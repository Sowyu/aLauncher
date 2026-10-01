package com.github.codeworkscreativehub.mlauncher.style

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.github.codeworkscreativehub.mlauncher.R

/** Bundled Google Sans Flex (rounded), real weights 400 to 700. */
val GoogleSansFlex = FontFamily(
    Font(R.font.google_sans_flex_regular, FontWeight.Normal),
    Font(R.font.google_sans_flex_medium, FontWeight.Medium),
    Font(R.font.google_sans_flex_semibold, FontWeight.SemiBold),
    Font(R.font.google_sans_flex_bold, FontWeight.Bold),
)

private val LocalSettingsPalette = staticCompositionLocalOf { DarkSettingsPalette }

/** Text styles for the settings screens. Every style uses Google Sans Flex. */
object SettingsType {
    private fun style(size: Int, weight: FontWeight, lineHeight: Int, spacing: Float = 0f) = TextStyle(
        fontFamily = GoogleSansFlex,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = spacing.em,
    )

    val screenTitle = style(34, FontWeight.Medium, 42)
    val dialogTitle = style(24, FontWeight.Medium, 30)
    val categoryTitle = style(20, FontWeight.Medium, 26)
    val rowTitle = style(17, FontWeight.Normal, 23)
    val option = style(17, FontWeight.Normal, 23)
    val subtitle = style(14, FontWeight.Normal, 19)
    val section = style(13, FontWeight.Medium, 18, 0.08f)
    val value = style(15, FontWeight.Normal, 20)
    val button = style(16, FontWeight.Medium, 20)
    val bigValue = style(40, FontWeight.Medium, 46)
}

@Composable
fun SettingsTheme(
    isDark: Boolean,
    content: @Composable () -> Unit
) {
    val palette = if (isDark) DarkSettingsPalette else LightSettingsPalette
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    val colorScheme = base.copy(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        secondary = palette.accent,
        background = palette.background,
        onBackground = palette.text,
        surface = palette.surface,
        onSurface = palette.text,
        surfaceVariant = palette.surfaceVariant,
        onSurfaceVariant = palette.textSecondary,
        surfaceContainerHigh = palette.surface,
        surfaceContainerHighest = palette.surfaceVariant,
        outline = palette.textSecondary,
    )
    val default = Typography()
    val typography = Typography(
        displayLarge = default.displayLarge.copy(fontFamily = GoogleSansFlex),
        displayMedium = default.displayMedium.copy(fontFamily = GoogleSansFlex),
        displaySmall = default.displaySmall.copy(fontFamily = GoogleSansFlex),
        headlineLarge = default.headlineLarge.copy(fontFamily = GoogleSansFlex),
        headlineMedium = default.headlineMedium.copy(fontFamily = GoogleSansFlex),
        headlineSmall = default.headlineSmall.copy(fontFamily = GoogleSansFlex),
        titleLarge = default.titleLarge.copy(fontFamily = GoogleSansFlex),
        titleMedium = default.titleMedium.copy(fontFamily = GoogleSansFlex),
        titleSmall = default.titleSmall.copy(fontFamily = GoogleSansFlex),
        bodyLarge = default.bodyLarge.copy(fontFamily = GoogleSansFlex),
        bodyMedium = default.bodyMedium.copy(fontFamily = GoogleSansFlex),
        bodySmall = default.bodySmall.copy(fontFamily = GoogleSansFlex),
        labelLarge = default.labelLarge.copy(fontFamily = GoogleSansFlex),
        labelMedium = default.labelMedium.copy(fontFamily = GoogleSansFlex),
        labelSmall = default.labelSmall.copy(fontFamily = GoogleSansFlex),
    )
    CompositionLocalProvider(LocalSettingsPalette provides palette) {
        MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
    }
}

object SettingsTheme {
    val palette: SettingsPalette
        @Composable
        get() = LocalSettingsPalette.current
}
