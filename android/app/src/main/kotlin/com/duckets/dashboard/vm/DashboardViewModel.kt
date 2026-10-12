package com.duckets.dashboard.vm

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.duckets.dashboard.data.DashboardRepository
import com.duckets.dashboard.data.Overview
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
    val connected: Boolean = false,
    val everConnected: Boolean = false,
    val lastError: String? = null,
    val lastUpdatedMillis: Long = 0L,
    val bridgeUrl: String = DEFAULT_BRIDGE_URL,
    val refreshSeconds: Int = DEFAULT_REFRESH_SECONDS,
    /** Rolling history (oldest -> newest) for sparklines, capped at 90 samples. */
    val cpuHistory: List<Float> = emptyList(),
    val ramHistory: List<Float> = emptyList(),
    val gpuHistory: List<Float> = emptyList(),
    val netInHistory: List<Float> = emptyList(),
    val netOutHistory: List<Float> = emptyList(),
) {
    companion object {
        /** Placeholder LAN default; real deployments override via BuildConfig/local.properties. */
        const val DEFAULT_BRIDGE_URL = com.duckets.dashboard.BuildConfig.DEFAULT_BRIDGE_URL
        const val DEFAULT_REFRESH_SECONDS = 5
    }
}

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("dashboard", Context.MODE_PRIVATE)
    private val repo = DashboardRepository()

    private val _state = MutableStateFlow(
        DashboardUiState(
            bridgeUrl = prefs.getString("bridge_url", DashboardUiState.DEFAULT_BRIDGE_URL)
                ?: DashboardUiState.DEFAULT_BRIDGE_URL,
            refreshSeconds = prefs.getInt("refresh_seconds", DashboardUiState.DEFAULT_REFRESH_SECONDS),
        )
    )
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        startPolling()
    }

    fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshOnce()
                delay(_state.value.refreshSeconds.coerceIn(2, 60) * 1000L)
            }
        }
    }

    suspend fun refreshOnce() {
        val url = _state.value.bridgeUrl
        try {
            val ov = repo.fetchOverview(url)
            _state.update { s ->
                val gpuPct = ov.darkbloom.capacityTotalGb?.let { total ->
                    ov.darkbloom.gpuActiveGb?.let { active ->
                        if (total > 0) (active / total * 100).toFloat() else 0f
                    }
                } ?: 0f
                s.copy(
                    overview = ov,
                    connected = true,
                    everConnected = true,
                    lastError = null,
                    lastUpdatedMillis = System.currentTimeMillis(),
                    cpuHistory = (s.cpuHistory + ov.system.cpu.usedPct.toFloat()).takeLast(90),
                    ramHistory = (s.ramHistory + ov.system.memory.usedPct.toFloat()).takeLast(90),
                    gpuHistory = (s.gpuHistory + gpuPct).takeLast(90),
                    netInHistory = (s.netInHistory + ov.system.network.rateInBps.toFloat()).takeLast(90),
                    netOutHistory = (s.netOutHistory + ov.system.network.rateOutBps.toFloat()).takeLast(90),
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(connected = false, lastError = friendlyError(e)) }
        }
    }

    fun setBridgeUrl(url: String) {
        val clean = url.trim()
        prefs.edit().putString("bridge_url", clean).apply()
        _state.update { it.copy(bridgeUrl = clean, lastError = null) }
        startPolling()
    }

    fun setRefreshSeconds(seconds: Int) {
        val v = seconds.coerceIn(2, 60)
        prefs.edit().putInt("refresh_seconds", v).apply()
        _state.update { it.copy(refreshSeconds = v) }
        startPolling()
    }

    private fun friendlyError(e: Exception): String = when {
        e is java.net.ConnectException -> "Can't reach the bridge — is the Mac awake and the bridge running?"
        e is java.net.SocketTimeoutException -> "Bridge timed out."
        e is java.net.UnknownHostException -> "Unknown host — check the bridge address."
        else -> e.message ?: "Unknown error"
    }
}
