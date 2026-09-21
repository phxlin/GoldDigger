package com.golddigger.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resolver = LocalContext.current.contentResolver
    val hasData by viewModel.hasData.collectAsStateWithLifecycle()
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var pendingImport by rememberSaveable { mutableStateOf<Uri?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportBackup(resolver, uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingImport = uri }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
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
            SectionTitle("Your data")
            Text(
                "Stored only on this device. Export a backup so a lost phone or a reinstall " +
                    "doesn't mean lost holdings or price history.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                DataRow(
                    title = "Export backup",
                    subtitle = "Save a JSON file with all holdings, groups and price history",
                    onClick = { exportLauncher.launch("golddigger-backup-${LocalDate.now()}.json") },
                )
                HorizontalDivider()
                DataRow(
                    title = "Import backup",
                    subtitle = "Replace everything here with a backup file",
                    onClick = {
                        importLauncher.launch(
                            arrayOf("application/json", "text/plain", "application/octet-stream"),
                        )
                    },
                )
                HorizontalDivider()
                DataRow(
                    title = "Delete all data",
                    subtitle = "Remove every holding, group and price history from this device",
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { showDelete = true },
                )
            }

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

    if (showDelete) {
        ConfirmDialog(
            title = "Delete all data?",
            text = "Every holding, group and price history on this device will be permanently " +
                "removed. Export a backup first if you might want it back.",
            confirmLabel = "Delete everything",
            destructive = true,
            confirmPhrase = DELETE_CONFIRM_PHRASE,
            onConfirm = {
                showDelete = false
                viewModel.deleteEverything()
            },
            onDismiss = { showDelete = false },
        )
    }
    pendingImport?.let { uri ->
        val prompt = importPrompt(hasData)
        ConfirmDialog(
            title = prompt.title,
            text = prompt.text,
            confirmLabel = prompt.confirmLabel,
            destructive = prompt.destructive,
            onConfirm = {
                pendingImport = null
                viewModel.importBackup(resolver, uri)
            },
            onDismiss = { pendingImport = null },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

/**
 * A confirm/cancel dialog. With a [confirmPhrase] the confirm button stays disabled until that
 * phrase has been typed, for actions that can't be undone.
 */
@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmPhrase: String? = null,
) {
    // Local to the dialog, so it starts empty every time the dialog is opened.
    var typed by rememberSaveable { mutableStateOf("") }
    val confirmed = confirmPhrase == null || matchesConfirmPhrase(typed, confirmPhrase)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text)
                if (confirmPhrase != null) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text("Type $confirmPhrase to confirm") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmed,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A tappable title-plus-description row, like the ones in Avalanche's "Your data" section. */
@Composable
private fun DataRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
