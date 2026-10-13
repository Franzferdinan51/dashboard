import SwiftUI
import Foundation

/// Validate a bridge URL draft (Android's bridgeUrlError, ported).
/// Returns nil when valid, else a short reason.
private func bridgeUrlError(_ raw: String) -> String? {
    let v = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if v.isEmpty { return "Enter the bridge address" }
    if v.contains(where: { $0.isWhitespace }) { return "No spaces in the address" }
    if !v.hasPrefix("http://") && !v.hasPrefix("https://") {
        return "Must start with http:// or https://"
    }
    guard let url = URL(string: v) else { return "That address can't be parsed" }
    guard let host = url.host, !host.isEmpty else {
        return "Missing host — e.g. http://192.168.1.50:8901"
    }
    if let port = url.port, !(1...65535).contains(port) {
        return "Port must be between 1 and 65535"
    }
    if !url.path.isEmpty && url.path != "/" {
        return "No path after the port — just http://host:8901"
    }
    if url.query != nil { return "No query string in the bridge address" }
    return nil
}

/// The default bridge address (the Mac's tailnet IP) — the Reset
/// target, shared with the view model's first-run default.
private let defaultBridgeURL = "http://100.68.208.113:8901"

/// Connection + refresh + about, mirroring Android's SettingsScreen:
/// the bridge URL editor with validation, a Test probe (GET
/// /api/health on the draft address), Reset, the poll-interval
/// control, and the about card. Styled as a Form on the dark theme —
/// the system grouped background is hidden so Theme.bg shows through
/// and rows sit on the card color.
struct SettingsView: View {
    @EnvironmentObject private var vm: DashboardViewModel
    @State private var draft = ""
    @State private var pollDraft: Double = 5
    @State private var seeded = false

    private var appVersion: String {
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "—"
    }

    private var urlError: String? { bridgeUrlError(draft) }
    private var dirty: Bool { normalizeBridgeURL(draft) != vm.bridgeURLString }

    var body: some View {
        Form {
            Section {
                HStack {
                    Text("Status")
                        .foregroundStyle(Theme.textDim)
                    Spacer()
                    StatusChip(text: vm.connected ? "CONNECTED" : "UNREACHABLE",
                               color: vm.connected ? Theme.good : Theme.bad)
                }
                .listRowBackground(Theme.card)

                if vm.connected {
                    Text("\(fmtFreshness(vm.lastUpdatedAt)) · polls every \(vm.refreshSeconds)s")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textDim)
                        .listRowBackground(Theme.card)
                }

                TextField("Bridge URL", text: $draft)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled(true)
                    .keyboardType(.URL)
                    .foregroundStyle(Theme.mist)
                    .listRowBackground(Theme.card)
                    .onChange(of: draft) { vm.clearTestState() }

                urlFeedback
                    .listRowBackground(Theme.card)

                HStack(spacing: 10) {
                    Button("Apply") {
                        vm.setBridgeURL(draft.trimmingCharacters(in: .whitespacesAndNewlines))
                    }
                    .foregroundStyle(Theme.accent)
                    .disabled(urlError != nil)

                    Button("Test") {
                        Task { await vm.testConnection(draft) }
                    }
                    .foregroundStyle(Theme.blueSoft)
                    .disabled(urlError != nil)

                    Button("Reset") {
                        draft = defaultBridgeURL
                        vm.clearTestState()
                    }
                    .foregroundStyle(Theme.textDim)
                }
                .listRowBackground(Theme.card)

                testFeedback
                    .listRowBackground(Theme.card)
            } header: {
                Text("Bridge URL")
                    .foregroundStyle(Theme.textDim)
            } footer: {
                Text("Bridge address (the dashboard bridge running on the Mac). The default is the Mac's tailnet address, http://100.68.208.113:8901 — the LAN address, http://192.168.1.96:8901, works at home.")
                    .foregroundStyle(Theme.textFaint)
            }

            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Poll every \(Int(pollDraft))s")
                        .foregroundStyle(Theme.mist)
                    Slider(value: $pollDraft, in: 3...30, step: 1)
                        .tint(Theme.accent)
                        .onChange(of: pollDraft) {
                            vm.setRefreshSeconds(Int(pollDraft.rounded()))
                        }
                    Text("Shorter intervals update faster but cost battery and Mac wake time. 5s suits a glanceable display.")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textFaint)
                }
                .listRowBackground(Theme.card)
            } header: {
                Text("Refresh")
                    .foregroundStyle(Theme.textDim)
            }

            Section {
                KVRow("App", "Mac Studio Dashboard v\(appVersion)")
                    .listRowBackground(Theme.card)
                KVRow("Bridge", vm.bridgeURLString)
                    .listRowBackground(Theme.card)
                KVRow("Bridge version", vm.overview?.bridgeVersion ?? "—")
                    .listRowBackground(Theme.card)
                KVRow("Bridge uptime", fmtDuration(vm.overview?.bridgeUptimeSeconds.map { Double($0) }))
                    .listRowBackground(Theme.card)
                KVRow("Data", "System vitals · Darkbloom · LM Studio")
                    .listRowBackground(Theme.card)
                Text("Talks only to your bridge on your tailnet/LAN. The Actions tab can run commands on your own machines.")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textDim)
                    .listRowBackground(Theme.card)
                Text("Earnings come from Darkbloom's account ledger — the same numbers behind the web console. Only settled data is shown; estimates are not.")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textFaint)
                    .listRowBackground(Theme.card)
            } header: {
                Text("About")
                    .foregroundStyle(Theme.textDim)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.bg)
        .onAppear {
            // Seed the drafts once from the live settings; after that
            // the fields are the user's, untouched by polls.
            if !seeded {
                draft = vm.bridgeURLString
                pollDraft = Double(vm.refreshSeconds)
                seeded = true
            }
        }
    }

    /// Under-field feedback (Android's supportingText): the validation
    /// error wins, then the unsaved-changes hint, then the example.
    @ViewBuilder
    private var urlFeedback: some View {
        if let urlError, !draft.trimmingCharacters(in: .whitespaces).isEmpty {
            Text(urlError)
                .font(.system(size: 13))
                .foregroundStyle(Theme.bad)
        } else if dirty {
            Text("Unsaved — tap Apply, or Test first.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.warn)
        } else {
            Text("Example: http://192.168.1.50:8901")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textFaint)
        }
    }

    /// The Test probe's verdict (Android's TestState rendering).
    @ViewBuilder
    private var testFeedback: some View {
        switch vm.testState {
        case .idle:
            if !vm.connected, let err = vm.lastError {
                Text(err)
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.bad)
            }
        case .testing:
            Text("Testing \(draft.trimmingCharacters(in: .whitespacesAndNewlines)) …")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textDim)
        case .ok(let latencyMs, let version):
            Text("Reachable in \(latencyMs) ms" + (version.map { " · bridge v\($0)" } ?? "") + ".")
                .font(.system(size: 13))
                .foregroundStyle(Theme.good)
        case .failed(let message):
            Text(message)
                .font(.system(size: 13))
                .foregroundStyle(Theme.bad)
        }
    }
}
