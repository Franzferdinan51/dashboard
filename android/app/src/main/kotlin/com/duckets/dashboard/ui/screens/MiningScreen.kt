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
import com.duckets.dashboard.data.BatmanHistory
import com.duckets.dashboard.data.BatmanVerified
import com.duckets.dashboard.data.MiningProfit
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.SkeletonCard
import com.duckets.dashboard.ui.components.Sparkline
import com.duckets.dashboard.ui.components.StatusChip
import com.duckets.dashboard.ui.fmtCount
import com.duckets.dashboard.vm.DashboardUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun usdD(v: Double?, digits: Int = 2): String =
    if (v == null || !v.isFinite()) "—" else "$" + String.format(Locale.US, "%,.${digits}f", v)

internal fun fmtClock(epochSec: Double?): String =
    if (epochSec == null) "—"
    else SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date((epochSec * 1000).toLong()))

/** Electricity rate for display: "0.12" not "0.120", but keep a real
 * third decimal when the rate has one ("0.115"). */
internal fun trimRate(rate: Double): String =
    String.format(Locale.US, "%.3f", rate).trimEnd('0').trimEnd('.')

/**
 * Mining is Batman-3 only, and every number on this tab is computed by
 * the bridge from ground truth: the miner's own log (hashrate history)
 * × live Pearl network emission × live PRL price, less the pool fee.
 * Kryptex's dashboard is never an input — its claim appears once, as a
 * labeled comparison. The rest of the Kryptex fleet was dropped from
 * this tab: there is no trusted source for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiningScreen(state: DashboardUiState, columns: Int = 1, onRefresh: () -> Unit = {}, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState()) {
    val hPad = if (columns >= 2) 24.dp else 16.dp
    val maxW = if (columns >= 2) 1080.dp else 720.dp
    PullToRefreshBox(isRefreshing = state.isPullRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
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
                    item { VerifiedCard(mn.batmanVerified, mn.profit) }
                    mn.batmanHistory?.let { h -> item { HistoryCard(h) } }
                    item { LiveMinerCard(state) }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun VerifiedCard(ver: BatmanVerified?, profit: MiningProfit? = null) {
    MuseCard(Modifier.fillMaxWidth(), hero = true) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Batman-3 — verified mining earnings", subtitle = "miner log × live network")
            Spacer(Modifier.height(10.dp))
            if (ver == null) {
                Text("Waiting on live hashrate + network data…",
                    style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            } else {
                Text((ver.usdPerDayNet?.let { usdD(it) } ?: "—") + " /day net",
                    style = MaterialTheme.typography.headlineLarge, color = Muse.Mist)
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Verified gross",
                    "${usdD(ver.usdPerDayGross)}/day · ${ver.prlPerDayGross ?: "—"} PRL/day")
                KeyValueRow("Verified / week · / month",
                    "${usdD(ver.usdPerWeekNet)} · ${usdD(ver.usdPerMonthNet)}")
                KeyValueRow("Hashrate basis",
                    "${ver.hashrateBasisThs ?: "—"} TH/s (${ver.hashrateBasis ?: "miner log"})")
                KeyValueRow("PRL price",
                    "${usdD(ver.priceUsd, 2)} (${ver.priceSource ?: "—"})")
                KeyValueRow("Network",
                    "${(ver.nethashThs?.let { it / 1e6 } ?: 0.0).let { String.format(Locale.US, "%.1f", it) }} EH/s · ${usdD(ver.usdPerThDay, 4)}/TH/day · fee ${ver.poolFeePct ?: "—"}%")
                KeyValueRow("Kryptex claims (12:32 PM snapshot)",
                    if (ver.kryptexClaimPerDayUsd != null)
                        "${usdD(ver.kryptexClaimPerDayUsd)}/day — verified runs ${String.format(Locale.US, "%+.1f", ver.deltaVsClaimPct ?: 0.0)}% vs the claim"
                    else "—")
                if (profit != null && profit.powerCostDay != null) {
                    KeyValueRow(
                        "Power" + (profit.rateUsdKwh?.let { " (at $${trimRate(it)}/kWh)" } ?: ""),
                        "−${usdD(profit.powerCostDay)}/day" +
                            (profit.kwhPerDay?.let { " · ${String.format(Locale.US, "%.2f", it)} kWh" } ?: "") +
                            (profit.powerW?.let { " · ${String.format(Locale.US, "%.0f", it)} W" } ?: ""),
                        valueColor = Muse.Warn,
                    )
                }
                if (profit?.netDay != null) {
                    KeyValueRow("Net after power", "${usdD(profit.netDay)}/day", valueColor = Muse.Good)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Computed from the miner's own log (sustained average hashrate) × live Pearl network emission (WhatToMine) × live PRL price (CoinGecko), less Kryptex's 3% pool fee. Kryptex's dashboard figures are not an input.",
                    style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint,
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(h: BatmanHistory) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader(
                "Hashrate history — the miner's log",
                subtitle = "${h.spanHours ?: "—"}h of log · ${fmtCount((h.sampleCount ?: 0).toLong())} samples",
            )
            Spacer(Modifier.height(10.dp))
            if (h.points.size >= 2) {
                Sparkline(h.points.map { it.ths.toFloat() }, Muse.Warn, heightDp = 84,
                    description = "Batman-3 hashrate history from the miner log")
                Spacer(Modifier.height(8.dp))
            }
            KeyValueRow("Coverage", "${fmtClock(h.coverageFromEpochSec)} → ${fmtClock(h.coverageToEpochSec)}")
            KeyValueRow("Average (active)", h.avgActiveThs?.let { "$it TH/s" } ?: "—")
            KeyValueRow("Average (overall)", h.avgOverallThs?.let { "$it TH/s" } ?: "—")
            if (h.avg24hOverallThs != null) {
                KeyValueRow(
                    "24h overall avg",
                    "${h.avg24hOverallThs} TH/s" +
                        (h.win24SpanHours?.let { span ->
                            " (over ${if (span % 1.0 == 0.0) "${span.toLong()}" else String.format(Locale.US, "%.1f", span)}h)"
                        } ?: ""),
                )
            }
            KeyValueRow("Uptime in log",
                h.uptimeFraction?.let { "${Math.round(it * 100)}%" } ?: "—")
            KeyValueRow("Realized in window",
                if (h.realizedPrlEst != null) "${h.realizedPrlEst} PRL ≈ ${usdD(h.realizedUsdNetEst)} net" else "—")
            KeyValueRow("Realized basis", h.realizedBasis ?: "—")
        }
    }
}

@Composable
private fun LiveMinerCard(state: DashboardUiState) {
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
