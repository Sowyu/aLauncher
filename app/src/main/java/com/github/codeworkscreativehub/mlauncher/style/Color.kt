package com.github.codeworkscreativehub.mlauncher.style

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Colours used by the settings screens. */
@Immutable
data class SettingsPalette(
    val background: Color,
    val surface: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val textSecondary: Color,
    val isDark: Boolean,
) {
    /** Fill behind category icons. */
    val accentTile: Color get() = accent.copy(alpha = 0.16f)

    /** Inset dividers and the unchecked switch track. */
    val divider: Color get() = text.copy(alpha = 0.08f)
    val surfaceVariant: Color get() = text.copy(alpha = 0.12f)
}

val DarkSettingsPalette = SettingsPalette(
    background = Color(0xFF120E1A),
    surface = Color(0xFF1E1826),
    accent = Color(0xFFE4B9E2),
    onAccent = Color(0xFF3B1830),
    text = Color(0xFFF3EAF3),
    textSecondary = Color(0xFFB8A9B8),
    isDark = true,
)

val LightSettingsPalette = SettingsPalette(
    background = Color(0xFFFBF6FA),
    surface = Color(0xFFFFFFFF),
    accent = Color(0xFF8E4A8A),
    onAccent = Color(0xFFFFFFFF),
    text = Color(0xFF1E1826),
    textSecondary = Color(0xFF6B5C6B),
    isDark = false,
)
