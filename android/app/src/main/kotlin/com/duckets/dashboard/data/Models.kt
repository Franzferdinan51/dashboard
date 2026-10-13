package com.duckets.dashboard.data

// Data model mirroring the dashboard-bridge JSON API (bridge/dashboard_bridge.py).

data class Overview(
    val generatedAt: Double = 0.0,
    val system: SystemStats = SystemStats(),
    val darkbloom: DarkbloomStats = DarkbloomStats(),
    val lmstudio: LmStudioStats = LmStudioStats(),
)

data class SystemStats(
    val computerName: String = "Mac Studio",
    val chip: String = "",
    val model: String = "",
    val cpuCores: Int? = null,
    val gpuCores: Int? = null,
    val macosVersion: String = "",
    val uptimeSeconds: Long? = null,
    val loadAvg: List<Double> = emptyList(),
    val cpu: CpuStats = CpuStats(),
    val memory: MemoryStats = MemoryStats(),
    val disks: List<DiskStats> = emptyList(),
    val network: NetworkStats = NetworkStats(),
    val thermal: ThermalStats = ThermalStats(),
    val topProcesses: List<ProcessInfo> = emptyList(),
)

data class CpuStats(
    val userPct: Double = 0.0,
    val sysPct: Double = 0.0,
    val idlePct: Double = 100.0,
    val usedPct: Double = 0.0,
)

data class MemoryStats(
    val totalBytes: Long = 0,
    val usedBytes: Long = 0,
    val freeBytes: Long = 0,
    val activeBytes: Long = 0,
    val inactiveBytes: Long = 0,
    val wiredBytes: Long = 0,
    val compressedBytes: Long = 0,
    val fileBackedBytes: Long = 0,
    val anonymousBytes: Long = 0,
    val usedPct: Double = 0.0,
    val swapTotalMb: Double = 0.0,
    val swapUsedMb: Double = 0.0,
)

data class DiskStats(
    val label: String = "",
    val path: String = "",
    val totalBytes: Long = 0,
    val usedBytes: Long = 0,
    val freeBytes: Long = 0,
    val usedPct: Double = 0.0,
)

data class NetworkStats(
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val rateInBps: Long = 0,
    val rateOutBps: Long = 0,
)

data class ThermalStats(
    val state: String = "nominal",
    val cpuSpeedLimitPct: Int = 100,
)

data class ProcessInfo(
    val pid: Int = 0,
    val cpuPct: Double = 0.0,
    val memPct: Double = 0.0,
    val elapsed: String = "",
    val name: String = "",
)

data class DarkbloomStats(
    val installed: Boolean = false,
    val running: Boolean = false,
    val version: String? = null,
    val pid: Int? = null,
    val uptimeSeconds: Long? = null,
    val lifecycle: String? = null,
    val currentModel: String? = null,
    val trustLevel: String? = null,
    val trustStatus: String? = null,
    val trustReason: String? = null,
    val trustPath: String? = null,
    /** When the Darkbloom auth session ends (epoch seconds, parsed
     * tolerantly from whatever the daemon wrote — ISO string or epoch).
     * Past this point the provider silently stops earning until the
     * session is refreshed, so the app surfaces it before it bites. */
    val trustSessionExpiresAtEpochSec: Double? = null,
    val earning: Boolean = false,
    val verificationPending: Boolean = true,
    val requestsServed: Long? = null,
    val tokensGenerated: Long? = null,
    val capacityTotalGb: Double? = null,
    val gpuActiveGb: Double? = null,
    val gpuCacheGb: Double? = null,
    val freeForLoadGb: Double? = null,
    val warmModels: List<String> = emptyList(),
    val advertisedModels: List<String> = emptyList(),
    val slots: List<SlotInfo> = emptyList(),
    val residentModels: List<ResidentModel> = emptyList(),
    val autopilotPhase: String? = null,
    val autopilotEnabled: Boolean? = null,
    val autopilotObserveOnly: Boolean? = null,
    val attestAttested: Boolean? = null,
    val attestKeyAgeSeconds: Long? = null,
    val attestLastSuccessAgeSeconds: Long? = null,
    val earnings: Earnings? = null,
)

data class SlotInfo(
    val model: String = "",
    val mtpEnabled: Boolean = false,
    val mtpActive: Boolean = false,
    val kvBackend: String = "",
)

data class ResidentModel(
    val model: String = "",
    val residentGb: Double = 0.0,
    val weightsGb: Double = 0.0,
    val idleSeconds: Long? = null,
    val residentSeconds: Long? = null,
)

data class Earnings(
    val balanceUsd: String? = null,
    val withdrawableUsd: String? = null,
    val totalUsd: String? = null,
    val jobsInHistory: Int? = null,
    val baseRewardsMicroRecent: Long = 0,
    val perModel: List<ModelEarnings> = emptyList(),
    val recent: List<EarningEntry> = emptyList(),
    val estimates: EarningsEstimates? = null,
)

