import SwiftUI

private let cMist = Color(red: 0.957, green: 0.973, blue: 1.000)
private let cFaint = Color(red: 0.431, green: 0.525, blue: 0.678)
private let cTrack = Color(red: 0.031, green: 0.078, blue: 0.149)

private func countText(_ v: Int64?) -> String {
    guard let v else { return "—" }
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.usesGroupingSeparator = true
    return f.string(from: NSNumber(value: v)) ?? "\(v)"
}

/// Strip the publisher prefix: "publisher/model" -> "model".
private func shortModelName(_ id: String) -> String {
    id.split(separator: "/").last.map(String.init) ?? id
}

private func gbText(_ gb: Double?, digits: Int = 1) -> String {
    guard let gb, gb.isFinite else { return "—" }
    return String(format: "%.\(digits)f GB", gb)
}

/// "Oct 12, 4:55 PM" from epoch seconds.
private func clockText(_ epochSec: Double?) -> String {
    guard let epochSec, epochSec.isFinite else { return "—" }
    let f = DateFormatter()
    f.dateFormat = "MMM d, h:mm a"
    f.locale = Locale(identifier: "en_US")
    return f.string(from: Date(timeIntervalSince1970: epochSec))
}

/// "4:55 PM" from an ISO-8601 ledger timestamp.
private func ledgerTime(_ iso: String?) -> String {
    guard let iso, !iso.isEmpty else { return "" }
    let parser = ISO8601DateFormatter()
    parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    var date = parser.date(from: iso)
    if date == nil {
        parser.formatOptions = [.withInternetDateTime]
        date = parser.date(from: iso)
    }
    guard let date else { return String(iso.prefix(16)).replacingOccurrences(of: "T", with: " ") }
    let f = DateFormatter()
    f.dateFormat = "h:mm a"
    f.locale = Locale(identifier: "en_US")
    return f.string(from: date)
}

/// (previous, next) milestone bracket for a balance: $1 first (the
/// withdrawal minimum), then 2/5 x 10^n steps.
private func nextMilestone(_ bal: Double) -> (prev: Double, next: Double) {
    var prev = 0.0
    var p = 1.0
    while p <= 100_000 {
        for s in [p, p * 2, p * 5] {
            if bal < s { return (prev, s) }
            prev = s
        }
        p *= 10
    }
    return (prev, prev * 2)
}

private struct MiniBar: View {
    let fraction: Double
    let color: Color
    var height: CGFloat = 6

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: height / 2)
                    .fill(cTrack)
                RoundedRectangle(cornerRadius: height / 2)
                    .fill(color)
                    .frame(width: max(0, min(1, fraction)) * geo.size.width)
            }
        }
        .frame(height: height)
    }
}

