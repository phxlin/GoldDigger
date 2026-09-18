package com.golddigger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.golddigger.app.data.repository.SyncState
import com.golddigger.app.ui.common.clockTime
import com.golddigger.app.ui.common.relativeTime
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Calm, single-line status about the freshness of on-screen prices. Rate limits
 * and offline states are shown here as information, never as an error.
 */
@Composable
fun SyncStatusBar(
    state: SyncState,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    val (icon: ImageVector, message: String) = when (state) {
        SyncState.Idle, SyncState.Syncing -> return
        is SyncState.UpToDate ->
            Icons.Filled.Schedule to "Prices updated ${relativeTime(state.atEpochMs, now)}"

        is SyncState.RateLimited -> {
            val mins = state.nextAllowedEpochMs
                ?.let { max(0L, it - now) }
                ?.let { (it / 60_000.0).roundToInt() }
                ?: 1
            val base = state.lastUpdatedEpochMs?.let { "Prices from ${clockTime(it)}. " } ?: ""
            Icons.Filled.Info to
                "${base}Refresh limit reached — next update in ~${max(1, mins)} min"
        }

        is SyncState.Offline ->
            Icons.Filled.CloudOff to
                "Offline — showing cached prices from ${relativeTime(state.lastUpdatedEpochMs, now)}"

        SyncState.NoApiKey ->
            Icons.Filled.Info to
                "No price API key configured — add FINNHUB_API_KEY to local.properties"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
