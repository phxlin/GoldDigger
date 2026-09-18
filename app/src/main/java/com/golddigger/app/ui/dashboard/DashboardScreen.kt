package com.golddigger.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.domain.model.PortfolioSummary
import com.golddigger.app.ui.common.asCurrency
import com.golddigger.app.ui.common.asPercent
import com.golddigger.app.ui.common.asPlainPercent
import com.golddigger.app.ui.common.asSignedCurrency
import com.golddigger.app.ui.common.asShares
import com.golddigger.app.ui.common.displayLabel
import com.golddigger.app.ui.common.isCash
import com.golddigger.app.ui.common.rememberDeviceDate
import com.golddigger.app.ui.common.relativeTime
import com.golddigger.app.ui.components.ChartLegend
import com.golddigger.app.ui.components.ChartSlice
import com.golddigger.app.ui.components.ColorDot
import com.golddigger.app.ui.components.DeltaChip
import com.golddigger.app.ui.components.EmptyState
import com.golddigger.app.ui.components.GoldDiggerWordmark
import com.golddigger.app.ui.components.PieChart
import com.golddigger.app.ui.components.SectionCard
import com.golddigger.app.ui.components.SyncStatusBar
import com.golddigger.app.ui.theme.PortfolioColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onHoldingClick: (Long) -> Unit,
    onAddHolding: () -> Unit,
    onImportPhoto: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceDate = rememberDeviceDate()
    var showAddCash by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { GoldDiggerWordmark() },
                actions = {
                    IconButton(onClick = { showAddCash = true }) {
                        Icon(Icons.Filled.AccountBalanceWallet, contentDescription = "Add cash")
                    }
                    IconButton(onClick = onImportPhoto) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = "Import from photo")
                    }
                    IconButton(onClick = onAddHolding) {
                        Icon(Icons.Filled.Add, contentDescription = "Add holding")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.isEmpty -> Column(Modifier.padding(padding).fillMaxSize()) {
                DateHeader(deviceDate, Modifier.padding(start = 16.dp, top = 16.dp))
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    title = "Track your first holding",
                    body = "GoldDigger starts empty. Add a ticker (or tap the wallet to add " +
                        "cash), and your portfolio builds from there.",
                    actionLabel = "Add a holding",
                    onAction = onAddHolding,
                    secondaryActionLabel = "Or import from a photo",
                    onSecondaryAction = onImportPhoto,
                )
            }

            else -> {
                val summary = state.summary ?: return@Scaffold
                val pagerState = rememberPagerState(pageCount = { PortfolioTab.entries.size })
                val scope = rememberCoroutineScope()
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.padding(padding).fillMaxSize(),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            DateHeader(deviceDate)
                            SyncStatusBar(state.syncState)
                            HeroCard(summary)
                        }
                        PortfolioTabRow(
                            selected = PortfolioTab.entries[pagerState.currentPage],
                            onSelect = { tab -> scope.launch { pagerState.animateScrollToPage(tab.ordinal) } },
                        )
                        // Swiping left/right here switches Individual Stocks <-> ETFs,
                        // same as tapping a tab; each page scrolls independently since a
                        // group's chart + full holdings list can be longer than the screen.
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) { page ->
                            val group = when (PortfolioTab.entries[page]) {
                                PortfolioTab.STOCKS -> state.stocks
                                PortfolioTab.ETFS -> state.etfs
                            }
                            // Collapsed by default: the chart + up-to-8-row legend used
                            // to always push the holdings list below the fold on a
                            // typical phone. Tapping the chart header expands it back.
                            var chartExpanded by rememberSaveable(page) { mutableStateOf(false) }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                portfolioGroupItems(
                                    group = group,
                                    sort = state.sort,
                                    onSort = viewModel::onSortSelected,
                                    onHoldingClick = onHoldingClick,
                                    chartExpanded = chartExpanded,
                                    onToggleChart = { chartExpanded = !chartExpanded },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddCash) {
        AddCashDialog(
            onDismiss = { showAddCash = false },
            onConfirm = {
                viewModel.addCash(it)
                showAddCash = false
            },
        )
    }
}

@Composable
private fun PortfolioTabRow(
    selected: PortfolioTab,
    onSelect: (PortfolioTab) -> Unit,
) {
    TabRow(selectedTabIndex = selected.ordinal) {
        Tab(
            selected = selected == PortfolioTab.STOCKS,
            onClick = { onSelect(PortfolioTab.STOCKS) },
            text = { Text("Individual Stocks") },
        )
        Tab(
            selected = selected == PortfolioTab.ETFS,
            onClick = { onSelect(PortfolioTab.ETFS) },
            text = { Text("ETFs") },
        )
    }
}

/**
 * The currently-selected tab's section: an allocation chart (its slices are
 * this group's own holdings, so the legend percentages sum to 100% *within
 * the group*, not the whole portfolio) followed by its holdings list. Shows
 * an empty-state message instead when the group has nothing in it yet (e.g.
 * no ETFs have been added/marked yet).
 */
