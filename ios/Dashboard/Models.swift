import Foundation

// Data model mirroring the dashboard-bridge JSON API (bridge/dashboard_bridge.py),
// ported from the Android app's Models.kt field list and Repository.kt parsers.
//
// Tolerance rule (same as Android): the bridge omits or nulls fields when a
// probe fails, so every property decodes via decodeIfPresent and falls back
// to the Kotlin default; a wrong-typed value reads as "absent", never as a
// failed response. The decoder uses .convertFromSnakeCase, so CodingKeys
// below are written in their post-conversion (camelCase) form — e.g. the
// spec for "max_context_length" is the case raw value "maxContextLength".

// MARK: - Decoding helpers

/// Wrapper that turns a failing element decode into nil instead of
/// failing the whole array (Android's parsers skip bad elements).
private struct Failable<T: Decodable>: Decodable {
    let value: T?
    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        value = try? container.decode(T.self)
    }
}

private struct LossyArray<T: Decodable>: Decodable {
    let elements: [T]
    init(from decoder: Decoder) throws {
        var container = try decoder.unkeyedContainer()
        var result: [T] = []
        result.reserveCapacity(container.count ?? 0)
        while !container.isAtEnd {
            guard let failable = try? container.decode(Failable<T>.self) else { break }
            if let value = failable.value { result.append(value) }
        }
        elements = result
    }
}

private extension KeyedDecodingContainer {
    /// decodeIfPresent that also treats a wrong-typed value as absent,
    /// mirroring org.json's opt* accessors in the Android parsers.
    func tolerant<T: Decodable>(_ type: T.Type, forKey key: Key) -> T? {
        (try? decodeIfPresent(type, forKey: key)) ?? nil
    }

    func lossyArray<T: Decodable>(_ type: T.Type, forKey key: Key) -> [T] {
        tolerant(LossyArray<T>.self, forKey: key)?.elements ?? []
    }
}

private extension Optional where Wrapped == String {
    /// Android's str(): a blank string reads as nil.
    var nilIfBlank: String? {
        guard let s = self, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return s
    }
}

// MARK: - Overview (GET /api/overview)

struct OverviewResponse {
    var generatedAt: Double = 0
    var bridgeVersion: String? = nil
    var bridgeUptimeSeconds: Int64? = nil
    var system: SystemStats = SystemStats()
    var darkbloom: DarkbloomStats = DarkbloomStats()
    var lmstudio: LmStudioStats = LmStudioStats()
}

extension OverviewResponse: Decodable {
    enum CodingKeys: String, CodingKey { case generatedAt, bridge, system, darkbloom, lmstudio }
    enum BridgeKeys: String, CodingKey { case version, uptimeSeconds }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        generatedAt = c.tolerant(Double.self, forKey: .generatedAt) ?? 0
        if let bridge = try? c.nestedContainer(keyedBy: BridgeKeys.self, forKey: .bridge) {
            bridgeVersion = bridge.tolerant(String.self, forKey: .version).nilIfBlank
            bridgeUptimeSeconds = bridge.tolerant(Int64.self, forKey: .uptimeSeconds)
        }
        system = c.tolerant(SystemStats.self, forKey: .system) ?? SystemStats()
        darkbloom = c.tolerant(DarkbloomStats.self, forKey: .darkbloom) ?? DarkbloomStats()
        lmstudio = c.tolerant(LmStudioStats.self, forKey: .lmstudio) ?? LmStudioStats()
    }
}

/// GET /api/health — the Settings "Test" probe's answer.
struct BridgeHealth {
    var ok: Bool = false
    var version: String? = nil
    var machine: String? = nil
    var uptimeSeconds: Int64? = nil
}

extension BridgeHealth: Decodable {
    enum CodingKeys: String, CodingKey { case ok, version, machine, uptimeSeconds }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ok = c.tolerant(Bool.self, forKey: .ok) ?? false
        version = c.tolerant(String.self, forKey: .version).nilIfBlank
        machine = c.tolerant(String.self, forKey: .machine).nilIfBlank
        uptimeSeconds = c.tolerant(Int64.self, forKey: .uptimeSeconds)
    }
}

struct SystemStats {
    var computerName: String = "Mac Studio"
    var chip: String = ""
    var model: String = ""
    var cpuCores: Int? = nil
    var gpuCores: Int? = nil
    var macosVersion: String = ""
    var uptimeSeconds: Int64? = nil
    var loadAvg: [Double] = []
    var cpu: CpuStats = CpuStats()
    var memory: MemoryStats = MemoryStats()
    var disks: [DiskStats] = []
    var network: NetworkStats = NetworkStats()
    var thermal: ThermalStats = ThermalStats()
    var topProcesses: [ProcessInfo] = []
}

extension SystemStats: Decodable {
    enum CodingKeys: String, CodingKey {
        case computerName, chip, model, cpuCores, gpuCores, macosVersion, uptimeSeconds,
             loadAvg, cpu, memory, disks, network, thermal, topProcesses
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        computerName = c.tolerant(String.self, forKey: .computerName).nilIfBlank ?? "Mac Studio"
        chip = c.tolerant(String.self, forKey: .chip).nilIfBlank ?? ""
        model = c.tolerant(String.self, forKey: .model).nilIfBlank ?? ""
        cpuCores = c.tolerant(Int.self, forKey: .cpuCores)
        gpuCores = c.tolerant(Int.self, forKey: .gpuCores)
        macosVersion = c.tolerant(String.self, forKey: .macosVersion).nilIfBlank ?? ""
        uptimeSeconds = c.tolerant(Int64.self, forKey: .uptimeSeconds)
        // Android drops non-finite load samples rather than poisoning the gauges.
        loadAvg = c.lossyArray(Double.self, forKey: .loadAvg).filter { $0.isFinite }
        cpu = c.tolerant(CpuStats.self, forKey: .cpu) ?? CpuStats()
        memory = c.tolerant(MemoryStats.self, forKey: .memory) ?? MemoryStats()
        disks = c.lossyArray(DiskStats.self, forKey: .disks)
        network = c.tolerant(NetworkStats.self, forKey: .network) ?? NetworkStats()
        thermal = c.tolerant(ThermalStats.self, forKey: .thermal) ?? ThermalStats()
        topProcesses = c.lossyArray(ProcessInfo.self, forKey: .topProcesses)
    }
}

struct CpuStats {
    var userPct: Double = 0
    var sysPct: Double = 0
    var idlePct: Double = 100
    var usedPct: Double = 0
}

extension CpuStats: Decodable {
    enum CodingKeys: String, CodingKey { case userPct, sysPct, idlePct, usedPct }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        userPct = c.tolerant(Double.self, forKey: .userPct) ?? 0
        sysPct = c.tolerant(Double.self, forKey: .sysPct) ?? 0
        idlePct = c.tolerant(Double.self, forKey: .idlePct) ?? 100
        usedPct = c.tolerant(Double.self, forKey: .usedPct) ?? 0
    }
}

struct MemoryStats {
    var totalBytes: Int64 = 0
    var usedBytes: Int64 = 0
    var freeBytes: Int64 = 0
    var activeBytes: Int64 = 0
    var inactiveBytes: Int64 = 0
    var wiredBytes: Int64 = 0
    var compressedBytes: Int64 = 0
    var fileBackedBytes: Int64 = 0
    var anonymousBytes: Int64 = 0
    var usedPct: Double = 0
    var swapTotalMb: Double = 0
    var swapUsedMb: Double = 0
}

