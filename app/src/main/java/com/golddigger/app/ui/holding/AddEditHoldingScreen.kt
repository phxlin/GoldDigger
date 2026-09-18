package com.golddigger.app.ui.holding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.golddigger.app.ui.common.asCurrency

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditHoldingScreen(
    onDone: () -> Unit,
    viewModel: AddEditHoldingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.editing) "Edit holding" else "Add holding") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Ticker symbol") },
                singleLine = true,
                enabled = !state.editing,
                supportingText = { state.selectedName?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.searching) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Searching…", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (state.alreadyHeld) {
                Text(
                    "You already hold ${state.selectedTicker} — saving will combine these " +
                        "shares into your existing position rather than adding a second one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (state.results.isNotEmpty() && state.selectedTicker == null) {
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(state.results, key = { it.symbol }) { result ->
                        ListItem(
                            headlineContent = { Text(result.symbol) },
                            supportingContent = { Text(result.description) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onSelectSymbol(result) },
                        )
                        HorizontalDivider()
                    }
                }
            }

            if (state.selectedTicker != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("This is an ETF", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.isEtf, onCheckedChange = viewModel::onIsEtfChange)
                }
            }

            OutlinedTextField(
                value = state.sharesText,
                onValueChange = viewModel::onSharesChange,
                label = { Text("Shares") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.costMode == CostMode.PER_SHARE,
                    onClick = { viewModel.onCostModeChange(CostMode.PER_SHARE) },
                    label = { Text("Cost / share") },
                )
                FilterChip(
                    selected = state.costMode == CostMode.TOTAL,
                    onClick = { viewModel.onCostModeChange(CostMode.TOTAL) },
                    label = { Text("Total cost") },
                )
            }

            OutlinedTextField(
                value = state.costText,
                onValueChange = viewModel::onCostChange,
                label = {
                    Text(
                        if (state.costMode == CostMode.PER_SHARE) "Price per share"
                        else "Total amount paid",
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            state.totalCostBasis?.let {
                Text(
                    "Cost basis: ${it.asCurrency()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.editing) "Save changes" else "Add to portfolio")
            }
        }
    }
}
