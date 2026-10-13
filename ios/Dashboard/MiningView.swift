import SwiftUI

private let cMist = Color(red: 0.957, green: 0.973, blue: 1.000)
private let cFaint = Color(red: 0.431, green: 0.525, blue: 0.678)

/// "99.3" style shortest-form number, or the absent placeholder.
private func dstr(_ v: Double?) -> String {
    guard let v, v.isFinite else { return "—" }
    return String(describing: v)
}

private func countText(_ v: Int64?) -> String {
    guard let v else { return "—" }
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.usesGroupingSeparator = true
    return f.string(from: NSNumber(value: v)) ?? "\(v)"
}

/// Four-decimal USD for the per-TH network rate.
private func usd4(_ v: Double?) -> String {
    guard let v, v.isFinite else { return "—" }
    return "$" + String(format: "%.4f", v)
}

/// "Oct 12, 4:55 PM" from epoch seconds.
private func clockText(_ epochSec: Double?) -> String {
    guard let epochSec, epochSec.isFinite else { return "—" }
    let f = DateFormatter()
    f.dateFormat = "MMM d, h:mm a"
    f.locale = Locale(identifier: "en_US")
    return f.string(from: Date(timeIntervalSince1970: epochSec))
}

/// Line chart of hashrate points (the miner's own log), plain Path —
/// callers guard the <2-point case.
private struct HashrateChart: View {
    let values: [Double]

    var body: some View {
        GeometryReader { geo in
            let minV = values.min() ?? 0
            let maxV = values.max() ?? 0
            let range = max(maxV - minV, 0.0001)
            Path { path in
                for (i, v) in values.enumerated() {
                    let x = geo.size.width * CGFloat(i) / CGFloat(max(values.count - 1, 1))
                    let y = geo.size.height - (CGFloat((v - minV) / range) * (geo.size.height - 8) + 4)
                    if i == 0 {
                        path.move(to: CGPoint(x: x, y: y))
                    } else {
                        path.addLine(to: CGPoint(x: x, y: y))
                    }
                }
            }
            .stroke(Theme.warn, lineWidth: 1.5)
        }
        .frame(height: 84)
    }
}

