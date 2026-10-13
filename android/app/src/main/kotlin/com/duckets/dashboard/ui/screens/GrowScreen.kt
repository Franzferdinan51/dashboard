package com.duckets.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.duckets.dashboard.data.CannaAiData
import com.duckets.dashboard.data.GrowProvider
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.CardDivider
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.SkeletonCard
import com.duckets.dashboard.ui.components.StatusChip
import com.duckets.dashboard.ui.fmtRelativeTime
import com.duckets.dashboard.vm.DashboardUiState
import java.util.Locale

/**
 * The Grow tab: CannaAI in depth — the Overview card is the glance,
 * this is the full picture (GET /api/cannaai, bridge 1.12+ for history
 * and the provider list). Sensors, alerts, and the plant-analysis
 * history the tent-photo pipeline posts. Empty sections say so
 * honestly instead of vanishing: no readings means no sensors are
 * configured, not that the tab is broken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrowScreen(state: DashboardUiState, columns: Int = 1, onRefresh: () -> Unit = {}, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState()) {
    val hPad = if (columns >= 2) 24.dp else 16.dp
    val maxW = if (columns >= 2) 1080.dp else 720.dp
    PullToRefreshBox(isRefreshing = state.isPullRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxSize().widthIn(max = maxW).padding(horizontal = hPad),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val g = state.cannaai
                if (g == null) {
                    item { SkeletonCard(lines = 3) }
                    item {
                        MuseCard(Modifier.fillMaxWidth()) {
                            Text("Waiting for the first CannaAI sample from the bridge…",
                                Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                        }
                    }
                } else if (!g.reachable) {
                    item { GrowUnreachableCard() }
                } else {
                    item { GrowStatusCard(g) }
                    item { GrowSensorsCard(g) }
                    item { GrowAlertsCard(g) }
                    item { GrowHistoryCard(g) }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun GrowUnreachableCard() {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Grow — CannaAI") {
                StatusChip("UNREACHABLE", Muse.Bad)
            }
            Spacer(Modifier.height(10.dp))
            Text("CannaAI isn't answering the bridge — its backend is down or still starting. Nothing is shown rather than stale guesses.",
                style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
        }
    }
}

private fun cannaStatusColor(status: String?): Color = when (status?.lowercase(Locale.US)) {
    "degraded" -> Muse.Warn
    "healthy", "ok" -> Muse.Good
    else -> Muse.Good
}

private fun providerStatusColor(status: String?): Color = when (status?.lowercase(Locale.US)) {
    "available", "healthy", "ok", "up" -> Muse.Good
    "degraded", "partial" -> Muse.Warn
    "down", "unavailable", "error", "offline" -> Muse.Bad
    else -> Muse.Sky
}

@Composable
private fun GrowStatusCard(g: CannaAiData) {
    MuseCard(Modifier.fillMaxWidth(), hero = true) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Grow — CannaAI", subtitle = "grow room, live from CannaAI") {
                StatusChip((g.status ?: "UP").uppercase(Locale.US), cannaStatusColor(g.status), pulse = true)
            }
            if (g.providers.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                g.providers.forEachIndexed { i, p ->
                    if (i > 0) Spacer(Modifier.height(4.dp))
                    ProviderRow(p)
                }
            } else if (g.providersUpCount != null) {
                Spacer(Modifier.height(10.dp))
                KeyValueRow("AI providers", "${g.providersUpCount} up")
            }
        }
    }
}

@Composable
private fun ProviderRow(p: GrowProvider) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.name, style = MaterialTheme.typography.bodyMedium, color = Muse.Mist,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        if (p.status != null) {
            StatusChip(p.status.uppercase(Locale.US), providerStatusColor(p.status))
        } else {
            Text("—", style = MaterialTheme.typography.bodyMedium, color = Muse.TextFaint)
        }
    }
}

@Composable
private fun GrowSensorsCard(g: CannaAiData) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Sensors", subtitle = "latest readings CannaAI holds")
            Spacer(Modifier.height(10.dp))
            if (g.sensors.isEmpty()) {
                Text("No sensor readings yet — CannaAI has no sensors configured.",
                    style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            } else {
                g.sensors.forEach { s ->
                    KeyValueRow(s.label, s.value + (s.unit?.let { " $it" } ?: ""))
                }
            }
        }
    }
}

private fun alertColor(level: String?): Color = when (level?.lowercase(Locale.US)) {
    "critical", "error", "high" -> Muse.Bad
    "warning", "warn", "medium" -> Muse.Warn
    else -> Muse.Sky
}

@Composable
private fun GrowAlertsCard(g: CannaAiData) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Alerts", subtitle = "active in CannaAI right now")
            Spacer(Modifier.height(10.dp))
            if (g.alerts.isEmpty()) {
                Text("No active alerts.",
                    style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            } else {
                g.alerts.forEachIndexed { i, a ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.padding(top = 5.dp).size(8.dp)
                                .clip(CircleShape).background(alertColor(a.level))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(a.text, style = MaterialTheme.typography.bodyMedium, color = Muse.Mist,
                            modifier = Modifier.weight(1f))
                        if (a.atEpochSec != null) {
                            Spacer(Modifier.width(10.dp))
                            Text(fmtRelativeTime(a.atEpochSec),
                                style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint)
                        }
                    }
                }
            }
        }
    }
}

/** "85%" / "85.5%" — bridge passes CannaAI's confidence through raw. */
private fun fmtConfidence(v: Double?): String? {
    if (v == null || !v.isFinite()) return null
    return if (v == v.toLong().toDouble()) "${v.toLong()}% confidence"
    else String.format(Locale.US, "%.1f%% confidence", v)
}

/** "health 75" — health_score rides the same raw pass-through. */
private fun fmtHealthScore(v: Double?): String? {
    if (v == null || !v.isFinite()) return null
    return if (v == v.toLong().toDouble()) "health ${v.toLong()}"
    else String.format(Locale.US, "health %.1f", v)
}

@Composable
private fun GrowHistoryCard(g: CannaAiData) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Analysis history", subtitle = "plant photos, diagnosed by CannaAI")
            Spacer(Modifier.height(10.dp))
            if (g.history.isEmpty()) {
                Text("No analyses yet — the tent-photo pipeline posts here.",
                    style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            } else {
                g.history.forEachIndexed { i, e ->
                    if (i > 0) CardDivider()
                    Text(e.text ?: "Analysis",
                        style = MaterialTheme.typography.titleMedium, color = Muse.Mist)
                    val subline = listOfNotNull(
                        e.strain?.takeIf { it.isNotBlank() },
                        fmtConfidence(e.confidence),
                        fmtHealthScore(e.healthScore),
                    ).joinToString(" · ")
                    if (subline.isNotEmpty()) {
                        Text(subline, style = MaterialTheme.typography.bodySmall, color = Muse.TextDim)
                    }
                    if (e.atEpochSec != null) {
                        Text("${fmtClock(e.atEpochSec)} · ${fmtRelativeTime(e.atEpochSec)}",
                            style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint)
                    }
                    // When CannaAI had no diagnosis, the bridge falls
                    // back to the notes as the headline — don't print
                    // the same text twice.
                    val notes = e.notes?.takeIf { it.isNotBlank() && it != e.text }
                    if (notes != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(notes, style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                    }
                }
            }
        }
    }
}
