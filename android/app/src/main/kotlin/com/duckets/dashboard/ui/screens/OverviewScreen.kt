package com.duckets.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.duckets.dashboard.data.Overview
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.ArcGauge
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MeterBar
import com.duckets.dashboard.ui.components.MuseCard
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

/**
 * Overview: hero machine card, the three big gauges, Darkbloom earnings strip,
 * network + disk at a glance, LM Studio loaded models.
 * [columns] comes from the window size class: 1 on phones, 2 on tablets.
 */
@Composable
fun OverviewScreen(state: DashboardUiState, columns: Int, modifier: Modifier = Modifier) {
    val ov = state.overview
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { HeroCard(state) }
        if (ov == null) {
            item { ConnectingCard(state) }
        } else {
            item { GaugeCard(state, ov) }
            item { DarkbloomStrip(ov) }
            if (columns >= 2) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f)) { NetworkDiskCard(ov) }
                        Column(Modifier.weight(1f)) { LmStudioCard(ov) }
                    }
                }
            } else {
                item { NetworkDiskCard(ov) }
                item { LmStudioCard(ov) }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun HeroCard(state: DashboardUiState) {
    val ov = state.overview
    val sys = ov?.system
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        sys?.computerName ?: "Mac Studio",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Muse.Mist,
                    )
                    Text(
                        listOfNotNull(
                            sys?.chip?.takeIf { it.isNotBlank() },
                            sys?.gpuCores?.let { "$it GPU cores" },
                            sys?.macosVersion?.takeIf { it.isNotBlank() }?.let { "macOS $it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muse.TextDim,
                    )
                }
                when {
                    state.connected -> StatusChip("ONLINE", Muse.Good)
                    state.everConnected -> StatusChip("OFFLINE", Muse.Bad)
                    else -> StatusChip("CONNECTING", Muse.Warn)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                MiniStat("Uptime", fmtDuration(sys?.uptimeSeconds))
                MiniStat(
                    "Load",
                    sys?.loadAvg?.takeIf { it.isNotEmpty() }
                        ?.joinToString(" ") { String.format("%.2f", it) } ?: "—",
                )
                MiniStat(
                    "Thermal",
                    (sys?.thermal?.state ?: "—").replaceFirstChar { it.uppercase() },
                )
                MiniStat(
                    "Net in",
                    fmtRate(sys?.network?.rateInBps ?: 0),
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Muse.TextFaint,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = Muse.Mist,
        )
    }
}

@Composable
private fun ConnectingCard(state: DashboardUiState) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Connecting to your Mac…", style = MaterialTheme.typography.titleMedium, color = Muse.Mist)
            Spacer(Modifier.height(6.dp))
            Text(
                state.lastError ?: "Reaching the bridge at ${state.bridgeUrl}",
                style = MaterialTheme.typography.bodyMedium,
                color = Muse.TextDim,
            )
            if (state.lastError != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Check the bridge is running on the Mac (dashboard-bridge), " +
                        "or update the address in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muse.TextFaint,
                )
            }
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
            CardHeader("Live load", subtitle = "refreshed every ${state.refreshSeconds}s")
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ArcGauge(
                    label = "CPU",
                    valueText = fmtPct(cpu),
                    fraction = (cpu / 100).toFloat(),
                    color = Muse.Blue,
                    subText = "${ov.system.cpuCores ?: "—"} cores",
                )
                ArcGauge(
                    label = "Memory",
                    valueText = fmtPct(ram.usedPct),
                    fraction = (ram.usedPct / 100).toFloat(),
                    color = Muse.Purple,
                    subText = "${fmtBytes(ram.usedBytes)} / ${fmtBytes(ram.totalBytes)}",
                )
                ArcGauge(
                    label = "GPU memory",
                    valueText = fmtPct(gpuPct),
                    fraction = (gpuPct / 100).toFloat(),
                    color = Muse.Cyan,
                    subText = fmtGb(ov.darkbloom.gpuActiveGb),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("CPU history", style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
            Sparkline(state.cpuHistory, Muse.Blue, maxValue = 100f)
        }
    }
}

