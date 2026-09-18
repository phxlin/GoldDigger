package com.golddigger.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * GoldDigger brand palette — warm gold primary on money-green accents. Locked
 * (dynamic color off by default) so the identity stays consistent.
 */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF7A5A12),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFCE7B0),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = Color(0xFF1F6E43),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC5EDD1),
    onSecondaryContainer = Color(0xFF00210F),
    tertiary = Color(0xFF2F5C8A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E4FF),
    background = Color(0xFFFBFAF5),
    onBackground = Color(0xFF1D1B14),
    surface = Color(0xFFFBFAF5),
    onSurface = Color(0xFF1D1B14),
    surfaceVariant = Color(0xFFEDE6D4),
    onSurfaceVariant = Color(0xFF4C463A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F1E6),
    surfaceContainer = Color(0xFFF1EBDD),
    surfaceContainerHigh = Color(0xFFEBE4D4),
    surfaceContainerHighest = Color(0xFFE5DECD),
    outline = Color(0xFF7E7667),
    outlineVariant = Color(0xFFCFC6B2),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFEEC25A),
    onPrimary = Color(0xFF3F2E00),
    primaryContainer = Color(0xFF5B4300),
    onPrimaryContainer = Color(0xFFFCE7B0),
    secondary = Color(0xFF8AD3A5),
    onSecondary = Color(0xFF00391E),
    secondaryContainer = Color(0xFF00522E),
    onSecondaryContainer = Color(0xFFC5EDD1),
    tertiary = Color(0xFFA0C9FF),
    onTertiary = Color(0xFF00325A),
    tertiaryContainer = Color(0xFF124A78),
    background = Color(0xFF1A1811),
    onBackground = Color(0xFFEBE4D4),
    surface = Color(0xFF1A1811),
    onSurface = Color(0xFFEBE4D4),
    surfaceVariant = Color(0xFF4C463A),
    onSurfaceVariant = Color(0xFFCFC6B2),
    surfaceContainerLowest = Color(0xFF120F0A),
    surfaceContainerLow = Color(0xFF262219),
    surfaceContainer = Color(0xFF2E2920),
    surfaceContainerHigh = Color(0xFF39332A),
    surfaceContainerHighest = Color(0xFF453E33),
    outline = Color(0xFF988F7E),
    outlineVariant = Color(0xFF4C463A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/** Semantic up/down colors — deliberately outside the M3 scheme. */
internal val GainLight = Color(0xFF1B7F4B)
internal val LossLight = Color(0xFFC0392B)
internal val GainDark = Color(0xFF63D89B)
internal val LossDark = Color(0xFFFF8A80)
