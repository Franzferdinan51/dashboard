import SwiftUI

/// The Grow tab: CannaAI in depth. The Overview card stays the glance
/// (latest state only); this tab carries the full picture — provider
/// health, sensors, alerts, and the analysis history the tent-photo
/// pipeline posts to. Data rides DashboardViewModel's existing cannaai
/// fetch (the ~25 s side cadence); this view adds no polling of its own.
struct GrowView: View {
    @EnvironmentObject private var vm: DashboardViewModel

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if let g = vm.cannaai {
                    if !g.reachable {
                        unreachableCard
                    } else {
                        statusCard(g)
                        sensorsCard(g)
                        alertsCard(g)
                        historyCard(g)
                    }
                } else {
                    DashCard {
                        Text(vm.lastError ?? "Waiting for the first CannaAI sample from the bridge…")
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

    // MARK: - Unreachable

    /// Matching the Windows tab's down-service pattern: a single
    /// honest card, no stale data dressed up as current.
    private var unreachableCard: some View {
        DashCard {
            HStack(alignment: .top, spacing: 10) {
                Text("CANNAI — GROW")
                    .font(.system(size: 12, weight: .semibold))
                    .tracking(0.6)
                    .foregroundStyle(Theme.textDim)
                Spacer(minLength: 0)
                StatusChip(text: "UNREACHABLE", color: Theme.bad)
            }
            Text("CannaAI isn't answering — the grow app isn't running on the Mac right now. Nothing is shown rather than stale readings.")
                .font(.system(size: 15))
                .foregroundStyle(Theme.textDim)
        }
    }

    // MARK: - Status & providers

    private func statusCard(_ g: CannaaiData) -> some View {
        DashCard(title: "CannaAI") {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("grow tent")
                        .font(.caption)
                        .foregroundStyle(Theme.textDim)
                    Spacer()
                    if let status = g.status {
                        StatusChip(text: status.uppercased(),
                                   color: statusColor(status))
                    }
                }
                if let providers = g.providers, !providers.isEmpty {
                    ForEach(Array(providers.enumerated()), id: \.offset) { _, p in
                        KVRow(p.name ?? "Provider", p.status ?? "—")
                    }
                } else if let count = g.providersUpCount {
                    KVRow("AI providers up", "\(count)")
                } else if let up = g.providersUp {
                    KVRow("AI providers", up ? "up" : "down")
                }
            }
        }
    }

    private func statusColor(_ status: String) -> Color {
        switch status.lowercased() {
        case "ok", "healthy", "up": return Theme.good
        case "degraded": return Theme.warn
        default: return Theme.accent
        }
    }

    // MARK: - Sensors

    private func sensorsCard(_ g: CannaaiData) -> some View {
        DashCard(title: "Sensors") {
            if g.sensors.isEmpty {
                Text("No sensor readings yet — CannaAI has no sensors configured.")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.textDim)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(g.sensors.enumerated()), id: \.offset) { _, s in
                        KVRow(s.label, s.valueText + (s.unit.map { " \($0)" } ?? ""))
                    }
                }
            }
        }
    }

    // MARK: - Alerts

    private func alertsCard(_ g: CannaaiData) -> some View {
        DashCard(title: "Alerts") {
            if g.alerts.isEmpty {
                Text("No active alerts.")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.textDim)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(g.alerts.enumerated()), id: \.offset) { _, a in
                        HStack(alignment: .top, spacing: 8) {
                            Circle()
                                .fill(alertColor(a.level))
                                .frame(width: 8, height: 8)
                                .padding(.top, 5)
                            Text(a.text)
                                .font(.subheadline)
                                .foregroundStyle(Theme.mist)
                            Spacer(minLength: 8)
                            let when = fmtFlexibleTime(a.at)
                            if !when.isEmpty {
                                Text(when)
                                    .font(.caption)
                                    .foregroundStyle(Theme.textFaint)
                            }
                        }
                    }
                }
            }
        }
    }

    private func alertColor(_ level: String?) -> Color {
        switch level?.lowercased() {
        case "critical", "error", "high": return Theme.bad
        case "warning", "warn", "medium": return Theme.warn
        default: return Theme.accent
        }
    }

    // MARK: - Analysis history

    private func historyCard(_ g: CannaaiData) -> some View {
        let entries = g.history ?? []
        return DashCard(title: "Analysis history") {
            if entries.isEmpty {
                Text("No analyses yet — the tent-photo pipeline posts here.")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.textDim)
            } else {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(Array(entries.enumerated()), id: \.offset) { _, e in
                        analysisRow(e)
                    }
                }
            }
        }
    }

    private func analysisRow(_ e: CannaAnalysis) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(e.text ?? "Analysis")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Theme.mist)
            let sub = analysisSubline(e)
            if !sub.isEmpty {
                Text(sub)
                    .font(.caption)
                    .foregroundStyle(Theme.textDim)
            }
            if let line = analysisTimeLine(e) {
                Text(line)
                    .font(.caption)
                    .foregroundStyle(Theme.textFaint)
            }
            if let notes = e.notes {
                Text(notes)
                    .font(.subheadline)
                    .foregroundStyle(Theme.textDim)
            }
        }
    }

    /// "Strain · confidence 85% · health 75" — each part only when the
    /// analysis actually carries it.
    private func analysisSubline(_ e: CannaAnalysis) -> String {
        var parts: [String] = []
        if let strain = e.strain { parts.append(strain) }
        if let conf = e.confidence, conf.isFinite {
            // The grow app records confidence as a percent (85), but a
            // 0...1 fraction would render as 85% too, never "0%".
            let pct = conf <= 1 ? conf * 100 : conf
            parts.append("confidence \(Int(pct.rounded()))%")
        }
        if let health = e.healthScore, health.isFinite {
            let score = health == health.rounded()
                ? "\(Int(health))"
                : String(format: "%.1f", health)
            parts.append("health \(score)")
        }
        return parts.joined(separator: " · ")
    }

    /// "Sep 27, 2026 · 15d ago" — absolute date plus relative age,
    /// nil when the entry carries no parseable timestamp.
    private func analysisTimeLine(_ e: CannaAnalysis) -> String? {
        guard let date = flexibleDate(e.at) else { return nil }
        var parts = [Self.dateText(date)]
        let rel = fmtRelativeTime(date.timeIntervalSince1970)
        if !rel.isEmpty { parts.append(rel) }
        return parts.joined(separator: " · ")
    }

    private func flexibleDate(_ t: FlexibleTime?) -> Date? {
        guard let t else { return nil }
        if let epoch = t.epochSec { return Date(timeIntervalSince1970: epoch) }
        guard let raw = t.raw else { return nil }
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = parser.date(from: raw) { return date }
        parser.formatOptions = [.withInternetDateTime]
        return parser.date(from: raw)
    }

    private static func dateText(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "MMM d, yyyy"
        f.locale = Locale(identifier: "en_US")
        return f.string(from: date)
    }
}
