import SwiftUI

private let cMist = Color(red: 0.957, green: 0.973, blue: 1.000)
private let cSky = Color(red: 0.220, green: 0.741, blue: 0.973)
private let cFaint = Color(red: 0.431, green: 0.525, blue: 0.678)
private let cTrack = Color(red: 0.031, green: 0.078, blue: 0.149)

/// Horizontal stacked meter (CPU split, memory split).
private struct StackBar: View {
    let segments: [(fraction: Double, color: Color)]
    var height: CGFloat = 10

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: height / 2)
                    .fill(cTrack)
                HStack(spacing: 0) {
                    ForEach(Array(segments.enumerated()), id: \.offset) { _, seg in
                        Rectangle()
                            .fill(seg.color)
                            .frame(width: max(0, min(1, seg.fraction)) * geo.size.width)
                    }
                }
            }
        }
        .frame(height: height)
        .clipShape(RoundedRectangle(cornerRadius: height / 2))
    }
}

/// Single-value meter bar.
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

private struct LegendDot: View {
    let color: Color
    let text: String

    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(text)
                .font(.caption)
                .foregroundStyle(Theme.textDim)
                .lineLimit(1)
        }
    }
}

private struct MemoryRow: View {
    let label: String
    let bytes: Int64
    let color: Color

    var body: some View {
        HStack(spacing: 8) {
            RoundedRectangle(cornerRadius: 3)
                .fill(color)
                .frame(width: 9, height: 9)
            Text(label)
                .font(.subheadline)
                .foregroundStyle(Theme.textDim)
            Spacer()
            Text(fmtBytes(bytes))
                .font(.subheadline)
                .foregroundStyle(cMist)
        }
        .padding(.vertical, 2)
    }
}

