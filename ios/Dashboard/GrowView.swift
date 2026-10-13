import PhotosUI
import SwiftUI
import UIKit

/// The Grow tab: the grow room in depth. The live environment comes
/// from the AC Infinity app on the Moto G desk phone (harvested by
/// the bridge — CannaAI's own sensor table is empty, so the sensors
/// card that used to sit here is gone); CannaAI itself contributes
/// the component health, alerts, and the photo-analysis history the
/// tent-photo pipeline posts to. The tab also uploads new plant
/// photos for analysis. Data rides DashboardViewModel's existing
/// cannaai fetch (the ~25 s side cadence); this view adds no polling
/// of its own.
struct GrowView: View {
    @EnvironmentObject private var vm: DashboardViewModel

    // Photo-picker state: the raw pick is downscaled to a ≤1600 px
    // JPEG as soon as it lands, and that JPEG (plus its preview) is
    // what the Analyze button sends.
    @State private var pickerItem: PhotosPickerItem?
    @State private var pickedImage: UIImage?
    @State private var pickedJPEG: Data?
    @State private var strainText: String = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if let g = vm.cannaai {
                    if let env = g.environment {
                        environmentCard(env)
                    }
                    if !g.reachable {
                        unreachableCard
                    } else {
                        statusCard(g)
                        alertsCard(g)
                        analyzeCard(g)
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
        .onChange(of: pickerItem) { _, item in
            Task { await loadPicked(item) }
        }
    }

    // MARK: - Environment (AC Infinity via the Moto G)

    /// The real sensor story: what the tent controllers report right
    /// now. Rendered whenever the bridge has a harvest — even when
    /// CannaAI itself is down, the tents don't stop existing.
    private func environmentCard(_ env: GrowEnvironment) -> some View {
        DashCard {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("ENVIRONMENT — AC INFINITY")
                        .font(.system(size: 12, weight: .semibold))
                        .tracking(0.6)
                        .foregroundStyle(Theme.textDim)
                    Text("Moto G desk phone")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textFaint)
                }
                Spacer(minLength: 0)
            }
            if env.devices.isEmpty {
                Text("No controllers in the last harvest.")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.textDim)
            } else {
                ForEach(Array(env.devices.enumerated()), id: \.offset) { _, device in
                    deviceBlock(device)
                }
            }
            if let stamp = envStamp(env) {
                Text(stamp.text)
                    .font(.system(size: 12))
                    .foregroundStyle(stamp.stale ? Theme.warn : Theme.textFaint)
            }
        }
    }

    private func deviceBlock(_ device: EnvDevice) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(device.name ?? "Controller")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.mist)
                if let id = device.id {
                    Text(id)
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textFaint)
                }
            }
            if let inside = device.inside {
                HStack(alignment: .firstTextBaseline, spacing: 14) {
                    Text(fmtTemp(inside.tempF))
                        .font(.system(size: 30, weight: .bold))
                        .foregroundStyle(Theme.mist)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("RH \(fmtRh(inside.rh))")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(Theme.mist)
                        Text("VPD \(fmtVpd(inside.vpdKpa))")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(Theme.mist)
                    }
                    Spacer(minLength: 0)
                    Text("INSIDE")
                        .font(.system(size: 11, weight: .semibold))
                        .tracking(0.5)
                        .foregroundStyle(Theme.textFaint)
                }
                if let outside = device.outside {
                    Text("Outside \(fmtTemp(outside.tempF)) · RH \(fmtRh(outside.rh)) · VPD \(fmtVpd(outside.vpdKpa))")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textDim)
                }
            } else {
                Text("No inside reading — the probe isn't reporting.")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textDim)
            }
            if !device.ports.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(device.ports.enumerated()), id: \.offset) { _, port in
                        KVRow(port.name ?? "Port", portValue(port))
                    }
                }
                .padding(.top, 2)
            }
        }
    }

    /// "AI · 7" / "OFF" / "—" — mode word first, level when it has one.
    private func portValue(_ port: EnvPort) -> String {
        var parts: [String] = []
        if let mode = port.mode { parts.append(mode) }
        if let level = port.level { parts.append("\(level)") }
        return parts.isEmpty ? "—" : parts.joined(separator: " · ")
    }

    /// "updated 5m ago" — stale (amber) past 30 minutes: the harvest
    /// rides the desk phone, so an old stamp means the phone is away
    /// or asleep, and the readings must not dress up as live.
    private func envStamp(_ env: GrowEnvironment) -> (text: String, stale: Bool)? {
        guard let date = flexibleDate(env.fetchedAt) else { return nil }
        let rel = fmtRelativeTime(date.timeIntervalSince1970)
        guard !rel.isEmpty else { return nil }
        return ("updated \(rel)", Date().timeIntervalSince(date) > 30 * 60)
    }

    private func fmtTemp(_ v: Double?) -> String {
        guard let v, v.isFinite else { return "—" }
        return "\(fmtMeasure(v))°F"
    }

    private func fmtRh(_ v: Double?) -> String {
        guard let v, v.isFinite else { return "—" }
        return "\(fmtMeasure(v))%"
    }

    private func fmtVpd(_ v: Double?) -> String {
        guard let v, v.isFinite else { return "—" }
        var s = String(format: "%.2f", v)
        if s.hasSuffix("0") { s.removeLast() }
        return "\(s) kPa"
    }

    /// One decimal, dropping a trailing ".0" (74.8 stays 74.8, 72.0
    /// reads 72) — the AC Infinity display's own style.
    private func fmtMeasure(_ v: Double) -> String {
        v == v.rounded() && abs(v) < 9.0e15
            ? "\(Int64(v))"
            : String(format: "%.1f", v)
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

    // MARK: - Status, providers & components

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
                // The component checklist is what the status word is
                // made of: DEGRADED with "openclaw unreachable" under
                // it is an answer, not a shrug.
                if let components = g.components, !components.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(components.enumerated()), id: \.offset) { _, c in
                            componentRow(c)
                        }
                    }
                    .padding(.top, 4)
                }
            }
        }
    }

    private func componentRow(_ c: CannaComponent) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack(alignment: .center, spacing: 12) {
                Text(c.name ?? "Component")
                    .foregroundStyle(Theme.textDim)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Text(c.status ?? "—")
                    .fontWeight(.semibold)
                    .foregroundStyle(componentColor(c.status))
                    .lineLimit(1)
            }
            .font(.system(size: 15))
            if let detail = c.detail {
                Text(detail)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textFaint)
            }
        }
        .padding(.vertical, 3)
    }

    private func statusColor(_ status: String) -> Color {
        switch status.lowercased() {
        case "ok", "healthy", "up": return Theme.good
        case "degraded": return Theme.warn
        default: return Theme.accent
        }
    }

    /// Component status words: healthy greens, dead reds, and the
    /// in-between states (unconfigured, degraded) amber.
    private func componentColor(_ status: String?) -> Color {
        switch status?.lowercased() {
        case "ok", "available", "healthy", "up", "running": return Theme.good
        case "unreachable", "down", "error", "failed", "offline": return Theme.bad
        case "unconfigured", "degraded", "warn", "warning": return Theme.warn
        default: return Theme.accent
        }
    }

    // MARK: - Alerts (CannaAI + AC Infinity, source-tagged)

    private func alertsCard(_ g: CannaaiData) -> some View {
        let canna = g.alerts.map {
            MergedAlert(text: $0.text, level: $0.level, at: $0.at, source: "CANNAI")
        }
        let env = (g.environment?.alerts ?? []).map {
            MergedAlert(text: $0.text, level: $0.level, at: $0.at, source: "AC INFINITY")
        }
        let merged = canna + env
        return DashCard(title: "Alerts") {
            if merged.isEmpty {
                Text("No active alerts — CannaAI and AC Infinity both quiet.")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.textDim)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(merged.enumerated()), id: \.offset) { _, a in
                        alertRow(a)
                    }
                }
            }
        }
    }

    private struct MergedAlert {
        let text: String
        let level: String?
        let at: FlexibleTime
        let source: String
    }

    private func alertRow(_ a: MergedAlert) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Circle()
                .fill(alertColor(a.level))
                .frame(width: 8, height: 8)
                .padding(.top, 5)
            VStack(alignment: .leading, spacing: 1) {
                Text(a.text)
                    .font(.subheadline)
                    .foregroundStyle(Theme.mist)
                Text(a.source)
                    .font(.system(size: 11, weight: .semibold))
                    .tracking(0.5)
                    .foregroundStyle(Theme.textFaint)
            }
            Spacer(minLength: 8)
            let when = fmtFlexibleTime(a.at)
            if !when.isEmpty {
                Text(when)
                    .font(.caption)
                    .foregroundStyle(Theme.textFaint)
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

    // MARK: - Photo analysis

    /// Upload a tent photo for CannaAI's vision model. The bridge
    /// accepts one at a time (409 while one runs); the result lands
    /// at the top of the analysis history on a later poll — several
    /// minutes is normal, and analyze.pending says so meanwhile.
    private func analyzeCard(_ g: CannaaiData) -> some View {
        let analyze = g.analyze
        let pending = analyze?.pending ?? false
        let busy = pending || vm.analyzeSubmitting
        return DashCard(title: "Analyze a plant photo") {
            VStack(alignment: .leading, spacing: 10) {
                Text("Pick a tent photo and the bridge runs it through the vision model. Results appear at the top of the analysis history below.")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textFaint)
                if let pickedImage {
                    Image(uiImage: pickedImage)
                        .resizable()
                        .scaledToFit()
                        .frame(maxHeight: 180)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                PhotosPicker(selection: $pickerItem, matching: .images,
                             photoLibrary: .shared()) {
                    Label(pickedImage == nil ? "Choose a photo" : "Choose a different photo",
                          systemImage: "photo")
                }
                .buttonStyle(.bordered)
                .tint(Theme.blueSoft)
                .disabled(busy)
                TextField("Strain (optional)", text: $strainText)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled(true)
                    .foregroundStyle(Theme.mist)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(Theme.cardAlt, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .disabled(busy)
                if pending {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("Analyzing… the vision model takes a few minutes")
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textDim)
                    }
                }
                Button("Analyze photo") {
                    guard let pickedJPEG else { return }
                    let base64 = pickedJPEG.base64EncodedString()
                    let strain = strainText.trimmingCharacters(in: .whitespacesAndNewlines)
                    Task {
                        await vm.submitPlantPhoto(
                            imageBase64: base64,
                            strain: strain.isEmpty ? nil : strain)
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.accent)
                .disabled(pickedJPEG == nil || busy)
                if let outcome = vm.analyzeOutcome {
                    Text(outcome.message)
                        .font(.system(size: 13))
                        .foregroundStyle(outcome.ok ? Theme.good : Theme.bad)
                }
                if !pending, let lastError = analyze?.lastError {
                    Text(lastError)
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.bad)
                }
            }
        }
    }

    /// Load the picked photo and downscale it immediately, so the
    /// Analyze button's payload is ready (and its size known) before
    /// the tap. A pick that won't decode just clears the selection.
    private func loadPicked(_ item: PhotosPickerItem?) async {
        pickedImage = nil
        pickedJPEG = nil
        guard let item,
              let data = try? await item.loadTransferable(type: Data.self),
              let prepared = Self.downscaledJPEG(from: data) else { return }
        pickedImage = prepared.image
        pickedJPEG = prepared.jpeg
    }

    /// Downscale to a max 1600 px long edge, JPEG quality 0.85 — big
    /// enough for the vision model, small enough that the base64
    /// POST stays around a megabyte.
    private static func downscaledJPEG(from data: Data,
                                       maxDimension: CGFloat = 1600)
        -> (image: UIImage, jpeg: Data)? {
        guard let image = UIImage(data: data) else { return nil }
        let size = image.size
        let longest = max(size.width, size.height)
        var target = image
        if longest > maxDimension {
            let scale = maxDimension / longest
            let newSize = CGSize(width: size.width * scale,
                                 height: size.height * scale)
            let renderer = UIGraphicsImageRenderer(size: newSize)
            target = renderer.image { _ in
                image.draw(in: CGRect(origin: .zero, size: newSize))
            }
        }
        guard let jpeg = target.jpegData(compressionQuality: 0.85) else { return nil }
        return (target, jpeg)
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
