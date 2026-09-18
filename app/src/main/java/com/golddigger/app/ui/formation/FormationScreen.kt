package com.golddigger.app.ui.formation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.golddigger.app.core.FormationConfig
import com.golddigger.app.domain.model.Formation
import com.golddigger.app.domain.model.FormationInsight
import com.golddigger.app.domain.model.FormationRole
import com.golddigger.app.domain.model.FormationZone
import com.golddigger.app.domain.model.InsightSeverity
import com.golddigger.app.domain.model.PlayerCard
import com.golddigger.app.ui.common.asCompactCurrency
import com.golddigger.app.ui.common.asPlainPercent
import com.golddigger.app.ui.components.EmptyState
import com.golddigger.app.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormationScreen(
    onHoldingClick: (Long) -> Unit,
    viewModel: FormationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var roleTarget by remember { mutableStateOf<PlayerCard?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Formation") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.isEmpty -> EmptyState(
                icon = Icons.Filled.SportsSoccer,
                title = "Build your lineup",
                body = "Add holdings and GoldDigger arranges them on a pitch by risk role — " +
                    "keeper (cash), defenders (low beta), midfield, and attack (high beta or " +
                    "sector-correlated) — so you can see your portfolio's shape at a glance.",
                modifier = Modifier.padding(padding),
            )

            else -> {
                val formation = state.formation ?: return@Scaffold
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
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
                        PitchDiagram(
                            formation = formation,
                            onPlayer = { onHoldingClick(it.holdingId) },
                            onPlayerLong = { roleTarget = it },
                        )

                        if (formation.insights.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Where the gaps are",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                formation.insights.forEach { InsightCard(it) }
                            }
                        }

                        if (formation.bench.isNotEmpty()) {
                            BenchSection(
                                bench = formation.bench,
                                onPlayer = { onHoldingClick(it.holdingId) },
                                onPlayerLong = { roleTarget = it },
                            )
                        }

                        FormationLegend()
                    }
                }
            }
        }
    }

    roleTarget?.let { card ->
        RoleOverrideDialog(
            card = card,
            onDismiss = { roleTarget = null },
            onSelect = { role ->
                viewModel.setRole(card.holdingId, role)
                roleTarget = null
            },
        )
    }
}

// --- Pitch -------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PitchDiagram(
    formation: Formation,
    onPlayer: (PlayerCard) -> Unit,
    onPlayerLong: (PlayerCard) -> Unit,
) {
    val lineColor = Color.White.copy(alpha = 0.38f)
    val faintLine = Color.White.copy(alpha = 0.20f)
    val grassTop = Color(0xFF2F7D46)
    val grassBottom = Color(0xFF276B3C)

    // Proportional-but-legible zone heights: split a budget by dollar weight,
    // but never let a non-empty band get shorter than one chip row. This is a
    // *minimum* only — [ZoneBand] below grows past it (via heightIn, not a
    // fixed height) whenever a zone's actual chips need more room than its
    // dollar weight alone would suggest, e.g. right after a manual role
    // override moves an extra low-value holding into a zone. Without that
    // headroom, the extra row would be silently clipped away instead of shown.
    val pitchBudget = 460.dp
    val minZoneHeight = 112.dp
    val zoneHeights = formation.zones.map { z ->
        maxOf(minZoneHeight, pitchBudget * z.heightWeight)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
    ) {
        Canvas(Modifier.matchParentSize()) {
            // Grass with faint mowing stripes.
            drawRect(brush = Brush.verticalGradient(listOf(grassTop, grassBottom)))
            val stripe = size.height / 6f
            for (i in 0 until 6) {
                if (i % 2 == 0) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.03f),
                        topLeft = Offset(0f, i * stripe),
                        size = Size(size.width, stripe),
                    )
                }
            }
            val s = Stroke(width = 3f)
            // Outer touchline.
            drawRect(
                color = lineColor,
                topLeft = Offset(8f, 8f),
                size = Size(size.width - 16f, size.height - 16f),
                style = s,
            )
            // Halfway line + centre circle (kept faint so player chips stay legible).
            drawLine(faintLine, Offset(8f, size.height / 2f), Offset(size.width - 8f, size.height / 2f), strokeWidth = 2f)
            drawCircle(faintLine, radius = size.width * 0.13f, center = Offset(size.width / 2f, size.height / 2f), style = s)
            // Penalty boxes top (attack) and bottom (keeper).
            val boxW = size.width * 0.5f
            val boxH = size.height * 0.12f
            drawRect(lineColor, topLeft = Offset((size.width - boxW) / 2f, 8f), size = Size(boxW, boxH), style = s)
            drawRect(lineColor, topLeft = Offset((size.width - boxW) / 2f, size.height - 8f - boxH), size = Size(boxW, boxH), style = s)
        }

        Column(Modifier.fillMaxWidth()) {
            formation.zones.forEachIndexed { i, zone ->
                ZoneBand(
                    zone = zone,
                    // A minimum, not a fixed height: a zone whose chips need
                    // more vertical room than its dollar weight allocates
                    // (e.g. right after a role override adds a low-value
                    // holding) grows to fit instead of clipping it away.
                    minHeight = zoneHeights[i],
                    onPlayer = onPlayer,
                    onPlayerLong = onPlayerLong,
                )
            }
        }
    }
}

