import SwiftUI

/// Batman-3, the Windows PC, via the bridge's SSH probe. Mirrors
/// Android's WindowsScreen: hero card, detail card (CPU / memory /
/// GPU / volumes), miner card. When the PC isn't answering, nothing
/// is shown rather than stale guesses — except a cached sample, which
/// is flagged LAST KNOWN and dimmed.
struct WindowsView: View {
    @EnvironmentObject private var vm: DashboardViewModel

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if let w = vm.windows {
                    if !w.reachable {
                        offlineCard
                    } else {
                        heroCard(w)
                        detailCard(w)
                        minerCard(w)
                    }
                } else {
                    DashCard {
                        Text("Waiting for the first Windows sample from the bridge…")
                            .font(.system(size: 15))
                            .foregroundStyle(Theme.textDim)
                    }
                }
            }
            .padding(16)
        }
        .background(Theme.bg)
        .refreshable { await vm.refresh() }
    }

    // MARK: - Offline

    private var offlineCard: some View {
        DashCard {
            HStack(alignment: .top, spacing: 10) {
                Text("WINDOWS PC — BATMAN-3")
                    .font(.system(size: 12, weight: .semibold))
                    .tracking(0.6)
                    .foregroundStyle(Theme.textDim)
                Spacer(minLength: 0)
                StatusChip(text: "OFFLINE", color: Theme.bad)
            }
            Text("Batman-3 isn't answering — asleep, off, or SSH blocked. Nothing is shown rather than stale guesses.")
                .font(.system(size: 15))
                .foregroundStyle(Theme.textDim)
        }
    }

    // MARK: - Hero

    private func heroCard(_ w: WindowsData) -> some View {
        let subtitle = [w.osName, w.osVersion].compactMap { $0 }.joined(separator: " ")
        return DashCard {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("WINDOWS PC")
                        .font(.system(size: 12, weight: .semibold))
                        .tracking(0.6)
                        .foregroundStyle(Theme.textDim)
                    if !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textFaint)
                    }
                }
                Spacer(minLength: 0)
                StatusChip(text: w.stale ? "LAST KNOWN" : "ONLINE",
                           color: w.stale ? Theme.warn : Theme.good)
            }
            Text("Batman-3")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(Theme.mist)
            KVRow("Uptime", fmtDuration(w.uptimeSeconds.map { Double($0) }))
            KVRow("CPU load", w.cpuLoadPct.map { "\($0)%" } ?? "—")
            KVRow("Net in", fmtRate(w.netRateInBps.map { Int64($0) }))
            KVRow("Net out", fmtRate(w.netRateOutBps.map { Int64($0) }))
            if w.stale {
                Text("Last known values — the PC isn't answering right now.")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.warn)
            }
        }
        .opacity(w.stale ? 0.85 : 1)
    }

    // MARK: - Detail

    private func detailCard(_ w: WindowsData) -> some View {
        DashCard(title: "Detail") {
            SectionHeader("CPU")
            WinMeterBar(fraction: Double(w.cpuLoadPct ?? 0) / 100, color: Theme.accent)
            KVRow("Processor", w.cpuName ?? "—")
            KVRow("Cores / threads",
                  "\(w.cpuCores.map { "\($0)" } ?? "—") / \(w.cpuLogical.map { "\($0)" } ?? "—")")
            KVRow("Load", w.cpuLoadPct.map { "\($0)%" } ?? "—")

            SectionHeader("Memory")
                .padding(.top, 8)
            WinMeterBar(fraction: (w.memUsedPct ?? 0) / 100, color: Theme.sky)
            KVRow("Used", fmtBytes(w.memUsedBytes))
            KVRow("Free", fmtBytes(w.memFreeBytes))
            KVRow("Total", fmtBytes(w.memTotalBytes))
            KVRow("Used %", fmtPct(w.memUsedPct))

            SectionHeader("GPU")
                .padding(.top, 8)
            WinMeterBar(fraction: (w.gpuUtilPct ?? 0) / 100, color: Theme.good)
            KVRow("GPU", w.gpuName ?? "—")
            KVRow("Utilization", fmtPct(w.gpuUtilPct))
            KVRow("VRAM", vramText(w))
            KVRow("Temperature", w.gpuTempC.map { "\($0)°C" } ?? "—")
            KVRow("Power", w.gpuPowerW.map { "\($0) W" } ?? "—")

            if !w.disks.isEmpty {
                SectionHeader("Volumes")
                    .padding(.top, 8)
                ForEach(w.disks, id: \.label) { disk in
                    VStack(spacing: 4) {
                        KVRow(disk.label, "\(fmtBytes(disk.freeBytes)) free · \(fmtPct(disk.usedPct))")
                        WinMeterBar(fraction: disk.usedPct / 100, color: diskColor(disk.usedPct))
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        .opacity(w.stale ? 0.85 : 1)
    }

    private func vramText(_ w: WindowsData) -> String {
        guard let used = w.gpuMemUsedMb, let total = w.gpuMemTotalMb else { return "—" }
        return "\(fmtBytes(Int64(used * 1_048_576))) of \(fmtBytes(Int64(total * 1_048_576)))"
    }

    private func diskColor(_ usedPct: Double) -> Color {
        if usedPct > 90 { return Theme.bad }
        if usedPct > 80 { return Theme.warn }
        return Theme.accent
    }

    // MARK: - Miner

    private func minerCard(_ w: WindowsData) -> some View {
        DashCard {
            HStack(alignment: .top, spacing: 10) {
                Text("MINER — SRBMINER")
                    .font(.system(size: 12, weight: .semibold))
                    .tracking(0.6)
                    .foregroundStyle(Theme.textDim)
                Spacer(minLength: 0)
                StatusChip(text: w.minerRunning ? "RUNNING" : "STOPPED",
                           color: w.minerRunning ? Theme.good : Theme.bad)
            }
            KVRow("Hashrate", fmtThs(w.minerHashrateThs))
            KVRow("Avg 15m / 1h / 6h", avgText(w))
            KVRow("Shares", sharesText(w))
            KVRow("Miner power", w.minerPowerW.map { "\($0) W" } ?? "—")
        }
        .opacity(w.stale ? 0.85 : 1)
    }

    private func avgText(_ w: WindowsData) -> String {
        [w.minerAvg15mThs, w.minerAvg1hThs, w.minerAvg6hThs]
            .map { fmtThs($0) }
            .joined(separator: " / ")
    }

    private func sharesText(_ w: WindowsData) -> String {
        guard let accepted = w.minerAccepted else { return "—" }
        return "\(winCount(accepted)) accepted · \(winCount(w.minerRejected ?? 0)) rejected"
    }
}

/// Grouped count ("1,234"), Android's fmtCount.
private func winCount(_ n: Int) -> String {
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.locale = Locale(identifier: "en_US")
    return f.string(from: NSNumber(value: n)) ?? "\(n)"
}

/// Slim progress bar — Android's MeterBar. Fraction is sanitized and
/// clamped before it can reach layout.
private struct WinMeterBar: View {
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
        .padding(.vertical, 4)
    }
}
