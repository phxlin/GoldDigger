package com.golddigger.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionTitle("Background price sync")

            SwitchRow(
                label = "Sync prices in the background",
                checked = state.backgroundSyncEnabled,
                onCheckedChange = viewModel::setBackgroundSyncEnabled,
            )
            SwitchRow(
                label = "Only during US market hours",
                checked = state.marketHoursOnly,
                onCheckedChange = viewModel::setMarketHoursOnly,
            )

            Column {
                Text("Refresh every ${state.refreshIntervalMinutes} min")
                Slider(
                    value = state.refreshIntervalMinutes.toFloat(),
                    onValueChange = { viewModel.setRefreshInterval(it.toInt()) },
                    valueRange = state.minIntervalMinutes.toFloat()..state.maxIntervalMinutes.toFloat(),
                    enabled = state.backgroundSyncEnabled,
                )
                Text(
                    "WorkManager enforces a ${state.minIntervalMinutes}-minute minimum for " +
                        "periodic work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            SectionTitle("Rate limiting")

            Column {
                Text("Symbols per API call: ${state.batchSize}")
                Slider(
                    value = state.batchSize.toFloat(),
                    onValueChange = { viewModel.setBatchSize(it.toInt().coerceAtLeast(1)) },
                    valueRange = 1f..50f,
                )
                Text(
                    "The current provider (Finnhub free tier) returns one symbol per quote " +
                        "call, so this stays at 1. Raise it if you switch to a provider with a " +
                        "multi-symbol endpoint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Hard cap: ${state.requestsPerMinuteCap} requests/minute, enforced by a shared " +
                    "throttler across pull-to-refresh, background sync and ticker search.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SectionTitle("Price provider")
            Text(
                "Quotes come from Finnhub. The API key lives in local.properties " +
                    "(FINNHUB_API_KEY) and is read into BuildConfig at build time — it is never " +
                    "hardcoded. See the README to swap providers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.height(4.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