@Composable
private fun DarkbloomStrip(ov: Overview) {
    val db = ov.darkbloom
    val e = db.earnings
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader(
                "Darkbloom provider",
                subtitle = if (db.running) "v${db.version ?: "?"} · up ${fmtDuration(db.uptimeSeconds)}" else "not running",
            ) {
                when {
                    !db.installed -> StatusChip("NOT INSTALLED", Muse.TextDim)
                    !db.running -> StatusChip("STOPPED", Muse.Bad)
                    db.earning -> StatusChip("EARNING", Muse.Good)
                    else -> StatusChip("ONLINE", Muse.Warn)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MonetizationOn, null, tint = Muse.Good)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        fmtUsd(e?.balanceUsd),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Muse.Mist,
                    )
                    Text(
                        "balance · ${fmtCount(e?.jobsInHistory?.toLong())} paid jobs",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muse.TextDim,
                    )
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    MiniStat("Requests", fmtCount(db.requestsServed))
                    Spacer(Modifier.height(6.dp))
                    MiniStat("Tokens", fmtCount(db.tokensGenerated))
                }
            }
            if (db.verificationPending && db.running) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Trust: ${db.trustLevel ?: "—"} — serving verification still pending on " +
                        "Darkbloom's side, but paid jobs are landing in the ledger.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muse.Warn,
                )
            }
            if (db.warmModels.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Warm models", style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
                Spacer(Modifier.height(4.dp))
                db.warmModels.forEach { m ->
                    Text(
                        "• ${shortModel(m)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muse.Mist,
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkDiskCard(ov: Overview) {
    val net = ov.system.network
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Network & storage")
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Download", fmtRate(net.rateInBps))
            KeyValueRow("Upload", fmtRate(net.rateOutBps))
            KeyValueRow("Total in", fmtBytes(net.bytesIn))
            KeyValueRow("Total out", fmtBytes(net.bytesOut))
            ov.system.disks.forEach { d ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Storage, null, tint = Muse.BlueSoft)
                    Spacer(Modifier.width(8.dp))
                    Text(d.label, style = MaterialTheme.typography.titleSmall, color = Muse.Mist)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${fmtBytes(d.freeBytes)} free",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muse.TextDim,
                    )
                }
                Spacer(Modifier.height(6.dp))
                MeterBar(
                    fraction = (d.usedPct / 100).toFloat(),
                    color = if (d.usedPct > 90) Muse.Bad else if (d.usedPct > 75) Muse.Warn else Muse.Blue,
                )
            }
        }
    }
}

@Composable
private fun LmStudioCard(ov: Overview) {
    val lm = ov.lmstudio
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("LM Studio") {
                if (lm.reachable) {
                    StatusChip("${lm.loadedCount} LOADED", Muse.Good)
                } else {
                    StatusChip("UNREACHABLE", Muse.TextDim)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!lm.reachable) {
                Text(
                    "LM Studio's API isn't answering — the app may be closed or its server off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muse.TextDim,
                )
            } else if (lm.models.isEmpty()) {
                Text("No models in the library.", style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            } else {
                val loaded = lm.models.filter { it.state == "loaded" }
                val rest = lm.models.filter { it.state != "loaded" }
                (loaded + rest).take(8).forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Memory, null, tint = if (m.state == "loaded") Muse.Good else Muse.TextFaint)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(shortModel(m.id), style = MaterialTheme.typography.bodyMedium, color = Muse.Mist)
                            Text(
                                listOfNotNull(m.quantization, m.sizeBytes?.let { fmtBytes(it) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Muse.TextFaint,
                            )
                        }
                        if (m.state == "loaded") {
                            StatusChip("LOADED", Muse.Good)
                        }
                    }
                }
                if (lm.models.size > 8) {
                    Text(
                        "+ ${lm.models.size - 8} more in the library",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muse.TextFaint,
                    )
                }
            }
        }
    }
}
