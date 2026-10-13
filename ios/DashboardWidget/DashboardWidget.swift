import WidgetKit
import SwiftUI

// Dashboard widget — combined $/day (Darkbloom + Batman-3 mining).
//
// Deliberately self-contained: no App Group, no shared UserDefaults with
// the app. The widget fetches the bridge itself at the app's default
// bridge URL (tailnet). The last good entry is cached in the extension's
// OWN UserDefaults so a failed refresh renders stale data with an
// "unreachable" note instead of a blank widget.

private let bridgeBase = "http://100.68.208.113:8901"
private let cacheKey = "lastGoodEntry"

// MARK: - Palette (mirrors Theme in Dashboard/Components.swift — the
// widget is a separate target and can't import the app's types.)

private enum WTheme {
    static let bg = Color(red: 6 / 255.0, green: 13 / 255.0, blue: 24 / 255.0)
    static let card = Color(red: 15 / 255.0, green: 35 / 255.0, blue: 66 / 255.0)
    static let mist = Color(red: 244 / 255.0, green: 248 / 255.0, blue: 255 / 255.0)
    static let dim = Color(red: 159 / 255.0, green: 180 / 255.0, blue: 216 / 255.0)
    static let faint = Color(red: 110 / 255.0, green: 134 / 255.0, blue: 173 / 255.0)
    static let good = Color(red: 47 / 255.0, green: 212 / 255.0, blue: 140 / 255.0)
    static let warn = Color(red: 245 / 255.0, green: 166 / 255.0, blue: 35 / 255.0)
    static let bad = Color(red: 255 / 255.0, green: 107 / 255.0, blue: 107 / 255.0)
}

// MARK: - Wire shapes (explicit snake_case keys — no convertFromSnakeCase,
// so none of the digit-key traps the app models document.)

private struct OverviewWire: Decodable {
    let darkbloom: Darkbloom?
    struct Darkbloom: Decodable {
        let running: Bool?
        let earning: Bool?
        let earnings: Earnings?
        struct Earnings: Decodable {
            let estimates: Estimates?
            struct Estimates: Decodable {
                let perDayUsd: Double?
                enum CodingKeys: String, CodingKey { case perDayUsd = "per_day_usd" }
            }
        }
    }
}

private struct MiningWire: Decodable {
    let mining: Mining?
    struct Mining: Decodable {
        let batmanLive: Live?
        let batmanVerified: Verified?
        enum CodingKeys: String, CodingKey {
            case batmanLive = "batman_live"
            case batmanVerified = "batman_verified"
        }
        struct Live: Decodable {
            let running: Bool?
            let hashrateThs: Double?
            enum CodingKeys: String, CodingKey {
                case running
                case hashrateThs = "hashrate_ths"
            }
        }
        struct Verified: Decodable {
            let usdPerDayNet: Double?
            enum CodingKeys: String, CodingKey { case usdPerDayNet = "usd_per_day_net" }
        }
    }
}

// MARK: - Entry

struct DashboardEntry: TimelineEntry, Codable {
    var date: Date
    var combinedPerDay: Double? = nil
    var darkbloomPerDay: Double? = nil
    var darkbloomRunning: Bool = false
    var darkbloomEarning: Bool = false
    var hashrateThs: Double? = nil
    var minerRunning: Bool = false
    var miningPerDay: Double? = nil
    /// false = this entry is the cached last-good (or nothing at all)
    /// because the bridge couldn't be reached this cycle.
    var reachable: Bool = true
    var isSample: Bool = false

    static var sample: DashboardEntry {
        DashboardEntry(date: Date(), combinedPerDay: 6.55,
                       darkbloomPerDay: 3.46, darkbloomRunning: true,
                       darkbloomEarning: true, hashrateThs: 99.3,
                       minerRunning: true, miningPerDay: 3.09,
                       reachable: true, isSample: true)
    }

    static var unreachable: DashboardEntry {
        DashboardEntry(date: Date(), reachable: false)
    }
}

// MARK: - Fetch

private func fetchJSON<T: Decodable>(_ path: String, as type: T.Type) async throws -> T {
    let config = URLSessionConfiguration.default
    config.timeoutIntervalForRequest = 12
    config.waitsForConnectivity = false
    let session = URLSession(configuration: config)
    guard let url = URL(string: bridgeBase + path) else {
        throw URLError(.badURL)
    }
    let (data, response) = try await session.data(from: url)
    guard (response as? HTTPURLResponse)?.statusCode == 200 else {
        throw URLError(.badServerResponse)
    }
    return try JSONDecoder().decode(T.self, from: data)
}

private func loadCachedEntry() -> DashboardEntry? {
    guard let data = UserDefaults.standard.data(forKey: cacheKey),
          let entry = try? JSONDecoder().decode(DashboardEntry.self, from: data)
    else { return nil }
    return entry
}

private func fetchEntry() async -> DashboardEntry {
    async let overview = try? fetchJSON("/api/overview", as: OverviewWire.self)
    async let mining = try? fetchJSON("/api/mining", as: MiningWire.self)
    let (ov, mi) = await (overview, mining)

    guard ov != nil || mi != nil else {
        // Total failure: serve the last good entry, marked stale; if
        // there is none, the views render the unreachable state.
        if var cached = loadCachedEntry() {
            cached.reachable = false
            return cached
        }
        return .unreachable
    }

    let dbDay = ov?.darkbloom?.earnings?.estimates?.perDayUsd
    let mineDay = mi?.mining?.batmanVerified?.usdPerDayNet
    let combined: Double? = (dbDay == nil && mineDay == nil)
        ? nil : (dbDay ?? 0) + (mineDay ?? 0)
    let entry = DashboardEntry(
        date: Date(),
        combinedPerDay: combined,
        darkbloomPerDay: dbDay,
        darkbloomRunning: ov?.darkbloom?.running ?? false,
        darkbloomEarning: ov?.darkbloom?.earning ?? false,
        hashrateThs: mi?.mining?.batmanLive?.hashrateThs,
        minerRunning: mi?.mining?.batmanLive?.running ?? false,
        miningPerDay: mineDay,
        reachable: true)
    if let data = try? JSONEncoder().encode(entry) {
        UserDefaults.standard.set(data, forKey: cacheKey)
    }
    return entry
}

