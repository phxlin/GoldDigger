package com.golddigger.app.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.golddigger.app.domain.model.GroupAllocation
import com.golddigger.app.ui.common.asCurrency
import com.golddigger.app.ui.common.asPlainPercent
import com.golddigger.app.ui.components.ChartLegend
import com.golddigger.app.ui.components.ChartSlice
import com.golddigger.app.ui.components.EmptyState
import com.golddigger.app.ui.components.PieChart
import com.golddigger.app.ui.components.SectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    onGroupClick: (Long) -> Unit,
    viewModel: GroupsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Groups") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New group")
            }
        },
    ) { padding ->
        when {
            state.loading -> Unit
            state.isEmpty -> EmptyState(
                icon = Icons.Filled.Category,
                title = "No groups yet",
                body = "Groups are buckets you define — \"AI/Semis\", \"Big Tech\", " +
                    "\"Cash equivalents\". Set a target allocation and GoldDigger shows " +
                    "how far each bucket is from it.",
                actionLabel = "Create a group",
                onAction = { showCreate = true },
                modifier = Modifier.padding(padding),
            )

            else -> {
                val pagerState = rememberPagerState(pageCount = { GroupsTab.entries.size })
                val scope = rememberCoroutineScope()
                Column(Modifier.padding(padding).fillMaxSize()) {
                    GroupsTabRow(
                        selected = GroupsTab.entries[pagerState.currentPage],
                        onSelect = { tab -> scope.launch { pagerState.animateScrollToPage(tab.ordinal) } },
                    )
                    // Swiping left/right here switches Individual Stocks <-> ETFs,
                    // same as tapping a tab.
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) { page ->
                        val tab = GroupsTab.entries[page]
                        val groups = if (tab == GroupsTab.STOCKS) state.stockGroups else state.etfGroups
                        val slices = if (tab == GroupsTab.STOCKS) state.stockSlices else state.etfSlices
                        if (groups.isEmpty()) {
                            Text(
                                if (tab == GroupsTab.ETFS) {
                                    "No ETF groups yet."
                                } else {
                                    "No individual-stock groups yet."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (slices.isNotEmpty()) {
                                    item(key = "chart") {
                                        SectionCard {
                                            Text(
                                                if (tab == GroupsTab.ETFS) {
                                                    "ETF groups by value"
                                                } else {
                                                    "Individual-stock groups by value"
                                                },
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            PieChart(
                                                slices = slices,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(190.dp),
                                                centerContent = { focused ->
                                                    GroupsPieCenterLabel(focused, slices)
                                                },
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            ChartLegend(
                                                slices = slices,
                                                trailing = { slice ->
                                                    val total = slices.sumOf { it.value }
                                                    if (total > 0) {
                                                        (slice.value / total * 100).asPlainPercent()
                                                    } else {
                                                        ""
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                                items(groups, key = { it.groupId }) { allocation ->
                                    GroupCard(allocation) { onGroupClick(allocation.groupId) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateGroupDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, target, isEtfGroup ->
                viewModel.createGroup(name, target, isEtfGroup)
                showCreate = false
            },
        )
    }
}

@Composable
private fun GroupsTabRow(
    selected: GroupsTab,
    onSelect: (GroupsTab) -> Unit,
) {
    TabRow(selectedTabIndex = selected.ordinal) {
        Tab(
            selected = selected == GroupsTab.STOCKS,
            onClick = { onSelect(GroupsTab.STOCKS) },
            text = { Text("Individual Stocks") },
        )
        Tab(
            selected = selected == GroupsTab.ETFS,
            onClick = { onSelect(GroupsTab.ETFS) },
            text = { Text("ETFs") },
        )
    }
}

@Composable
private fun GroupsPieCenterLabel(focused: ChartSlice?, slices: List<ChartSlice>) {
    val total = slices.sumOf { it.value }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (focused == null) {
            Text(total.asCurrency(), style = MaterialTheme.typography.titleMedium)
            Text(
                "in groups",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(focused.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(focused.value.asCurrency(), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (total > 0) (focused.value / total * 100).asPlainPercent() else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupCard(allocation: GroupAllocation, onClick: () -> Unit) {
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(allocation.name, style = MaterialTheme.typography.titleMedium)
            Text(allocation.currentValue.asCurrency(), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${tickerPreview(allocation.tickers)} · ${allocation.currentPct.asPlainPercent()} of " +
                if (allocation.isEtfGroup) "your ETFs" else "your individual stocks",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val target = allocation.targetPct
        if (target != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (allocation.currentPct / target).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            Spacer(Modifier.height(4.dp))
            val amount = allocation.amountToTarget
            Text(
                text = when {
                    amount == null -> "Target ${target.asPlainPercent()}"
                    amount > 1.0 -> {
                        val underPct = target - allocation.currentPct
                        "Add ${amount.asCurrency()} (${underPct.asPlainPercent()}) to reach " +
                            target.asPlainPercent()
                    }
                    amount < -1.0 -> {
                        val overPct = allocation.currentPct - target
                        "Over target (${target.asPlainPercent()}) by ${(-amount).asCurrency()} " +
                            "(${overPct.asPlainPercent()})"
                    }
                    else -> "On target (${target.asPlainPercent()})"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * A one-line, space-conscious stand-in for "N holding(s)": the tickers
 * themselves when there are few enough to fit, otherwise the first few plus
 * a "+N more" tail — still a single glance, but showing what's actually in
 * the bucket instead of just how many things are.
 */
private fun tickerPreview(tickers: List<String>, maxShown: Int = 4): String = when {
    tickers.isEmpty() -> "No holdings"
    tickers.size <= maxShown -> tickers.joinToString(", ")
    else -> tickers.take(maxShown).joinToString(", ") + " +${tickers.size - maxShown} more"
}

@Composable
private fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Double?, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var isEtfGroup by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = { input ->
                        target = input.filter { it.isDigit() || it == '.' }
                    },
                    label = { Text("Target allocation % (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "This group tracks",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                GroupTypeToggle(isEtfGroup = isEtfGroup, onChange = { isEtfGroup = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), target.toDoubleOrNull(), isEtfGroup) },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Which universe a group's allocation % is a share of — total individual-stock
 * value or total ETF value — so an ETF-type group and a stocks-type group are
 * each measured against their own 100%, not the whole portfolio.
 */
@Composable
internal fun GroupTypeToggle(
    isEtfGroup: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilterChip(
            selected = !isEtfGroup,
            onClick = { onChange(false) },
            label = { Text("Individual stocks") },
        )
        androidx.compose.material3.FilterChip(
            selected = isEtfGroup,
            onClick = { onChange(true) },
            label = { Text("ETFs") },
        )
    }
}
