package com.duckets.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.duckets.dashboard.data.MiningData
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.CardDivider
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MeterBar
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.SkeletonCard
import com.duckets.dashboard.ui.components.StatusChip
import com.duckets.dashboard.ui.fmtCount
import com.duckets.dashboard.vm.DashboardUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun usdD(v: Double?, digits: Int = 2): String =
    if (v == null) "—" else "$" + String.format(Locale.US, "%,.${digits}f", v)

internal fun fmtClock(epochSec: Double?): String =
    if (epochSec == null) "—"
    else SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date((epochSec * 1000).toLong()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiningScreen(state: DashboardUiState, columns: Int = 1, onRefresh: () -> Unit = {}, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState()) {
    val hPad = if (columns >= 2) 24.dp else 16.dp
    val maxW = if (columns >= 2) 1080.dp else 720.dp
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxSize().widthIn(max = maxW).padding(horizontal = hPad),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val mn = state.mining
                if (mn == null) {
                    item { SkeletonCard(lines = 3) }
                    item {
                        MuseCard(Modifier.fillMaxWidth()) {
                            Text("Waiting for mining data from the bridge…",
                                Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                        }
                    }
                } else {
                    item { FleetCard(mn) }
                    item { BatmanLiveCard(state) }
                    item { RigsCard(mn) }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun FleetCard(mn: MiningData) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader(
                "Crypto mining — Kryptex fleet",
                subtitle = "As of ${fmtClock(mn.fleetAsOfEpochSec)}",
            )
            Spacer(Modifier.height(10.dp))
            Text(usdD(mn.totalPerDayUsd) + " /day", style = MaterialTheme.typography.headlineLarge, color = Muse.Mist)
            Spacer(Modifier.height(8.dp))
            KeyValueRow("Per week", usdD(mn.totalPerWeekUsd))
            KeyValueRow("Per month", usdD(mn.totalPerMonthUsd))
            KeyValueRow("Per year", usdD(mn.totalPerYearUsd, 0))
            KeyValueRow("Rigs", fmtCount(mn.coins.sumOf { it.rigs.size }.toLong()))
            KeyValueRow("Source", mn.fleetSource ?: "Kryptex")
            if (mn.coins.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                CardDivider()
                Spacer(Modifier.height(12.dp))
                mn.coins.forEach { c ->
                    val day = c.rigs.sumOf { it.perDayUsd ?: 0.0 }
                    val mo = c.rigs.sumOf { it.perMonthUsd ?: 0.0 }
                    val total = mn.totalPerDayUsd ?: 0.0
                    KeyValueRow("${c.coin} · ${c.scheme} · ${c.miner}",
                        "${usdD(day)}/day · ${usdD(mo, 0)}/mo")
                    MeterBar(
                        fraction = if (total > 0) (day / total).toFloat() else 0f,
                        color = Muse.Warn,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Kryptex's own estimates from a manual snapshot — they move with coin price and network difficulty. Batman-3 below is live.",
                style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint,
            )
        }
    }
}

@Composable
private fun BatmanLiveCard(state: DashboardUiState) {
    val live = state.mining?.batmanLive
    val win = state.windows
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Batman-3 — live miner", subtitle = "via the Windows probe") {
                StatusChip(
                    when {
                        live == null -> "PC UNREACHABLE"
                        live.running -> "MINER RUNNING"
                        else -> "MINER STOPPED"
                    },
                    when {
                        live == null -> Muse.Bad
                        live.running -> Muse.Good
                        else -> Muse.Bad
                    },
                )
            }
            if (live != null) {
                Spacer(Modifier.height(10.dp))
                KeyValueRow("Hashrate now", live.hashrateThs?.let { "$it TH/s" } ?: "—")
                KeyValueRow("Average 1h", live.avg1hThs?.let { "$it TH/s" } ?: "—")
                KeyValueRow("Miner power", live.powerW?.let { "$it W" } ?: "—")
                KeyValueRow("GPU temp", live.gpuTempC?.let { "$it°C" } ?: "—")
                KeyValueRow(
                    "Shares (session)",
                    if (win?.minerAccepted != null) "${fmtCount(win.minerAccepted.toLong())} accepted · ${fmtCount((win.minerRejected ?: 0).toLong())} rejected" else "—",
                )
                if (live.stale) {
                    Spacer(Modifier.height(6.dp))
                    Text("Last known values — the PC isn't answering right now.",
                        style = MaterialTheme.typography.bodySmall, color = Muse.Warn)
                }
            }
        }
    }
}

@Composable
private fun RigsCard(mn: MiningData) {
    val live = mn.batmanLive
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Rigs")
            Spacer(Modifier.height(8.dp))
            mn.coins.forEach { c ->
                Text(c.coin.uppercase(Locale.US), style = MaterialTheme.typography.labelMedium,
                    color = Muse.BlueSoft, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                c.rigs.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, style = MaterialTheme.typography.bodyLarge, color = Muse.Mist)
                                if (r.name == "Batman-3" && live != null) {
                                    Spacer(Modifier.padding(horizontal = 4.dp))
                                    StatusChip(
                                        if (live.running) "LIVE ${live.hashrateThs ?: "—"} TH/s" else "MINER OFF",
                                        if (live.running) Muse.Good else Muse.Bad,
                                    )
                                }
                            }
                            Text(
                                "30m ${r.hashrate30m ?: "—"} · 24h ${r.hashrate24h ?: "—"}",
                                style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(usdD(r.perDayUsd) + "/day", style = MaterialTheme.typography.bodyLarge, color = Muse.Mist)
                            Text(usdD(r.perMonthUsd, 0) + "/mo", style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint)
                        }
                    }
                }
            }
        }
    }
}
