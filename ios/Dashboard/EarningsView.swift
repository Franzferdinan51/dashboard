import SwiftUI
import Foundation

/// The accumulative page: the only place Darkbloom and mining numbers
/// are summed. Each stream keeps its own tab with its own unblended
/// figures; this tab states plainly that the two sides are different
/// kinds of truth (Darkbloom realized from the bridge ledger, mining
/// verified from the miner's own log). Mirrors Android's EarningsScreen
/// plus the Darkbloom earnings/estimates cards and the mining
/// verified card.
struct EarningsView: View {
    @EnvironmentObject private var vm: DashboardViewModel

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                let est = vm.overview?.darkbloom.earnings?.estimates
                if est == nil && vm.mining == nil {
                    DashCard {
                        Text("Waiting for earnings data from the bridge…")
                            .font(.system(size: 15))
                            .foregroundStyle(Theme.textDim)
                    }
                } else {
                    combinedCard
                    combinedGraphCard
                    darkbloomCard
                    estimatesCard
                    miningVerifiedCard
                }
            }
            .padding(16)
        }
        .background(Theme.bg)
        .refreshable { await vm.refresh() }
    }

    // MARK: - Combined hero

    private var combinedCard: some View {
        let earnings = vm.overview?.darkbloom.earnings
        let dbDay = earnings?.estimates?.perDayUsd
        let mineDay = vm.mining?.batmanVerified?.usdPerDayNet
        let day = (dbDay ?? 0) + (mineDay ?? 0)
        return DashCard {
            cardHeader("Combined earnings", "Darkbloom + mining")
            HStack(alignment: .top, spacing: 12) {
                combinedStat("Combined / day", fmtUsd(day))
                combinedStat("Combined / week", fmtUsd(day * 7))
                combinedStat("Combined / month", fmtUsd(day * 30))
            }
            if day > 0 {
                splitBar(dbFraction: (dbDay ?? 0) / day, mineFraction: (mineDay ?? 0) / day)
            }
            KVRow("Darkbloom (realized, bridge ledger)", rateLine(dbDay))
            KVRow("Mining — Batman-3 (verified, miner log)", rateLine(mineDay))
            KVRow("Darkbloom share",
                  (dbDay != nil && day > 0) ? "\(Int((dbDay! / day * 100).rounded()))%" : "—")
            KVRow("Mining share",
                  (mineDay != nil && day > 0) ? "\(Int((mineDay! / day * 100).rounded()))%" : "—")
            KVRow("Darkbloom balance", fmtUsd(earnings?.balanceUsd))
            Text("Two different kinds of truth, both computed by the bridge: Darkbloom is realized money from its earnings ledger; mining is Batman-3 only, from the miner's own log at live network rates. The rest of the Kryptex fleet is excluded — no trusted source for it.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textFaint)
        }
    }

    private func combinedStat(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label.uppercased())
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Theme.textFaint)
            Text(value)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(Theme.mist)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Stacked split bar: Darkbloom (sky) vs mining (amber).
    private func splitBar(dbFraction: Double, mineFraction: Double) -> some View {
        GeometryReader { geo in
            HStack(spacing: 0) {
                Rectangle()
                    .fill(Theme.sky)
                    .frame(width: geo.size.width * CGFloat(dbFraction.isFinite ? min(max(dbFraction, 0), 1) : 0))
                Rectangle()
                    .fill(Theme.warn)
                    .frame(width: geo.size.width * CGFloat(mineFraction.isFinite ? min(max(mineFraction, 0), 1) : 0))
            }
        }
        .frame(height: 8)
        .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
        .background(Theme.track, in: RoundedRectangle(cornerRadius: 4, style: .continuous))
    }

    /// "X · Y/wk · Z/mo" for a per-day rate, or an em dash.
    private func rateLine(_ perDay: Double?) -> String {
        guard let perDay else { return "—" }
        return "\(fmtUsd(perDay)) · \(fmtUsd(perDay * 7))/wk · \(fmtUsd(perDay * 30))/mo"
    }

    // MARK: - Combined graph

    /// Darkbloom's exact ledger growth with mining modeled on top at
    /// the verified rate — only when both inputs exist.
    @ViewBuilder
    private var combinedGraphCard: some View {
        if let est = vm.overview?.darkbloom.earnings?.estimates,
           let mineDay = vm.mining?.batmanVerified?.usdPerDayNet,
           est.series.count >= 2,
           let first = est.series.first, let last = est.series.last {
            let t0 = first.tsSec
            let base = first.totalMicroUsd
            let combined: [Double] = est.series.map {
                Double($0.totalMicroUsd - base) / 1e6 + ($0.tsSec - t0) * mineDay / 86400.0
            }
            DashCard {
                cardHeader("Combined tracked earnings",
                           "Since \(earnDateTime(t0)) · Darkbloom exact from the ledger, mining modeled at the verified rate")
                EarnSparkline(values: combined, color: Theme.good, height: 84)
                HStack {
                    Text("Combined \(fmtUsd(combined.last))")
                        .foregroundStyle(Theme.good)
                    Spacer()
                    Text("Darkbloom \(fmtUsd(Double(last.totalMicroUsd - base) / 1e6))")
                        .foregroundStyle(Theme.sky)
                    Spacer()
                    Text("Mining (modeled) \(fmtUsd((last.tsSec - t0) * mineDay / 86400.0))")
                        .foregroundStyle(Theme.warn)
                }
                .font(.system(size: 12))
            }
        }
    }

    // MARK: - Darkbloom earnings detail

    @ViewBuilder
    private var darkbloomCard: some View {
        if let db = vm.overview?.darkbloom {
            let e = db.earnings
            DashCard {
                HStack(alignment: .top, spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("DARKEBLOOM EARNINGS")
                            .font(.system(size: 12, weight: .semibold))
                            .tracking(0.6)
                            .foregroundStyle(Theme.textDim)
                        Text("USD via Stripe · $1 minimum withdrawal · 0% fee")
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textFaint)
                    }
                    Spacer(minLength: 0)
                    StatusChip(text: darkbloomStatusText(db), color: darkbloomStatusColor(db))
                }
                if let e {
                    Text(fmtUsd(e.balanceUsd))
                        .font(.system(size: 32, weight: .bold))
                        .foregroundStyle(Theme.mist)
                    Text("available balance")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textDim)
                    milestoneBlock(balance: e.balanceUsd)
                    KVRow("Withdrawable", fmtUsd(e.withdrawableUsd))
                    KVRow("Lifetime total", fmtUsd(e.totalUsd))
                    KVRow("Paid jobs (recent history)", earnCount(e.jobsInHistory.map { Int64($0) }))
                    KVRow("Base rewards (recent)", microToUsd(e.baseRewardsMicroRecent))
                } else {
                    Text("—")
                        .font(.system(size: 32, weight: .bold))
                        .foregroundStyle(Theme.mist)
                    Text("available balance")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textDim)
                    Text("Earnings unavailable from the bridge right now.")
                        .font(.system(size: 15))
                        .foregroundStyle(Theme.warn)
                }
                KVRow("Requests served (session)", earnCount(db.requestsServed))
                KVRow("Tokens generated (session)", earnCount(db.tokensGenerated))
            }
        }
    }

    private func darkbloomStatusText(_ db: DarkbloomStats) -> String {
        if !vm.connected { return "OFFLINE" }
        if db.earning { return "EARNING" }
        if db.running { return "NOT EARNING YET" }
        return "STOPPED"
    }

    private func darkbloomStatusColor(_ db: DarkbloomStats) -> Color {
        if !vm.connected { return Theme.bad }
        if db.earning { return Theme.good }
        if db.running { return Theme.warn }
        return Theme.bad
    }

    /// Progress toward the next milestone: the $1 withdrawal minimum
    /// first, then rolling 2/5 × 10^n targets.
    @ViewBuilder
    private func milestoneBlock(balance: String?) -> some View {
        let bal = balance.flatMap { Double($0) }.flatMap { $0.isFinite ? $0 : nil } ?? 0
        let (prev, next) = nextMilestone(bal)
        VStack(alignment: .leading, spacing: 6) {
            EarnMeterBar(fraction: (bal - prev) / (next - prev), color: Theme.good, height: 8)
            if bal >= 1.0 {
                Text("Above the $1 minimum — withdrawable anytime · \(fmtUsd(next - bal)) to the \(fmtUsd(next)) milestone")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.good)
            } else {
                Text("\(fmtUsd(1.0 - bal)) to the $1 withdrawal minimum")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textDim)
            }
        }
    }

    // MARK: - Estimates

    @ViewBuilder
    private var estimatesCard: some View {
        if let est = vm.overview?.darkbloom.earnings?.estimates {
            DashCard {
                HStack(alignment: .top, spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("ESTIMATED RETURNS")
                            .font(.system(size: 12, weight: .semibold))
                            .tracking(0.6)
                            .foregroundStyle(Theme.textDim)
                        Text("live · realized rates from the bridge ledger"
                             + (est.confidence.map { " · confidence: \($0)" } ?? "")
                             + (est.method.map { " · method: \($0)" } ?? ""))
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textFaint)
                    }
                    Spacer(minLength: 0)
                    StatusChip(text: vm.connected ? "LIVE" : "OFFLINE",
                               color: vm.connected ? Theme.good : Theme.bad)
                }
                HStack(alignment: .top, spacing: 12) {
                    estBox("PER DAY", est.perDayUsd)
                    estBox("PER WEEK", est.perWeekUsd)
                    estBox("PER MONTH", est.perMonthUsd)
                }
                if est.series.count >= 2 {
                    EarnSparkline(values: est.series.map { Double($0.totalMicroUsd) / 1e6 },
                                  color: Theme.good, height: 54)
                    Text("cumulative earnings · bridge ledger")
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.textDim)
                }
                if est.basePerDayUsd != nil || est.jobsPerDayUsd != nil {
                    KVRow("Base floor (per day)", fmtUsd(est.basePerDayUsd))
                    KVRow("Paid jobs (per day)", fmtUsd(est.jobsPerDayUsd))
                }
                if est.compLastHourUsd != nil || est.compLast6hUsd != nil
                    || est.compLast24hUsd != nil || est.compLast7dUsd != nil {
                    SectionHeader("Realized rate by window")
                        .padding(.top, 2)
                    KVRow("Last hour", est.compLastHourUsd != nil ? "\(fmtUsd(est.compLastHourUsd))/day" : "—")
                    KVRow("Last 6 hours", est.compLast6hUsd != nil ? "\(fmtUsd(est.compLast6hUsd))/day" : "—")
                    KVRow("Last 24 hours", est.compLast24hUsd != nil ? "\(fmtUsd(est.compLast24hUsd))/day" : "—")
                    KVRow("Last 7 days", est.compLast7dUsd != nil ? "\(fmtUsd(est.compLast7dUsd))/day" : "—")
                }
                if let online = est.onlineFraction {
                    KVRow("Provider online", "\(Int(online * 100))% of tracked time")
                }
                if let serving = est.whileServingPerDayUsd {
                    KVRow("While serving (per day)", fmtUsd(serving))
                }
                let basis = basisText(est)
                if !basis.isEmpty {
                    Text(basis)
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textDim)
                }
                Text("Estimate — moves as real earnings land; includes downtime.")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textDim)
            }
        }
    }

    private func estBox(_ label: String, _ value: Double) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Theme.textDim)
            Text(fmtUsd(value))
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(Theme.mist)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func basisText(_ est: EarningsEstimates) -> String {
        var parts: [String] = []
        if let label = est.basisLabel { parts.append(label) }
        if let since = est.trackedSinceEpochSec {
            parts.append("tracked since \(earnDateTime(since))")
        }
        return parts.joined(separator: " · ")
    }

    // MARK: - Mining verified summary

    @ViewBuilder
    private var miningVerifiedCard: some View {
        if let mining = vm.mining {
            DashCard {
                cardHeader("Batman-3 — verified mining earnings", "miner log × live network")
                if let ver = mining.batmanVerified {
                    Text("\(fmtUsd(ver.usdPerDayNet)) /day net")
                        .font(.system(size: 30, weight: .bold))
                        .foregroundStyle(Theme.mist)
                    KVRow("Verified gross",
                          "\(fmtUsd(ver.usdPerDayGross))/day · \(ver.prlPerDayGross.map { "\($0)" } ?? "—") PRL/day")
                    KVRow("Verified / week · / month",
                          "\(fmtUsd(ver.usdPerWeekNet)) · \(fmtUsd(ver.usdPerMonthNet))")
                    KVRow("Hashrate basis",
                          "\(ver.hashrateBasisThs.map { fmtThs($0) } ?? "—") (\(ver.hashrateBasis ?? "miner log"))")
                    KVRow("PRL price",
                          "\(fmtUsd(ver.priceUsd)) (\(ver.priceSource ?? "—"))")
                    KVRow("Network",
                          "\(earnFixed((ver.nethashThs ?? 0) / 1e6, 1)) EH/s · \(earnFixed4(ver.usdPerThDay))/TH/day · fee \(ver.poolFeePct.map { "\($0)" } ?? "—")%")
                    KVRow("Kryptex claims",
                          ver.kryptexClaimPerDayUsd != nil
                            ? "\(fmtUsd(ver.kryptexClaimPerDayUsd))/day — verified runs \(earnSigned(ver.deltaVsClaimPct ?? 0))% vs the claim"
                            : "—")
                    Text("Computed from the miner's own log (sustained average hashrate) × live Pearl network emission (WhatToMine) × live PRL price (CoinGecko), less Kryptex's 3% pool fee. Kryptex's dashboard figures are not an input.")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textFaint)
                } else {
                    Text("Waiting on live hashrate + network data…")
                        .font(.system(size: 15))
                        .foregroundStyle(Theme.textDim)
                }
            }
        }
    }

    // MARK: - Shared bits

    /// Android's CardHeader for cards that don't need a trailing chip.
    @ViewBuilder
    private func cardHeader(_ title: String, _ subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title.uppercased())
                .font(.system(size: 12, weight: .semibold))
                .tracking(0.6)
                .foregroundStyle(Theme.textDim)
            Text(subtitle)
                .font(.system(size: 13))
                .foregroundStyle(Theme.textFaint)
        }
    }
}