struct MiningView: View {
    @EnvironmentObject private var vm: DashboardViewModel

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if let mn = vm.mining {
                    verifiedCard(mn.batmanVerified)
                    if let h = mn.batmanHistory {
                        historyCard(h)
                    }
                    liveMinerCard(mn.batmanLive)
                } else {
                    DashCard(title: nil) {
                        Text("Waiting for mining data from the bridge…")
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

    // MARK: - Verified earnings (Batman-3 only)

    private func verifiedCard(_ ver: BatmanVerified?) -> some View {
        DashCard(title: "Batman-3 — verified mining earnings") {
            VStack(alignment: .leading, spacing: 10) {
                Text("miner log × live network")
                    .font(.caption)
                    .foregroundStyle(Theme.textDim)
                if let ver {
                    Text("\(fmtUsd(ver.usdPerDayNet)) /day net")
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(cMist)
                    KVRow("Verified gross",
                          "\(fmtUsd(ver.usdPerDayGross))/day · \(dstr(ver.prlPerDayGross)) PRL/day")
                    KVRow("Verified / week · / month",
                          "\(fmtUsd(ver.usdPerWeekNet)) · \(fmtUsd(ver.usdPerMonthNet))")
                    KVRow("Hashrate basis",
                          "\(fmtThs(ver.hashrateBasisThs)) (\(ver.hashrateBasis ?? "miner log"))")
                    KVRow("PRL price",
                          "\(fmtUsd(ver.priceUsd)) (\(ver.priceSource ?? "—"))")
                    KVRow("Network",
                          String(format: "%.1f EH/s · %@/TH/day · fee %@%%",
                                 (ver.nethashThs ?? 0) / 1e6, usd4(ver.usdPerThDay), dstr(ver.poolFeePct)))
                    KVRow("Kryptex claims (snapshot)", kryptexText(ver))
                    if let profit = vm.mining?.profit {
                        KVRow("Power",
                              "−\(fmtUsd(profit.powerCostDay))/day · \(dstr(profit.powerW)) W · \(dstr(profit.kwhPerDay)) kWh/day")
                        KVRow("Net after power",
                              "\(fmtUsd(profit.netDay))/day (at \(fmtKwhRate(profit.rateUsdKwh))/kWh)")
                    }
                    Text("Computed from the miner's own log (sustained average hashrate) × live Pearl network emission (WhatToMine) × live PRL price (CoinGecko), less Kryptex's 3% pool fee. Kryptex's dashboard figures are not an input.")
                        .font(.caption)
                        .foregroundStyle(cFaint)
                } else {
                    Text("Waiting on live hashrate + network data…")
                        .font(.subheadline)
                        .foregroundStyle(Theme.textDim)
                }
            }
        }
    }

    private func kryptexText(_ ver: BatmanVerified) -> String {
        guard let claim = ver.kryptexClaimPerDayUsd else { return "—" }
        return "\(fmtUsd(claim))/day — verified runs \(String(format: "%+.1f", ver.deltaVsClaimPct ?? 0))% vs the claim"
    }

    // MARK: - Hashrate history

    private func historyCard(_ h: BatmanHistory) -> some View {
        DashCard(title: "Hashrate history — the miner's log") {
            VStack(alignment: .leading, spacing: 10) {
                Text("\(dstr(h.spanHours))h of log · \(countText(h.sampleCount.map { Int64($0) })) samples")
                    .font(.caption)
                    .foregroundStyle(Theme.textDim)
                if h.points.count >= 2 {
                    HashrateChart(values: h.points.map(\.ths))
                } else {
                    Text("Collecting hashrate history…")
                        .font(.subheadline)
                        .foregroundStyle(Theme.textDim)
                }
                KVRow("Coverage", "\(clockText(h.coverageFromEpochSec)) → \(clockText(h.coverageToEpochSec))")
                KVRow("Average (active)", fmtThs(h.avgActiveThs))
                KVRow("Average (overall)", fmtThs(h.avgOverallThs))
                KVRow("Uptime in log",
                      h.uptimeFraction.map { "\(Int(($0 * 100).rounded()))%" } ?? "—")
                KVRow("Realized in window",
                      h.realizedPrlEst != nil
                          ? "\(dstr(h.realizedPrlEst)) PRL ≈ \(fmtUsd(h.realizedUsdNetEst)) net"
                          : "—")
                KVRow("Realized basis", h.realizedBasis ?? "—")
            }
        }
    }

    // MARK: - Live miner

    private func liveMinerCard(_ live: BatmanLive?) -> some View {
        DashCard(title: "Batman-3 — live miner") {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("via the Windows probe")
                        .font(.caption)
                        .foregroundStyle(Theme.textDim)
                    Spacer()
                    if live == nil {
                        StatusChip(text: "PC UNREACHABLE", color: Theme.bad)
                    } else if live!.running {
                        StatusChip(text: "MINER RUNNING", color: Theme.good)
                    } else {
                        StatusChip(text: "MINER STOPPED", color: Theme.bad)
                    }
                }
                if let live {
                    KVRow("Hashrate now", fmtThs(live.hashrateThs))
                    KVRow("Average 1h", fmtThs(live.avg1hThs))
                    KVRow("Miner power", live.powerW.map { "\(dstr($0)) W" } ?? "—")
                    KVRow("GPU temp", live.gpuTempC.map { "\(dstr($0))°C" } ?? "—")
                    KVRow("Shares (session)", sharesText())
                    if live.stale {
                        Text("Last known values — the PC isn't answering right now.")
                            .font(.caption)
                            .foregroundStyle(Theme.warn)
                    }
                }
            }
        }
    }

    private func sharesText() -> String {
        guard let win = vm.windows, let accepted = win.minerAccepted else { return "—" }
        return "\(countText(Int64(accepted))) accepted · \(countText(Int64(win.minerRejected ?? 0))) rejected"
    }
}