extension MemoryStats: Decodable {
    enum CodingKeys: String, CodingKey {
        case totalBytes, usedBytes, freeBytes, activeBytes, inactiveBytes, wiredBytes,
             compressedBytes, fileBackedBytes, anonymousBytes, usedPct, swap
    }
    enum SwapKeys: String, CodingKey { case totalMb, usedMb }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        totalBytes = c.tolerant(Int64.self, forKey: .totalBytes) ?? 0
        usedBytes = c.tolerant(Int64.self, forKey: .usedBytes) ?? 0
        freeBytes = c.tolerant(Int64.self, forKey: .freeBytes) ?? 0
        activeBytes = c.tolerant(Int64.self, forKey: .activeBytes) ?? 0
        inactiveBytes = c.tolerant(Int64.self, forKey: .inactiveBytes) ?? 0
        wiredBytes = c.tolerant(Int64.self, forKey: .wiredBytes) ?? 0
        compressedBytes = c.tolerant(Int64.self, forKey: .compressedBytes) ?? 0
        fileBackedBytes = c.tolerant(Int64.self, forKey: .fileBackedBytes) ?? 0
        anonymousBytes = c.tolerant(Int64.self, forKey: .anonymousBytes) ?? 0
        usedPct = c.tolerant(Double.self, forKey: .usedPct) ?? 0
        // Swap lives in a nested "swap" object on the wire; Kotlin flattens it.
        if let swap = try? c.nestedContainer(keyedBy: SwapKeys.self, forKey: .swap) {
            swapTotalMb = swap.tolerant(Double.self, forKey: .totalMb) ?? 0
            swapUsedMb = swap.tolerant(Double.self, forKey: .usedMb) ?? 0
        }
    }
}

struct DiskStats {
    var label: String = ""
    var path: String = ""
    var totalBytes: Int64 = 0
    var usedBytes: Int64 = 0
    var freeBytes: Int64 = 0
    var usedPct: Double = 0
}

extension DiskStats: Decodable {
    enum CodingKeys: String, CodingKey { case label, path, totalBytes, usedBytes, freeBytes, usedPct }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        label = c.tolerant(String.self, forKey: .label).nilIfBlank ?? ""
        path = c.tolerant(String.self, forKey: .path).nilIfBlank ?? ""
        totalBytes = c.tolerant(Int64.self, forKey: .totalBytes) ?? 0
        usedBytes = c.tolerant(Int64.self, forKey: .usedBytes) ?? 0
        freeBytes = c.tolerant(Int64.self, forKey: .freeBytes) ?? 0
        usedPct = c.tolerant(Double.self, forKey: .usedPct) ?? 0
    }
}

struct NetworkStats {
    var bytesIn: Int64 = 0
    var bytesOut: Int64 = 0
    var rateInBps: Int64 = 0
    var rateOutBps: Int64 = 0
}

extension NetworkStats: Decodable {
    enum CodingKeys: String, CodingKey { case bytesIn, bytesOut, rateInBps, rateOutBps }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        bytesIn = c.tolerant(Int64.self, forKey: .bytesIn) ?? 0
        bytesOut = c.tolerant(Int64.self, forKey: .bytesOut) ?? 0
        rateInBps = c.tolerant(Int64.self, forKey: .rateInBps) ?? 0
        rateOutBps = c.tolerant(Int64.self, forKey: .rateOutBps) ?? 0
    }
}

struct ThermalStats {
    var state: String = "nominal"
    var cpuSpeedLimitPct: Int = 100
}

extension ThermalStats: Decodable {
    enum CodingKeys: String, CodingKey { case state, cpuSpeedLimitPct }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        state = c.tolerant(String.self, forKey: .state).nilIfBlank ?? "nominal"
        cpuSpeedLimitPct = c.tolerant(Int.self, forKey: .cpuSpeedLimitPct) ?? 100
    }
}

struct ProcessInfo {
    var pid: Int = 0
    var cpuPct: Double = 0
    var memPct: Double = 0
    var elapsed: String = ""
    var name: String = ""
}

extension ProcessInfo: Decodable {
    enum CodingKeys: String, CodingKey { case pid, cpuPct, memPct, elapsed, name }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        pid = c.tolerant(Int.self, forKey: .pid) ?? 0
        cpuPct = c.tolerant(Double.self, forKey: .cpuPct) ?? 0
        memPct = c.tolerant(Double.self, forKey: .memPct) ?? 0
        elapsed = c.tolerant(String.self, forKey: .elapsed).nilIfBlank ?? ""
        name = c.tolerant(String.self, forKey: .name).nilIfBlank ?? ""
    }
}

// MARK: - Darkbloom

struct DarkbloomStats {
    var installed: Bool = false
    var running: Bool = false
    var version: String? = nil
    var pid: Int? = nil
    var uptimeSeconds: Int64? = nil
    var lifecycle: String? = nil
    var currentModel: String? = nil
    var trustLevel: String? = nil
    var trustStatus: String? = nil
    var trustReason: String? = nil
    var trustPath: String? = nil
    /// trust.session_expires_at on the wire — decoded but DELIBERATELY
    /// NOT RENDERED: since bridge 1.11.1 it is null by design, because
    /// the daemon field behind it is the App Attest authorization
    /// token's expiry, which self-renews every few seconds. It is NOT
    /// the login session (the daemon exposes no login expiry), and a
    /// row showing it flickered "expired" while earning was fine. The
    /// earning signal + lastCreditAt are the real dead-session
    /// detectors. Keep the decode so the contract stays checked.
    var trustSessionExpiresAt: Double? = nil
    var earning: Bool = false
    var verificationPending: Bool = true
    var requestsServed: Int64? = nil
    var tokensGenerated: Int64? = nil
    var usageGaps: Int64? = nil
    var capacityTotalGb: Double? = nil
    var gpuActiveGb: Double? = nil
    var gpuCacheGb: Double? = nil
    var freeForLoadGb: Double? = nil
    var loadHeadroomGb: Double? = nil
    var loadUsableGb: Double? = nil
    var warmModels: [String] = []
    var advertisedModels: [String] = []
    var slots: [SlotInfo] = []
    var residentModels: [ResidentModel] = []
    var autopilotPhase: String? = nil
    var autopilotEnabled: Bool? = nil
    var autopilotObserveOnly: Bool? = nil
    var autopilotPaused: Bool? = nil
    var autopilotMaxModelSlots: Int? = nil
    var attestAttested: Bool? = nil
    var attestKeyAgeSeconds: Int64? = nil
    var attestLastSuccessAgeSeconds: Int64? = nil
    var attestGenerationsLast24h: Int64? = nil
    var attestConsecutiveFailures: Int64? = nil
    var earnings: Earnings? = nil
}