private fun LazyListScope.portfolioGroupItems(
    group: PortfolioGroup,
    sort: HoldingSort,
    onSort: (HoldingSortKey) -> Unit,
    onHoldingClick: (Long) -> Unit,
    chartExpanded: Boolean,
    onToggleChart: () -> Unit,
) {
    if (group.holdings.isEmpty()) {
        item(key = "group-empty") {
            Text(
                if (group.label == "ETFs") {
                    "No ETFs yet — toggle \"This is an ETF\" when adding or importing a holding."
                } else {
                    "No individual stocks yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        return
    }
    val sliceColors = group.slices.associate { it.label to it.color }

    if (group.slices.isNotEmpty()) {
        item(key = "group-chart") {
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleChart),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${group.label} allocation",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Icon(
                        imageVector = if (chartExpanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (chartExpanded) "Collapse chart" else "Expand chart",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chartExpanded) {
                    Spacer(Modifier.height(8.dp))
                    PieChart(
                        slices = group.slices,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp),
                        centerContent = { focused ->
                            PieCenterLabel(focused, group.totalValue, group.slices)
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    ChartLegend(
                        slices = group.slices,
                        trailing = { slice ->
                            val total = group.slices.sumOf { it.value }
                            if (total > 0) (slice.value / total * 100).asPlainPercent() else ""
                        },
                    )
                }
            }
        }
    }
    item(key = "group-header") {
        Column(Modifier.padding(top = 4.dp)) {
            HoldingsHeader(sort = sort, onSort = onSort)
            HorizontalDivider()
        }
    }
    items(items = group.holdings, key = { it.holdingId }) { holding ->
        HoldingRow(
            holding = holding,
            accent = sliceColors[holding.displayLabel()],
            onClick = { onHoldingClick(holding.holdingId) },
        )
        HorizontalDivider()
    }
}

@Composable
private fun LoadingBox(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { CircularProgressIndicator() }
}

@Composable
private fun DateHeader(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Event,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HeroCard(summary: PortfolioSummary) {
    SectionCard(padding = 20.dp) {
        Text(
            "Total value",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            summary.totalMarketValue.asCurrency(),
            style = MaterialTheme.typography.displayMedium,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeltaChip(
                text = "${summary.totalGainLoss.asSignedCurrency()} · ${summary.totalGainLossPct.asPercent()}",
                positive = summary.totalGainLoss >= 0,
            )
            DeltaChip(
                text = "${summary.dayChangeValue.asSignedCurrency()} today",
                positive = summary.dayChangeValue >= 0,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Cost basis ${summary.totalCostBasis.asCurrency()}" +
                if (summary.hasUnpricedHoldings) {
                    " · ${summary.holdingCount - summary.pricedHoldingCount} awaiting price"
                } else {
                    ""
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HoldingRow(
    holding: HoldingValuation,
    accent: Color?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(
            color = accent ?: MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                holding.displayLabel(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Text(
                if (holding.isCash) {
                    "Cash balance"
                } else {
                    "${holding.shares.asShares()} sh · avg ${holding.avgCost.asCurrency()}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        // Live per-share price + today's move.
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.width(PRICE_COL_WIDTH).padding(end = 8.dp),
        ) {
            if (!holding.isCash) {
                Text(
                    holding.price?.asCurrency() ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Text(
                    text = holding.dayChangePct?.asPercent()
                        ?: relativeTime(holding.priceUpdatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = holding.dayChangePct?.let {
                        if (it >= 0) PortfolioColors.gain else PortfolioColors.loss
                    } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.width(VALUE_COL_WIDTH),
        ) {
            Text(
                holding.marketValue?.asCurrency() ?: "—",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            val gl = holding.gainLossPct
            if (!holding.isCash && gl != null) {
                Text(
                    gl.asPercent(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = if (gl >= 0) PortfolioColors.gain else PortfolioColors.loss,
                )
            }
        }
    }
}

private val PRICE_COL_WIDTH = 92.dp
private val VALUE_COL_WIDTH = 108.dp

@Composable
private fun HoldingsHeader(
    sort: HoldingSort,
    onSort: (HoldingSortKey) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Align with the row's leading colour dot (8dp dot + 10dp gap).
        Spacer(Modifier.width(18.dp))
        SortLabel(
            text = "Symbol",
            key = HoldingSortKey.SYMBOL,
            sort = sort,
            onSort = onSort,
            modifier = Modifier.weight(1f),
            alignEnd = false,
        )
        SortLabel(
            text = "Price",
            key = HoldingSortKey.PRICE,
            sort = sort,
            onSort = onSort,
            modifier = Modifier.width(PRICE_COL_WIDTH).padding(end = 8.dp),
            alignEnd = true,
        )
        SortLabel(
            text = "Value",
            key = HoldingSortKey.VALUE,
            sort = sort,
            onSort = onSort,
            modifier = Modifier.width(VALUE_COL_WIDTH),
            alignEnd = true,
        )
    }
}

@Composable
private fun SortLabel(
    text: String,
    key: HoldingSortKey,
    sort: HoldingSort,
    onSort: (HoldingSortKey) -> Unit,
    modifier: Modifier,
    alignEnd: Boolean,
) {
    val active = sort.key == key
    Row(
        modifier = modifier.clickable { onSort(key) },
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (active) {
            Icon(
                imageVector = if (sort.ascending) Icons.Filled.KeyboardArrowUp
                else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (sort.ascending) "ascending" else "descending",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun PieCenterLabel(
    focused: ChartSlice?,
    totalValue: Double,
    slices: List<ChartSlice>,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (focused == null) {
            Text(totalValue.asCurrency(), style = MaterialTheme.typography.titleMedium)
            Text(
                "total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val sum = slices.sumOf { it.value }
            Text(focused.label, style = MaterialTheme.typography.titleMedium)
            Text(focused.value.asCurrency(), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (sum > 0) (focused.value / sum * 100).asPlainPercent() else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddCashDialog(
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    val parsed = amount.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add cash") },
        text = {
            Column {
                Text(
                    "Adds to your cash position. It shows on the dashboard and in the " +
                        "pie chart, and can be tagged into groups like \"Cash equivalents\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { input ->
                        amount = input.filterIndexed { i, c ->
                            c.isDigit() || (c == '.' && !input.substring(0, i).contains('.'))
                        }
                    },
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null && parsed > 0.0,
                onClick = { parsed?.let(onConfirm) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
