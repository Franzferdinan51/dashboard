package com.duckets.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.duckets.dashboard.data.ActionsData
import com.duckets.dashboard.data.LmModel
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.SkeletonCard
import com.duckets.dashboard.ui.components.StatusChip
import com.duckets.dashboard.ui.fmtBytes
import com.duckets.dashboard.ui.fmtCount
import com.duckets.dashboard.ui.fmtRelativeTime
import com.duckets.dashboard.ui.shortModel
import com.duckets.dashboard.vm.ActionsUiState
import com.duckets.dashboard.vm.DashboardUiState
import com.duckets.dashboard.vm.DashboardViewModel
import com.duckets.dashboard.vm.InFlightAction

/** A user intent waiting on its confirm dialog. Nothing fires until the
 * dialog's confirm button turns one of these into a VM call. */
private data class PendingAction(
    val action: String,
    val target: String? = null,
    val title: String,
    val body: String,
    val confirmLabel: String,
)

/**
 * The control surface: LM Studio model load/unload (via the bridge's lms
 * CLI), Darkbloom restart, the Batman-3 miner, and the bridge itself.
 * State comes from GET /api/actions, fetched when the tab opens and
 * after every action — never on the 5s stats poll. Every button goes
 * through a confirm dialog first; this tab is the one place the app
 * stops being read-only, so it should feel deliberate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsScreen(state: DashboardUiState, vm: DashboardViewModel, columns: Int = 1, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState()) {
    val actions = state.actions
    val hPad = if (columns >= 2) 24.dp else 16.dp
    val maxW = if (columns >= 2) 1080.dp else 720.dp
    var pending by remember { mutableStateOf<PendingAction?>(null) }

    PullToRefreshBox(isRefreshing = actions.isLoading, onRefresh = { vm.refreshActions() }, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxSize().widthIn(max = maxW).padding(horizontal = hPad),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val data = actions.data
                val loadError = actions.loadError
                when {
                    data != null -> {
                        item {
                            LmStudioCard(
                                data, actions,
                                lmModels = state.overview?.lmstudio?.models.orEmpty(),
                                onAction = { pending = it },
                            )
                        }
                        item { DarkbloomCard(data, actions, onAction = { pending = it }) }
                        item { MiningActionsCard(data, actions, onAction = { pending = it }) }
                        item { BridgeCard(data, actions, onAction = { pending = it }) }
                        if (data.history.isNotEmpty()) {
                            item { ActionsHistoryCard(data.history) }
                        }
                    }
                    loadError != null -> item {
                        MuseCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) {
                                CardHeader("Actions")
                                Spacer(Modifier.height(8.dp))
                                Text(loadError, style = MaterialTheme.typography.bodyMedium, color = Muse.Bad)
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton(onClick = { vm.refreshActions() }, shape = Muse.CardShape) {
                                    Text("Retry", color = Muse.BlueSoft)
                                }
                            }
                        }
                    }
                    else -> {
                        item { SkeletonCard(lines = 3) }
                        item {
                            MuseCard(Modifier.fillMaxWidth()) {
                                Text("Waiting for actions data from the bridge…",
                                    Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(p.title) },
            text = { Text(p.body) },
            confirmButton = {
                Button(
                    onClick = { vm.performAction(p.action, p.target); pending = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Muse.Blue),
                    shape = Muse.CardShape,
                ) { Text(p.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel", color = Muse.TextDim) }
            },
            containerColor = Muse.Card,
            titleContentColor = Muse.Mist,
            textContentColor = Muse.TextDim,
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    busyLabel: String,
    busy: Boolean,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val content: @Composable RowScope.() -> Unit = {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(16.dp),
                color = if (filled) Muse.Mist else Muse.BlueSoft,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(busyLabel)
        } else {
            Text(label)
        }
    }
    if (filled) {
        Button(
            onClick = onClick, enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = Muse.Blue),
            shape = Muse.CardShape, content = content,
        )
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, shape = Muse.CardShape, content = content)
    }
}

/** The bridge's last word on a section, inline until the next refresh
 * or action replaces it. */
@Composable
private fun SectionResult(actions: ActionsUiState, section: String) {
    val result = actions.results[section] ?: return
    Spacer(Modifier.height(10.dp))
    Text(
        result.message,
        style = MaterialTheme.typography.bodySmall,
        color = if (result.ok) Muse.Good else Muse.Bad,
    )
}

