package com.duckets.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MeterBar
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.Sparkline
import com.duckets.dashboard.ui.components.StatusChip
import com.duckets.dashboard.ui.fmtBytes
import com.duckets.dashboard.ui.fmtDuration
import com.duckets.dashboard.ui.fmtPct
import com.duckets.dashboard.ui.fmtRate
import com.duckets.dashboard.vm.DashboardUiState

/** Deep-dive system screen: CPU split, full memory breakdown, disks, top processes. */
@Composable
fun SystemScreen(state: DashboardUiState, modifier: Modifier = Modifier) {
    val ov = state.overview
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (ov == null) {
            item {
                MuseCard(Modifier.fillMaxWidth()) {
                    Text(
                        state.lastError ?: "Waiting for the first sample from the bridge…",
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muse.TextDim,
                    )
                }
            }
        } else {
            val sys = ov.system
            item {
                MuseCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        CardHeader("Processor", subtitle = sys.chip) {
                            StatusChip(
                                if (sys.thermal.state == "nominal") "THERMAL NOMINAL" else "THROTTLED",
                                if (sys.thermal.state == "nominal") Muse.Good else Muse.Warn,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        KeyValueRow("Used", fmtPct(sys.cpu.usedPct, 1))
                        KeyValueRow("User", fmtPct(sys.cpu.userPct, 1))
                        KeyValueRow("System", fmtPct(sys.cpu.sysPct, 1))
                        KeyValueRow("Idle", fmtPct(sys.cpu.idlePct, 1))
                        KeyValueRow("CPU cores", "${sys.cpuCores ?: "—"}")
                        KeyValueRow("GPU cores", "${sys.gpuCores ?: "—"}")
                        KeyValueRow(
                            "Load average",
                            sys.loadAvg.joinToString("  ") { String.format("%.2f", it) }.ifBlank { "—" },
                        )
                        KeyValueRow("Uptime", fmtDuration(sys.uptimeSeconds))
                        Spacer(Modifier.height(10.dp))
                        Text("CPU history", style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
                        Sparkline(state.cpuHistory, Muse.Blue, maxValue = 100f)
                    }
                }
            }
            item {
                MuseCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        CardHeader("Memory", subtitle = "${fmtBytes(sys.memory.usedBytes)} of ${fmtBytes(sys.memory.totalBytes)} used")
                        Spacer(Modifier.height(12.dp))
                        MeterBar((sys.memory.usedPct / 100).toFloat(), Muse.Purple)
                        Spacer(Modifier.height(12.dp))
                        MemoryRow("Active", sys.memory.activeBytes, Muse.Purple)
                        MemoryRow("Wired", sys.memory.wiredBytes, Muse.Blue)
                        MemoryRow("Compressed", sys.memory.compressedBytes, Muse.Cyan)
                        MemoryRow("Inactive", sys.memory.inactiveBytes, Muse.TextDim)
                        MemoryRow("Free", sys.memory.freeBytes, Muse.Good)
                        Spacer(Modifier.height(8.dp))
                        KeyValueRow(
                            "Swap",
                            "${fmtBytes((sys.memory.swapUsedMb * 1_000_000).toLong())} of " +
                                fmtBytes((sys.memory.swapTotalMb * 1_000_000).toLong()),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Memory history", style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
                        Sparkline(state.ramHistory, Muse.Purple, maxValue = 100f)
                    }
                }
            }
            item {
                MuseCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        CardHeader("Storage")
                        Spacer(Modifier.height(10.dp))
                        sys.disks.forEach { d ->
                            KeyValueRow(d.label, "${fmtBytes(d.usedBytes)} / ${fmtBytes(d.totalBytes)}")
                            MeterBar(
                                (d.usedPct / 100).toFloat(),
                                if (d.usedPct > 90) Muse.Bad else if (d.usedPct > 75) Muse.Warn else Muse.Blue,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        KeyValueRow("Network in", "${fmtRate(sys.network.rateInBps)} · ${fmtBytes(sys.network.bytesIn)} total")
                        KeyValueRow("Network out", "${fmtRate(sys.network.rateOutBps)} · ${fmtBytes(sys.network.bytesOut)} total")
                    }
                }
            }
            item {
                MuseCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        CardHeader("Top processes", subtitle = "by CPU")
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            TableCell("PROCESS", 3f, Muse.TextFaint)
                            TableCell("CPU", 1f, Muse.TextFaint)
                            TableCell("MEM", 1f, Muse.TextFaint)
                            TableCell("TIME", 1.2f, Muse.TextFaint)
                        }
                        sys.topProcesses.forEach { p ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                TableCell(p.name, 3f, Muse.Mist)
                                TableCell(fmtPct(p.cpuPct, 1), 1f, Muse.TextDim)
                                TableCell(fmtPct(p.memPct, 1), 1f, Muse.TextDim)
                                TableCell(p.elapsed, 1.2f, Muse.TextDim)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun MemoryRow(label: String, bytes: Long, color: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .height(9.dp)
                .fillMaxWidth(0.03f)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim, modifier = Modifier.weight(1f))
        Text(fmtBytes(bytes), style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun TableCell(text: String, weight: Float, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.fillMaxWidth(weight),
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}