/// (previous, next) milestone bracket for a balance: $1 first (the
/// withdrawal minimum), then 2/5 × 10^n steps —
/// 1 → 2 → 5 → 10 → 20 → 50 → 100 …
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

/// Grouped count ("1,234") or an em dash — Android's fmtCount.
private func earnCount(_ n: Int64?) -> String {
    guard let n else { return "—" }
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.locale = Locale(identifier: "en_US")
    return f.string(from: NSNumber(value: n)) ?? "\(n)"
}

/// "MMM d, h:mm a" in en_US, matching Android's fmtClock.
private func earnDateTime(_ epochSec: Double) -> String {
    let f = DateFormatter()
    f.dateFormat = "MMM d, h:mm a"
    f.locale = Locale(identifier: "en_US")
    return f.string(from: Date(timeIntervalSince1970: epochSec))
}

private func earnFixed(_ v: Double, _ digits: Int) -> String {
    String(format: "%.\(digits)f", v)
}

private func earnFixed4(_ v: Double?) -> String {
    guard let v, v.isFinite else { return "—" }
    return String(format: "$%.4f", v)
}

private func earnSigned(_ v: Double) -> String {
    String(format: "%+.1f", v)
}

/// Slim progress bar — Android's MeterBar. Fraction is sanitized and
/// clamped before it can reach layout.
private struct EarnMeterBar: View {
    let fraction: Double
    let color: Color
    var height: CGFloat = 8