/** Detail line for an Actions row, joined from the /api/lmstudio
 * library entry: quant · size · context. Loaded models show the
 * context they're actually running at against their maximum — a model
 * loaded at a sliver of its context is a silent capability cut. */
private fun lmDetail(m: LmModel?, loadedNow: Boolean): String? {
    if (m == null) return null
    val ctx = when {
        loadedNow && m.loadedContext != null && m.maxContext != null ->
            "ctx ${fmtCount(m.loadedContext)} of ${fmtCount(m.maxContext)}"
        loadedNow && m.loadedContext != null -> "ctx ${fmtCount(m.loadedContext)}"
        m.maxContext != null -> "max ctx ${fmtCount(m.maxContext)}"
        else -> null
    }
    return listOfNotNull(m.quantization, m.sizeBytes?.let { fmtBytes(it) }, ctx)
        .joinToString(" · ").ifBlank { null }
}

private fun findLmModel(models: List<LmModel>, key: String): LmModel? =
    models.firstOrNull { it.id == key }
        ?: models.firstOrNull { shortModel(it.id) == shortModel(key) }

@Composable
private fun LmStudioCard(
    data: ActionsData,
    actions: ActionsUiState,
    lmModels: List<LmModel> = emptyList(),
    onAction: (PendingAction) -> Unit,
) {
    // Any action running locks every button on the tab (the VM enforces
    // one-at-a-time too); only the running button shows its spinner.
    val anyBusy = actions.inFlight != null
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("LM Studio", subtitle = "models on the Mac — a cold load can take minutes") {
                StatusChip(
                    "${data.lmLoaded.size} LOADED",
                    if (data.lmLoaded.isEmpty()) Muse.TextDim else Muse.Good,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("LOADED", style = MaterialTheme.typography.labelMedium, color = Muse.TextFaint)
            Spacer(Modifier.height(4.dp))
            if (data.lmLoaded.isEmpty()) {
                Text("No models loaded", style = MaterialTheme.typography.bodySmall, color = Muse.TextDim)
            } else {
                data.lmLoaded.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.identifier, style = MaterialTheme.typography.bodyMedium, color = Muse.Mist,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            lmDetail(findLmModel(lmModels, m.identifier), loadedNow = true)?.let { d ->
                                Text(d, style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        ActionButton(
                            label = "Unload", busyLabel = "Unloading…",
                            busy = actions.inFlight == InFlightAction("lm_unload", m.identifier),
                            enabled = !anyBusy, filled = false,
                            onClick = {
                                onAction(
                                    PendingAction(
                                        action = "lm_unload", target = m.identifier,
                                        title = "Unload ${m.identifier}?",
                                        body = "This frees the model's memory on the Mac. Anything using it — Hermes, local chat — loses access until it's loaded again.",
                                        confirmLabel = "Unload",
                                    )
                                )
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("LIBRARY", style = MaterialTheme.typography.labelMedium, color = Muse.TextFaint)
            Spacer(Modifier.height(4.dp))
            if (data.lmAvailable.isEmpty()) {
                Text("Library unavailable", style = MaterialTheme.typography.bodySmall, color = Muse.TextDim)
            } else {
                data.lmAvailable.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.key, style = MaterialTheme.typography.bodyMedium, color = Muse.Mist,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val detail = lmDetail(findLmModel(lmModels, m.key), loadedNow = false) ?: m.size
                            if (detail != null) {
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        ActionButton(
                            label = "Load", busyLabel = "Loading…",
                            busy = actions.inFlight == InFlightAction("lm_load", m.key),
                            enabled = !anyBusy, filled = true,
                            onClick = {
                                onAction(
                                    PendingAction(
                                        action = "lm_load", target = m.key,
                                        title = "Load ${m.key}?",
                                        body = "Loading a model can use a lot of memory on the Mac, and a cold load can take several minutes. The button stays busy until the bridge answers.",
                                        confirmLabel = "Load",
                                    )
                                )
                            },
                        )
                    }
                }
            }
            SectionResult(actions, "lmstudio")
        }
    }
}

@Composable
private fun DarkbloomCard(data: ActionsData, actions: ActionsUiState, onAction: (PendingAction) -> Unit) {
    val anyBusy = actions.inFlight != null
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Darkbloom", subtitle = "provider service on the Mac") {
                StatusChip(
                    if (data.darkbloomRunning) "RUNNING" else "NOT RUNNING",
                    if (data.darkbloomRunning) Muse.Good else Muse.Bad,
                )
            }
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Provider", if (data.darkbloomRunning) "Running" else "Not running")
            Spacer(Modifier.height(10.dp))
            ActionButton(
                label = "Restart Darkbloom", busyLabel = "Restarting…",
                busy = actions.inFlight == InFlightAction("darkbloom_restart"),
                enabled = !anyBusy, filled = true,
                onClick = {
                    onAction(
                        PendingAction(
                            action = "darkbloom_restart",
                            title = "Restart Darkbloom?",
                            body = "Serving interrupts briefly while the provider restarts; it comes back on its own. Earnings pause only for the restart itself.",
                            confirmLabel = "Restart",
                        )
                    )
                },
            )
            SectionResult(actions, "darkbloom")
        }
    }
}

@Composable
private fun MiningActionsCard(data: ActionsData, actions: ActionsUiState, onAction: (PendingAction) -> Unit) {
    val anyBusy = actions.inFlight != null
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Mining — Batman-3", subtitle = "the miner on the Windows PC") {
                StatusChip(
                    if (data.minerRunning) "MINER RUNNING" else "MINER STOPPED",
                    if (data.minerRunning) Muse.Good else Muse.Bad,
                )
            }
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Miner", if (data.minerRunning) "Running" else "Stopped")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    label = "Start miner", busyLabel = "Starting…",
                    busy = actions.inFlight == InFlightAction("miner_start"),
                    enabled = !anyBusy && !data.minerRunning, filled = true,
                    onClick = {
                        onAction(
                            PendingAction(
                                action = "miner_start",
                                title = "Start the miner?",
                                body = "Starts the miner on Batman-3. The GPU draws full power while it runs.",
                                confirmLabel = "Start",
                            )
                        )
                    },
                )
                ActionButton(
                    label = "Stop miner", busyLabel = "Stopping…",
                    busy = actions.inFlight == InFlightAction("miner_stop"),
                    enabled = !anyBusy && data.minerRunning, filled = false,
                    onClick = {
                        onAction(
                            PendingAction(
                                action = "miner_stop",
                                title = "Stop the miner?",
                                body = "Mining earnings halt while the miner is off.",
                                confirmLabel = "Stop",
                            )
                        )
                    },
                )
            }
            SectionResult(actions, "mining")
        }
    }
}