extension DarkbloomStats: Decodable {
    enum CodingKeys: String, CodingKey {
        case installed, running, version, pid, uptimeSeconds, lifecycle, currentModel,
             trust, earning, verificationPending, stats, capacity, warmModels,
             advertisedModels, slots, residentModels, autopilot, appAttest, earnings
    }
    enum TrustKeys: String, CodingKey { case level, status, reason, path, sessionExpiresAt }
    enum StatsKeys: String, CodingKey { case requestsServed, tokensGenerated, usageGaps }
    enum CapacityKeys: String, CodingKey { case totalMemoryGb, gpuActiveGb, gpuCacheGb, freeForLoadGb, loadHeadroomGb, loadUsableGb }
    enum AutopilotKeys: String, CodingKey { case phase, enabled, observeOnly, paused, maxModelSlots }
    enum AttestKeys: String, CodingKey {
        case attested, keyAgeSeconds, lastSuccessAgeSeconds, consecutiveFailures
        // Digit key: the wire's generations_last_24h becomes
        // "generationsLast24H" under .convertFromSnakeCase — pin it.
        case generationsLast24h = "generationsLast24H"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        installed = c.tolerant(Bool.self, forKey: .installed) ?? false
        running = c.tolerant(Bool.self, forKey: .running) ?? false
        version = c.tolerant(String.self, forKey: .version).nilIfBlank
        pid = c.tolerant(Int.self, forKey: .pid)
        uptimeSeconds = c.tolerant(Int64.self, forKey: .uptimeSeconds)
        lifecycle = c.tolerant(String.self, forKey: .lifecycle).nilIfBlank
        currentModel = c.tolerant(String.self, forKey: .currentModel).nilIfBlank
        // The wire nests these groups; Kotlin flattens them into this struct.
        if let trust = try? c.nestedContainer(keyedBy: TrustKeys.self, forKey: .trust) {
            trustLevel = trust.tolerant(String.self, forKey: .level).nilIfBlank
            trustStatus = trust.tolerant(String.self, forKey: .status).nilIfBlank
            trustReason = trust.tolerant(String.self, forKey: .reason).nilIfBlank
            trustPath = trust.tolerant(String.self, forKey: .path).nilIfBlank
            trustSessionExpiresAt = trust.tolerant(Double.self, forKey: .sessionExpiresAt)
        }
        earning = c.tolerant(Bool.self, forKey: .earning) ?? false
        verificationPending = c.tolerant(Bool.self, forKey: .verificationPending) ?? true
        if let stats = try? c.nestedContainer(keyedBy: StatsKeys.self, forKey: .stats) {
            requestsServed = stats.tolerant(Int64.self, forKey: .requestsServed)
            tokensGenerated = stats.tolerant(Int64.self, forKey: .tokensGenerated)
            usageGaps = stats.tolerant(Int64.self, forKey: .usageGaps)
        }
        if let capacity = try? c.nestedContainer(keyedBy: CapacityKeys.self, forKey: .capacity) {
            capacityTotalGb = capacity.tolerant(Double.self, forKey: .totalMemoryGb)
            gpuActiveGb = capacity.tolerant(Double.self, forKey: .gpuActiveGb)
            gpuCacheGb = capacity.tolerant(Double.self, forKey: .gpuCacheGb)
            freeForLoadGb = capacity.tolerant(Double.self, forKey: .freeForLoadGb)
            loadHeadroomGb = capacity.tolerant(Double.self, forKey: .loadHeadroomGb)
            loadUsableGb = capacity.tolerant(Double.self, forKey: .loadUsableGb)
        }
        warmModels = c.lossyArray(String.self, forKey: .warmModels)
        advertisedModels = c.lossyArray(String.self, forKey: .advertisedModels)
        slots = c.lossyArray(SlotInfo.self, forKey: .slots)
        residentModels = c.lossyArray(ResidentModel.self, forKey: .residentModels)
        if let autopilot = try? c.nestedContainer(keyedBy: AutopilotKeys.self, forKey: .autopilot) {
            autopilotPhase = autopilot.tolerant(String.self, forKey: .phase).nilIfBlank
            autopilotEnabled = autopilot.tolerant(Bool.self, forKey: .enabled)
            autopilotObserveOnly = autopilot.tolerant(Bool.self, forKey: .observeOnly)
            autopilotPaused = autopilot.tolerant(Bool.self, forKey: .paused)
            autopilotMaxModelSlots = autopilot.tolerant(Int.self, forKey: .maxModelSlots)
        }
        if let attest = try? c.nestedContainer(keyedBy: AttestKeys.self, forKey: .appAttest) {
            attestAttested = attest.tolerant(Bool.self, forKey: .attested)
            attestKeyAgeSeconds = attest.tolerant(Int64.self, forKey: .keyAgeSeconds)
            attestLastSuccessAgeSeconds = attest.tolerant(Int64.self, forKey: .lastSuccessAgeSeconds)
            attestGenerationsLast24h = attest.tolerant(Int64.self, forKey: .generationsLast24h)
            attestConsecutiveFailures = attest.tolerant(Int64.self, forKey: .consecutiveFailures)
        }
        earnings = c.tolerant(Earnings.self, forKey: .earnings)
    }
}

struct SlotInfo {
    var model: String = ""
    var mtpEnabled: Bool = false
    var mtpActive: Bool = false
    var kvBackend: String = ""
}

extension SlotInfo: Decodable {
    enum CodingKeys: String, CodingKey { case model, mtpEnabled, mtpActive, kvBackend }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        model = c.tolerant(String.self, forKey: .model).nilIfBlank ?? ""
        mtpEnabled = c.tolerant(Bool.self, forKey: .mtpEnabled) ?? false
        mtpActive = c.tolerant(Bool.self, forKey: .mtpActive) ?? false
        kvBackend = c.tolerant(String.self, forKey: .kvBackend).nilIfBlank ?? ""
    }
}

struct ResidentModel {
    var model: String = ""
    var residentGb: Double = 0
    var weightsGb: Double = 0
    var idleSeconds: Int64? = nil
    var residentSeconds: Int64? = nil
}

extension ResidentModel: Decodable {
    enum CodingKeys: String, CodingKey { case model, residentGb, weightsGb, idleSeconds, residentSeconds }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        model = c.tolerant(String.self, forKey: .model).nilIfBlank ?? ""
        residentGb = c.tolerant(Double.self, forKey: .residentGb) ?? 0
        weightsGb = c.tolerant(Double.self, forKey: .weightsGb) ?? 0
        idleSeconds = c.tolerant(Int64.self, forKey: .idleSeconds)
        residentSeconds = c.tolerant(Int64.self, forKey: .residentSeconds)
    }
}

// MARK: - Darkbloom earnings

/// One entry of the wire's `per_model_recent` object: jobs + micro-USD,
/// keyed by model name on the wire, flattened into ModelEarnings below.
private struct PerModelRecentEntry: Decodable {
    var jobs: Int = 0
    var microUsd: Int64 = 0

    enum CodingKeys: String, CodingKey { case jobs, microUsd }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        jobs = c.tolerant(Int.self, forKey: .jobs) ?? 0
        microUsd = c.tolerant(Int64.self, forKey: .microUsd) ?? 0
    }
}

struct Earnings {
    var balanceUsd: String? = nil
    var withdrawableUsd: String? = nil
    var totalUsd: String? = nil
    var jobsInHistory: Int? = nil
    var baseRewardsMicroRecent: Int64 = 0
    var perModel: [ModelEarnings] = []
    var recent: [EarningEntry] = []
    /// The full ledger window (up to history_limit entries, same
    /// shape as `recent`) — `recent` is just its newest few.
    var history: [EarningEntry] = []
    /// Epoch seconds of the most recent ledger credit (paid job or
    /// base reward) — the dead-session heartbeat: earning can only be
    /// real if this keeps moving.
    var lastCreditAt: Double? = nil
    var estimates: EarningsEstimates? = nil
}

extension Earnings: Decodable {
    enum CodingKeys: String, CodingKey {
        case balanceUsd, withdrawableUsd, totalUsd, jobsInHistory,
             baseRewardsMicroUsdRecent, perModelRecent, recent, history,
             lastCreditAt, estimates
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // Money arrives as exact decimal strings from the ledger — keep
        // them as strings end to end, like Android (no floating-point money).
        balanceUsd = c.tolerant(String.self, forKey: .balanceUsd).nilIfBlank
        withdrawableUsd = c.tolerant(String.self, forKey: .withdrawableUsd).nilIfBlank
        totalUsd = c.tolerant(String.self, forKey: .totalUsd).nilIfBlank
        jobsInHistory = c.tolerant(Int.self, forKey: .jobsInHistory)
        baseRewardsMicroRecent = c.tolerant(Int64.self, forKey: .baseRewardsMicroUsdRecent) ?? 0
        if let byModel = c.tolerant([String: PerModelRecentEntry].self, forKey: .perModelRecent) {
            perModel = byModel
                .map { ModelEarnings(model: $0.key, jobs: $0.value.jobs, microUsd: $0.value.microUsd) }
                .sorted { $0.microUsd > $1.microUsd }
        }
        recent = c.lossyArray(EarningEntry.self, forKey: .recent)
        history = c.lossyArray(EarningEntry.self, forKey: .history)
        lastCreditAt = c.tolerant(Double.self, forKey: .lastCreditAt)
        estimates = c.tolerant(EarningsEstimates.self, forKey: .estimates)
    }
}

