package com.golddigger.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()

/**
 * Mostly the Material 3 defaults, with a tighter, heavier display/headline for
 * the big money figures and section titles.
 */
val GoldDiggerTypography = base.copy(
    displayMedium = base.displayMedium.copy(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp,
    ),
    displaySmall = base.displaySmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(letterSpacing = 0.5.sp),
)