private fun actionLabel(action: String): String = when (action) {
    "lm_load" -> "Load model"
    "lm_unload" -> "Unload model"
    "darkbloom_restart" -> "Restart Darkbloom"
    "miner_start" -> "Start miner"
    "miner_stop" -> "Stop miner"
    "bridge_restart" -> "Restart bridge"
    else -> action.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** The bridge's read-back of its own actions ledger: every tap, when
 * it happened, and the answer it gave — including the rejections a
 * section result may already have scrolled past. */
@Composable
private fun ActionsHistoryCard(history: List<com.duckets.dashboard.data.ActionHistoryEntry>) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Recent activity", subtitle = "actions recorded by the bridge")
            Spacer(Modifier.height(10.dp))
            history.take(12).forEach { h ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(8.dp).background(
                            if (h.ok) Muse.Good else Muse.Bad,
                            androidx.compose.foundation.shape.CircleShape,
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            actionLabel(h.action) +
                                (h.target?.let { " · ${shortModel(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium, color = Muse.Mist,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (h.message != null) {
                            Text(h.message, style = MaterialTheme.typography.bodySmall,
                                color = if (h.ok) Muse.TextFaint else Muse.Bad,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(fmtRelativeTime(h.tsEpochSec), style = MaterialTheme.typography.bodySmall,
                        color = Muse.TextDim, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BridgeCard(data: ActionsData, actions: ActionsUiState, onAction: (PendingAction) -> Unit) {
    val anyBusy = actions.inFlight != null
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Bridge", subtitle = "the dashboard bridge itself")
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Bridge version", data.bridgeVersion ?: "—")
            Spacer(Modifier.height(10.dp))
            ActionButton(
                label = "Restart bridge", busyLabel = "Restarting…",
                busy = actions.inFlight == InFlightAction("bridge_restart"),
                enabled = !anyBusy, filled = true,
                onClick = {
                    onAction(
                        PendingAction(
                            action = "bridge_restart",
                            title = "Restart the bridge?",
                            body = "The dashboard drops for a few seconds while the bridge restarts, then reconnects on its own.",
                            confirmLabel = "Restart",
                        )
                    )
                },
            )
            SectionResult(actions, "bridge")
        }
    }
}
