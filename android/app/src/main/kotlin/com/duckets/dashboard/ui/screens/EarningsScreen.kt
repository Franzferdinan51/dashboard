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
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.SkeletonCard
import com.duckets.dashboard.ui.components.Sparkline
import com.duckets.dashboard.ui.fmtUsd
import com.duckets.dashboard.vm.DashboardUiState

/**
 * The accumulative page: the only place Darkbloom and mining numbers are
 * summed. Each stream keeps its own tab with its own unblended figures;
 * this tab states plainly that the two sides are different kinds of truth
 * (Darkbloom realized from the bridge ledger, mining a Kryptex estimate).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarningsScreen(state: DashboardUiState, columns: Int = 1, onRefresh: () -> Unit = {}, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState()) {
    val hPad = if (columns >= 2) 24.dp else 16.dp
    val maxW = if (columns >= 2) 1080.dp else 720.dp
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxSize().widthIn(max = maxW).padding(horizontal = hPad),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val est = state.overview?.darkbloom?.earnings?.estimates
                val mn = state.mining
                if (est == null && mn == null) {
                    item { SkeletonCard(lines = 3) }
                    item {
                        MuseCard(Modifier.fillMaxWidth()) {
                            Text("Waiting for earnings data from the bridge…",
                                Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                        }
                    }
                } else {
                    item { CombinedCard(state) }
                    val series = est?.series.orEmpty()
                    val mineDay = mn?.batmanVerified?.usdPerDayNet
                    if (series.size >= 2 && mineDay != null) {
                        item { CombinedGraphCard(state) }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun CombinedStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(java.util.Locale.US), style = MaterialTheme.typography.labelSmall, color = Muse.TextFaint)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = Muse.Mist)
    }
}

@Composable
private fun CombinedCard(state: DashboardUiState) {
    val e = state.overview?.darkbloom?.earnings
    val est = e?.estimates
    val dbDay = est?.perDayUsd
    val mineDay = state.mining?.batmanVerified?.usdPerDayNet
    val day = (dbDay ?: 0.0) + (mineDay ?: 0.0)
    MuseCard(Modifier.fillMaxWidth(), hero = true) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Combined earnings", subtitle = "Darkbloom + mining")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                CombinedStat("Combined / day", usdD(day), Modifier.weight(1f))
                CombinedStat("Combined / week", usdD(day * 7), Modifier.weight(1f))
                CombinedStat("Combined / month", usdD(day * 30), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            KeyValueRow("Darkbloom (realized, bridge ledger)",
                if (dbDay != null) "${usdD(dbDay)} · ${usdD(dbDay * 7)}/wk · ${usdD(dbDay * 30)}/mo" else "—")
            KeyValueRow("Mining — Batman-3 (verified, miner log)",
                if (mineDay != null) "${usdD(mineDay)} · ${usdD(mineDay * 7)}/wk · ${usdD(mineDay * 30)}/mo" else "—")
            KeyValueRow("Darkbloom share",
                if (dbDay != null && day > 0) "${Math.round(dbDay / day * 100)}%" else "—")
            KeyValueRow("Mining share",
                if (mineDay != null && day > 0) "${Math.round(mineDay / day * 100)}%" else "—")
            KeyValueRow("Darkbloom balance", fmtUsd(e?.balanceUsd))
            Spacer(Modifier.height(8.dp))
            Text(
                "Two different kinds of truth, both computed by the bridge: Darkbloom is realized money from its earnings ledger; mining is Batman-3 only, from the miner's own log at live network rates. The rest of the Kryptex fleet is excluded — no trusted source for it.",
                style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint,
            )
        }
    }
}

@Composable
private fun CombinedGraphCard(state: DashboardUiState) {
    val est = state.overview?.darkbloom?.earnings?.estimates ?: return
    val mineDay = state.mining?.batmanVerified?.usdPerDayNet ?: return
    val series = est.series
    if (series.size < 2) return
    val t0 = series.first().tsSec
    val base = series.first().totalMicroUsd
    val combined = series.map { p ->
        ((p.totalMicroUsd - base) / 1e6 + (p.tsSec - t0) * mineDay / 86400.0).toFloat()
    }
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader(
                "Combined tracked earnings",
                subtitle = "Since ${fmtClock(t0)} · Darkbloom exact from the ledger, mining modeled at the verified rate",
            )
            Spacer(Modifier.height(10.dp))
            Sparkline(combined, Muse.Emerald, heightDp = 84,
                description = "Combined tracked earnings graph")
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Combined " + usdD(combined.lastOrNull()?.toDouble()),
                    style = MaterialTheme.typography.bodySmall, color = Muse.Emerald)
                Text("Darkbloom " + usdD((series.last().totalMicroUsd - base) / 1e6),
                    style = MaterialTheme.typography.bodySmall, color = Muse.Sky)
                Text("Mining — Batman-3 (modeled) " + usdD((series.last().tsSec - t0) * mineDay / 86400.0),
                    style = MaterialTheme.typography.bodySmall, color = Muse.Warn)
            }
        }
    }
}
