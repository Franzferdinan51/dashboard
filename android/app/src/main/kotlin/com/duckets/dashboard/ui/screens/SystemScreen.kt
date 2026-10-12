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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.duckets.dashboard.data.SystemStats
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MeterBar
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.SkeletonCard
import com.duckets.dashboard.ui.components.Sparkline
import com.duckets.dashboard.ui.components.StackedBar
import com.duckets.dashboard.ui.components.StatusChip
import com.duckets.dashboard.ui.fmtBytes
import com.duckets.dashboard.ui.fmtDuration
import com.duckets.dashboard.ui.fmtPct
import com.duckets.dashboard.ui.fmtRate
import com.duckets.dashboard.vm.DashboardUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(state: DashboardUiState, columns: Int = 1, onRefresh: () -> Unit = {}, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState()) {
    val ov = state.overview
    val hPad = if (columns >= 2) 24.dp else 16.dp
    val maxW = if (columns >= 2) 1080.dp else 720.dp
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxSize().widthIn(max = maxW).padding(horizontal = hPad),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (ov == null) {
                    item { SkeletonCard(lines = 3) }
                    item {
                        MuseCard(Modifier.fillMaxWidth()) {
                            Text(state.lastError ?: "Waiting for the first sample from the bridge…",
                                Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                        }
                    }
                } else {
                    val sys = ov.system
                    if (columns >= 2) {
                        // Tablet: processor beside memory, storage beside processes.
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Column(Modifier.weight(1f)) { ProcessorCard(sys, state) }
                                Column(Modifier.weight(1f)) { MemoryCard(sys, state) }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Column(Modifier.weight(1f)) { StorageNetworkCard(sys, state) }
                                Column(Modifier.weight(1f)) { ProcessesCard(sys, state) }
                            }
                        }
                    } else {
                        item { ProcessorCard(sys, state) }
                        item { MemoryCard(sys, state) }
                        item { StorageNetworkCard(sys, state) }
                        item { ProcessesCard(sys, state) }
                    }
                    item { Spacer(Modifier.height(10.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ProcessorCard(sys: SystemStats, state: DashboardUiState) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Processor", subtitle = sys.chip) {
                if (!state.connected) {
                    StatusChip("OFFLINE", Muse.Bad)
                } else {
                    val nominal = sys.thermal.state.equals("nominal", true)
                    StatusChip(if (nominal) "THERMAL NOMINAL" else "THERMAL ${sys.thermal.state.uppercase(java.util.Locale.US)}",
                        if (nominal) Muse.Good else Muse.Warn)
                }
            }
            Spacer(Modifier.height(12.dp))
            StackedBar(listOf(
                sys.cpu.userPct.toFloat() to Muse.Blue,
                sys.cpu.sysPct.toFloat() to Muse.Sky,
                sys.cpu.idlePct.toFloat() to Muse.Track,
            ))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(Muse.Blue, "User ${fmtPct(sys.cpu.userPct, 1)}")
                LegendDot(Muse.Sky, "System ${fmtPct(sys.cpu.sysPct, 1)}")
                LegendDot(Muse.TextFaint, "Idle ${fmtPct(sys.cpu.idlePct, 1)}")
            }
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Used", fmtPct(sys.cpu.usedPct, 1))
            KeyValueRow("CPU cores", "${sys.cpuCores ?: "—"}")
            KeyValueRow("GPU cores", "${sys.gpuCores ?: "—"}")
            KeyValueRow("Load average", sys.loadAvg.joinToString("  ") { String.format("%.2f", it) }.ifBlank { "—" })
            KeyValueRow("Uptime", fmtDuration(sys.uptimeSeconds))
            if (sys.thermal.cpuSpeedLimitPct < 100) {
                KeyValueRow("CPU speed limit", "${sys.thermal.cpuSpeedLimitPct}%", valueColor = Muse.Warn)
            }
            Spacer(Modifier.height(10.dp))
            Text("CPU history", style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
            Sparkline(state.cpuHistory, Muse.Blue, maxValue = 100f, description = "CPU usage history")
        }
    }
}

