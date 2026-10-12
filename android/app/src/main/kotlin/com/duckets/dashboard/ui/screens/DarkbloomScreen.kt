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
import androidx.compose.foundation.shape.CircleShape
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
import com.duckets.dashboard.data.DarkbloomStats
import com.duckets.dashboard.data.Earnings
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.CardDivider
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MeterBar
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.SkeletonCard
import com.duckets.dashboard.ui.components.StatusChip
import com.duckets.dashboard.ui.fmtCount
import com.duckets.dashboard.ui.fmtDuration
import com.duckets.dashboard.ui.fmtGb
import com.duckets.dashboard.ui.fmtLedgerTime
import com.duckets.dashboard.ui.fmtMicroUsd
import com.duckets.dashboard.ui.fmtUsd
import com.duckets.dashboard.ui.shortModel
import com.duckets.dashboard.vm.DashboardUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DarkbloomScreen(state: DashboardUiState, columns: Int = 1, onRefresh: () -> Unit = {}, modifier: Modifier = Modifier) {
    val ov = state.overview
    val hPad = if (columns >= 2) 24.dp else 16.dp
    val maxW = if (columns >= 2) 1080.dp else 720.dp
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxSize().widthIn(max = maxW).padding(horizontal = hPad),
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
                    val db = ov.darkbloom
                    val e = db.earnings
                    item { EarningsCard(db, e) }
                    if (columns >= 2) {
                        if (e != null && (e.perModel.isNotEmpty() || e.recent.isNotEmpty())) {
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Column(Modifier.weight(1f)) { if (e.perModel.isNotEmpty()) PerModelCard(e) }
                                    Column(Modifier.weight(1f)) { if (e.recent.isNotEmpty()) RecentJobsCard(e) }
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Column(Modifier.weight(1f)) { ProviderCard(db) }
                                Column(Modifier.weight(1f)) { ModelsCard(db) }
                            }
                        }
                    } else {
                        if (e != null && e.perModel.isNotEmpty()) item { PerModelCard(e) }
                        if (e != null && e.recent.isNotEmpty()) item { RecentJobsCard(e) }
                        item { ProviderCard(db) }
                        item { ModelsCard(db) }
                    }
                    item { GpuCard(db) }
                    item { Spacer(Modifier.height(10.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EarningsCard(db: DarkbloomStats, e: Earnings?) {
    MuseCard(Modifier.fillMaxWidth(), hero = true) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Earnings", subtitle = "USD via Stripe · $1 minimum withdrawal · 0% fee") {
                when {
                    db.earning -> StatusChip("EARNING", Muse.Good, pulse = true)
                    db.running -> StatusChip("NOT EARNING YET", Muse.Warn)
                    else -> StatusChip("STOPPED", Muse.Bad)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(fmtUsd(e?.balanceUsd), style = MaterialTheme.typography.headlineLarge, color = Muse.Mist)
            Text("available balance", style = MaterialTheme.typography.bodySmall, color = Muse.TextDim)
            // Progress toward the $1 withdrawal threshold.
            val bal = e?.balanceUsd?.toDoubleOrNull() ?: 0.0
            Spacer(Modifier.height(10.dp))
            MeterBar((bal / 1.0).toFloat().coerceIn(0f, 1f), Muse.Emerald, heightDp = 8)
            Text(
                if (bal >= 1.0) "Above the $1 withdrawal minimum — ready to withdraw."
                else "${fmtUsd((1.0 - bal).toString())} to the $1 withdrawal minimum",
                style = MaterialTheme.typography.bodySmall,
                color = if (bal >= 1.0) Muse.Good else Muse.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            KeyValueRow("Withdrawable", fmtUsd(e?.withdrawableUsd))
            KeyValueRow("Lifetime total", fmtUsd(e?.totalUsd))
            KeyValueRow("Paid jobs (recent history)", fmtCount(e?.jobsInHistory?.toLong()))
            KeyValueRow("Base rewards (recent)", fmtMicroUsd(e?.baseRewardsMicroRecent ?: 0))
            KeyValueRow("Requests served (session)", fmtCount(db.requestsServed))
            KeyValueRow("Tokens generated (session)", fmtCount(db.tokensGenerated))
        }
    }
}

@Composable
private fun PerModelCard(e: Earnings) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Earnings by model", subtitle = "recent ledger window")
            Spacer(Modifier.height(10.dp))
            val maxMicro = e.perModel.maxOf { it.microUsd }.coerceAtLeast(1)
            e.perModel.forEach { pm ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (pm.model == "base_reward") "Base reward (online floor)" else shortModel(pm.model),
                            style = MaterialTheme.typography.bodyMedium, color = Muse.Mist,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pm.jobs} jobs", style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint)
                    }
                    Text(fmtMicroUsd(pm.microUsd), style = MaterialTheme.typography.titleMedium, color = Muse.Good)
                }
                MeterBar((pm.microUsd.toFloat() / maxMicro), Muse.Emerald, heightDp = 6)
            }
        }
    }
}

