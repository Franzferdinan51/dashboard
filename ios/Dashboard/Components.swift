import SwiftUI

// Shared palette + primitives, mirroring the Android app's theme
// (theme/Theme.kt, the `Muse` object) and components
// (ui/components/Components.kt): deep-navy ink, one disciplined blue
// primary, sky for memory, emerald for GPU/earnings. Solid surfaces,
// hairline borders, no glow.

enum Theme {
    /// Ink #060D18 — page background.
    static let bg: Color = Color(red: 6 / 255.0, green: 13 / 255.0, blue: 24 / 255.0)
    /// Card #0F2342 — card surface.
    static let card: Color = Color(red: 15 / 255.0, green: 35 / 255.0, blue: 66 / 255.0)
    /// CardAlt #0B1A33 — recessed surface (chips, tracks on cards).
    static let cardAlt: Color = Color(red: 11 / 255.0, green: 26 / 255.0, blue: 51 / 255.0)
    /// CardElevated #14305E — hero surface.
    static let cardElevated: Color = Color(red: 20 / 255.0, green: 48 / 255.0, blue: 94 / 255.0)
    /// Blue #2F7CF6 — the one primary.
    static let accent: Color = Color(red: 47 / 255.0, green: 124 / 255.0, blue: 246 / 255.0)
    /// BlueSoft #7AB3FF — soft blue for secondary accents/labels.
    static let blueSoft: Color = Color(red: 122 / 255.0, green: 179 / 255.0, blue: 255 / 255.0)
    /// Sky #38BDF8 — memory / Darkbloom stream.
    static let sky: Color = Color(red: 56 / 255.0, green: 189 / 255.0, blue: 248 / 255.0)
    /// Emerald #2FD48C — GPU / earnings / ok.
    static let good: Color = Color(red: 47 / 255.0, green: 212 / 255.0, blue: 140 / 255.0)
    /// Warn #F5A623.
    static let warn: Color = Color(red: 245 / 255.0, green: 166 / 255.0, blue: 35 / 255.0)
    /// Bad #FF6B6B.
    static let bad: Color = Color(red: 255 / 255.0, green: 107 / 255.0, blue: 107 / 255.0)
    /// Mist #F4F8FF — primary text.
    static let mist: Color = Color(red: 244 / 255.0, green: 248 / 255.0, blue: 255 / 255.0)
    /// TextDim #9FB4D8 — secondary text / labels.
    static let textDim: Color = Color(red: 159 / 255.0, green: 180 / 255.0, blue: 216 / 255.0)
    /// TextFaint #6E86AD — tertiary text.
    static let textFaint: Color = Color(red: 110 / 255.0, green: 134 / 255.0, blue: 173 / 255.0)
    /// White @ 18% — hairline card border.
    static let border: Color = Color.white.opacity(0.18)
    /// Track #081426 — gauge / meter track.
    static let track: Color = Color(red: 8 / 255.0, green: 20 / 255.0, blue: 38 / 255.0)
}

/// The standard dashboard card (Android's MuseCard): navy surface,
/// 20pt corners, hairline border, optional uppercase header title.
struct DashCard<Content: View>: View {
    let title: String?
    let content: Content

    init(title: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let title {
                Text(title.uppercased())
                    .font(.system(size: 12, weight: .semibold))
                    .tracking(0.6)
                    .foregroundStyle(Theme.textDim)
            }
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(Theme.border, lineWidth: 1)
        )
    }
}

/// Label left (dim), value right (semibold) — Android's KeyValueRow.
struct KVRow: View {
    let label: String
    let value: String

    init(_ label: String, _ value: String) {
        self.label = label
        self.value = value
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Text(label)
                .foregroundStyle(Theme.textDim)
                .lineLimit(1)
            Spacer(minLength: 0)
            Text(value)
                .fontWeight(.semibold)
                .foregroundStyle(Theme.mist)
                .multilineTextAlignment(.trailing)
                .lineLimit(1)
        }
        .font(.system(size: 15))
        .padding(.vertical, 3)
    }
}

/// Dot + label in a tinted capsule — Android's StatusChip.
struct StatusChip: View {
    let text: String
    let color: Color

    init(text: String, color: Color) {
        self.text = text
        self.color = color
    }

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(color)
                .frame(width: 7, height: 7)
            Text(text)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(color)
                .lineLimit(1)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(color.opacity(0.14), in: Capsule())
        .overlay(Capsule().strokeBorder(color.opacity(0.5), lineWidth: 1))
    }
}

/// Circular gauge: track circle + trimmed arc, percent centered,
/// label (and optional sublabel) below — Android's ArcGauge, simplified
/// to a full circle per the iOS layout. The fraction is clamped to
/// 0...1 and a non-finite fraction reads as 0, never NaN geometry.
struct GaugeView: View {
    let fraction: Double
    let percentText: String
    let label: String
    let sublabel: String?
    let color: Color

    init(fraction: Double, percentText: String, label: String, sublabel: String?, color: Color) {
        self.fraction = fraction
        self.percentText = percentText
        self.label = label
        self.sublabel = sublabel
        self.color = color
    }

    private var clamped: Double {
        fraction.isFinite ? min(max(fraction, 0), 1) : 0
    }

    var body: some View {
        VStack(spacing: 3) {
            ZStack {
                Circle()
                    .stroke(Theme.track, lineWidth: 11)
                Circle()
                    .trim(from: 0, to: clamped)
                    .stroke(color, style: StrokeStyle(lineWidth: 11, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.easeOut(duration: 0.45), value: clamped)
                Text(percentText)
                    .font(.system(size: 24, weight: .bold))
                    .foregroundStyle(Theme.mist)
                    .lineLimit(1)
            }
            .frame(width: 112, height: 112)
            Text(label.uppercased())
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Theme.mist)
                .multilineTextAlignment(.center)
            if let sublabel {
                Text(sublabel)
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textFaint)
                    .multilineTextAlignment(.center)
                    .lineLimit(1)
            }
        }
    }
}

/// Small uppercase section label inside a card — Android's SectionLabel
/// (blue-soft, label weight).
struct SectionHeader: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 12, weight: .semibold))
            .tracking(0.5)
            .foregroundStyle(Theme.blueSoft)
    }
}
