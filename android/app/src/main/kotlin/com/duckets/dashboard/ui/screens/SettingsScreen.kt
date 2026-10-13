package com.duckets.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.duckets.dashboard.BuildConfig
import com.duckets.dashboard.theme.Muse
import com.duckets.dashboard.ui.bridgeUrlError
import com.duckets.dashboard.ui.components.CardHeader
import com.duckets.dashboard.ui.components.KeyValueRow
import com.duckets.dashboard.ui.components.MuseCard
import com.duckets.dashboard.ui.components.StatusChip
import com.duckets.dashboard.ui.fmtDuration
import com.duckets.dashboard.ui.fmtFreshness
import com.duckets.dashboard.vm.DashboardUiState
import com.duckets.dashboard.vm.DashboardViewModel
import com.duckets.dashboard.vm.TestState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: DashboardUiState, vm: DashboardViewModel, columns: Int = 1, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState()) {
    // Saveable: an unsaved draft must survive rotation and tab switches.
    var urlDraft by rememberSaveable(state.bridgeUrl) { mutableStateOf(state.bridgeUrl) }
    val urlError = bridgeUrlError(urlDraft)
    val dirty = urlDraft.trim().trimEnd('/') != state.bridgeUrl
    // Settings reads best narrow: cap at 640dp and center on tablets.
    val maxW = if (columns >= 2) 640.dp else 720.dp
    val hPad = if (columns >= 2) 24.dp else 16.dp
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.fillMaxSize().widthIn(max = maxW).padding(horizontal = hPad),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                MuseCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        CardHeader("Connection") {
                            if (state.connected) StatusChip("CONNECTED", Muse.Good, pulse = true)
                            else StatusChip("UNREACHABLE", Muse.Bad)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (state.connected) {
                            Text(listOfNotNull(
                                fmtFreshness(state.lastUpdatedMillis),
                                state.latencyMs?.let { "$it ms" },
                                "polls every ${state.refreshSeconds}s",
                            ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = Muse.TextDim)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Bridge address (the dashboard bridge running on the Mac). Use the Mac's LAN or tailnet address.",
                            style = MaterialTheme.typography.bodyMedium, color = Muse.TextDim)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = urlDraft, onValueChange = { urlDraft = it; vm.clearTestState() },
                            label = { Text("Bridge URL") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                autoCorrectEnabled = false,
                                capitalization = KeyboardCapitalization.None,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { if (urlError == null) vm.testConnection(urlDraft) },
                            ),
                            isError = urlError != null && urlDraft.isNotBlank(),
                            supportingText = {
                                when {
                                    urlError != null && urlDraft.isNotBlank() -> Text(urlError, color = Muse.Bad)
                                    dirty -> Text("Unsaved — tap Save & reconnect, or Test first.", color = Muse.Warn)
                                    else -> Text("Example: http://192.168.1.50:8901", color = Muse.TextFaint)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Muse.Blue, unfocusedBorderColor = Muse.Border,
                                focusedLabelColor = Muse.BlueSoft, unfocusedLabelColor = Muse.TextDim,
                                focusedTextColor = Muse.Mist, unfocusedTextColor = Muse.Mist, cursorColor = Muse.Blue,
                                errorBorderColor = Muse.Bad, errorLabelColor = Muse.Bad,
                            ),
                        )
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { vm.setBridgeUrl(urlDraft) }, enabled = urlError == null,
                                colors = ButtonDefaults.buttonColors(containerColor = Muse.Blue), shape = Muse.CardShape) {
                                Text("Save & reconnect")
                            }
                            OutlinedButton(onClick = { vm.testConnection(urlDraft) }, enabled = urlError == null,
                                shape = Muse.CardShape) { Text("Test", color = Muse.BlueSoft) }
                            OutlinedButton(onClick = { urlDraft = DashboardUiState.DEFAULT_BRIDGE_URL; vm.clearTestState() },
                                shape = Muse.CardShape) { Text("Reset", color = Muse.TextDim) }
                        }
                        when (val ts = state.testState) {
                            is TestState.Testing -> Text("Testing ${urlDraft.trim()} …",
                                style = MaterialTheme.typography.bodySmall, color = Muse.TextDim,
                                modifier = Modifier.padding(top = 10.dp))
                            is TestState.Ok -> Text("Reachable in ${ts.latencyMs} ms${ts.version?.let { " · bridge v$it" } ?: ""}.",
                                style = MaterialTheme.typography.bodySmall, color = Muse.Good,
                                modifier = Modifier.padding(top = 10.dp))
                            is TestState.Failed -> Text(ts.message, style = MaterialTheme.typography.bodySmall,
                                color = Muse.Bad, modifier = Modifier.padding(top = 10.dp))
                            TestState.Idle -> if (state.lastError != null && !state.connected) {
                                Text(state.lastError, style = MaterialTheme.typography.bodySmall, color = Muse.Bad,
                                    modifier = Modifier.padding(top = 10.dp))
                            }
                        }
                    }
                }
            }
            item {
                MuseCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        CardHeader("Refresh")
                        Spacer(Modifier.height(8.dp))
                        Text("Poll every ${state.refreshSeconds}s", style = MaterialTheme.typography.bodyMedium, color = Muse.Mist)
                        Slider(value = state.refreshSeconds.toFloat(),
                            onValueChange = { vm.setRefreshSeconds(it.toInt()) },
                            valueRange = DashboardUiState.MIN_REFRESH_SECONDS.toFloat()..DashboardUiState.MAX_REFRESH_SECONDS.toFloat(),
                            colors = SliderDefaults.colors(thumbColor = Muse.Blue, activeTrackColor = Muse.Blue,
                                inactiveTrackColor = Muse.CardAlt))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(2, 5, 10, 30).forEach { s ->
                                FilterChip(selected = state.refreshSeconds == s, onClick = { vm.setRefreshSeconds(s) },
                                    label = { Text("${s}s") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Muse.Blue, selectedLabelColor = Muse.Mist,
                                        containerColor = Muse.CardAlt, labelColor = Muse.TextDim))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Shorter intervals update faster but cost battery and Mac wake time. 5s suits a glanceable wall display.",
                            style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint)
                    }
                }
            }
            item {
                MuseCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        CardHeader("About")
                        Spacer(Modifier.height(10.dp))
                        KeyValueRow("App", "Mac Studio Dashboard v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                        KeyValueRow("Bridge version", (state.overview?.bridgeVersion ?: state.actions.data?.bridgeVersion)?.let { "v$it" } ?: "—")
                        KeyValueRow("Bridge uptime", state.overview?.bridgeUptimeSeconds?.let { fmtDuration(it) } ?: "—")
                        KeyValueRow("Bridge URL", state.bridgeUrl)
                        KeyValueRow("Data", "System vitals · Darkbloom · LM Studio")
                        KeyValueRow("Layout", if (columns >= 2) "Tablet (rail, 2 columns)" else "Phone (bottom nav)")
                        KeyValueRow("Privacy", "Talks only to your bridge on your tailnet/LAN. The Actions tab can run commands on your own machines.")
                        Spacer(Modifier.height(8.dp))
                        Text("Earnings come from Darkbloom's account ledger — the same numbers behind the web console. Only settled data is shown; estimates are not.",
                            style = MaterialTheme.typography.bodySmall, color = Muse.TextFaint)
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}
