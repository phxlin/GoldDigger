package com.golddigger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.golddigger.app.ui.theme.PortfolioColors

/**
 * A small rounded pill showing a gain/loss figure with a direction triangle,
 * tinted green or red.
 */
@Composable
fun DeltaChip(
    text: String,
    positive: Boolean,
    modifier: Modifier = Modifier,
    showArrow: Boolean = true,
) {
    val color = if (positive) PortfolioColors.gain else PortfolioColors.loss
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArrow) {
            Icon(
                imageVector = if (positive) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

/** Small filled circle used to tie a list row to its chart slice. */
@Composable
fun ColorDot(color: Color, modifier: Modifier = Modifier, size: Int = 8) {
    Row(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
    ) {}
}