struct ModelEarnings {
    var model: String = ""
    var jobs: Int = 0
    var microUsd: Int64 = 0
}

extension ModelEarnings: Decodable {
    enum CodingKeys: String, CodingKey { case model, jobs, microUsd }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        model = c.tolerant(String.self, forKey: .model).nilIfBlank ?? ""
        jobs = c.tolerant(Int.self, forKey: .jobs) ?? 0
        microUsd = c.tolerant(Int64.self, forKey: .microUsd) ?? 0
    }
}

struct EarningEntry {
    var model: String = ""
    var amountMicroUsd: Int64 = 0
    var promptTokens: Int64? = nil
    var completionTokens: Int64? = nil
    var createdAt: String? = nil
}

extension EarningEntry: Decodable {
    enum CodingKeys: String, CodingKey { case model, amountMicroUsd, promptTokens, completionTokens, createdAt }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        model = c.tolerant(String.self, forKey: .model).nilIfBlank ?? ""
        amountMicroUsd = c.tolerant(Int64.self, forKey: .amountMicroUsd) ?? 0
        promptTokens = c.tolerant(Int64.self, forKey: .promptTokens)
        completionTokens = c.tolerant(Int64.self, forKey: .completionTokens)
        createdAt = c.tolerant(String.self, forKey: .createdAt).nilIfBlank
    }
}

/// Estimated returns computed by the bridge from its snapshot ledger of
/// the lifetime earnings total (realized trailing-window rates, blended).
/// Doubles are fine here: these are projections, not ledger money.
struct EarningsEstimates {
    var perDayUsd: Double = 0
    var perWeekUsd: Double = 0
    var perMonthUsd: Double = 0
    var method: String? = nil
    var trackedSinceEpochSec: Double? = nil
    var basisLabel: String? = nil
    var confidence: String? = nil
    var basePerDayUsd: Double? = nil
    var jobsPerDayUsd: Double? = nil
    var onlineFraction: Double? = nil
    var whileServingPerDayUsd: Double? = nil
    /// The realized per-day rate over each trailing window — the
    /// components the blended headline is averaged from.
    var compLastHourUsd: Double? = nil
    var compLast6hUsd: Double? = nil
    var compLast24hUsd: Double? = nil
    var compLast7dUsd: Double? = nil
    var series: [EarningsPoint] = []
}

extension EarningsEstimates: Decodable {
    enum CodingKeys: String, CodingKey {
        case perDayUsd, perWeekUsd, perMonthUsd, method, trackedSince,
             basis, confidence, split, serving, components, series
    }
    enum BasisKeys: String, CodingKey { case label }
    enum SplitKeys: String, CodingKey { case basePerDayUsd, jobsPerDayUsd }
    enum ServingKeys: String, CodingKey { case onlineFraction, perDayWhileServingUsd }
    // Digit trap: "last_6h" converts to "last6H", "last_24h" to
    // "last24H", "last_7d" to "last7D" — raw values are the CONVERTED
    // spellings, like the miner average keys.
    enum ComponentsKeys: String, CodingKey {
        case lastHour, last6h = "last6H", last24h = "last24H", last7d = "last7D"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        perDayUsd = c.tolerant(Double.self, forKey: .perDayUsd) ?? 0
        perWeekUsd = c.tolerant(Double.self, forKey: .perWeekUsd) ?? 0
        perMonthUsd = c.tolerant(Double.self, forKey: .perMonthUsd) ?? 0
        method = c.tolerant(String.self, forKey: .method).nilIfBlank
        trackedSinceEpochSec = c.tolerant(Double.self, forKey: .trackedSince)
        if let basis = try? c.nestedContainer(keyedBy: BasisKeys.self, forKey: .basis) {
            basisLabel = basis.tolerant(String.self, forKey: .label).nilIfBlank
        }
        confidence = c.tolerant(String.self, forKey: .confidence).nilIfBlank
        if let split = try? c.nestedContainer(keyedBy: SplitKeys.self, forKey: .split) {
            basePerDayUsd = split.tolerant(Double.self, forKey: .basePerDayUsd)
            jobsPerDayUsd = split.tolerant(Double.self, forKey: .jobsPerDayUsd)
        }
        if let serving = try? c.nestedContainer(keyedBy: ServingKeys.self, forKey: .serving) {
            onlineFraction = serving.tolerant(Double.self, forKey: .onlineFraction)
            whileServingPerDayUsd = serving.tolerant(Double.self, forKey: .perDayWhileServingUsd)
        }
        if let comps = try? c.nestedContainer(keyedBy: ComponentsKeys.self, forKey: .components) {
            compLastHourUsd = comps.tolerant(Double.self, forKey: .lastHour)
            compLast6hUsd = comps.tolerant(Double.self, forKey: .last6h)
            compLast24hUsd = comps.tolerant(Double.self, forKey: .last24h)
            compLast7dUsd = comps.tolerant(Double.self, forKey: .last7d)
        }
        series = c.lossyArray(EarningsPoint.self, forKey: .series)
    }
}

/// One point of the bridge's cumulative-earnings ledger series,
/// encoded on the wire as a [epochSec, totalMicroUsd] pair.
struct EarningsPoint {
    var tsSec: Double = 0
    var totalMicroUsd: Int64 = 0
}

extension EarningsPoint: Decodable {
    init(from decoder: Decoder) throws {
        var c = try decoder.unkeyedContainer()
        tsSec = (try? c.decode(Double.self)) ?? 0
        let micro = (try? c.decode(Double.self)) ?? 0
        totalMicroUsd = micro.isFinite && micro.magnitude < 9.0e15 ? Int64(micro) : 0
    }
}

// MARK: - LM Studio (part of Overview)

struct LmStudioStats {
    var reachable: Bool = false
    var loadedCount: Int = 0
    var models: [LmModel] = []
}

extension LmStudioStats: Decodable {
    enum CodingKeys: String, CodingKey { case reachable, loadedCount, models }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        reachable = c.tolerant(Bool.self, forKey: .reachable) ?? false
        loadedCount = c.tolerant(Int.self, forKey: .loadedCount) ?? 0
        models = c.lossyArray(LmModel.self, forKey: .models)
    }
}

struct LmModel {
    var id: String = ""
    var type: String? = nil
    var state: String? = nil
    var publisher: String? = nil
    var quantization: String? = nil
    var sizeBytes: Int64? = nil
    var maxContext: Int64? = nil
    var loadedContext: Int64? = nil
}

extension LmModel: Decodable {
    enum CodingKeys: String, CodingKey {
        case id, type, state, publisher, quantization, sizeBytes
        // Post-conversion forms of "max_context_length" / "loaded_context_length".
        case maxContext = "maxContextLength"
        case loadedContext = "loadedContextLength"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.tolerant(String.self, forKey: .id).nilIfBlank ?? ""
        type = c.tolerant(String.self, forKey: .type).nilIfBlank
        state = c.tolerant(String.self, forKey: .state).nilIfBlank
        publisher = c.tolerant(String.self, forKey: .publisher).nilIfBlank
        quantization = c.tolerant(String.self, forKey: .quantization).nilIfBlank
        sizeBytes = c.tolerant(Int64.self, forKey: .sizeBytes)
        maxContext = c.tolerant(Int64.self, forKey: .maxContext)
        loadedContext = c.tolerant(Int64.self, forKey: .loadedContext)
    }
}

// MARK: - Mining (GET /api/mining)
//
// Separate endpoint on purpose (mirrors Android): Kryptex fleet numbers
// and the Windows rig must never bleed into the Mac or Darkbloom numbers.
// The combined Earnings view sums them client-side.

struct MiningResponse {
    var generatedAt: Double = 0
    var mining: MiningData = MiningData()
}