/** Estimated returns computed by the bridge from its snapshot ledger of
 * the lifetime earnings total (realized trailing-window rates, blended).
 * Doubles are fine here: these are projections, not ledger money. */
data class EarningsEstimates(
    val perDayUsd: Double = 0.0,
    val perWeekUsd: Double = 0.0,
    val perMonthUsd: Double = 0.0,
    val method: String? = null,
    val trackedSinceEpochSec: Double? = null,
    val basisLabel: String? = null,
    val confidence: String? = null,
    val basePerDayUsd: Double? = null,
    val jobsPerDayUsd: Double? = null,
    val onlineFraction: Double? = null,
    val whileServingPerDayUsd: Double? = null,
    val series: List<EarningsPoint> = emptyList(),
    /** Realized $/day over each trailing window (estimates.components) —
     * the ingredients the blended per-day headline is averaged from. */
    val componentLastHourUsd: Double? = null,
    val componentLast6hUsd: Double? = null,
    val componentLast24hUsd: Double? = null,
    val componentLast7dUsd: Double? = null,
)

/** One point of the bridge's cumulative-earnings ledger series. */
data class EarningsPoint(
    val tsSec: Double = 0.0,
    val totalMicroUsd: Long = 0,
)

data class ModelEarnings(
    val model: String = "",
    val jobs: Int = 0,
    val microUsd: Long = 0,
)

data class EarningEntry(
    val model: String = "",
    val amountMicroUsd: Long = 0,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val createdAt: String? = null,
)

data class LmStudioStats(
    val reachable: Boolean = false,
    val loadedCount: Int = 0,
    val models: List<LmModel> = emptyList(),
)

data class LmModel(
    val id: String = "",
    val type: String? = null,
    val state: String? = null,
    val publisher: String? = null,
    val quantization: String? = null,
    val sizeBytes: Long? = null,
    val maxContext: Long? = null,
    val loadedContext: Long? = null,
)

// ---------------------------------------------------------------------------
// Mining (Kryptex fleet) + Windows PC (Batman-3) — separate bridge endpoints,
// deliberately NOT part of Overview so neither can bleed into the Mac or
// Darkbloom numbers. The combined Earnings tab sums them client-side.
// ---------------------------------------------------------------------------

data class MiningData(
    val fleetAsOfEpochSec: Double? = null,
    val fleetSource: String? = null,
    val coins: List<MiningCoin> = emptyList(),
    val totalPerDayUsd: Double? = null,
    val totalPerWeekUsd: Double? = null,
    val totalPerMonthUsd: Double? = null,
    val totalPerYearUsd: Double? = null,
    val batmanLive: BatmanLive? = null,
    val batmanVerified: BatmanVerified? = null,
    val batmanHistory: BatmanHistory? = null,
    val profit: MiningProfit? = null,
)

/** Electricity math for Batman-3, computed bridge-side (bridge 1.11+):
 * the miner's live draw at the configured $/kWh rate, so the verified
 * card can state profit — not just revenue. */
data class MiningProfit(
    val powerW: Double? = null,
    val rateUsdKwh: Double? = null,
    val kwhPerDay: Double? = null,
    val powerCostDay: Double? = null,
    val netDay: Double? = null,
)

data class MiningCoin(
    val coin: String = "",
    val scheme: String = "",
    val miner: String = "",
    val rigs: List<MiningRig> = emptyList(),
)

data class MiningRig(
    val name: String = "",
    val hashrate30m: String? = null,
    val hashrate24h: String? = null,
    val perDayUsd: Double? = null,
    val perMonthUsd: Double? = null,
)

data class BatmanLive(
    val running: Boolean = false,
    val hashrateThs: Double? = null,
    val avg1hThs: Double? = null,
    val powerW: Double? = null,
    val gpuTempC: Double? = null,
    val checkedAtEpochSec: Double? = null,
    val stale: Boolean = false,
)

data class WindowsData(
    val reachable: Boolean = false,
    val stale: Boolean = false,
    val osName: String? = null,
    val osVersion: String? = null,
    val uptimeSeconds: Long? = null,
    val cpuName: String? = null,
    val cpuLoadPct: Int? = null,
    val cpuCores: Int? = null,
    val cpuLogical: Int? = null,
    val memUsedPct: Double? = null,
    val memUsedBytes: Long = 0,
    val memFreeBytes: Long = 0,
    val memTotalBytes: Long = 0,
    val gpuName: String? = null,
    val gpuUtilPct: Double? = null,
    val gpuMemUsedMb: Double? = null,
    val gpuMemTotalMb: Double? = null,
    val gpuTempC: Double? = null,
    val gpuPowerW: Double? = null,
    val disks: List<WinDisk> = emptyList(),
    val netRateInBps: Double? = null,
    val netRateOutBps: Double? = null,
    val minerRunning: Boolean = false,
    val minerHashrateThs: Double? = null,
    val minerAvg15mThs: Double? = null,
    val minerAvg1hThs: Double? = null,
    val minerAvg6hThs: Double? = null,
    val minerPowerW: Double? = null,
    val minerAccepted: Int? = null,
    val minerRejected: Int? = null,
)

