package com.duckets.dashboard.vm

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.duckets.dashboard.data.ActionsData
import com.duckets.dashboard.data.DashboardRepository
import com.duckets.dashboard.data.Overview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DashboardUiState(
    val overview: Overview? = null,
    val mining: com.duckets.dashboard.data.MiningData? = null,
    val windows: com.duckets.dashboard.data.WindowsData? = null,
    /** Side panels on the same slow cadence as mining/windows: the
     * decisions event log, CannaAI, SystemOne (bridge 1.11+). A failed
     * or absent endpoint leaves the last value (or null) untouched. */
    val decisions: List<com.duckets.dashboard.data.DecisionEvent> = emptyList(),
    val cannaai: com.duckets.dashboard.data.CannaAiData? = null,
    val systemone: com.duckets.dashboard.data.SystemOneData? = null,
    val connected: Boolean = false,
    val everConnected: Boolean = false,
    val lastError: String? = null,
    val lastUpdatedMillis: Long = 0L,
    /** The bridge's own generation timestamp of the current sample (ms). */
    val dataTimestampMillis: Long = 0L,
    val bridgeUrl: String = DEFAULT_BRIDGE_URL,
    val refreshSeconds: Int = DEFAULT_REFRESH_SECONDS,
    val isRefreshing: Boolean = false,
    /** True only while an explicit refresh (pull-to-refresh, header
     * button, retry) is in flight — background polls never set it, so
     * the pull-to-refresh indicator doesn't flash on every poll. */
    val isPullRefreshing: Boolean = false,
    val latencyMs: Long? = null,
    val testState: TestState = TestState.Idle,
    val actions: ActionsUiState = ActionsUiState(),
    val cpuHistory: List<Float> = emptyList(),
    val ramHistory: List<Float> = emptyList(),
    val gpuHistory: List<Float> = emptyList(),
    val netInHistory: List<Float> = emptyList(),
    val netOutHistory: List<Float> = emptyList(),
) {
    companion object {
        const val DEFAULT_BRIDGE_URL = com.duckets.dashboard.BuildConfig.DEFAULT_BRIDGE_URL
        const val DEFAULT_REFRESH_SECONDS = 5
        const val MIN_REFRESH_SECONDS = 2
        const val MAX_REFRESH_SECONDS = 30
    }
}

/** Actions tab state: fetched on open (never on the stats poll — the
 * bridge's control surface changes only when the user acts). */
data class ActionsUiState(
    val data: ActionsData? = null,
    val isLoading: Boolean = false,
    /** Fetch failure with nothing on screen yet; later failures are
     * silent and the sections keep their last known state. */
    val loadError: String? = null,
    /** The action currently running — one at a time, app-wide. */
    val inFlight: InFlightAction? = null,
    /** Last outcome per section ("lmstudio" / "darkbloom" / "mining" /
     * "bridge"), shown inline until the next refresh or action. */
    val results: Map<String, ActionOutcome> = emptyMap(),
)

data class InFlightAction(val action: String, val target: String? = null)

data class ActionOutcome(val message: String, val ok: Boolean)

sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Ok(val latencyMs: Long, val version: String?) : TestState
    data class Failed(val message: String) : TestState
}

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("dashboard", Context.MODE_PRIVATE)
    private val repo = DashboardRepository()

    private val _state = MutableStateFlow(
        DashboardUiState(
            bridgeUrl = prefs.getString("bridge_url", DashboardUiState.DEFAULT_BRIDGE_URL)
                ?: DashboardUiState.DEFAULT_BRIDGE_URL,
            refreshSeconds = prefs.getInt("refresh_seconds", DashboardUiState.DEFAULT_REFRESH_SECONDS)
                .coerceIn(DashboardUiState.MIN_REFRESH_SECONDS, DashboardUiState.MAX_REFRESH_SECONDS),
        )
    )
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var testJob: Job? = null
    private var consecutiveFailures = 0
    /** Bumped on every bridge-URL change. Fetches and actions capture it
     * at launch and refuse to write state if it has moved on — an
     * in-flight request to the OLD bridge must never land on the new
     * one's screen (the repo only cancels on coroutine cancellation,
     * and not every caller is cancelled by a URL switch). */
    private var urlGeneration = 0
    private var lastSideFetchMs = 0L
    @Volatile private var pendingRefresh = false

    init { startPolling() }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                // Anchor the cadence to the START of the fetch: the old
                // loop delayed a full interval AFTER each fetch finished,
                // so the real period was fetch-time + interval (a 1.5s
                // fetch turned a 5s setting into 6.5s updates — visible lag).
                val cycleStart = System.currentTimeMillis()
                refreshOnce()
                if (pendingRefresh) {
                    // A manual refresh arrived mid-flight; honor it now
                    // instead of dropping it on the floor.
                    pendingRefresh = false
                    continue
                }
                val intervalMs = _state.value.refreshSeconds
                    .coerceIn(DashboardUiState.MIN_REFRESH_SECONDS, DashboardUiState.MAX_REFRESH_SECONDS) * 1000L
                val remaining = intervalMs - (System.currentTimeMillis() - cycleStart)
                if (remaining > 0) delay(remaining)
            }
        }
    }

    /** Polling only runs while the UI is visible (driven from DashboardRoot's
     * lifecycle) so the app costs no battery or network in the background. */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun refreshOnce() {
        if (_state.value.isRefreshing) {
            pendingRefresh = true
            return
        }
        val url = _state.value.bridgeUrl
        val gen = urlGeneration
        _state.update { it.copy(isRefreshing = true) }
        val started = System.currentTimeMillis()
        try {
            val ov = repo.fetchOverview(url)
            val latency = System.currentTimeMillis() - started
            if (gen == urlGeneration) consecutiveFailures = 0
            _state.update { s ->
                if (gen != urlGeneration) {
                    // The URL changed mid-flight: this sample belongs to
                    // the old bridge — drop it, just release the flag.
                    s.copy(isRefreshing = false)
                } else {
                    val gpuPct = ov.darkbloom.capacityTotalGb?.let { total ->
                        ov.darkbloom.gpuActiveGb?.let { active ->
                            if (total > 0) (active / total * 100).toFloat() else 0f
                        }
                    } ?: 0f
                    fun appendHist(old: List<Float>, v: Double): List<Float> =
                        if (v.isFinite()) (old + v.toFloat()).takeLast(90) else old
                    s.copy(
                        overview = ov, connected = true, everConnected = true,
                        lastError = null, isRefreshing = false, latencyMs = latency,
                        lastUpdatedMillis = System.currentTimeMillis(),
                        dataTimestampMillis = (ov.generatedAt * 1000).toLong(),
                        cpuHistory = appendHist(s.cpuHistory, ov.system.cpu.usedPct),
                        ramHistory = appendHist(s.ramHistory, ov.system.memory.usedPct),
                        gpuHistory = appendHist(s.gpuHistory, gpuPct.toDouble()),
                        netInHistory = appendHist(s.netInHistory, ov.system.network.rateInBps.toDouble()),
                        netOutHistory = appendHist(s.netOutHistory, ov.system.network.rateOutBps.toDouble()),
                    )
                }
            }
            // Mining + Windows ride a slower cadence (~30s): both are
            // SWR-cached on the bridge, so this mostly costs a local
            // round-trip. Failures here never touch the main state — the
            // tabs just keep their last data.
            val nowMs = System.currentTimeMillis()
            if (gen == urlGeneration && nowMs - lastSideFetchMs > 25_000L) {
                lastSideFetchMs = nowMs
                val mining = runCatching { repo.fetchMining(url) }.getOrNull()
                val windows = runCatching { repo.fetchWindows(url) }.getOrNull()
                val decisions = runCatching { repo.fetchDecisions(url) }.getOrNull()
                val cannaai = runCatching { repo.fetchCannaAi(url) }.getOrNull()
                val systemone = runCatching { repo.fetchSystemOne(url) }.getOrNull()
                if ((mining != null || windows != null || decisions != null ||
                        cannaai != null || systemone != null) && gen == urlGeneration
                ) {
                    _state.update {
                        it.copy(
                            mining = mining ?: it.mining,
                            windows = windows ?: it.windows,
                            decisions = decisions ?: it.decisions,
                            cannaai = cannaai ?: it.cannaai,
                            systemone = systemone ?: it.systemone,
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            // Never swallow cancellation: a dying job writing error state here
            // could land after its replacement's first success and flip the
            // UI back to OFFLINE with a stale error.
            _state.update { it.copy(isRefreshing = false) }
            throw e
        } catch (e: Exception) {
            if (gen != urlGeneration) {
                // A failure from the old bridge must not mark the new one
                // offline, burn one of its two strikes, or leave its error
                // on screen.
                _state.update { it.copy(isRefreshing = false) }
            } else {
                consecutiveFailures++
                _state.update {
                    it.copy(
                        // Two-strike debounce: a single flaky poll (slow bridge,
                        // network hiccup) must not flap every chip to OFFLINE;
                        // the second consecutive failure declares it for real.
                        connected = if (consecutiveFailures >= 2) false else it.connected,
                        isRefreshing = false,
                        lastError = friendlyError(e),
                    )
                }
            }
        }
    }

    /** Explicit refresh (pull-to-refresh, header button, retry banner,
     * reconnect): the only path that lights the pull-to-refresh
     * indicator. Background polls call refreshOnce directly. */
    fun refreshNow() {
        _state.update { it.copy(isPullRefreshing = true) }
        viewModelScope.launch {
            try {
                refreshOnce()
            } finally {
                _state.update { it.copy(isPullRefreshing = false) }
            }
        }
    }

    fun refreshActions() {
        viewModelScope.launch {
            _state.update { it.copy(actions = it.actions.copy(isLoading = true)) }
            try {
                val data = repo.fetchActions(_state.value.bridgeUrl)
                _state.update {
                    it.copy(actions = it.actions.copy(data = data, isLoading = false, loadError = null))
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(actions = it.actions.copy(isLoading = false)) }
                throw e
            } catch (e: Exception) {
                _state.update {
                    // With data on screen a failed refresh stays silent —
                    // the sections keep their last known state, the same
                    // rule the Mining/Windows side fetches follow.
                    it.copy(
                        actions = it.actions.copy(
                            isLoading = false,
                            loadError = if (it.actions.data == null) friendlyError(e) else it.actions.loadError,
                        )
                    )
                }
            }
        }
    }

    /**
     * Fire one bridge action (already user-confirmed in the UI). One at a
     * time: a second tap while one runs is ignored, because actions like
     * model loads take minutes and stacking them helps no one. The
     * bridge's reply (or the failure) lands in the section's result slot,
     * then the actions state refreshes to reflect the new reality.
     */
    fun performAction(action: String, target: String? = null) {
        val section = when (action) {
            "lm_load", "lm_unload" -> "lmstudio"
            "darkbloom_restart" -> "darkbloom"
            "miner_start", "miner_stop" -> "mining"
            "bridge_restart" -> "bridge"
            else -> return
        }
        // Claim the one-at-a-time slot synchronously, before launching:
        // when inFlight was set inside the coroutine, a fast double-tap
        // on the confirm button could pass the null check twice and
        // fire the action twice (two miner starts, two loads).
        var claimed = false
        _state.update {
            if (it.actions.inFlight != null) {
                it
            } else {
                claimed = true
                it.copy(
                    actions = it.actions.copy(
                        inFlight = InFlightAction(action, target),
                        results = it.actions.results - section,
                    )
                )
            }
        }
        if (!claimed) return
        // Capture the destination now: the POST must go to the bridge the
        // user was looking at, and its outcome must not be written into
        // a different bridge's state if the URL changes mid-action.
        val url = _state.value.bridgeUrl
        val gen = urlGeneration
        viewModelScope.launch {
            try {
                val result = repo.postAction(url, action, target)
                if (gen == urlGeneration) {
                    _state.update {
                        it.copy(
                            actions = it.actions.copy(
                                inFlight = null,
                                results = it.actions.results + (section to ActionOutcome(result.message, result.ok)),
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                if (gen == urlGeneration) {
                    _state.update { it.copy(actions = it.actions.copy(inFlight = null)) }
                }
                throw e
            } catch (e: Exception) {
                if (gen == urlGeneration) {
                    _state.update {
                        it.copy(
                            actions = it.actions.copy(
                                inFlight = null,
                                results = it.actions.results + (section to ActionOutcome(friendlyError(e), false)),
                            )
                        )
                    }
                }
            }
            // On a URL change the state was already reset by setBridgeUrl
            // (inFlight included) — nothing to clear or refresh here.
            // After a bridge restart this first attempt can fail while the
            // bridge is still down — silent with data on screen, and the
            // stats poll + pull-to-refresh recover it.
            if (gen == urlGeneration) refreshActions()
        }
    }

    fun testConnection(url: String) {
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _state.update { it.copy(testState = TestState.Testing) }
            try {
                val health = repo.fetchHealth(url)
                _state.update { it.copy(testState = TestState.Ok(health.latencyMs, health.version)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(testState = TestState.Failed(friendlyError(e))) }
            }
        }
    }

    fun clearTestState() { _state.update { it.copy(testState = TestState.Idle) } }

    fun setBridgeUrl(url: String) {
        val clean = repo.normalizeBaseUrl(url)
        prefs.edit().putString("bridge_url", clean).apply()
        consecutiveFailures = 0
        urlGeneration++
        // Full reset: the old bridge's snapshot, histories, and freshness must
        // never linger on screen as if they came from the new bridge.
        _state.update {
            it.copy(
                bridgeUrl = clean, overview = null, mining = null, windows = null,
                decisions = emptyList(), cannaai = null, systemone = null,
                actions = ActionsUiState(),
                connected = false, everConnected = false,
                lastError = null, lastUpdatedMillis = 0L, dataTimestampMillis = 0L,
                latencyMs = null, testState = TestState.Idle,
                cpuHistory = emptyList(), ramHistory = emptyList(), gpuHistory = emptyList(),
                netInHistory = emptyList(), netOutHistory = emptyList(),
            )
        }
        stopPolling()
        startPolling()
        refreshNow()
    }

    fun setRefreshSeconds(seconds: Int) {
        val v = seconds.coerceIn(DashboardUiState.MIN_REFRESH_SECONDS, DashboardUiState.MAX_REFRESH_SECONDS)
        prefs.edit().putInt("refresh_seconds", v).apply()
        _state.update { it.copy(refreshSeconds = v) }
        // No restart needed: the poll loop reads refreshSeconds each cycle.
    }

    private fun friendlyError(e: Exception): String = when {
        e is java.net.ConnectException -> "Can't reach the bridge — is the Mac awake and the bridge running?"
        e is java.net.SocketTimeoutException -> "Bridge timed out."
        e is java.net.UnknownHostException -> "Unknown host — check the bridge address."
        e is org.json.JSONException -> "The bridge sent data the app couldn't read."
        e is java.net.SocketException -> "Connection dropped — check the network."
        e is IllegalArgumentException -> "That bridge address doesn't look right."
        else -> "Couldn't reach the bridge."
    }
}
