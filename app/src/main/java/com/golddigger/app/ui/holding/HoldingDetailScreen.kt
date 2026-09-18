package com.golddigger.app.ui.holding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import com.golddigger.app.data.remote.NewsArticle
import com.golddigger.app.domain.model.PriceRange
import com.golddigger.app.domain.model.PricePoint
import com.golddigger.app.ui.components.DeltaChip
import com.golddigger.app.ui.components.SectionCard
import com.golddigger.app.ui.common.asChartTimestamp
import com.golddigger.app.ui.common.asCurrency
import com.golddigger.app.ui.common.asPercent
import com.golddigger.app.ui.common.asPlainPercent
import com.golddigger.app.ui.common.asShares
import com.golddigger.app.ui.common.asSignedCurrency
import com.golddigger.app.ui.common.displayLabel
import com.golddigger.app.ui.common.isCash
import com.golddigger.app.ui.common.relativeTime
import com.golddigger.app.ui.components.PriceHistoryChart
import com.golddigger.app.ui.theme.PortfolioColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HoldingDetailScreen(
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: HoldingDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    val title = (state as? HoldingDetailUiState.Loaded)?.holding?.displayLabel() ?: "Holding"

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
                    (state as? HoldingDetailUiState.Loaded)?.let { loaded ->
                        if (!loaded.holding.isCash) {
                            IconButton(onClick = onEdit) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit")
                            }
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            HoldingDetailUiState.Loading -> Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            HoldingDetailUiState.Missing ->
                Text(
                    "This holding no longer exists.",
                    modifier = Modifier.padding(padding).padding(24.dp),
                )

            is HoldingDetailUiState.Loaded -> {
                val h = s.holding
                PullToRefreshBox(
                    isRefreshing = s.isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.padding(padding).fillMaxSize(),
                ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            h.companyName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when {
                                h.isCash -> (h.marketValue ?: h.costBasis).asCurrency()
                                h.price != null -> h.price.asCurrency()
                                else -> "No price yet"
                            },
                            style = MaterialTheme.typography.displaySmall,
                        )
                        if (!h.isCash) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                h.dayChangePct?.let {
                                    DeltaChip("${it.asPercent()} today", positive = it >= 0)
                                }
                                Text(
                                    "per share · ${relativeTime(h.priceUpdatedAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (!h.isCash) {
                        SectionCard {
                            // Reset whenever a new range's data comes in (new list
                            // instance), so a lingering scrub from the previous
                            // range's chart doesn't survive the swap.
                            var scrubbedPoint by remember(s.priceHistory) {
                                mutableStateOf<PricePoint?>(null)
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Price history", style = MaterialTheme.typography.labelMedium)
                                val scrubbed = scrubbedPoint
                                if (scrubbed != null) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            scrubbed.price.asCurrency(),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            scrubbed.timestamp.asChartTimestamp(s.selectedRange),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    val change = s.rangeChange
                                    val changePct = s.rangeChangePct
                                    if (change != null && changePct != null) {
                                        DeltaChip(
                                            text = "${change.asSignedCurrency()} (${changePct.asPercent()})",
                                            positive = change >= 0,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            if (s.priceHistory.size >= 2) {
                                PriceHistoryChart(
                                    points = s.priceHistory,
                                    modifier = Modifier.fillMaxWidth().height(120.dp),
                                    onScrub = { scrubbedPoint = it },
                                )
                            } else {
                                Box(
                                    Modifier.fillMaxWidth().height(120.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "Not enough price history yet for this range.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            PriceRangeSelector(
                                selected = s.selectedRange,
                                onSelect = viewModel::onRangeSelected,
                            )
                        }
                    }

                    SectionCard(spacing = 10.dp) {
                        if (h.isCash) {
                            StatRow("Balance", (h.marketValue ?: h.costBasis).asCurrency())
                            StatRow("% of portfolio", h.portfolioWeightPct.asPlainPercent())
                        } else {
                            StatRow("Shares", h.shares.asShares())
                            StatRow("Average cost", h.avgCost.asCurrency())
                            StatRow("Cost basis", h.costBasis.asCurrency())
                            StatRow("Market value", h.marketValue?.asCurrency() ?: "—")
                            StatRow(
                                "Gain / loss",
                                h.gainLoss?.let {
                                    "${it.asSignedCurrency()} (${(h.gainLossPct ?: 0.0).asPercent()})"
                                } ?: "—",
                                valueColor = h.gainLoss?.let {
                                    if (it >= 0) PortfolioColors.gain else PortfolioColors.loss
                                },
                            )
                            StatRow("% of portfolio", h.portfolioWeightPct.asPlainPercent())
                        }
                    }

                    Column {
                        Text("Groups", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        if (s.allGroups.isEmpty()) {
                            Text(
                                "No groups yet — create one on the Groups tab to bucket this holding.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                s.allGroups.forEach { group ->
                                    FilterChip(
                                        selected = group.id in s.memberGroupIds,
                                        onClick = { viewModel.toggleGroup(h.ticker, group.id) },
                                        label = { Text(group.name) },
                                    )
                                }
                            }
                        }
                    }

                    if (!h.isCash) {
                        NewsSection(
                            articles = s.news,
                            loading = s.newsLoading,
                            onRetry = viewModel::refreshNews,
                        )
                    }
                }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete holding?") },
            text = { Text("This removes the position from your portfolio. Groups are unaffected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onDeleted)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/** How far (px) a drag must travel before it moves the selection by one range. */
private val SWIPE_THRESHOLD = 48.dp

@Composable
private fun PriceRangeSelector(
    selected: PriceRange,
    onSelect: (PriceRange) -> Unit,
) {
    // Read via rememberUpdatedState (not captured directly) so the gesture
    // detector below — kept alive across recompositions by the Unit key —
    // always acts on the latest selection instead of whatever it was when
    // the pointerInput block first launched.
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val thresholdPx = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }

    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        dragged += delta
                    },
                    onDragEnd = {
                        val entries = PriceRange.entries
                        val ordinal = currentSelected.ordinal
                        when {
                            // Swipe left: same direction as the app's other
                            // swipeable tabs — move to the next (wider) range.
                            dragged <= -thresholdPx && ordinal < entries.lastIndex ->
                                currentOnSelect(entries[ordinal + 1])
                            dragged >= thresholdPx && ordinal > 0 ->
                                currentOnSelect(entries[ordinal - 1])
                        }
                    },
                )
            },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PriceRange.entries.forEach { range ->
            val isSelected = range == selected
            Text(
                text = range.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clickable { onSelect(range) }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun NewsSection(
    articles: List<NewsArticle>,
    loading: Boolean,
    onRetry: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Recent news", style = MaterialTheme.typography.titleMedium)
            if (loading && articles.isNotEmpty()) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            articles.isNotEmpty() -> SectionCard(padding = 0.dp) {
                articles.forEachIndexed { index, article ->
                    NewsRow(article)
                    if (index != articles.lastIndex) HorizontalDivider()
                }
            }

            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Loading news…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "No recent news.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun NewsRow(article: NewsArticle) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // article.url comes from the news provider's response, not
                // from this app — restrict to http(s) before handing it to
                // an Intent so a non-web scheme (intent://, content://, a
                // deep link) can't launch an unintended activity.
                val uri = Uri.parse(article.url)
                if (uri.scheme == "http" || uri.scheme == "https") {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (article.imageUrl != null) {
            AsyncImage(
                model = article.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        Column(Modifier.padding(start = if (article.imageUrl != null) 12.dp else 0.dp)) {
            Text(
                article.headline,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${article.source} · ${relativeTime(article.publishedAtEpochMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