private struct EstBox: View {
    let label: String
    let value: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(Theme.textDim)
            Text(fmtUsd(value))
                .font(.title3.bold())
                .foregroundStyle(cMist)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct DarkbloomView: View {
    @EnvironmentObject private var vm: DashboardViewModel

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if let ov = vm.overview {
                    let db = ov.darkbloom
                    let e = db.earnings
                    providerCard(db)
                    earningsCard(db, e)
                    if let est = e?.estimates {
                        estimatesCard(est)
                    }
                    modelsCard(db)
                    gpuCard(db)
                } else {
                    DashCard(title: nil) {
                        Text(vm.lastError ?? "Waiting for the first sample from the bridge…")
                            .font(.subheadline)
                            .foregroundStyle(Theme.textDim)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .padding(16)
        }
        .background(Theme.bg)
        .refreshable { await vm.refresh() }
    }

    // MARK: - Provider status

    private func providerCard(_ db: DarkbloomStats) -> some View {
        DashCard(title: "Provider") {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 6) {
                    Text("Eigen Labs decentralized inference")
                        .font(.caption)
                        .foregroundStyle(Theme.textDim)
                    Spacer()
                    if !vm.connected {
                        StatusChip(text: "OFFLINE", color: Theme.bad)
                    } else {
                        StatusChip(text: (db.trustStatus ?? (db.running ? "online" : "offline")).uppercased(),
                                   color: db.running ? Theme.good : Theme.bad)
                    }
                }
                HStack(spacing: 6) {
                    StatusChip(text: db.running ? "RUNNING" : "STOPPED",
                               color: db.running ? Theme.good : Theme.bad)
                    if db.earning {
                        StatusChip(text: "EARNING", color: Theme.good)
                    }
                    Spacer()
                }
                KVRow("Version", db.version ?? "—")
                KVRow("Process", db.pid.map { "pid \($0)" } ?? "—")
                KVRow("Uptime", fmtDuration(db.uptimeSeconds.map { Double($0) }))
                KVRow("State", db.lifecycle ?? "—")
                KVRow("Currently serving", db.currentModel.map(shortModelName) ?? "—")
                SectionHeader("Trust & attestation")
                KVRow("Trust level", db.trustLevel ?? "—")
                KVRow("Reason", db.trustReason ?? "—")
                KVRow("Path", db.trustPath ?? "—")
                KVRow("Session expires", sessionExpiryText(db.trustSessionExpiresAt))
                KVRow("App Attest", attestText(db.attestAttested))
                KVRow("Attest key age", fmtDuration(db.attestKeyAgeSeconds.map { Double($0) }))
                KVRow("Last Apple success",
                      db.attestLastSuccessAgeSeconds.map { fmtDuration(Double($0)) + " ago" } ?? "—")
                if db.verificationPending {
                    Text("Verification is still pending on Darkbloom's coordinator. Paid jobs and base rewards land anyway — the ledger is the ground truth.")
                        .font(.caption)
                        .foregroundStyle(Theme.warn)
                }
            }
        }
    }

    /// trust.session_expires_at is epoch seconds on the wire:
    /// "in 5h 12m" while valid, "expired" once past (the session
    /// silently stops earning then — surface it before it bites).
    private func sessionExpiryText(_ epochSec: Double?) -> String {
        guard let epochSec, epochSec.isFinite, epochSec > 0 else { return "—" }
        let remaining = epochSec - Date().timeIntervalSince1970
        if remaining <= 0 { return "expired" }
        return "in " + fmtDuration(remaining)
    }

    private func attestText(_ v: Bool?) -> String {
        switch v {
        case .some(true): return "attested ✓"
        case .some(false): return "not attested"
        case .none: return "—"
        }
    }

    // MARK: - Earnings

    private func earningsCard(_ db: DarkbloomStats, _ e: Earnings?) -> some View {
        DashCard(title: "Earnings") {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("USD via Stripe · $1 minimum withdrawal · 0% fee")
                        .font(.caption)
                        .foregroundStyle(Theme.textDim)
                    Spacer()
                    if !vm.connected {
                        StatusChip(text: "OFFLINE", color: Theme.bad)
                    } else if db.earning {
                        StatusChip(text: "EARNING", color: Theme.good)
                    } else if db.running {
                        StatusChip(text: "NOT EARNING YET", color: Theme.warn)
                    } else {
                        StatusChip(text: "STOPPED", color: Theme.bad)
                    }
                }
                if let e {
                    Text(fmtUsd(e.balanceUsd))
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(cMist)
                    Text("available balance")
                        .font(.caption)
                        .foregroundStyle(Theme.textDim)
                    milestoneBlock(e)
                    KVRow("Withdrawable", fmtUsd(e.withdrawableUsd))
                    KVRow("Lifetime total", fmtUsd(e.totalUsd))
                    KVRow("Paid jobs (recent history)", countText(e.jobsInHistory.map { Int64($0) }))
                    KVRow("Base rewards (recent)", microToUsd(e.baseRewardsMicroRecent))
                    perModelBlock(e)
                    recentBlock(e)
                } else {
                    // No ledger data in this sample: say so — never
                    // fabricate a $0 balance or a "$1.00 to go" claim
                    // about an unknown amount.
                    Text("—")
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(cMist)
                    Text("available balance")
                        .font(.caption)
                        .foregroundStyle(Theme.textDim)
                    Text("Earnings unavailable from the bridge right now.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.warn)
                }
                KVRow("Requests served (session)", countText(db.requestsServed))
                KVRow("Tokens generated (session)", countText(db.tokensGenerated))
            }
        }
    }

    @ViewBuilder
    private func milestoneBlock(_ e: Earnings) -> some View {
        let bal = e.balanceUsd.flatMap { Double($0) }.flatMap { $0.isFinite ? $0 : nil } ?? 0
        let ms = nextMilestone(bal)
        VStack(alignment: .leading, spacing: 6) {
            MiniBar(fraction: (bal - ms.prev) / (ms.next - ms.prev), color: Theme.good, height: 8)
            if bal >= 1.0 {
                Text("Above the $1 minimum — withdrawable anytime · \(fmtUsd(ms.next - bal)) to the \(fmtUsd(ms.next)) milestone")
                    .font(.caption)
                    .foregroundStyle(Theme.good)
            } else {
                Text("\(fmtUsd(1.0 - bal)) to the $1 withdrawal minimum")
                    .font(.caption)
                    .foregroundStyle(Theme.textDim)
            }
        }
    }

    @ViewBuilder
    private func perModelBlock(_ e: Earnings) -> some View {
        if !e.perModel.isEmpty {
            let maxMicro = max(e.perModel.map(\.microUsd).max() ?? 1, 1)
            SectionHeader("Earnings by model")
            ForEach(e.perModel, id: \.model) { pm in
                VStack(spacing: 4) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(pm.model == "base_reward" ? "Base reward (online floor)" : shortModelName(pm.model))
                                .font(.subheadline)
                                .foregroundStyle(cMist)
                                .lineLimit(1)
                            Text("\(pm.jobs) jobs")
                                .font(.caption)
                                .foregroundStyle(cFaint)
                        }
                        Spacer()
                        Text(microToUsd(pm.microUsd))
                            .font(.headline)
                            .foregroundStyle(Theme.good)
                    }
                    MiniBar(fraction: Double(pm.microUsd) / Double(maxMicro), color: Theme.good)
                }
            }
        }
    }

    @ViewBuilder
    private func recentBlock(_ e: Earnings) -> some View {
        if !e.recent.isEmpty {
            SectionHeader("Recent jobs")
            ForEach(Array(e.recent.enumerated()), id: \.offset) { _, entry in
                HStack(alignment: .top, spacing: 10) {
                    Circle()
                        .fill(Theme.good)
                        .frame(width: 8, height: 8)
                        .padding(.top, 5)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(entry.model == "base_reward" ? "Base reward" : shortModelName(entry.model))
                            .font(.subheadline)
                            .foregroundStyle(cMist)
                            .lineLimit(1)
                        Text(recentSubline(entry))
                            .font(.caption)
                            .foregroundStyle(cFaint)
                            .lineLimit(1)
                    }
                    Spacer()
                    Text(microToUsd(entry.amountMicroUsd))
                        .font(.subheadline)
                        .foregroundStyle(Theme.good)
                }
            }
        }
    }

    private func recentSubline(_ entry: EarningEntry) -> String {
        var parts: [String] = []
        if let p = entry.promptTokens { parts.append("in \(countText(p))") }
        if let c = entry.completionTokens { parts.append("out \(countText(c))") }
        let t = ledgerTime(entry.createdAt)
        if !t.isEmpty { parts.append(t) }
        return parts.joined(separator: " · ")
    }

    // MARK: - Estimates

    private func estimatesCard(_ est: EarningsEstimates) -> some View {
        DashCard(title: "Estimated returns") {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("live · realized rates from the bridge ledger" +
                         (est.confidence.map { " · confidence: \($0)" } ?? ""))
                        .font(.caption)
                        .foregroundStyle(Theme.textDim)
                    Spacer()
                    StatusChip(text: vm.connected ? "LIVE" : "OFFLINE",
                               color: vm.connected ? Theme.good : Theme.bad)
                }
                HStack(alignment: .top, spacing: 12) {
                    EstBox(label: "PER DAY", value: est.perDayUsd)
                    EstBox(label: "PER WEEK", value: est.perWeekUsd)
                    EstBox(label: "PER MONTH", value: est.perMonthUsd)
                }
                if est.basePerDayUsd != nil || est.jobsPerDayUsd != nil {
                    KVRow("Base floor (per day)", fmtUsd(est.basePerDayUsd))
                    KVRow("Paid jobs (per day)", fmtUsd(est.jobsPerDayUsd))
                }
                if let f = est.onlineFraction {
                    KVRow("Provider online", "\(Int(f * 100))% of tracked time")
                }
                if let w = est.whileServingPerDayUsd {
                    KVRow("While serving (per day)", fmtUsd(w))
                }
                basisText(est)
                Text("Estimate — moves as real earnings land; includes downtime.")
                    .font(.caption)
                    .foregroundStyle(Theme.textDim)
            }
        }
    }

    private func basisText(_ est: EarningsEstimates) -> some View {
        var parts: [String] = []
        if let b = est.basisLabel { parts.append(b) }
        if let ts = est.trackedSinceEpochSec { parts.append("tracked since \(clockText(ts))") }
        return Group {
            if !parts.isEmpty {
                Text(parts.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(Theme.textDim)
            }
        }
    }

    // MARK: - Models & slots

    private func modelsCard(_ db: DarkbloomStats) -> some View {
        DashCard(title: "Models") {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("\(db.warmModels.count) warm of \(db.advertisedModels.count) advertised")
                        .font(.caption)
                        .foregroundStyle(Theme.textDim)
                    Spacer()
                    if !vm.connected {
                        StatusChip(text: "OFFLINE", color: Theme.bad)
                    }
                }
                if db.residentModels.isEmpty && db.advertisedModels.isEmpty {
                    Text("No model data from the provider yet.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.textDim)
                }
                ForEach(db.residentModels, id: \.model) { r in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(shortModelName(r.model))
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(cMist)
                                .lineLimit(1)
                            Spacer()
                            if vm.connected {
                                StatusChip(text: "WARM", color: Theme.good)
                            } else {
                                StatusChip(text: "WARM · LAST KNOWN", color: Theme.textDim)
                            }
                        }
                        KVRow("Resident", "\(gbText(r.residentGb)) (weights \(gbText(r.weightsGb)))")
                        KVRow("Idle", fmtDuration(r.idleSeconds.map { Double($0) }))
                        KVRow("Engine", engineText(db, r))
                    }
                }
                ForEach(db.advertisedModels.filter { !db.warmModels.contains($0) }, id: \.self) { m in
                    HStack {
                        Text(shortModelName(m))
                            .font(.subheadline)
                            .foregroundStyle(Theme.textDim)
                            .lineLimit(1)
                        Spacer()
                        StatusChip(text: "COLD", color: Theme.textDim)
                    }
                }
            }
        }
    }

    private func engineText(_ db: DarkbloomStats, _ r: ResidentModel) -> String {
        let slot = db.slots.first { $0.model == r.model }
        var parts: [String] = []
        if let kv = slot?.kvBackend, !kv.isEmpty { parts.append("KV \(kv)") }
        if slot?.mtpActive == true { parts.append("MTP on") }
        else if slot?.mtpEnabled == true { parts.append("MTP idle") }
        return parts.isEmpty ? "—" : parts.joined(separator: " · ")
    }

    // MARK: - GPU capacity & autopilot

    private func gpuCard(_ db: DarkbloomStats) -> some View {
        DashCard(title: "GPU capacity") {
            VStack(alignment: .leading, spacing: 10) {
                Text("unified memory pool")
                    .font(.caption)
                    .foregroundStyle(Theme.textDim)
                // Never invent a denominator: if the bridge didn't report
                // total capacity, show the rows without a meter.
                if let total = db.capacityTotalGb, total > 0 {
                    MiniBar(fraction: (db.gpuActiveGb ?? 0) / total, color: Theme.good, height: 10)
                }
                KVRow("Active", gbText(db.gpuActiveGb))
                KVRow("Cache", gbText(db.gpuCacheGb, digits: 2))
                KVRow("Free for new loads", gbText(db.freeForLoadGb))
                KVRow("Total", gbText(db.capacityTotalGb, digits: 0))
                SectionHeader("Autopilot")
                KVRow("Phase", db.autopilotPhase ?? "—")
                KVRow("Enabled", db.autopilotEnabled.map { $0 ? "yes" : "no" } ?? "—")
                KVRow("Mode", db.autopilotObserveOnly.map { $0 ? "shadow / observe only" : "active" } ?? "—")
            }
        }
    }
}