extension MiningResponse: Decodable {
    enum CodingKeys: String, CodingKey { case generatedAt, mining }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        generatedAt = c.tolerant(Double.self, forKey: .generatedAt) ?? 0
        mining = c.tolerant(MiningData.self, forKey: .mining) ?? MiningData()
    }
}

struct MiningData {
    var fleetAsOfEpochSec: Double? = nil
    var fleetSource: String? = nil
    var coins: [MiningCoin] = []
    var totalPerDayUsd: Double? = nil
    var totalPerWeekUsd: Double? = nil
    var totalPerMonthUsd: Double? = nil
    var totalPerYearUsd: Double? = nil
    var batmanLive: BatmanLive? = nil
    var batmanVerified: BatmanVerified? = nil
    var batmanHistory: BatmanHistory? = nil
    var profit: MiningProfit? = nil
}

extension MiningData: Decodable {
    enum CodingKeys: String, CodingKey { case fleet, totals, batmanLive, batmanVerified, batmanHistory, profit }
    enum FleetKeys: String, CodingKey { case asOf, source, coins }
    enum TotalsKeys: String, CodingKey { case perDayUsd, perWeekUsd, perMonthUsd, perYearUsd }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let fleet = try? c.nestedContainer(keyedBy: FleetKeys.self, forKey: .fleet) {
            fleetAsOfEpochSec = fleet.tolerant(Double.self, forKey: .asOf)
            fleetSource = fleet.tolerant(String.self, forKey: .source).nilIfBlank
            coins = fleet.lossyArray(MiningCoin.self, forKey: .coins)
        }
        if let totals = try? c.nestedContainer(keyedBy: TotalsKeys.self, forKey: .totals) {
            totalPerDayUsd = totals.tolerant(Double.self, forKey: .perDayUsd)
            totalPerWeekUsd = totals.tolerant(Double.self, forKey: .perWeekUsd)
            totalPerMonthUsd = totals.tolerant(Double.self, forKey: .perMonthUsd)
            totalPerYearUsd = totals.tolerant(Double.self, forKey: .perYearUsd)
        }
        batmanLive = c.tolerant(BatmanLive.self, forKey: .batmanLive)
        batmanVerified = c.tolerant(BatmanVerified.self, forKey: .batmanVerified)
        batmanHistory = c.tolerant(BatmanHistory.self, forKey: .batmanHistory)
        profit = c.tolerant(MiningProfit.self, forKey: .profit)
    }
}

/// Power-cost truth for the verified mining figure: the bridge prices
/// the rig's live draw at a configured electricity rate and nets it
/// against the verified revenue (bridge 1.11+, /api/mining "profit").
struct MiningProfit {
    var powerW: Double? = nil
    var rateUsdKwh: Double? = nil
    var kwhPerDay: Double? = nil
    var powerCostDay: Double? = nil
    var netDay: Double? = nil
}

extension MiningProfit: Decodable {
    enum CodingKeys: String, CodingKey { case powerW, rateUsdKwh, kwhPerDay, powerCostDay, netDay }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        powerW = c.tolerant(Double.self, forKey: .powerW)
        rateUsdKwh = c.tolerant(Double.self, forKey: .rateUsdKwh)
        kwhPerDay = c.tolerant(Double.self, forKey: .kwhPerDay)
        powerCostDay = c.tolerant(Double.self, forKey: .powerCostDay)
        netDay = c.tolerant(Double.self, forKey: .netDay)
    }
}

struct MiningCoin {
    var coin: String = ""
    var scheme: String = ""
    var miner: String = ""
    var rigs: [MiningRig] = []
}

extension MiningCoin: Decodable {
    enum CodingKeys: String, CodingKey { case coin, scheme, miner, rigs }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        coin = c.tolerant(String.self, forKey: .coin).nilIfBlank ?? ""
        scheme = c.tolerant(String.self, forKey: .scheme).nilIfBlank ?? ""
        miner = c.tolerant(String.self, forKey: .miner).nilIfBlank ?? ""
        rigs = c.lossyArray(MiningRig.self, forKey: .rigs)
    }
}

struct MiningRig {
    var name: String = ""
    var hashrate30m: String? = nil
    var hashrate24h: String? = nil
    var perDayUsd: Double? = nil
    var perMonthUsd: Double? = nil
}

extension MiningRig: Decodable {
    // NOTE: .convertFromSnakeCase turns hashrate_30m into "hashrate30M"
    // (it capitalizes the letter after a digit) — raw values must match
    // the CONVERTED spelling, not the property name. Same for the avg keys.
    enum CodingKeys: String, CodingKey {
        case name, hashrate30m = "hashrate30M", hashrate24h = "hashrate24H", perDayUsd, perMonthUsd
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = c.tolerant(String.self, forKey: .name).nilIfBlank ?? ""
        hashrate30m = c.tolerant(String.self, forKey: .hashrate30m).nilIfBlank
        hashrate24h = c.tolerant(String.self, forKey: .hashrate24h).nilIfBlank
        perDayUsd = c.tolerant(Double.self, forKey: .perDayUsd)
        perMonthUsd = c.tolerant(Double.self, forKey: .perMonthUsd)
    }
}

struct BatmanLive {
    var running: Bool = false
    var hashrateThs: Double? = nil
    var avg1hThs: Double? = nil
    var powerW: Double? = nil
    var gpuTempC: Double? = nil
    var checkedAtEpochSec: Double? = nil
    var stale: Bool = false
}

extension BatmanLive: Decodable {
    enum CodingKeys: String, CodingKey {
        case running, hashrateThs, avg1hThs = "avg1HThs", powerW, gpuTempC, stale
        case checkedAtEpochSec = "checkedAt"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        running = c.tolerant(Bool.self, forKey: .running) ?? false
        hashrateThs = c.tolerant(Double.self, forKey: .hashrateThs)
        avg1hThs = c.tolerant(Double.self, forKey: .avg1hThs)
        powerW = c.tolerant(Double.self, forKey: .powerW)
        gpuTempC = c.tolerant(Double.self, forKey: .gpuTempC)
        checkedAtEpochSec = c.tolerant(Double.self, forKey: .checkedAtEpochSec)
        stale = c.tolerant(Bool.self, forKey: .stale) ?? false
    }
}

/// Verified Batman-3 Pearl revenue, computed by the bridge from the
/// rig's live hashrate × live network emission × live PRL price, less
/// the pool fee. Kryptex's own claim is carried only for comparison.
struct BatmanVerified {
    var hashrateBasisThs: Double? = nil
    var hashrateBasis: String? = nil
    var prlPerDayGross: Double? = nil
    var usdPerDayGross: Double? = nil
    var usdPerDayNet: Double? = nil
    var usdPerWeekNet: Double? = nil
    var usdPerMonthNet: Double? = nil
    var kryptexClaimPerDayUsd: Double? = nil
    var deltaVsClaimPct: Double? = nil
    var priceUsd: Double? = nil
    var priceSource: String? = nil
    var nethashThs: Double? = nil
    var usdPerThDay: Double? = nil
    var poolFeePct: Double? = nil
}