    private var clamped: Double {
        fraction.isFinite ? min(max(fraction, 0), 1) : 0
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: height / 2, style: .continuous)
                    .fill(Theme.track)
                if clamped > 0.005 {
                    RoundedRectangle(cornerRadius: height / 2, style: .continuous)
                        .fill(color)
                        .frame(width: geo.size.width * clamped)
                }
            }
        }
        .frame(height: height)
    }
}

/// Minimal cumulative-series line chart (Path-based). Fewer than two
/// finite points draws a flat placeholder line instead of a path —
/// a single bad sample must never blank the chart.
private struct EarnSparkline: View {
    let values: [Double]
    let color: Color
    var height: CGFloat = 54

    var body: some View {
        GeometryReader { geo in
            let clean = values.filter { $0.isFinite }
            if clean.count >= 2, let lo = clean.min(), let hi = clean.max() {
                let span = max(hi - lo, 0.000_001)
                let points: [CGPoint] = clean.enumerated().map { i, v in
                    CGPoint(
                        x: geo.size.width * CGFloat(i) / CGFloat(clean.count - 1),
                        y: geo.size.height - 3 - CGFloat((v - lo) / span) * (geo.size.height - 6)
                    )
                }
                Path { path in
                    path.move(to: points[0])
                    for p in points.dropFirst() { path.addLine(to: p) }
                    path.addLine(to: CGPoint(x: geo.size.width, y: geo.size.height))
                    path.addLine(to: CGPoint(x: 0, y: geo.size.height))
                    path.closeSubpath()
                }
                .fill(color.opacity(0.15))
                Path { path in
                    path.move(to: points[0])
                    for p in points.dropFirst() { path.addLine(to: p) }
                }
                .stroke(color, style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round))
            } else {
                Path { path in
                    path.move(to: CGPoint(x: 0, y: geo.size.height / 2))
                    path.addLine(to: CGPoint(x: geo.size.width, y: geo.size.height / 2))
                }
                .stroke(color.opacity(0.4), lineWidth: 1.5)
            }
        }
        .frame(height: height)
    }
}
