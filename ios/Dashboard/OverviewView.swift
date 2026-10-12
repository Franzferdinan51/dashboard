import SwiftUI

// Muse palette extras not in the shared Theme contract (hexes from the
// Android theme): near-white text, sky, soft blue, faint text.
private let cMist = Color(red: 0.957, green: 0.973, blue: 1.000)
private let cSky = Color(red: 0.220, green: 0.741, blue: 0.973)
private let cSoft = Color(red: 0.478, green: 0.702, blue: 1.000)
private let cFaint = Color(red: 0.431, green: 0.525, blue: 0.678)

/// "99.3" style shortest-form number, or the absent placeholder.
private func dstr(_ v: Double?) -> String {
    guard let v, v.isFinite else { return "—" }
    return String(describing: v)
}

private func gbText(_ gb: Double?) -> String {
    guard let gb, gb.isFinite else { return "—" }
    return String(format: "%.1f GB", gb)
}

private struct MiniStat: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption2.weight(.semibold))
                .textCase(.uppercase)
                .foregroundStyle(cFaint)
            Text(value)
                .font(.headline)
                .foregroundStyle(cMist)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct SpecChip: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.medium))
            .foregroundStyle(Theme.textDim)
            .lineLimit(1)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Color.white.opacity(0.05))
            .clipShape(Capsule())
            .overlay(Capsule().stroke(Color.white.opacity(0.10), lineWidth: 1))
    }
}