// MARK: - Provider

struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> DashboardEntry { .sample }

    func getSnapshot(in context: Context,
                     completion: @escaping (DashboardEntry) -> Void) {
        if context.isPreview {
            completion(.sample)
            return
        }
        Task { completion(await fetchEntry()) }
    }

    func getTimeline(in context: Context,
                     completion: @escaping (Timeline<DashboardEntry>) -> Void) {
        Task {
            let entry = await fetchEntry()
            let next = Calendar.current.date(byAdding: .minute, value: 30,
                                             to: Date()) ?? Date()
            completion(Timeline(entries: [entry], policy: .after(next)))
        }
    }
}

// MARK: - Formatting

private func fmtUsd(_ v: Double?) -> String {
    guard let v, v.isFinite else { return "—" }
    return String(format: "$%.2f", v)
}

private func fmtThs(_ v: Double?) -> String {
    guard let v, v.isFinite else { return "—" }
    return String(format: "%.1f TH/s", v)
}

private func fmtClock(_ date: Date) -> String {
    let f = DateFormatter()
    f.dateFormat = "HH:mm"
    return f.string(from: date)
}

// MARK: - Views

struct DashboardWidgetEntryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: DashboardEntry

    var body: some View {
        Group {
            if family == .systemMedium {
                mediumView
            } else {
                smallView
            }
        }
        .containerBackground(for: .widget) {
            LinearGradient(colors: [WTheme.card, WTheme.bg],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }

    private var darkbloomDot: Color {
        if !entry.reachable { return WTheme.faint }
        if entry.darkbloomEarning { return WTheme.good }
        if entry.darkbloomRunning { return WTheme.warn }
        return WTheme.bad
    }

    private var minerDot: Color {
        if !entry.reachable { return WTheme.faint }
        return entry.minerRunning ? WTheme.good : WTheme.bad
    }

    private var footerText: String {
        if entry.isSample { return "sample data" }
        if !entry.reachable { return "bridge unreachable · upd \(fmtClock(entry.date))" }
        return "updated \(fmtClock(entry.date))"
    }

    private var smallView: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text("COMBINED / DAY")
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(WTheme.faint)
            Text(entry.reachable || entry.combinedPerDay != nil
                 ? fmtUsd(entry.combinedPerDay) : "offline")
                .font(.system(size: 26, weight: .bold))
                .foregroundStyle(WTheme.mist)
                .minimumScaleFactor(0.6)
                .lineLimit(1)
            HStack(spacing: 5) {
                Circle().fill(darkbloomDot).frame(width: 7, height: 7)
                Text("Darkbloom \(fmtUsd(entry.darkbloomPerDay))")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(WTheme.dim)
                    .lineLimit(1)
            }
            HStack(spacing: 5) {
                Circle().fill(minerDot).frame(width: 7, height: 7)
                Text("Batman \(fmtThs(entry.hashrateThs))")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(WTheme.dim)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            Text(footerText)
                .font(.system(size: 9))
                .foregroundStyle(WTheme.faint)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var mediumView: some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("COMBINED / DAY")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(WTheme.faint)
                Text(entry.reachable || entry.combinedPerDay != nil
                     ? fmtUsd(entry.combinedPerDay) : "offline")
                    .font(.system(size: 32, weight: .bold))
                    .foregroundStyle(WTheme.mist)
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Text(footerText)
                    .font(.system(size: 9))
                    .foregroundStyle(WTheme.faint)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: 10) {
                statRow(dot: darkbloomDot, label: "Darkbloom",
                        value: "\(fmtUsd(entry.darkbloomPerDay))/day",
                        sub: entry.darkbloomEarning ? "earning"
                             : (entry.darkbloomRunning ? "not earning" : "stopped"))
                statRow(dot: minerDot, label: "Batman-3",
                        value: fmtThs(entry.hashrateThs),
                        sub: "\(fmtUsd(entry.miningPerDay))/day net")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private func statRow(dot: Color, label: String, value: String, sub: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 5) {
                Circle().fill(dot).frame(width: 7, height: 7)
                Text(label)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(WTheme.mist)
            }
            Text(value)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(WTheme.mist)
                .lineLimit(1)
            Text(sub)
                .font(.system(size: 10))
                .foregroundStyle(WTheme.dim)
                .lineLimit(1)
        }
    }
}

// MARK: - Widget

struct DashboardWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "DashboardWidget", provider: Provider()) { entry in
            DashboardWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("Dashboard")
        .description("Combined $/day — Darkbloom earnings + Batman-3 mining.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

@main
struct DashboardWidgetBundle: WidgetBundle {
    var body: some Widget {
        DashboardWidget()
    }
}

#Preview(as: .systemSmall) {
    DashboardWidget()
} timeline: {
    DashboardEntry.sample
}

#Preview(as: .systemMedium) {
    DashboardWidget()
} timeline: {
    DashboardEntry.sample
}
