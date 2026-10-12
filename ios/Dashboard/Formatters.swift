import Foundation

// Display formatting, ported from the Android app's Format.kt.
// All number formatting is pinned to en_US_POSIX so values render the
// same regardless of device locale ("." decimals, "," grouping).

private let posixLocale = Locale(identifier: "en_US_POSIX")

private func makeFormatter(fractionDigits: Int, grouping: Bool = false) -> NumberFormatter {
    let f = NumberFormatter()
    f.locale = posixLocale
    f.numberStyle = .decimal
    f.minimumFractionDigits = fractionDigits
    f.maximumFractionDigits = fractionDigits
    f.usesGroupingSeparator = grouping
    f.roundingMode = .halfUp
    return f
}

private let usdGrouped2 = makeFormatter(fractionDigits: 2, grouping: true)
private let fixed0 = makeFormatter(fractionDigits: 0)
private let fixed1 = makeFormatter(fractionDigits: 1)
private let fixed2 = makeFormatter(fractionDigits: 2)
private let fixed3 = makeFormatter(fractionDigits: 3)
private let fixed4 = makeFormatter(fractionDigits: 4)

// MARK: - Money

private func formatUsdValue(_ v: Double) -> String {
    if v == 0 { return "$0.00" }
    if v < 0 { return "-$" + (usdGrouped2.string(from: NSNumber(value: -v)) ?? "") }
    if v < 0.00005 { return "<$0.0001" }
    if v < 0.01 { return "$" + (fixed4.string(from: NSNumber(value: v)) ?? "") }
    if v < 1 { return "$" + (fixed3.string(from: NSNumber(value: v)) ?? "") }
    return "$" + (usdGrouped2.string(from: NSNumber(value: v)) ?? "")
}

func fmtUsd(_ usd: Double?) -> String {
    // A non-finite value reads as "absent", never renders "$NaN".
    guard let v = usd, v.isFinite else { return "—" }
    return formatUsdValue(v)
}

func fmtUsd(_ usd: String?) -> String {
    guard let usd else { return "—" }
    // Bridge money strings stay exact; parse only to pick a display
    // tier. Double("NaN")/Double("Infinity") parse successfully — treat
    // them as unparseable and show the bridge's raw string instead.
    guard let v = Double(usd), v.isFinite else { return "$" + usd }
    return formatUsdValue(v)
}

/// Ledger money is integer micro-USD end to end; this is the only
/// place it becomes a (display-only) Double.
func microToUsd(_ micro: Int64) -> String {
    fmtUsd(Double(micro) / 1_000_000.0)
}

// MARK: - Numbers

func fmtPct(_ v: Double?, digits: Int = 0) -> String {
    guard let v, v.isFinite else { return "—" }
    let f = digits == 0 ? fixed0 : makeFormatter(fractionDigits: digits)
    return (f.string(from: NSNumber(value: v)) ?? "—") + "%"
}

func fmtThs(_ ths: Double?) -> String {
    guard let ths, ths.isFinite else { return "—" }
    return (fixed2.string(from: NSNumber(value: ths)) ?? "—") + " TH/s"
}

func fmtBytes(_ bytes: Int64?) -> String {
    guard let bytes else { return "—" }
    if bytes <= 0 { return "0 B" }
    // 1024-based so a 128 GB machine reads "128 GB", matching Activity Monitor.
    let units = ["B", "KB", "MB", "GB", "TB"]
    let i = min(max(Int(log10(Double(bytes)) / log10(1024.0)), 0), units.count - 1)
    let v = Double(bytes) / pow(1024.0, Double(i))
    let num = v >= 100 ? fixed0.string(from: NSNumber(value: v)) : fixed1.string(from: NSNumber(value: v))
    return "\(num ?? "0") \(units[i])"
}

func fmtRate(_ bps: Int64?) -> String {
    guard let bps else { return "—" }
    switch bps {
    case ...0:
        return "0 B/s"
    case ..<1_000:
        return "\(bps) B/s"
    case ..<1_000_000:
        return (fixed1.string(from: NSNumber(value: Double(bps) / 1_000.0)) ?? "") + " KB/s"
    case ..<1_000_000_000:
        return (fixed1.string(from: NSNumber(value: Double(bps) / 1_000_000.0)) ?? "") + " MB/s"
    default:
        return (fixed2.string(from: NSNumber(value: Double(bps) / 1_000_000_000.0)) ?? "") + " GB/s"
    }
}

// MARK: - Durations

/// Uptime-style duration: "1d 18h", "5h 12m", "12m", "45s".
func fmtDuration(_ totalSeconds: Double?) -> String {
    guard let totalSeconds, totalSeconds.isFinite else { return "—" }
    let secs = max(Int64(min(totalSeconds, 9.0e15)), 0)
    let d = secs / 86_400
    let h = (secs % 86_400) / 3_600
    let m = (secs % 3_600) / 60
    if d > 0 { return "\(d)d \(h)h" }
    if h > 0 { return "\(h)h \(m)m" }
    if m > 0 { return "\(m)m" }
    return "\(secs)s"
}
