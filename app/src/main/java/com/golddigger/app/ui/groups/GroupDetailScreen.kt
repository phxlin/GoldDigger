package com.golddigger.app.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.golddigger.app.ui.common.asCurrency
import com.golddigger.app.ui.common.asPlainPercent
import com.golddigger.app.ui.common.displayLabel
import com.golddigger.app.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state) { if (state is GroupDetailUiState.Missing) onBack() }

    val title = (state as? GroupDetailUiState.Loaded)?.group?.name ?: "Group"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state is GroupDetailUiState.Loaded) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete group")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state == GroupDetailUiState.Loading) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        val s = state as? GroupDetailUiState.Loaded ?: return@Scaffold
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                SectionCard(spacing = 4.dp) {
                    Text("Current value", style = MaterialTheme.typography.labelMedium)
                    Text(
                        s.allocation?.currentValue?.asCurrency() ?: "$0.00",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "${s.allocation?.currentPct?.asPlainPercent() ?: "0.0%"} of " +
                            if (s.group.isEtfGroup) "your ETFs" else "your individual stocks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val amount = s.allocation?.amountToTarget
                    val target = s.group.targetAllocationPct
                    if (target != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                amount == null -> "Target ${target.asPlainPercent()}"
                                amount > 1.0 -> {
                                    val currentPct = s.allocation?.currentPct ?: 0.0
                                    val underPct = target - currentPct
                                    "Add ${amount.asCurrency()} (${underPct.asPlainPercent()}) to reach " +
                                        target.asPlainPercent()
                                }
                                amount < -1.0 -> {
                                    val currentPct = s.allocation?.currentPct ?: 0.0
                                    val overPct = currentPct - target
                                    "Over target (${target.asPlainPercent()}) by ${(-amount).asCurrency()} " +
                                        "(${overPct.asPlainPercent()})"
                                }
                                else -> "On target (${target.asPlainPercent()})"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item(key = "edit") {
                GroupEditor(
                    initialName = s.group.name,
                    initialTarget = s.group.targetAllocationPct,
                    initialIsEtfGroup = s.group.isEtfGroup,
                    onSave = { name, target, isEtfGroup ->
                        viewModel.updateGroup(name, target, isEtfGroup)
                        onBack()
                    },
                )
            }

            item(key = "members-header") {
                Text("Holdings in this group", style = MaterialTheme.typography.titleMedium)
            }

            if (s.allHoldings.isEmpty()) {
                item {
                    Text(
                        if (s.group.isEtfGroup) {
                            "No ETFs to add yet — add one on the Portfolio tab first, " +
                                "then tick it here."
                        } else {
                            "No individual stocks to add yet — add one on the Portfolio " +
                                "tab first, then tick it here."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(s.allHoldings, key = { it.ticker }) { holding ->
                    ListItem(
                        headlineContent = { Text(holding.displayLabel()) },
                        supportingContent = { Text(holding.companyName) },
                        trailingContent = {
                            Checkbox(
                                checked = holding.ticker in s.memberTickers,
                                onCheckedChange = { viewModel.toggleTicker(holding.ticker) },
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete group?") },
            text = { Text("Holdings stay in your portfolio; only this bucket is removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteGroup(onBack)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GroupEditor(
    initialName: String,
    initialTarget: Double?,
    initialIsEtfGroup: Boolean,
    onSave: (String, Double?, Boolean) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var target by remember(initialTarget) {
        mutableStateOf(initialTarget?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "")
    }
    var isEtfGroup by remember(initialIsEtfGroup) { mutableStateOf(initialIsEtfGroup) }
    SectionCard(spacing = 8.dp) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Group name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = target,
            onValueChange = { input -> target = input.filter { it.isDigit() || it == '.' } },
            label = { Text("Target allocation % (blank = untracked)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "This group tracks",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GroupTypeToggle(isEtfGroup = isEtfGroup, onChange = { isEtfGroup = it })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onSave(name, target.toDoubleOrNull(), isEtfGroup) }) {
                Text("Save group")
            }
        }
    }
}
