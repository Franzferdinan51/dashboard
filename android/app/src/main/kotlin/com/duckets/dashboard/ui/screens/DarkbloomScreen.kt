package com.duckets.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MeterBar
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.StatusChip
import com.duckets.dashboard.ui.fmtCount
import com.duckets.dashboard.ui.fmtDuration
import com.duckets.dashboard.ui.fmtGb
import com.duckets.dashboard.ui.fmtLedgerTime
import com.duckets.dashboard.ui.fmtMicroUsd
import com.duckets.dashboard.ui.fmtUsd
import com.duckets.dashboard.ui.shortModel
import com.duckets.dashboard.vm.DashboardUiState

/** Darkbloom detail: earnings ledger, trust/attestation, models, GPU capacity, autopilot. */
@Composable
fun DarkbloomScreen(state: DashboardUiState, modifier: Modifier = Modifier) {
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
            val db = ov.darkbloom
            val e = db.earnings

            item {
                MuseCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        CardHeader("Earnings", subtitle = "USD via Stripe · $1 min withdrawal · 0% fee") {
                            when {
                                db.earning -> StatusChip("EARNING", Muse.Good)
                                db.running -> StatusChip("NOT EARNING YET", Muse.Warn)
                                else -> StatusChip("STOPPED", Muse.Bad)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            fmtUsd(e?.balanceUsd),
                            style = MaterialTheme.typography.headlineLarge,
                            color = Muse.Mist,
                        )
                        Text("available balance", style = MaterialTheme.typography.bodySmall, color = Muse.TextDim)
                        Spacer(Modifier.height(10.dp))
                        KeyValueRow("Withdrawable", fmtUsd(e?.withdrawableUsd))
                        KeyValueRow("Lifetime total", fmtUsd(e?.totalUsd))
                        KeyValueRow("Paid jobs (recent history)", fmtCount(e?.jobsInHistory?.toLong()))
                        KeyValueRow("Base rewards (recent)", fmtMicroUsd(e?.baseRewardsMicroRecent ?: 0))
                        KeyValueRow("Requests served (session)", fmtCount(db.requestsServed))
                        KeyValueRow("Tokens generated (session)", fmtCount(db.tokensGenerated))
                    }
                }
            }

            if (e != null && e.perModel.isNotEmpty()) {
                item {
                    MuseCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            CardHeader("Earnings by model", subtitle = "recent ledger window")
                            Spacer(Modifier.height(10.dp))
                            val maxMicro = e.perModel.maxOf { it.microUsd }.coerceAtLeast(1)
                            e.perModel.forEach { pm ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (pm.model == "base_reward") "Base reward (online floor)" else shortModel(pm.model),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Muse.Mist,
                                        )
                                        Text(
                                            "${pm.jobs} jobs",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Muse.TextFaint,
                                        )
                                    }
                                    Text(fmtMicroUsd(pm.microUsd), style = MaterialTheme.typography.titleMedium, color = Muse.Good)
                                }
                                MeterBar((pm.microUsd.toFloat() / maxMicro), Muse.Good)
                            }
                        }
                    }
                }
            }

            if (e != null && e.recent.isNotEmpty()) {
                item {
                    MuseCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            CardHeader("Recent jobs")
                            Spacer(Modifier.height(8.dp))
                            e.recent.forEach { entry ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (entry.model == "base_reward") "Base reward" else shortModel(entry.model),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Muse.Mist,
                                        )
                                        Text(
                                            listOfNotNull(
                                                entry.promptTokens?.let { "in ${fmtCount(it)}" },
                                                entry.completionTokens?.let { "out ${fmtCount(it)}" },
                                                fmtLedgerTime(entry.createdAt).takeIf { it.isNotBlank() },
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Muse.TextFaint,
                                        )
                                    }
                                    Text(
                                        fmtUsd(entry.amountUsd.toString()),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Muse.Good,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                MuseCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        CardHeader("Provider", subtitle = "Eigen Labs decentralized inference") {
                            StatusChip(
                                (db.trustStatus ?: if (db.running) "online" else "offline").uppercase(),
                                if (db.running) Muse.Good else Muse.Bad,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        KeyValueRow("Version", db.version ?: "—")
                        KeyValueRow("Process", db.pid?.let { "pid $it" } ?: "—")
                        KeyValueRow("Uptime", fmtDuration(db.uptimeSeconds))
                        KeyValueRow("State", db.lifecycle ?: "—")
                        KeyValueRow("Currently serving", db.currentModel?.let { shortModel(it) } ?: "—")
                        Spacer(Modifier.height(12.dp))
                        CardHeader("Trust & attestation")
                        Spacer(Modifier.height(8.dp))
                        KeyValueRow("Trust level", db.trustLevel ?: "—")
                        KeyValueRow("Reason", db.trustReason ?: "—")
                        KeyValueRow("Path", db.trustPath ?: "—")
                        KeyValueRow("App Attest", when (db.attestAttested) {
                            true -> "attested ✓"
                            false -> "not attested"
                            null -> "—"
                        })
                        KeyValueRow("Attest key age", fmtDuration(db.attestKeyAgeSeconds))
                        KeyValueRow("Last Apple success", db.attestLastSuccessAgeSeconds?.let { fmtDuration(it) + " ago" } ?: "—")
                        if (db.verificationPending) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Serving verification is still pending on Darkbloom's coordinator. " +
                                    "Paid jobs and base rewards are landing anyway — the ledger is the ground truth.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muse.Warn,
                            )
                        }
                    }
                }
            }

            item {
                MuseCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        CardHeader("Models", subtitle = "${db.warmModels.size} warm of ${db.advertisedModels.size} advertised")
                        Spacer(Modifier.height(10.dp))
                        db.residentModels.forEach { r ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        shortModel(r.model),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Muse.Mist,
                                        modifier = Modifier.weight(1f),
                                    )
                                    StatusChip("WARM", Muse.Good)
                                }
                                Spacer(Modifier.height(4.dp))
                                KeyValueRow("Resident", fmtGb(r.residentGb, 1) + " (weights ${fmtGb(r.weightsGb, 1)})")
                                KeyValueRow("Idle", fmtDuration(r.idleSeconds))
                                val slot = db.slots.firstOrNull { it.model == r.model }
                                KeyValueRow(
                                    "Engine",
                                    listOfNotNull(
                                        slot?.kvBackend?.takeIf { it.isNotBlank() }?.let { "KV $it" },
                                        if (slot?.mtpActive == true) "MTP on" else if (slot?.mtpEnabled == true) "MTP idle" else null,
                                    ).joinToString(" · ").ifBlank { "—" },
                                )
                            }
                        }
                        val notWarm = db.advertisedModels.filter { it !in db.warmModels }
                        notWarm.forEach { m ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(shortModel(m), style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim, modifier = Modifier.weight(1f))
                                StatusChip("COLD", Muse.TextDim)
                            }
                        }
                    }
                }
            }

            item {
                MuseCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        CardHeader("GPU capacity", subtitle = "unified memory pool")
                        Spacer(Modifier.height(12.dp))
                        val total = db.capacityTotalGb ?: 128.0
                        val active = db.gpuActiveGb ?: 0.0
                        MeterBar((active / total).toFloat(), Muse.Cyan)
                        Spacer(Modifier.height(10.dp))
                        KeyValueRow("Active", fmtGb(db.gpuActiveGb))
                        KeyValueRow("Cache", fmtGb(db.gpuCacheGb, 2))
                        KeyValueRow("Free for new loads", fmtGb(db.freeForLoadGb))
                        KeyValueRow("Total", fmtGb(db.capacityTotalGb, 0))
                        Spacer(Modifier.height(12.dp))
                        CardHeader("Autopilot")
                        Spacer(Modifier.height(8.dp))
                        KeyValueRow("Phase", db.autopilotPhase ?: "—")
                        KeyValueRow("Enabled", when (db.autopilotEnabled) { true -> "yes"; false -> "no"; null -> "—" })
                        KeyValueRow("Mode", if (db.autopilotObserveOnly == true) "shadow / observe only" else "active")
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}
