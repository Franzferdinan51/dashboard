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
