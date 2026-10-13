package com.duckets.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.duckets.dashboard.data.WindowsData
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MeterBar
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.SkeletonCard
import com.duckets.dashboard.ui.components.StatusChip
import com.duckets.dashboard.ui.fmtBytes
import com.duckets.dashboard.ui.fmtCount
import com.duckets.dashboard.ui.fmtDuration
import com.duckets.dashboard.ui.fmtPct
import com.duckets.dashboard.ui.fmtRate
import com.duckets.dashboard.vm.DashboardUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowsScreen(state: DashboardUiState, columns: Int = 1, onRefresh: () -> Unit = {}, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState()) {
    val hPad = if (columns >= 2) 24.dp else 16.dp
    val maxW = if (columns >= 2) 1080.dp else 720.dp
    PullToRefreshBox(isRefreshing = state.isPullRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxSize().widthIn(max = maxW).padding(horizontal = hPad),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val w = state.windows
                if (w == null) {
                    item { SkeletonCard(lines = 3) }
                    item {
                        MuseCard(Modifier.fillMaxWidth()) {
                            Text("Waiting for the first Windows sample from the bridge…",
                                Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                        }
                    }
                } else if (!w.reachable) {
                    item {
                        MuseCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) {
                                CardHeader("Windows PC — Batman-3") {
                                    StatusChip("OFFLINE", Muse.Bad)
                                }
                                Spacer(Modifier.height(10.dp))
                                Text("Batman-3 isn't answering — asleep, off, or SSH blocked. Nothing is shown rather than stale guesses.",
                                    style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                            }
                        }
                    }
                } else {
                    item { WindowsHeroCard(w) }
                    item { WindowsDetailCard(w) }
                    item { WindowsMinerCard(w) }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun WindowsHeroCard(w: WindowsData) {
    MuseCard(Modifier.fillMaxWidth(), hero = true) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Windows PC", subtitle = listOfNotNull(w.osName, w.osVersion).joinToString(" ")) {
                StatusChip(if (w.stale) "LAST KNOWN" else "ONLINE", if (w.stale) Muse.Warn else Muse.Good, pulse = !w.stale)
            }
            Spacer(Modifier.height(10.dp))
            Text("Batman-3", style = MaterialTheme.typography.headlineLarge, color = Muse.Mist)
            if (w.fetchedAtEpochSec != null) {
                // "Last known" (stale) only means something with the
                // snapshot's actual age attached to it.
                Text("PC data as of ${fmtClock(w.fetchedAtEpochSec)}",
                    style = MaterialTheme.typography.bodySmall, color = Muse.TextDim)
            }
            Spacer(Modifier.height(8.dp))
            KeyValueRow("Uptime", fmtDuration(w.uptimeSeconds))
            KeyValueRow("CPU load", w.cpuLoadPct?.let { "$it%" } ?: "—")
            KeyValueRow("Net in", fmtRate(w.netRateInBps?.toLong() ?: 0))
            KeyValueRow("Net out", fmtRate(w.netRateOutBps?.toLong() ?: 0))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(java.util.Locale.US), style = MaterialTheme.typography.labelMedium,
        color = Muse.BlueSoft, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun WindowsDetailCard(w: WindowsData) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Detail")
            SectionLabel("CPU")
            MeterBar(fraction = (w.cpuLoadPct ?: 0) / 100f, color = Muse.Blue)
            KeyValueRow("Processor", w.cpuName ?: "—")
            KeyValueRow("Cores / threads", "${w.cpuCores ?: "—"} / ${w.cpuLogical ?: "—"}")
            KeyValueRow("Load", w.cpuLoadPct?.let { "$it%" } ?: "—")
            SectionLabel("Memory")
            MeterBar(fraction = ((w.memUsedPct ?: 0.0) / 100).toFloat(), color = Muse.Sky)
            KeyValueRow("Used", fmtBytes(w.memUsedBytes))
            KeyValueRow("Free", fmtBytes(w.memFreeBytes))
            KeyValueRow("Total", fmtBytes(w.memTotalBytes))
            KeyValueRow("Used %", w.memUsedPct?.let { fmtPct(it) } ?: "—")
            SectionLabel("GPU")
            MeterBar(fraction = ((w.gpuUtilPct ?: 0.0) / 100).toFloat(), color = Muse.Emerald)
            KeyValueRow("GPU", w.gpuName ?: "—")
            KeyValueRow("Utilization", w.gpuUtilPct?.let { fmtPct(it) } ?: "—")
            KeyValueRow("VRAM",
                if (w.gpuMemUsedMb != null && w.gpuMemTotalMb != null)
                    "${fmtBytes((w.gpuMemUsedMb * 1048576).toLong())} of ${fmtBytes((w.gpuMemTotalMb * 1048576).toLong())}"
                else "—")
            KeyValueRow("Temperature", w.gpuTempC?.let { "$it°C" } ?: "—")
            KeyValueRow("Power", w.gpuPowerW?.let { "$it W" } ?: "—")
            SectionLabel("Volumes")
            w.disks.forEach { d ->
                KeyValueRow(d.label, "${fmtBytes(d.freeBytes)} free · ${fmtPct(d.usedPct)}")
                MeterBar(
                    fraction = (d.usedPct / 100).toFloat(),
                    color = when {
                        d.usedPct > 90 -> Muse.Bad
                        d.usedPct > 80 -> Muse.Warn
                        else -> Muse.Blue
                    },
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun WindowsMinerCard(w: WindowsData) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Miner — SRBMiner") {
                StatusChip(if (w.minerRunning) "RUNNING" else "STOPPED", if (w.minerRunning) Muse.Good else Muse.Bad)
            }
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Hashrate", w.minerHashrateThs?.let { "$it TH/s" } ?: "—")
            KeyValueRow("Avg 15m / 1h / 6h",
                listOf(w.minerAvg15mThs, w.minerAvg1hThs, w.minerAvg6hThs)
                    .joinToString(" / ") { it?.toString() ?: "—" } + " TH/s")
            KeyValueRow("Shares",
                if (w.minerAccepted != null) "${fmtCount(w.minerAccepted.toLong())} accepted · ${fmtCount((w.minerRejected ?: 0).toLong())} rejected" else "—")
            KeyValueRow("Miner power", w.minerPowerW?.let { "$it W" } ?: "—")
        }
    }
}