struct OverviewView: View {
    @EnvironmentObject private var vm: DashboardViewModel

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                statusLine
                heroCard
                if let ov = vm.overview {
                    gaugeCard(ov)
                    combinedCard(ov)
                    miningStrip
                } else {
                    connectingCard
                }
            }
            .padding(16)
        }
        .background(Theme.bg)
        .refreshable { await vm.refresh() }
    }

    // MARK: - Connection status (Android header behavior, Overview only)

    private var statusLine: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(vm.connected ? Theme.good : Theme.bad)
                .frame(width: 8, height: 8)
            Text(vm.connected ? "Live" : "Offline")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Theme.textDim)
            if !vm.connected, let err = vm.lastError {
                Text(err)
                    .font(.caption)
                    .foregroundStyle(Theme.bad)
                    .lineLimit(1)
            }
            Spacer()
        }
    }

    // MARK: - Machine hero

    private var heroCard: some View {
        let sys = vm.overview?.system
        var parts: [String] = []
        if let chip = sys?.chip, !chip.isEmpty { parts.append(chip) }
        if let v = sys?.macosVersion, !v.isEmpty { parts.append("macOS \(v)") }
        let subtitle = parts.isEmpty ? "Waiting for bridge…" : parts.joined(separator: " · ")
        var specs: [String] = []
        if let c = sys?.cpuCores { specs.append("\(c) CPU cores") }
        if let g = sys?.gpuCores { specs.append("\(g) GPU cores") }
        if let m = sys?.model, !m.isEmpty { specs.append(m) }

        return DashCard(title: nil) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "desktopcomputer")
                        .font(.title3)
                        .foregroundStyle(cSoft)
                        .frame(width: 44, height: 44)
                        .background(Theme.accent.opacity(0.18))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(sys?.computerName ?? "Mac Studio")
                            .font(.title3.bold())
                            .foregroundStyle(cMist)
                            .lineLimit(1)
                        Text(subtitle)
                            .font(.subheadline)
                            .foregroundStyle(Theme.textDim)
                            .lineLimit(1)
                    }
                    Spacer()
                    if vm.connected {
                        StatusChip(text: "ONLINE", color: Theme.good)
                    } else if vm.overview != nil {
                        StatusChip(text: "OFFLINE", color: Theme.bad)
                    } else {
                        StatusChip(text: "CONNECTING", color: Theme.warn)
                    }
                }
                if !specs.isEmpty {
                    HStack(spacing: 8) {
                        ForEach(specs, id: \.self) { SpecChip(text: $0) }
                    }
                }
                HStack(alignment: .top, spacing: 8) {
                    MiniStat(label: "Uptime", value: fmtDuration(sys?.uptimeSeconds.map { Double($0) }))
                    MiniStat(label: "Load", value: loadText(sys))
                    MiniStat(label: "Thermal", value: (sys?.thermal.state ?? "—").capitalized)
                    MiniStat(label: "Net in", value: fmtRate(sys?.network.rateInBps))
                }
            }
        }
    }

    private func loadText(_ sys: SystemStats?) -> String {
        guard let avg = sys?.loadAvg, !avg.isEmpty else { return "—" }
        return avg.map { String(format: "%.2f", $0) }.joined(separator: " ")
    }

    private var connectingCard: some View {
        DashCard(title: nil) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Connecting to your Mac…")
                    .font(.headline)
                    .foregroundStyle(cMist)
                Text(vm.lastError ?? "Reaching the bridge at \(vm.bridgeURLString)")
                    .font(.subheadline)
                    .foregroundStyle(Theme.textDim)
                Text("Pull down to retry, check the bridge is running on the Mac, or update the address in Settings.")
                    .font(.caption)
                    .foregroundStyle(cFaint)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: - Live load gauges

    private func gaugeCard(_ ov: OverviewResponse) -> some View {
        let cpu = ov.system.cpu.usedPct
        let ram = ov.system.memory
        let gpuPct: Double = {
            if let a = ov.darkbloom.gpuActiveGb, let t = ov.darkbloom.capacityTotalGb, t > 0 {
                return a / t * 100
            }
            return 0
        }()
        return DashCard(title: "Live load") {
            HStack(alignment: .top, spacing: 8) {
                GaugeView(
                    fraction: cpu / 100,
                    percentText: fmtPct(cpu),
                    label: "CPU",
                    sublabel: "\(ov.system.cpuCores.map(String.init) ?? "—") cores",
                    color: Theme.accent
                )
                .frame(maxWidth: .infinity)
                GaugeView(
                    fraction: ram.usedPct / 100,
                    percentText: fmtPct(ram.usedPct),
                    label: "Memory",
                    sublabel: "\(fmtBytes(ram.usedBytes)) used",
                    color: cSky
                )
                .frame(maxWidth: .infinity)
                GaugeView(
                    fraction: gpuPct / 100,
                    percentText: fmtPct(gpuPct),
                    label: "GPU memory",
                    sublabel: gbText(ov.darkbloom.gpuActiveGb),
                    color: Theme.good
                )
                .frame(maxWidth: .infinity)
            }
        }
    }

    // MARK: - Combined profit

    private func combinedCard(_ ov: OverviewResponse) -> some View {
        let dbDay = ov.darkbloom.earnings?.estimates?.perDayUsd
        let mineDay = vm.mining?.batmanVerified?.usdPerDayNet
        return DashCard(title: "Combined profit") {
            VStack(alignment: .leading, spacing: 10) {
                Text("Darkbloom + mining · both computed by the bridge")
                    .font(.caption)
                    .foregroundStyle(Theme.textDim)
                if dbDay == nil && mineDay == nil {
                    Text("Waiting on earnings data…")
                        .font(.subheadline)
                        .foregroundStyle(Theme.textDim)
                } else {
                    let day = (dbDay ?? 0) + (mineDay ?? 0)
                    Text("\(fmtUsd(day)) /day")
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(cMist)
                    HStack(alignment: .top, spacing: 8) {
                        MiniStat(label: "Per week", value: fmtUsd(day * 7))
                        MiniStat(label: "Per month", value: fmtUsd(day * 30))
                        MiniStat(label: "Darkbloom balance", value: fmtUsd(ov.darkbloom.earnings?.balanceUsd))
                    }
                    KVRow("Darkbloom (realized, bridge ledger)",
                          dbDay != nil ? "\(fmtUsd(dbDay))/day" : "—")
                    KVRow("Mining — Batman-3 (verified, miner log)",
                          mineDay != nil ? "\(fmtUsd(mineDay))/day" : "—")
                    if let dbDay, let mineDay, day > 0 {
                        KVRow("Split",
                              "Darkbloom \(Int((dbDay / day * 100).rounded()))% · mining \(Int((mineDay / day * 100).rounded()))%")
                    }
                }
            }
        }
    }

    // MARK: - Batman-3 mining strip

    private var miningStrip: some View {
        let ver = vm.mining?.batmanVerified
        let live = vm.mining?.batmanLive
        return DashCard(title: "Mining — Batman-3") {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("verified from the miner's own log")
                        .font(.caption)
                        .foregroundStyle(Theme.textDim)
                    Spacer()
                    if live == nil {
                        StatusChip(text: "WAITING", color: Theme.textDim)
                    } else if live!.running {
                        StatusChip(text: "MINER RUNNING", color: Theme.good)
                    } else {
                        StatusChip(text: "MINER STOPPED", color: Theme.bad)
                    }
                }
                if let ver {
                    HStack(spacing: 10) {
                        Image(systemName: "bolt.fill")
                            .font(.title2)
                            .foregroundStyle(Theme.warn)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(fmtUsd(ver.usdPerDayNet)) /day net")
                                .font(.title2.bold())
                                .foregroundStyle(cMist)
                                .lineLimit(1)
                            Text("\(dstr(ver.prlPerDayGross)) PRL/day · \(fmtUsd(ver.usdPerDayGross)) gross")
                                .font(.caption)
                                .foregroundStyle(Theme.textDim)
                        }
                    }
                    KVRow("Hashrate now", fmtThs(live?.hashrateThs))
                    KVRow("Hashrate basis",
                          "\(fmtThs(ver.hashrateBasisThs)) (\(ver.hashrateBasis ?? "miner log"))")
                    KVRow("Verified / week · / month",
                          "\(fmtUsd(ver.usdPerWeekNet)) · \(fmtUsd(ver.usdPerMonthNet))")
                    KVRow("PRL price",
                          "\(fmtUsd(ver.priceUsd)) (\(ver.priceSource ?? "—"))")
                } else {
                    Text("Waiting for mining data from the bridge…")
                        .font(.subheadline)
                        .foregroundStyle(Theme.textDim)
                }
            }
        }
    }
}