extension BatmanVerified: Decodable {
    enum CodingKeys: String, CodingKey {
        case hashrateBasisThs, hashrateBasis, prlPerDayGross, usdPerDayGross,
             usdPerDayNet, usdPerWeekNet, usdPerMonthNet, kryptexClaimPerDayUsd,
             deltaVsClaimPct, network
    }
    enum NetworkKeys: String, CodingKey { case priceUsd, priceSource, nethashThs, usdPerThDay, poolFeePct }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        hashrateBasisThs = c.tolerant(Double.self, forKey: .hashrateBasisThs)
        hashrateBasis = c.tolerant(String.self, forKey: .hashrateBasis).nilIfBlank
        prlPerDayGross = c.tolerant(Double.self, forKey: .prlPerDayGross)
        usdPerDayGross = c.tolerant(Double.self, forKey: .usdPerDayGross)
        usdPerDayNet = c.tolerant(Double.self, forKey: .usdPerDayNet)
        usdPerWeekNet = c.tolerant(Double.self, forKey: .usdPerWeekNet)
        usdPerMonthNet = c.tolerant(Double.self, forKey: .usdPerMonthNet)
        kryptexClaimPerDayUsd = c.tolerant(Double.self, forKey: .kryptexClaimPerDayUsd)
        deltaVsClaimPct = c.tolerant(Double.self, forKey: .deltaVsClaimPct)
        // Price/network figures nest under "network" on the wire.
        if let network = try? c.nestedContainer(keyedBy: NetworkKeys.self, forKey: .network) {
            priceUsd = network.tolerant(Double.self, forKey: .priceUsd)
            priceSource = network.tolerant(String.self, forKey: .priceSource).nilIfBlank
            nethashThs = network.tolerant(Double.self, forKey: .nethashThs)
            usdPerThDay = network.tolerant(Double.self, forKey: .usdPerThDay)
            poolFeePct = network.tolerant(Double.self, forKey: .poolFeePct)
        }
    }
}

/// Batman-3 hashrate history: the miner's own log (backfill) merged with
/// the bridge's per-minute ledger. Points are [epochSec, TH/s] pairs.
struct BatmanHistory {
    var points: [HashratePoint] = []
    var coverageFromEpochSec: Double? = nil
    var coverageToEpochSec: Double? = nil
    var spanHours: Double? = nil
    var sampleCount: Int? = nil
    var avgActiveThs: Double? = nil
    var avgOverallThs: Double? = nil
    /// Trailing-24h overall average and the span that window actually
    /// covers (the bridge's per-minute ledger is younger than 24h, so
    /// the span is stated rather than implied).
    var avg24hOverallThs: Double? = nil
    var win24SpanHours: Double? = nil
    var uptimeFraction: Double? = nil
    var realizedPrlEst: Double? = nil
    var realizedUsdNetEst: Double? = nil
    var realizedBasis: String? = nil
}

extension BatmanHistory: Decodable {
    enum CodingKeys: String, CodingKey {
        case points, spanHours, sampleCount, avgActiveThs, avgOverallThs,
             uptimeFraction, realizedPrlEst, realizedUsdNetEst, realizedBasis
        case coverageFromEpochSec = "coverageFrom"
        case coverageToEpochSec = "coverageTo"
        // Digit keys: the wire's avg_24h_overall_ths / win24_span_hours
        // become "avg24HOverallThs" / "win24SpanHours" under
        // .convertFromSnakeCase — pin the raw values.
        case avg24hOverallThs = "avg24HOverallThs"
        case win24SpanHours = "win24SpanHours"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        points = c.lossyArray(HashratePoint.self, forKey: .points)
        coverageFromEpochSec = c.tolerant(Double.self, forKey: .coverageFromEpochSec)
        coverageToEpochSec = c.tolerant(Double.self, forKey: .coverageToEpochSec)
        spanHours = c.tolerant(Double.self, forKey: .spanHours)
        sampleCount = c.tolerant(Int.self, forKey: .sampleCount)
        avgActiveThs = c.tolerant(Double.self, forKey: .avgActiveThs)
        avgOverallThs = c.tolerant(Double.self, forKey: .avgOverallThs)
        avg24hOverallThs = c.tolerant(Double.self, forKey: .avg24hOverallThs)
        win24SpanHours = c.tolerant(Double.self, forKey: .win24SpanHours)
        uptimeFraction = c.tolerant(Double.self, forKey: .uptimeFraction)
        realizedPrlEst = c.tolerant(Double.self, forKey: .realizedPrlEst)
        realizedUsdNetEst = c.tolerant(Double.self, forKey: .realizedUsdNetEst)
        realizedBasis = c.tolerant(String.self, forKey: .realizedBasis).nilIfBlank
    }
}

struct HashratePoint {
    var tsSec: Double = 0
    var ths: Double = 0
}

extension HashratePoint: Decodable {
    init(from decoder: Decoder) throws {
        var c = try decoder.unkeyedContainer()
        tsSec = (try? c.decode(Double.self)) ?? 0
        ths = (try? c.decode(Double.self)) ?? 0
    }
}

// MARK: - Windows (GET /api/windows)

struct WindowsResponse {
    var generatedAt: Double = 0
    var windows: WindowsData = WindowsData()
}

extension WindowsResponse: Decodable {
    enum CodingKeys: String, CodingKey { case generatedAt, windows }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        generatedAt = c.tolerant(Double.self, forKey: .generatedAt) ?? 0
        windows = c.tolerant(WindowsData.self, forKey: .windows) ?? WindowsData()
    }
}

struct WindowsData {
    var reachable: Bool = false
    var stale: Bool = false
    /// Epoch seconds when the bridge fetched this snapshot from the
    /// PC (for a last_good snapshot: when that sample was fetched).
    var fetchedAt: Double? = nil
    var osName: String? = nil
    var osVersion: String? = nil
    var uptimeSeconds: Int64? = nil
    var cpuName: String? = nil
    var cpuLoadPct: Int? = nil
    var cpuCores: Int? = nil
    var cpuLogical: Int? = nil
    var memUsedPct: Double? = nil
    var memUsedBytes: Int64 = 0
    var memFreeBytes: Int64 = 0
    var memTotalBytes: Int64 = 0
    var gpuName: String? = nil
    var gpuUtilPct: Double? = nil
    var gpuMemUsedMb: Double? = nil
    var gpuMemTotalMb: Double? = nil
    var gpuTempC: Double? = nil
    var gpuPowerW: Double? = nil
    var disks: [WinDisk] = []
    var netRateInBps: Double? = nil
    var netRateOutBps: Double? = nil
    var minerRunning: Bool = false
    var minerHashrateThs: Double? = nil
    var minerAvg15mThs: Double? = nil
    var minerAvg1hThs: Double? = nil
    var minerAvg6hThs: Double? = nil
    var minerPowerW: Double? = nil
    var minerAccepted: Int? = nil
    var minerRejected: Int? = nil
}