@Composable
private fun RecentJobsCard(e: Earnings) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Recent jobs")
            Spacer(Modifier.height(8.dp))
            e.recent.forEachIndexed { i, entry ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Muse.Emerald))
                        if (i < e.recent.lastIndex) Box(Modifier.width(2.dp).height(22.dp).background(Muse.Border))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (entry.model == "base_reward") "Base reward" else shortModel(entry.model),
                            style = MaterialTheme.typography.bodyMedium, color = Muse.Mist,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(
                            entry.promptTokens?.let { "in ${fmtCount(it)}" },
                            entry.completionTokens?.let { "out ${fmtCount(it)}" },
                            fmtLedgerTime(entry.createdAt).takeIf { it.isNotBlank() },
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(fmtUsd(entry.amountUsd.toString()), style = MaterialTheme.typography.bodyMedium, color = Muse.Good)
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(db: DarkbloomStats) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Provider", subtitle = "Eigen Labs decentralized inference") {
                StatusChip((db.trustStatus ?: if (db.running) "online" else "offline").uppercase(),
                    if (db.running) Muse.Good else Muse.Bad)
            }
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Version", db.version ?: "—")
            KeyValueRow("Process", db.pid?.let { "pid $it" } ?: "—")
            KeyValueRow("Uptime", fmtDuration(db.uptimeSeconds))
            KeyValueRow("State", db.lifecycle ?: "—")
            KeyValueRow("Currently serving", db.currentModel?.let { shortModel(it) } ?: "—")
            CardDivider()
            CardHeader("Trust & attestation")
            Spacer(Modifier.height(8.dp))
            KeyValueRow("Trust level", db.trustLevel ?: "—")
            KeyValueRow("Reason", db.trustReason ?: "—")
            KeyValueRow("Path", db.trustPath ?: "—")
            KeyValueRow("App Attest", when (db.attestAttested) { true -> "attested ✓"; false -> "not attested"; null -> "—" })
            KeyValueRow("Attest key age", fmtDuration(db.attestKeyAgeSeconds))
            KeyValueRow("Last Apple success", db.attestLastSuccessAgeSeconds?.let { fmtDuration(it) + " ago" } ?: "—")
            if (db.verificationPending) {
                Spacer(Modifier.height(6.dp))
                Text("Verification is still pending on Darkbloom's coordinator. Paid jobs and base rewards land anyway — the ledger is the ground truth.",
                    style = MaterialTheme.typography.bodySmall, color = Muse.Warn)
            }
        }
    }
}

@Composable
private fun ModelsCard(db: DarkbloomStats) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("Models", subtitle = "${db.warmModels.size} warm of ${db.advertisedModels.size} advertised")
            Spacer(Modifier.height(10.dp))
            if (db.residentModels.isEmpty() && db.advertisedModels.isEmpty()) {
                Text("No model data from the provider yet.", style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
            }
            db.residentModels.forEach { r ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(shortModel(r.model), style = MaterialTheme.typography.titleSmall, color = Muse.Mist,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StatusChip("WARM", Muse.Good)
                    }
                    Spacer(Modifier.height(4.dp))
                    KeyValueRow("Resident", fmtGb(r.residentGb, 1) + " (weights ${fmtGb(r.weightsGb, 1)})")
                    KeyValueRow("Idle", fmtDuration(r.idleSeconds))
                    val slot = db.slots.firstOrNull { it.model == r.model }
                    KeyValueRow("Engine", listOfNotNull(
                        slot?.kvBackend?.takeIf { it.isNotBlank() }?.let { "KV $it" },
                        if (slot?.mtpActive == true) "MTP on" else if (slot?.mtpEnabled == true) "MTP idle" else null,
                    ).joinToString(" · ").ifBlank { "—" })
                }
            }
            db.advertisedModels.filter { it !in db.warmModels }.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(shortModel(m), style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusChip("COLD", Muse.TextDim)
                }
            }
        }
    }
}

@Composable
private fun GpuCard(db: DarkbloomStats) {
    MuseCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            CardHeader("GPU capacity", subtitle = "unified memory pool")
            Spacer(Modifier.height(12.dp))
            val total = db.capacityTotalGb ?: 128.0
            val active = db.gpuActiveGb ?: 0.0
            MeterBar(if (total > 0) (active / total).toFloat() else 0f, Muse.Emerald, heightDp = 10)
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Active", fmtGb(db.gpuActiveGb))
            KeyValueRow("Cache", fmtGb(db.gpuCacheGb, 2))
            KeyValueRow("Free for new loads", fmtGb(db.freeForLoadGb))
            KeyValueRow("Total", fmtGb(db.capacityTotalGb, 0))
            CardDivider()
            CardHeader("Autopilot")
            Spacer(Modifier.height(8.dp))
            KeyValueRow("Phase", db.autopilotPhase ?: "—")
            KeyValueRow("Enabled", when (db.autopilotEnabled) { true -> "yes"; false -> "no"; null -> "—" })
            KeyValueRow("Mode", if (db.autopilotObserveOnly == true) "shadow / observe only" else "active")
        }
    }
}