@Composable
private fun MemoryCard(sys: SystemStats, state: DashboardUiState) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Memory", subtitle = "${fmtBytes(sys.memory.usedBytes)} of ${fmtBytes(sys.memory.totalBytes)} used (${fmtPct(sys.memory.usedPct, 0)})") {
                if (!state.connected) StatusChip("OFFLINE", Muse.Bad)
            }
            Spacer(Modifier.height(12.dp))
            val total = sys.memory.totalBytes.coerceAtLeast(1)
            StackedBar(listOf(
                sys.memory.activeBytes.toFloat() / total to Muse.Sky,
                sys.memory.wiredBytes.toFloat() / total to Muse.Blue,
                sys.memory.compressedBytes.toFloat() / total to Muse.Emerald,
                sys.memory.inactiveBytes.toFloat() / total to Muse.TextFaint,
                sys.memory.freeBytes.toFloat() / total to Muse.Track,
            ), heightDp = 14)
            Spacer(Modifier.height(12.dp))
            MemoryRow("Active", sys.memory.activeBytes, Muse.Sky)
            MemoryRow("Wired", sys.memory.wiredBytes, Muse.Blue)
            MemoryRow("Compressed", sys.memory.compressedBytes, Muse.Emerald)
            MemoryRow("Inactive", sys.memory.inactiveBytes, Muse.TextFaint)
            MemoryRow("Free", sys.memory.freeBytes, Muse.Good)
            Spacer(Modifier.height(8.dp))
            KeyValueRow("Swap",
                "${fmtBytes((sys.memory.swapUsedMb * 1_048_576).toLong())} of ${fmtBytes((sys.memory.swapTotalMb * 1_048_576).toLong())}")
            Spacer(Modifier.height(10.dp))
            Text("Memory history", style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
            Sparkline(state.ramHistory, Muse.Sky, maxValue = 100f, description = "Memory usage history")
        }
    }
}

@Composable
private fun StorageNetworkCard(sys: SystemStats, state: DashboardUiState) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Storage & network") {
                if (!state.connected) StatusChip("OFFLINE", Muse.Bad)
            }
            Spacer(Modifier.height(10.dp))
            sys.disks.forEach { d ->
                KeyValueRow(d.label, "${fmtBytes(d.usedBytes)} / ${fmtBytes(d.totalBytes)} · ${fmtPct(d.usedPct, 0)}")
                MeterBar((d.usedPct / 100).toFloat(),
                    if (d.usedPct > 90) Muse.Bad else if (d.usedPct > 75) Muse.Warn else Muse.Blue)
                Spacer(Modifier.height(10.dp))
            }
            KeyValueRow("Network in", "${fmtRate(sys.network.rateInBps)} · ${fmtBytes(sys.network.bytesIn)} total")
            Sparkline(state.netInHistory, Muse.Sky, heightDp = 26)
            KeyValueRow("Network out", "${fmtRate(sys.network.rateOutBps)} · ${fmtBytes(sys.network.bytesOut)} total")
            Sparkline(state.netOutHistory, Muse.Emerald, heightDp = 26)
        }
    }
}

@Composable
private fun ProcessesCard(sys: SystemStats, state: DashboardUiState) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Top processes",
                subtitle = if (state.connected) "by CPU · pull to refresh for a new sample" else "last known sample") {
                if (!state.connected) StatusChip("OFFLINE", Muse.Bad)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                TableCell("PROCESS", Modifier.weight(3f), Muse.TextFaint)
                TableCell("CPU", Modifier.weight(1f), Muse.TextFaint)
                TableCell("MEM", Modifier.weight(1f), Muse.TextFaint)
                TableCell("TIME", Modifier.weight(1.2f), Muse.TextFaint)
            }
            // Per-process %CPU on macOS is per-core and routinely exceeds
            // 100 — scale bars to the busiest process, not to 100.
            val maxCpu = sys.topProcesses.maxOfOrNull { it.cpuPct }?.coerceAtLeast(1.0) ?: 1.0
            sys.topProcesses.forEach { p ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        TableCell(p.name, Modifier.weight(3f), Muse.Mist)
                        TableCell(fmtPct(p.cpuPct, 1), Modifier.weight(1f), Muse.TextDim)
                        TableCell(fmtPct(p.memPct, 1), Modifier.weight(1f), Muse.TextDim)
                        TableCell(p.elapsed, Modifier.weight(1.2f), Muse.TextDim)
                    }
                    MeterBar((p.cpuPct / maxCpu).toFloat().coerceIn(0f, 1f),
                        if (p.cpuPct > 50) Muse.Warn else Muse.Blue, heightDp = 4)
                }
            }
            if (sys.topProcesses.isEmpty()) {
                Text("No process sample yet.", style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            }
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Muse.TextDim, maxLines = 1)
    }
}

@Composable
private fun MemoryRow(label: String, bytes: Long, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim, modifier = Modifier.weight(1f))
        Text(fmtBytes(bytes), style = MaterialTheme.typography.bodyMedium, color = Muse.Mist)
    }
}

@Composable
private fun TableCell(text: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color,
        modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