extension WindowsData: Decodable {
    enum CodingKeys: String, CodingKey {
        case reachable, stale, fetchedAt, osName, osVersion, uptimeSeconds,
             cpu, memory, gpu, disks, network, miner, lastGood
    }
    enum CpuKeys: String, CodingKey { case name, loadPct, cores, logical }
    enum MemKeys: String, CodingKey { case usedPct, usedBytes, freeBytes, totalBytes }
    enum GpuKeys: String, CodingKey { case name, utilPct, memUsedMb, memTotalMb, tempC, powerW }
    enum NetKeys: String, CodingKey { case rateInBps, rateOutBps }
    enum MinerKeys: String, CodingKey {
        case running, hashrateThs, avg15mThs = "avg15MThs", avg1hThs = "avg1HThs",
             avg6hThs = "avg6HThs", powerW, accepted, rejected
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let isReachable = c.tolerant(Bool.self, forKey: .reachable) ?? false
        if !isReachable, c.contains(.lastGood),
           var last = try? c.decode(WindowsData.self, forKey: .lastGood) {
            // The bridge keeps the last good PC snapshot server-side; an
            // unreachable body carrying one still renders, marked stale.
            last.reachable = true
            last.stale = true
            self = last
            return
        }
        reachable = isReachable
        stale = c.tolerant(Bool.self, forKey: .stale) ?? false
        fetchedAt = c.tolerant(Double.self, forKey: .fetchedAt)
        osName = c.tolerant(String.self, forKey: .osName).nilIfBlank
        osVersion = c.tolerant(String.self, forKey: .osVersion).nilIfBlank
        uptimeSeconds = c.tolerant(Int64.self, forKey: .uptimeSeconds)
        if let cpu = try? c.nestedContainer(keyedBy: CpuKeys.self, forKey: .cpu) {
            cpuName = cpu.tolerant(String.self, forKey: .name).nilIfBlank
            cpuLoadPct = cpu.tolerant(Int.self, forKey: .loadPct)
            cpuCores = cpu.tolerant(Int.self, forKey: .cores)
            cpuLogical = cpu.tolerant(Int.self, forKey: .logical)
        }
        if let memory = try? c.nestedContainer(keyedBy: MemKeys.self, forKey: .memory) {
            memUsedPct = memory.tolerant(Double.self, forKey: .usedPct)
            memUsedBytes = memory.tolerant(Int64.self, forKey: .usedBytes) ?? 0
            memFreeBytes = memory.tolerant(Int64.self, forKey: .freeBytes) ?? 0
            memTotalBytes = memory.tolerant(Int64.self, forKey: .totalBytes) ?? 0
        }
        if let gpu = try? c.nestedContainer(keyedBy: GpuKeys.self, forKey: .gpu) {
            gpuName = gpu.tolerant(String.self, forKey: .name).nilIfBlank
            gpuUtilPct = gpu.tolerant(Double.self, forKey: .utilPct)
            gpuMemUsedMb = gpu.tolerant(Double.self, forKey: .memUsedMb)
            gpuMemTotalMb = gpu.tolerant(Double.self, forKey: .memTotalMb)
            gpuTempC = gpu.tolerant(Double.self, forKey: .tempC)
            gpuPowerW = gpu.tolerant(Double.self, forKey: .powerW)
        }
        disks = c.lossyArray(WinDisk.self, forKey: .disks)
        if let network = try? c.nestedContainer(keyedBy: NetKeys.self, forKey: .network) {
            netRateInBps = network.tolerant(Double.self, forKey: .rateInBps)
            netRateOutBps = network.tolerant(Double.self, forKey: .rateOutBps)
        }
        if let miner = try? c.nestedContainer(keyedBy: MinerKeys.self, forKey: .miner) {
            minerRunning = miner.tolerant(Bool.self, forKey: .running) ?? false
            minerHashrateThs = miner.tolerant(Double.self, forKey: .hashrateThs)
            minerAvg15mThs = miner.tolerant(Double.self, forKey: .avg15mThs)
            minerAvg1hThs = miner.tolerant(Double.self, forKey: .avg1hThs)
            minerAvg6hThs = miner.tolerant(Double.self, forKey: .avg6hThs)
            minerPowerW = miner.tolerant(Double.self, forKey: .powerW)
            minerAccepted = miner.tolerant(Int.self, forKey: .accepted)
            minerRejected = miner.tolerant(Int.self, forKey: .rejected)
        }
    }
}

struct WinDisk {
    var label: String = ""
    var totalBytes: Int64 = 0
    var freeBytes: Int64 = 0
    var usedPct: Double = 0
}

extension WinDisk: Decodable {
    enum CodingKeys: String, CodingKey { case label, totalBytes, freeBytes, usedPct }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        label = c.tolerant(String.self, forKey: .label).nilIfBlank ?? ""
        totalBytes = c.tolerant(Int64.self, forKey: .totalBytes) ?? 0
        freeBytes = c.tolerant(Int64.self, forKey: .freeBytes) ?? 0
        usedPct = c.tolerant(Double.self, forKey: .usedPct) ?? 0
    }
}

// MARK: - Actions (GET /api/actions, POST /api/action)
//
// The bridge's control surface, deliberately separate from the read-only
// stats models: these drive real side effects on the Mac and Batman-3
// (model loads, service restarts, the miner), so they never ride the
// stats poll.

struct ActionsResponse {
    var lmstudio: ActionsLm = ActionsLm()
    var darkbloom: ActionsDarkbloom = ActionsDarkbloom()
    var mining: ActionsMining = ActionsMining()
    var bridge: ActionsBridge = ActionsBridge()
    /// Recent action attempts, newest first (bridge 1.11+; absent on
    /// older bridges, decodes as empty).
    var history: [ActionHistoryEntry] = []
}

extension ActionsResponse: Decodable {
    enum CodingKeys: String, CodingKey { case lmstudio, darkbloom, mining, bridge, history }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        lmstudio = c.tolerant(ActionsLm.self, forKey: .lmstudio) ?? ActionsLm()
        darkbloom = c.tolerant(ActionsDarkbloom.self, forKey: .darkbloom) ?? ActionsDarkbloom()
        mining = c.tolerant(ActionsMining.self, forKey: .mining) ?? ActionsMining()
        bridge = c.tolerant(ActionsBridge.self, forKey: .bridge) ?? ActionsBridge()
        history = c.lossyArray(ActionHistoryEntry.self, forKey: .history)
    }
}

/// One entry of the bridge's actions audit trail (actions.jsonl,
/// surfaced by GET /api/actions as "history").
struct ActionHistoryEntry {
    var ts: Double = 0
    var action: String = ""
    var target: String? = nil
    var ok: Bool = false
    var message: String? = nil
}

extension ActionHistoryEntry: Decodable {
    enum CodingKeys: String, CodingKey { case ts, action, target, ok, message }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ts = c.tolerant(Double.self, forKey: .ts) ?? 0
        action = c.tolerant(String.self, forKey: .action).nilIfBlank ?? ""
        target = c.tolerant(String.self, forKey: .target).nilIfBlank
        ok = c.tolerant(Bool.self, forKey: .ok) ?? false
        message = c.tolerant(String.self, forKey: .message).nilIfBlank
    }
}

struct ActionsLm {
    var loaded: [LmLoaded] = []
    var available: [LmAvailable] = []
}

extension ActionsLm: Decodable {
    enum CodingKeys: String, CodingKey { case loaded, available }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        loaded = c.lossyArray(LmLoaded.self, forKey: .loaded)
        available = c.lossyArray(LmAvailable.self, forKey: .available)
    }
}

struct LmLoaded {
    var identifier: String = ""
}

extension LmLoaded: Decodable {
    enum CodingKeys: String, CodingKey { case identifier }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        identifier = c.tolerant(String.self, forKey: .identifier).nilIfBlank ?? ""
    }
}

struct LmAvailable {
    var key: String = ""
    var size: String? = nil
}

extension LmAvailable: Decodable {
    enum CodingKeys: String, CodingKey { case key, size }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = c.tolerant(String.self, forKey: .key).nilIfBlank ?? ""
        size = c.tolerant(String.self, forKey: .size).nilIfBlank
    }
}

struct ActionsDarkbloom {
    var running: Bool = false
}

extension ActionsDarkbloom: Decodable {
    enum CodingKeys: String, CodingKey { case running }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        running = c.tolerant(Bool.self, forKey: .running) ?? false
    }
}

struct ActionsMining {
    var minerRunning: Bool = false
}

extension ActionsMining: Decodable {
    enum CodingKeys: String, CodingKey { case minerRunning }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        minerRunning = c.tolerant(Bool.self, forKey: .minerRunning) ?? false
    }
}

struct ActionsBridge {
    var version: String = ""
}

extension ActionsBridge: Decodable {
    enum CodingKeys: String, CodingKey { case version }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = c.tolerant(String.self, forKey: .version).nilIfBlank ?? ""
    }
}

/// The bridge's answer to a POST /api/action. Both response shapes fold
/// into this — the 200 {"ok", "message"} and the 400 {"ok": false,
/// "error"} — so `message` always carries the bridge's own words.
struct ActionResult: Decodable {
    let ok: Bool
    let message: String