/** The zone label row's own rendered height — reserved so player chips can never be laid out over it. */
private val ZONE_HEADER_HEIGHT = 34.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneBand(
    zone: FormationZone,
    minHeight: Dp,
    onPlayer: (PlayerCard) -> Unit,
    onPlayerLong: (PlayerCard) -> Unit,
) {
    val accent = roleColor(zone.role)

    // Where the line of players sits in its third of the zone's own content
    // area (below the label) — attackers high near the opponent's goal, the
    // back line low, the keeper on the goal line.
    val lineAlignment = when (zone.role) {
        FormationRole.ATTACK -> Alignment.TopCenter
        FormationRole.MIDFIELD -> Alignment.Center
        FormationRole.DEFENSE, FormationRole.GOALKEEPER -> Alignment.BottomCenter
        FormationRole.BENCH -> Alignment.Center
    }
    val topInset = if (zone.role == FormationRole.ATTACK) 12.dp else 6.dp
    val bottomInset = if (
        zone.role == FormationRole.DEFENSE || zone.role == FormationRole.GOALKEEPER
    ) 16.dp else 6.dp

    // Keep a "line" to at most four across, like a real back four / front three;
    // every holding is shown — however many there are, extras just wrap to as
    // many further lines as needed (the zone grows to fit them; see [zoneHeights]).
    val perRow = zone.players.size.coerceIn(1, 4)

    // The label is a normal Column child (stacked above the content, never
    // overlapping it), so centering/bottom-aligning the player line below only
    // ever moves it within the space left over — it can never land back on
    // top of the label the way it could when both were free-floating in the
    // same Box and the zone was sized just tightly enough to fit its chips.
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .background(accent.copy(alpha = 0.14f)),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                roleLabel(zone.role).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                if (zone.players.isEmpty()) {
                    "empty"
                } else {
                    "${zone.valueSharePct.asPlainPercent()} · ${zone.valueSum.asCompactCurrency()}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = if (zone.players.isEmpty()) 0.7f else 0.85f),
            )
        }

        if (zone.players.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = (minHeight - ZONE_HEADER_HEIGHT).coerceAtLeast(0.dp)),
            ) {
                FlowRow(
                    modifier = Modifier
                        .align(lineAlignment)
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, top = topInset, bottom = bottomInset),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    maxItemsInEachRow = perRow,
                ) {
                    zone.players.forEach { card ->
                        PlayerChip(
                            card = card,
                            onClick = { onPlayer(card) },
                            onLongClick = { onPlayerLong(card) },
                            // Nudge the more-extreme holdings toward the end of the
                            // pitch their role points at: the hottest attacker sits
                            // highest, the lowest-beta defender drops deepest.
                            modifier = Modifier.offset(
                                y = playerNudge(card.intensity, zone.role, zone.players.size),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerChip(
    card: PlayerCard,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = roleColor(card.role)
    val fill = lerp(base.copy(alpha = 0.45f), base, card.intensity.toFloat())
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.label,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            if (card.isOverridden) {
                Spacer(Modifier.size(3.dp))
                Text("•", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
        Text(
            card.marketValue.asCompactCurrency(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}

// --- Bench, insights, legend ----------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BenchSection(
    bench: List<PlayerCard>,
    onPlayer: (PlayerCard) -> Unit,
    onPlayerLong: (PlayerCard) -> Unit,
) {
    SectionCard {
        Text("Bench", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            "Not enough price history or beta yet to place these — they'll take the " +
                "field once a few syncs have run.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            bench.forEach { card ->
                PlayerChip(card.copy(role = FormationRole.BENCH), { onPlayer(card) }, { onPlayerLong(card) })
            }
        }
    }
}

@Composable
private fun InsightCard(insight: FormationInsight) {
    val accent = when (insight.severity) {
        InsightSeverity.WARNING -> MaterialTheme.colorScheme.error
        InsightSeverity.INFO -> MaterialTheme.colorScheme.primary
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                insight.headline,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            insight.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FormationLegend() {
    SectionCard {
        Text("How roles are assigned", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        listOf(
            FormationRole.GOALKEEPER to "Cash and cash-equivalent holdings.",
            FormationRole.DEFENSE to "Beta below ${FormationConfig.DEFENSE_BETA_MAX}, not correlated to your dominant sector.",
            FormationRole.MIDFIELD to "Beta ${FormationConfig.DEFENSE_BETA_MAX}–${FormationConfig.MIDFIELD_BETA_MAX}, or moderately sector-correlated.",
            FormationRole.ATTACK to "Beta above ${FormationConfig.MIDFIELD_BETA_MAX}, highly sector-correlated, or volatile.",
        ).forEach { (role, text) ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(roleColor(role)),
                )
                Spacer(Modifier.size(8.dp))
                Text("${roleLabel(role)} — $text", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Zone height tracks the dollars in that zone. Tap a player to open it; " +
                "long-press to reassign its role. ETFs are placed by the same rules as " +
                "individual stocks — nothing here treats them differently.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Dialogs / sheets ----------------------------------------------------

@Composable
private fun RoleOverrideDialog(
    card: PlayerCard,
    onDismiss: () -> Unit,
    onSelect: (FormationRole?) -> Unit,
) {
    val options: List<Pair<FormationRole?, String>> = listOf(
        null to "Auto (${roleLabel(card.autoRole)})",
        FormationRole.ATTACK to roleLabel(FormationRole.ATTACK),
        FormationRole.MIDFIELD to roleLabel(FormationRole.MIDFIELD),
        FormationRole.DEFENSE to roleLabel(FormationRole.DEFENSE),
        FormationRole.GOALKEEPER to roleLabel(FormationRole.GOALKEEPER),
    )
    val current: FormationRole? = if (card.isOverridden) card.role else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${card.label} · role") },
        text = {
            Column {
                Text(
                    card.metricSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                options.forEach { (role, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(role) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = role == current, onClick = { onSelect(role) })
                        Spacer(Modifier.size(4.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

// --- helpers -------------------------------------------------------------

@Composable
private fun roleColor(role: FormationRole): Color = when (role) {
    FormationRole.ATTACK -> Color(0xFFC0392B)
    FormationRole.MIDFIELD -> Color(0xFFC9962B)
    FormationRole.DEFENSE -> Color(0xFF1F8A4C)
    FormationRole.GOALKEEPER -> Color(0xFF2F6FB0)
    FormationRole.BENCH -> MaterialTheme.colorScheme.outline
}

/**
 * Vertical shift for a player chip so the line slopes toward the end of the
 * pitch its role attacks: within a zone the hottest attacker rides highest and
 * the lowest-beta defender sits deepest. [intensity] is the chip's 0..1 rank
 * within its zone; a lone player in a zone is not nudged.
 */
private fun playerNudge(intensity: Double, role: FormationRole, playerCount: Int): Dp {
    if (playerCount < 2) return 0.dp
    val centered = ((intensity - 0.5) * 2.0).toFloat() // -1 (mild) .. +1 (extreme)
    val spread = 15.dp
    return when (role) {
        FormationRole.ATTACK -> spread * -centered
        FormationRole.MIDFIELD -> spread * (-centered * 0.5f)
        FormationRole.DEFENSE -> spread * centered
        FormationRole.GOALKEEPER, FormationRole.BENCH -> 0.dp
    }
}

private fun roleLabel(role: FormationRole): String = when (role) {
    FormationRole.ATTACK -> "Attack"
    FormationRole.MIDFIELD -> "Midfield"
    FormationRole.DEFENSE -> "Defense"
    FormationRole.GOALKEEPER -> "Goalkeeper"
    FormationRole.BENCH -> "Bench"
}

private fun PlayerCard.metricSummary(): String {
    val parts = buildList {
        beta?.let { add("β ${String.format(java.util.Locale.US, "%.2f", it)}") }
        sectorCorrelation?.let { add("sector corr ${String.format(java.util.Locale.US, "%.2f", it)}") }
        realizedVolatility?.let { add("vol ${String.format(java.util.Locale.US, "%.1f%%", it * 100)}") }
    }
    return if (parts.isEmpty()) "no risk data yet" else parts.joinToString(" · ")
}