data class WinDisk(
    val label: String = "",
    val totalBytes: Long = 0,
    val freeBytes: Long = 0,
    val usedPct: Double = 0.0,
)

/** Verified Batman-3 Pearl revenue, computed by the bridge from the
 * rig's live hashrate × live network emission × live PRL price, less
 * the pool fee. Kryptex's own claim is carried only for comparison. */
data class BatmanVerified(
    val hashrateBasisThs: Double? = null,
    val hashrateBasis: String? = null,
    val prlPerDayGross: Double? = null,
    val usdPerDayGross: Double? = null,
    val usdPerDayNet: Double? = null,
    val usdPerWeekNet: Double? = null,
    val usdPerMonthNet: Double? = null,
    val kryptexClaimPerDayUsd: Double? = null,
    val deltaVsClaimPct: Double? = null,
    val priceUsd: Double? = null,
    val priceSource: String? = null,
    val nethashThs: Double? = null,
    val usdPerThDay: Double? = null,
    val poolFeePct: Double? = null,
)

/** Batman-3 hashrate history: the miner's own log (backfill) merged with
 * the bridge's per-minute ledger. Points are [epochSec, TH/s]. */
data class BatmanHistory(
    val points: List<HashratePoint> = emptyList(),
    val coverageFromEpochSec: Double? = null,
    val coverageToEpochSec: Double? = null,
    val spanHours: Double? = null,
    val sampleCount: Int? = null,
    val avgActiveThs: Double? = null,
    val avgOverallThs: Double? = null,
    val uptimeFraction: Double? = null,
    val realizedPrlEst: Double? = null,
    val realizedUsdNetEst: Double? = null,
    val realizedBasis: String? = null,
)

data class HashratePoint(val tsSec: Double = 0.0, val ths: Double = 0.0)

// ---------------------------------------------------------------------------
// Actions — the bridge's control surface (GET /api/actions, POST
// /api/action). Deliberately separate from the read-only stats models:
// these drive real side effects on the Mac and Batman-3 (model loads,
// service restarts, the miner), so they never ride the stats poll.
// ---------------------------------------------------------------------------

data class ActionsData(
    val lmLoaded: List<LmLoadedModel> = emptyList(),
    val lmAvailable: List<LmAvailableModel> = emptyList(),
    val darkbloomRunning: Boolean = false,
    val minerRunning: Boolean = false,
    val bridgeVersion: String? = null,
    val history: List<ActionHistoryEntry> = emptyList(),
)

/** One entry of the bridge's actions ledger (actions.jsonl), read back
 * through GET /api/actions (bridge 1.11+): what was tapped, when, and
 * how the bridge answered. */
data class ActionHistoryEntry(
    val tsEpochSec: Double = 0.0,
    val action: String = "",
    val target: String? = null,
    val ok: Boolean = false,
    val message: String? = null,
)

// ---------------------------------------------------------------------------
// Side panels fed by their own bridge endpoints on the slow cadence:
// the decisions event log (GET /api/decisions), CannaAI (GET /api/cannaai)
// and SystemOne (GET /api/systemone). All bridge-1.11+ surfaces; every
// parser is tolerant — an absent endpoint just leaves the panel hidden.
// ---------------------------------------------------------------------------

/** A state-change event from the bridge's watcher thread: Darkbloom
 * start/stop/earning, trust changes, LM Studio up/down, loaded-model
 * changes. [detailText] is a one-line human summary built at parse time
 * from the event's free-form detail object (may be null). */
data class DecisionEvent(
    val tsEpochSec: Double = 0.0,
    val type: String = "",
    val detailText: String? = null,
)

data class CannaAiData(
    val reachable: Boolean = false,
    val status: String? = null,
    val sensors: List<GrowSensor> = emptyList(),
    val alerts: List<GrowAlert> = emptyList(),
    val journalText: String? = null,
    val journalAt: String? = null,
    val providersUp: Boolean? = null,
)

data class GrowSensor(
    val label: String = "",
    val value: String = "",
    val unit: String? = null,
)

data class GrowAlert(
    val text: String = "",
    val level: String? = null,
    val at: String? = null,
)

data class SystemOneData(
    val reachable: Boolean = false,
    val metricsOk: Boolean = false,
    val shimUp: Boolean = false,
    val decisionsTotal: Long? = null,
)

data class LmLoadedModel(val identifier: String = "")

data class LmAvailableModel(val key: String = "", val size: String? = null)

/** The bridge's answer to a POST /api/action. Both response shapes fold
 * into this — the 200 {"ok", "message"} and the 400 {"ok": false,
 * "error"} — so [message] always carries the bridge's own words. */
data class ActionResult(
    val ok: Boolean = false,
    val action: String = "",
    val message: String = "",
)