    enum CodingKeys: String, CodingKey { case ok, message, error }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let error = c.tolerant(String.self, forKey: .error).nilIfBlank
        // An "error" field means failure whatever "ok" claims.
        ok = error == nil && (c.tolerant(Bool.self, forKey: .ok) ?? false)
        message = error ?? c.tolerant(String.self, forKey: .message).nilIfBlank ?? "Done."
    }

    init(ok: Bool, message: String) {
        self.ok = ok
        self.message = message
    }
}

// MARK: - Decisions (GET /api/decisions)
//
// The bridge's state-change event log: a watcher samples the same
// stats the endpoints serve and records transitions (Darkbloom
// start/stop, earning start/stop, trust changes, LM Studio up/down,
// loaded-model changes, serving-model changes). Newest first.

struct DecisionsResponse {
    var generatedAt: Double = 0
    var decisions: [DecisionEvent] = []
}

extension DecisionsResponse: Decodable {
    enum CodingKeys: String, CodingKey { case generatedAt, decisions }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        generatedAt = c.tolerant(Double.self, forKey: .generatedAt) ?? 0
        decisions = c.lossyArray(DecisionEvent.self, forKey: .decisions)
    }
}

struct DecisionEvent {
    /// Epoch seconds.
    var ts: Double = 0
    /// The event kind, verbatim from the bridge: bridge_started,
    /// darkbloom_started/stopped, darkbloom_earning_started/stopped,
    /// darkbloom_trust_changed, darkbloom_model_changed,
    /// lmstudio_up/down, lmstudio_models_changed.
    var type: String = ""
    // The detail object is kind-specific; the fields any kind uses are
    // flattened here (absent reads as nil, like everywhere else).
    var detailFrom: String? = nil
    var detailTo: String? = nil
    var detailModel: String? = nil
    var detailVersion: String? = nil
    var detailLoaded: [String] = []
}

extension DecisionEvent: Decodable {
    enum CodingKeys: String, CodingKey { case ts, type, detail }
    enum DetailKeys: String, CodingKey { case from, to, model, version, loaded }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ts = c.tolerant(Double.self, forKey: .ts) ?? 0
        type = c.tolerant(String.self, forKey: .type).nilIfBlank ?? ""
        if let detail = try? c.nestedContainer(keyedBy: DetailKeys.self, forKey: .detail) {
            detailFrom = detail.tolerant(String.self, forKey: .from).nilIfBlank
            detailTo = detail.tolerant(String.self, forKey: .to).nilIfBlank
            detailModel = detail.tolerant(String.self, forKey: .model).nilIfBlank
            detailVersion = detail.tolerant(String.self, forKey: .version).nilIfBlank
            detailLoaded = detail.lossyArray(String.self, forKey: .loaded)
        }
    }
}

// MARK: - CannaAI grow (GET /api/cannaai, bridge 1.11+)
//
// The bridge proxies Duckets' CannaAI grow app (tent sensors, alerts,
// journal). Shown on Overview only when reachable — an unreachable
// CannaAI means "grow app not running", not an error state.

struct CannaaiResponse {
    var generatedAt: Double = 0
    var cannaai: CannaaiData? = nil
}

extension CannaaiResponse: Decodable {
    enum CodingKeys: String, CodingKey { case generatedAt, cannaai }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        generatedAt = c.tolerant(Double.self, forKey: .generatedAt) ?? 0
        cannaai = c.tolerant(CannaaiData.self, forKey: .cannaai)
    }
}

struct CannaaiData {
    var reachable: Bool = false
    var status: String? = nil
    var sensors: [GrowSensor] = []
    var alerts: [GrowAlert] = []
    var journalLatest: GrowJournalEntry? = nil
    /// providers_up on the wire — a bool ("are providers up") or a
    /// count, depending on the bridge build; both decode attempts run.
    var providersUp: Bool? = nil
    var providersUpCount: Int? = nil
}

extension CannaaiData: Decodable {
    enum CodingKeys: String, CodingKey {
        case reachable, status, sensors, alerts, journalLatest, providersUp
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        reachable = c.tolerant(Bool.self, forKey: .reachable) ?? false
        status = c.tolerant(String.self, forKey: .status).nilIfBlank
        sensors = c.lossyArray(GrowSensor.self, forKey: .sensors)
        alerts = c.lossyArray(GrowAlert.self, forKey: .alerts)
        journalLatest = c.tolerant(GrowJournalEntry.self, forKey: .journalLatest)
        providersUp = c.tolerant(Bool.self, forKey: .providersUp)
        providersUpCount = c.tolerant(Int.self, forKey: .providersUp)
    }
}

struct GrowSensor {
    var label: String = ""
    /// Display-ready value: a numeric wire value is pre-formatted
    /// (integers without a fraction, others to 1 dp); strings pass through.
    var valueText: String = "—"
    var unit: String? = nil
}

extension GrowSensor: Decodable {
    enum CodingKeys: String, CodingKey { case label, value, unit }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        label = c.tolerant(String.self, forKey: .label).nilIfBlank ?? ""
        if let d = c.tolerant(Double.self, forKey: .value), d.isFinite {
            valueText = d == d.rounded() && abs(d) < 9.0e15
                ? String(Int64(d))
                : String(format: "%.1f", d)
        } else {
            valueText = c.tolerant(String.self, forKey: .value).nilIfBlank ?? "—"
        }
        unit = c.tolerant(String.self, forKey: .unit).nilIfBlank
    }
}

/// A timestamp that may arrive as epoch seconds (number) or an
/// ISO-8601 string; both decode attempts run and display picks a form.
struct FlexibleTime {
    var epochSec: Double? = nil
    var raw: String? = nil

    var isEmpty: Bool { epochSec == nil && raw == nil }
}

private extension KeyedDecodingContainer {
    func flexibleTime(forKey key: Key) -> FlexibleTime {
        FlexibleTime(
            epochSec: tolerant(Double.self, forKey: key),
            raw: tolerant(String.self, forKey: key).nilIfBlank
        )
    }
}

struct GrowAlert {
    var text: String = ""
    var level: String? = nil
    var at: FlexibleTime = FlexibleTime()
}

extension GrowAlert: Decodable {
    enum CodingKeys: String, CodingKey { case text, level, at }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        text = c.tolerant(String.self, forKey: .text).nilIfBlank ?? ""
        level = c.tolerant(String.self, forKey: .level).nilIfBlank
        at = c.flexibleTime(forKey: .at)
    }
}

struct GrowJournalEntry {
    var text: String = ""
    var at: FlexibleTime = FlexibleTime()
}

extension GrowJournalEntry: Decodable {
    enum CodingKeys: String, CodingKey { case text, at }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        text = c.tolerant(String.self, forKey: .text).nilIfBlank ?? ""
        at = c.flexibleTime(forKey: .at)
    }
}

// MARK: - SystemOne (GET /api/systemone, bridge 1.11+)
//
// Duckets' local decision engine, proxied by the bridge: is it up,
// and how many decisions has it served.

struct SystemoneResponse {
    var generatedAt: Double = 0
    var systemone: SystemOneData? = nil
}

extension SystemoneResponse: Decodable {
    enum CodingKeys: String, CodingKey { case generatedAt, systemone }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        generatedAt = c.tolerant(Double.self, forKey: .generatedAt) ?? 0
        systemone = c.tolerant(SystemOneData.self, forKey: .systemone)
    }
}

struct SystemOneData {
    var reachable: Bool = false
    var metricsOk: Bool = false
    var shimUp: Bool = false
    var decisionsTotal: Int64? = nil
}

extension SystemOneData: Decodable {
    enum CodingKeys: String, CodingKey { case reachable, metricsOk, shimUp, decisionsTotal }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        reachable = c.tolerant(Bool.self, forKey: .reachable) ?? false
        metricsOk = c.tolerant(Bool.self, forKey: .metricsOk) ?? false
        shimUp = c.tolerant(Bool.self, forKey: .shimUp) ?? false
        decisionsTotal = c.tolerant(Int64.self, forKey: .decisionsTotal)
    }
}
