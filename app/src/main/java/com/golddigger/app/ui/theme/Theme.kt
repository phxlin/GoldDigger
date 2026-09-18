package com.golddigger.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Semantic gain/loss colors, resolved for the active theme. Kept out of the
 * Material color scheme because "up is good / down is bad" is domain meaning,
 * not a UI role.
 */
object PortfolioColors {
    val gain: Color @Composable get() = if (isSystemInDarkTheme()) GainDark else GainLight
    val loss: Color @Composable get() = if (isSystemInDarkTheme()) LossDark else LossLight

    @Composable
    fun forDelta(value: Double): Color = if (value >= 0) gain else loss
}

@Composable
fun GoldDiggerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand identity first: the gold/green palette is used unless the caller
    // explicitly opts into Material You.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = GoldDiggerTypography,
        content = content,
    )
}