struct SystemView: View {
    @EnvironmentObject private var vm: DashboardViewModel

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if let ov = vm.overview {
                    processorCard(ov.system)
                    memoryCard(ov.system)
                    storageNetworkCard(ov.system)
                    processesCard(ov.system)
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

    // MARK: - Processor

    private func processorCard(_ sys: SystemStats) -> some View {
        DashCard(title: "Processor") {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text(sys.chip)
                        .font(.caption)
                        .foregroundStyle(Theme.textDim)
                        .lineLimit(1)
                    Spacer()
                    if !vm.connected {
                        StatusChip(text: "OFFLINE", color: Theme.bad)
                    } else if sys.thermal.state.lowercased() == "nominal" {
                        StatusChip(text: "THERMAL NOMINAL", color: Theme.good)
                    } else {
                        StatusChip(text: "THERMAL \(sys.thermal.state.uppercased())", color: Theme.warn)
                    }
                }
                StackBar(segments: [
                    (sys.cpu.userPct / 100, Theme.accent),
                    (sys.cpu.sysPct / 100, cSky),
                    (sys.cpu.idlePct / 100, cTrack),
                ])
                HStack(spacing: 16) {
                    LegendDot(color: Theme.accent, text: "User \(fmtPct(sys.cpu.userPct))")
                    LegendDot(color: cSky, text: "System \(fmtPct(sys.cpu.sysPct))")
                    LegendDot(color: cFaint, text: "Idle \(fmtPct(sys.cpu.idlePct))")
                }
                KVRow("Used", fmtPct(sys.cpu.usedPct))
                KVRow("CPU cores", sys.cpuCores.map(String.init) ?? "—")
                KVRow("GPU cores", sys.gpuCores.map(String.init) ?? "—")
                KVRow("Load average",
                      sys.loadAvg.isEmpty ? "—" : sys.loadAvg.map { String(format: "%.2f", $0) }.joined(separator: "  "))
                KVRow("Uptime", fmtDuration(sys.uptimeSeconds.map { Double($0) }))
                if sys.thermal.cpuSpeedLimitPct < 100 {
                    KVRow("CPU speed limit", "\(sys.thermal.cpuSpeedLimitPct)%")
                }
            }
        }
    }

    // MARK: - Memory

    private func memoryCard(_ sys: SystemStats) -> some View {
        let mem = sys.memory
        let total = Double(max(mem.totalBytes, 1))
        return DashCard(title: "Memory") {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("\(fmtBytes(mem.usedBytes)) of \(fmtBytes(mem.totalBytes)) used (\(fmtPct(mem.usedPct)))")
                        .font(.caption)
                        .foregroundStyle(Theme.textDim)
                        .lineLimit(1)
                    Spacer()
                    if !vm.connected {
                        StatusChip(text: "OFFLINE", color: Theme.bad)
                    }
                }
                StackBar(segments: [
                    (Double(mem.activeBytes) / total, cSky),
                    (Double(mem.wiredBytes) / total, Theme.accent),
                    (Double(mem.compressedBytes) / total, Theme.good),
                    (Double(mem.inactiveBytes) / total, cFaint),
                    (Double(mem.freeBytes) / total, cTrack),
                ], height: 14)
                MemoryRow(label: "Active", bytes: mem.activeBytes, color: cSky)
                MemoryRow(label: "Wired", bytes: mem.wiredBytes, color: Theme.accent)
                MemoryRow(label: "Compressed", bytes: mem.compressedBytes, color: Theme.good)
                MemoryRow(label: "Inactive", bytes: mem.inactiveBytes, color: cFaint)
                MemoryRow(label: "Free", bytes: mem.freeBytes, color: Theme.good)
                KVRow("Swap",
                      "\(fmtBytes(Int64(mem.swapUsedMb * 1_048_576))) of \(fmtBytes(Int64(mem.swapTotalMb * 1_048_576)))")
            }
        }
    }

    // MARK: - Storage & network

    private func storageNetworkCard(_ sys: SystemStats) -> some View {
        DashCard(title: "Storage & network") {
            VStack(alignment: .leading, spacing: 10) {
                if !vm.connected {
                    HStack {
                        Spacer()
                        StatusChip(text: "OFFLINE", color: Theme.bad)
                    }
                }
                ForEach(sys.disks, id: \.path) { d in
                    VStack(alignment: .leading, spacing: 6) {
                        KVRow(d.label,
                              "\(fmtBytes(d.usedBytes)) / \(fmtBytes(d.totalBytes)) · \(fmtPct(d.usedPct))")
                        MiniBar(fraction: d.usedPct / 100,
                                color: d.usedPct > 90 ? Theme.bad : (d.usedPct > 75 ? Theme.warn : Theme.accent))
                    }
                }
                KVRow("Network in",
                      "\(fmtRate(sys.network.rateInBps)) · \(fmtBytes(sys.network.bytesIn)) total")
                KVRow("Network out",
                      "\(fmtRate(sys.network.rateOutBps)) · \(fmtBytes(sys.network.bytesOut)) total")
            }
        }
    }

    // MARK: - Top processes

    private func processesCard(_ sys: SystemStats) -> some View {
        // Per-process %CPU on macOS is per-core and routinely exceeds
        // 100 — scale bars to the busiest process, not to 100.
        let maxCpu = max(sys.topProcesses.map(\.cpuPct).max() ?? 1.0, 1.0)
        return DashCard(title: "Top processes") {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(vm.connected ? "by CPU · pull to refresh for a new sample" : "last known sample")
                        .font(.caption)
                        .foregroundStyle(Theme.textDim)
                    Spacer()
                    if !vm.connected {
                        StatusChip(text: "OFFLINE", color: Theme.bad)
                    }
                }
                HStack {
                    Text("PROCESS").frame(maxWidth: .infinity, alignment: .leading)
                    Text("CPU").frame(width: 52, alignment: .trailing)
                    Text("MEM").frame(width: 52, alignment: .trailing)
                    Text("TIME").frame(width: 64, alignment: .trailing)
                }
                .font(.caption2.weight(.semibold))
                .foregroundStyle(cFaint)
                if sys.topProcesses.isEmpty {
                    Text("No process sample yet.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.textDim)
                }
                ForEach(sys.topProcesses, id: \.pid) { p in
                    VStack(spacing: 3) {
                        HStack {
                            Text(p.name)
                                .font(.subheadline)
                                .foregroundStyle(cMist)
                                .lineLimit(1)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Text(fmtPct(p.cpuPct))
                                .frame(width: 52, alignment: .trailing)
                            Text(fmtPct(p.memPct))
                                .frame(width: 52, alignment: .trailing)
                            Text(p.elapsed)
                                .lineLimit(1)
                                .frame(width: 64, alignment: .trailing)
                        }
                        .font(.caption)
                        .foregroundStyle(Theme.textDim)
                        MiniBar(fraction: p.cpuPct / maxCpu,
                                color: p.cpuPct > 50 ? Theme.warn : Theme.accent,
                                height: 4)
                    }
                }
            }
        }
    }
}
