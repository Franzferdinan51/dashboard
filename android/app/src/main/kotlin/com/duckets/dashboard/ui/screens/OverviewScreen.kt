package com.duckets.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.duckets.dashboard.data.Overview
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.ArcGauge
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MeterBar
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.SkeletonCard
import com.duckets.dashboard.ui.components.Sparkline
import com.duckets.dashboard.ui.components.StatusChip
import com.duckets.dashboard.ui.fmtBytes
import com.duckets.dashboard.ui.fmtCount
import com.duckets.dashboard.ui.fmtDuration
import com.duckets.dashboard.ui.fmtGb
import com.duckets.dashboard.ui.fmtPct
import com.duckets.dashboard.ui.fmtRate
import com.duckets.dashboard.ui.fmtUsd
import com.duckets.dashboard.ui.shortModel
import com.duckets.dashboard.vm.DashboardUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(state: DashboardUiState, columns: Int, onRefresh: () -> Unit = {}, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState()) {
    val ov = state.overview
    val hPad = if (columns >= 2) 24.dp else 16.dp
    val maxW = if (columns >= 2) 1080.dp else 720.dp
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        LazyColumn(
            Modifier.fillMaxSize().widthIn(max = maxW).padding(horizontal = hPad),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { HeroCard(state) }
            if (ov == null) {
                item { SkeletonCard(lines = 2) }
                item { SkeletonCard(lines = 4) }
                item { ConnectingCard(state) }
            } else {
                item { GaugeCard(state, ov) }
                if (columns >= 2) {
                    // Tablet: earnings beside network, LM Studio full width below.
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(Modifier.weight(1f)) { DarkbloomStrip(ov, state.connected) }
                            Column(Modifier.weight(1f)) { NetworkDiskCard(ov, state) }
                        }
                    }
                    item { LmStudioCard(ov, state.connected) }
                } else {
                    item { DarkbloomStrip(ov, state.connected) }
                    item { NetworkDiskCard(ov, state) }
                    item { LmStudioCard(ov, state.connected) }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCard(state: DashboardUiState) {
    val ov = state.overview
    val sys = ov?.system
    MuseCard(Modifier.fillMaxWidth(), hero = true) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoxIcon()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(sys?.computerName ?: "Mac Studio", style = MaterialTheme.typography.headlineSmall,
                        color = Muse.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            sys?.chip?.takeIf { it.isNotBlank() },
                            sys?.macosVersion?.takeIf { it.isNotBlank() }?.let { "macOS $it" },
                        ).joinToString(" · ").ifBlank { "Waiting for bridge…" },
                        style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    state.connected -> StatusChip("ONLINE", Muse.Good, pulse = true)
                    state.everConnected -> StatusChip("OFFLINE", Muse.Bad)
                    else -> StatusChip("CONNECTING", Muse.Warn, pulse = true)
                }
            }
            val specs = listOfNotNull(
                sys?.cpuCores?.let { "$it CPU cores" },
                sys?.gpuCores?.let { "$it GPU cores" },
                sys?.model?.takeIf { it.isNotBlank() },
            )
            if (specs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    specs.forEach { SpecChip(it) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("Uptime", fmtDuration(sys?.uptimeSeconds), Modifier.weight(1f))
                MiniStat("Load", sys?.loadAvg?.takeIf { it.isNotEmpty() }
                    ?.joinToString(" ") { String.format("%.2f", it) } ?: "—", Modifier.weight(1f))
                MiniStat("Thermal", (sys?.thermal?.state ?: "—").replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
                MiniStat("Net in", fmtRate(sys?.network?.rateInBps ?: 0), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BoxIcon() {
    androidx.compose.foundation.layout.Box(
        Modifier.size(44.dp)
            .background(Muse.Blue.copy(alpha = 0.18f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Computer, null, tint = Muse.BlueSoft, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun SpecChip(text: String) {
    androidx.compose.material3.Surface(shape = Muse.ChipShape, color = Muse.CardAlt,
        border = androidx.compose.foundation.BorderStroke(1.dp, Muse.Border)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Muse.TextDim,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), maxLines = 1)
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(java.util.Locale.US), style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
        Text(value, style = MaterialTheme.typography.titleMedium, color = Muse.Mist,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConnectingCard(state: DashboardUiState) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Connecting to your Mac…", style = MaterialTheme.typography.titleMedium, color = Muse.Mist)
            Spacer(Modifier.height(6.dp))
            Text(state.lastError ?: "Reaching the bridge at ${state.bridgeUrl}",
                style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            Text("Pull down to retry, check the bridge is running on the Mac, or update the address in Settings.",
                style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint)
        }
    }
}

@Composable
private fun GaugeCard(state: DashboardUiState, ov: Overview) {
    val cpu = ov.system.cpu.usedPct
    val ram = ov.system.memory
    val gpuPct = ov.darkbloom.gpuActiveGb?.let { a ->
        ov.darkbloom.capacityTotalGb?.let { t -> if (t > 0) a / t * 100 else 0.0 }
    } ?: 0.0
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Live load", subtitle = if (state.connected) "polls every ${state.refreshSeconds}s · drag down to refresh" else "connection lost · last known values")
            Spacer(Modifier.height(14.dp))
            // Weight-based so the three gauges fit any phone width and spread on tablets.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GaugeWithSpark("CPU", fmtPct(cpu), (cpu / 100).toFloat(), Muse.Blue,
                    "${ov.system.cpuCores ?: "—"} cores", state.cpuHistory, 100f, Modifier.weight(1f))
                GaugeWithSpark("Memory", fmtPct(ram.usedPct), (ram.usedPct / 100).toFloat(), Muse.Sky,
                    "${fmtBytes(ram.usedBytes)} used", state.ramHistory, 100f, Modifier.weight(1f))
                GaugeWithSpark("GPU memory", fmtPct(gpuPct), (gpuPct / 100).toFloat(), Muse.Emerald,
                    fmtGb(ov.darkbloom.gpuActiveGb), state.gpuHistory, 100f, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GaugeWithSpark(label: String, value: String, frac: Float, color: androidx.compose.ui.graphics.Color,
                           sub: String, history: List<Float>, maxV: Float, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        ArcGauge(label = label, valueText = value, fraction = frac, color = color, subText = sub)
        Spacer(Modifier.height(6.dp))
        Sparkline(history, color, maxValue = maxV, heightDp = 22, description = "$label history")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DarkbloomStrip(ov: Overview, connected: Boolean) {
    val db = ov.darkbloom
    val e = db.earnings
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Darkbloom provider",
                subtitle = if (!connected) "connection lost · last known"
                    else if (db.running) "v${db.version ?: "?"} · up ${fmtDuration(db.uptimeSeconds)}" else "not running") {
                when {
                    !connected -> StatusChip("OFFLINE", Muse.Bad)
                    !db.installed -> StatusChip("NOT INSTALLED", Muse.TextDim)
                    !db.running -> StatusChip("STOPPED", Muse.Bad)
                    db.earning -> StatusChip("EARNING", Muse.Good, pulse = true)
                    else -> StatusChip("ONLINE", Muse.Warn)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MonetizationOn, null, tint = Muse.Emerald, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(fmtUsd(e?.balanceUsd), style = MaterialTheme.typography.headlineMedium, color = Muse.Mist,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("balance · ${fmtCount(e?.jobsInHistory?.toLong())} paid jobs",
                        style = MaterialTheme.typography.bodySmall, color = Muse.TextDim)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    MiniStat("Requests", fmtCount(db.requestsServed))
                    Spacer(Modifier.height(6.dp))
                    MiniStat("Tokens", fmtCount(db.tokensGenerated))
                }
            }
            if (db.verificationPending && db.running && connected) {
                Spacer(Modifier.height(10.dp))
                Text("Trust: ${db.trustLevel ?: "—"} — verification pending on Darkbloom's side; paid jobs still land in the ledger.",
                    style = MaterialTheme.typography.bodySmall, color = Muse.Warn)
            }
            if (db.warmModels.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(if (connected) "Warm models" else "Warm models · last known",
                    style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    db.warmModels.forEach { SpecChip(shortModel(it)) }
                }
            }
            if (db.currentModel != null) {
                Spacer(Modifier.height(10.dp))
                Text(if (connected) "Now serving" else "Last known serving",
                    style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
                Text(shortModel(db.currentModel), style = MaterialTheme.typography.bodyMedium, color = Muse.Mist,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun NetworkDiskCard(ov: Overview, state: DashboardUiState) {
    val net = ov.system.network
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Network & storage")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ArrowDownward, null, tint = Muse.Sky, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Download", style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                    }
                    Text(fmtRate(net.rateInBps), style = MaterialTheme.typography.titleMedium, color = Muse.Mist)
                    Sparkline(state.netInHistory, Muse.Sky, heightDp = 24, description = "Download rate history")
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ArrowUpward, null, tint = Muse.Emerald, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Upload", style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                    }
                    Text(fmtRate(net.rateOutBps), style = MaterialTheme.typography.titleMedium, color = Muse.Mist)
                    Sparkline(state.netOutHistory, Muse.Emerald, heightDp = 24, description = "Upload rate history")
                }
            }
            KeyValueRow("Total in", fmtBytes(net.bytesIn))
            KeyValueRow("Total out", fmtBytes(net.bytesOut))
            ov.system.disks.forEach { d ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Storage, null, tint = Muse.BlueSoft, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(d.label, style = MaterialTheme.typography.titleSmall, color = Muse.Mist,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Text("${fmtBytes(d.freeBytes)} free · ${fmtPct(d.usedPct, 0)} used",
                        style = MaterialTheme.typography.bodySmall, color = if (d.usedPct > 90) Muse.Bad else Muse.TextDim,
                        maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))
                MeterBar((d.usedPct / 100).toFloat(),
                    if (d.usedPct > 90) Muse.Bad else if (d.usedPct > 75) Muse.Warn else Muse.Blue)
                Text("${fmtBytes(d.usedBytes)} of ${fmtBytes(d.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint)
            }
        }
    }
}

@Composable
private fun LmStudioCard(ov: Overview, connected: Boolean) {
    val lm = ov.lmstudio
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("LM Studio") {
                when {
                    !connected -> StatusChip("OFFLINE", Muse.Bad)
                    lm.reachable -> StatusChip("${lm.loadedCount} LOADED", Muse.Good)
                    else -> StatusChip("UNREACHABLE", Muse.TextDim)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!connected) {
                Text("Connection to the Mac is lost — this is the last known state.",
                    style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            } else if (!lm.reachable) {
                Text("LM Studio's API isn't answering — the app may be closed or its server off.",
                    style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            } else if (lm.models.isEmpty()) {
                Text("No models in the library.", style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            } else {
                val loaded = lm.models.filter { it.state == "loaded" }
                val rest = lm.models.filter { it.state != "loaded" }
                (loaded + rest).take(8).forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BoxDot(if (m.state == "loaded") Muse.Good else Muse.TextFaint)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(shortModel(m.id), style = MaterialTheme.typography.bodyMedium, color = Muse.Mist,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(m.quantization, m.sizeBytes?.let { fmtBytes(it) },
                                m.loadedContext?.let { "ctx ${fmtCount(it)}" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (m.state == "loaded") StatusChip("LOADED", Muse.Good)
                    }
                }
                if (lm.models.size > 8) Text("+ ${lm.models.size - 8} more in the library",
                    style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint)
            }
        }
    }
}

@Composable
private fun BoxDot(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.layout.Box(Modifier.size(8.dp)
        .background(color, androidx.compose.foundation.shape.CircleShape))
}
