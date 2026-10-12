package com.duckets.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.StatusChip
import com.duckets.dashboard.vm.DashboardUiState
import com.duckets.dashboard.vm.DashboardViewModel

@Composable
fun SettingsScreen(
    state: DashboardUiState,
    vm: DashboardViewModel,
    modifier: Modifier = Modifier,
) {
    var urlDraft by remember(state.bridgeUrl) { mutableStateOf(state.bridgeUrl) }
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            MuseCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    CardHeader("Connection") {
                        if (state.connected) StatusChip("CONNECTED", Muse.Good)
                        else StatusChip("UNREACHABLE", Muse.Bad)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Bridge address (the dashboard bridge running on the Mac). " +
                            "Use your Mac's address on your LAN or tailnet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muse.TextDim,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        label = { Text("Bridge URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Muse.Blue,
                            unfocusedBorderColor = Muse.Border,
                            focusedLabelColor = Muse.BlueSoft,
                            unfocusedLabelColor = Muse.TextDim,
                            focusedTextColor = Muse.Mist,
                            unfocusedTextColor = Muse.Mist,
                            cursorColor = Muse.Blue,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.setBridgeUrl(urlDraft) },
                            colors = ButtonDefaults.buttonColors(containerColor = Muse.Blue),
                            shape = Muse.CardShape,
                        ) { Text("Save & reconnect") }
                        Button(
                            onClick = { urlDraft = DashboardUiState.DEFAULT_BRIDGE_URL },
                            colors = ButtonDefaults.buttonColors(containerColor = Muse.CardAlt),
                            shape = Muse.CardShape,
                        ) { Text("Reset default") }
                    }
                    if (state.lastError != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(state.lastError, style = MaterialTheme.typography.bodySmall, color = Muse.Bad)
                    }
                }
            }
        }
        item {
            MuseCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    CardHeader("Refresh")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Poll every ${state.refreshSeconds}s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muse.Mist,
                    )
                    Slider(
                        value = state.refreshSeconds.toFloat(),
                        onValueChange = { vm.setRefreshSeconds(it.toInt()) },
                        valueRange = 2f..30f,
                        steps = 13,
                        colors = SliderDefaults.colors(
                            thumbColor = Muse.Blue,
                            activeTrackColor = Muse.Blue,
                            inactiveTrackColor = Muse.CardAlt,
                        ),
                    )
                    Text(
                        "Shorter intervals update the gauges faster but cost a little battery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muse.TextFaint,
                    )
                }
            }
        }
        item {
            MuseCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    CardHeader("About")
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow("App", "Mac Studio Dashboard v1.0.0")
                    KeyValueRow("Bridge", "dashboard-bridge (Python, on the Mac)")
                    KeyValueRow("Data", "System vitals · Darkbloom · LM Studio")
                    KeyValueRow("Privacy", "Read-only. Nothing leaves your tailnet/LAN.")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Earnings figures come from Darkbloom's account ledger — the same " +
                            "numbers behind the web console. Estimates (like the earnings " +
                            "calculator) are not shown here; only settled data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muse.TextFaint,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}
