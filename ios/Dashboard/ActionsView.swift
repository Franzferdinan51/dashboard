import SwiftUI

/// The control surface: LM Studio model load/unload (via the bridge's
/// lms CLI), Darkbloom restart, the Batman-3 miner, and the bridge
/// itself. State comes from GET /api/actions, fetched when the tab
/// opens and after every action — never on the stats poll. Every
/// button goes through a confirmation first; this tab is the one
/// place the app stops being read-only, so it should feel deliberate.
/// Mirrors Android's ActionsScreen.
struct ActionsView: View {
    @EnvironmentObject private var vm: DashboardViewModel
    @State private var pending: PendingAction?

    /// A user intent waiting on its confirmation. Nothing fires until
    /// the alert's confirm button turns one of these into a VM call.
    private struct PendingAction: Identifiable {
        let id = UUID()
        let action: String
        let target: String?
        let section: String
        let title: String
        let body: String
        let confirmLabel: String
    }

    private var anyBusy: Bool { vm.actionInFlight != nil }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if let data = vm.actions {
                    lmStudioCard(data)
                    darkbloomCard(data)
                    miningCard(data)
                    bridgeCard(data)
                } else if let error = vm.lastError {
                    DashCard(title: "Actions") {
                        Text(error)
                            .font(.system(size: 15))
                            .foregroundStyle(Theme.bad)
                        Button("Retry") {
                            Task { await vm.refreshActions() }
                        }
                        .buttonStyle(.bordered)
                        .tint(Theme.blueSoft)
                    }
                } else {
                    DashCard {
                        Text("Waiting for actions data from the bridge…")
                            .font(.system(size: 15))
                            .foregroundStyle(Theme.textDim)
                    }
                }
            }
            .padding(16)
        }
        .background(Theme.bg)
        .task { await vm.refreshActions() }
        .refreshable { await vm.refreshActions() }
        .alert(
            pending?.title ?? "",
            isPresented: Binding(
                get: { pending != nil },
                set: { if !$0 { pending = nil } }
            ),
            presenting: pending
        ) { p in
            Button(p.confirmLabel) {
                pending = nil
                Task { await vm.performAction(p.action, target: p.target, section: p.section) }
            }
            Button("Cancel", role: .cancel) { pending = nil }
        } message: { p in
            Text(p.body)
        }
    }

    // MARK: - Card chrome

    /// Android's CardHeader: uppercase title + faint subtitle on the
    /// left, an optional status chip trailing.
    @ViewBuilder
    private func cardHeader(_ title: String, _ subtitle: String,
                            chip: (text: String, color: Color)? = nil) -> some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title.uppercased())
                    .font(.system(size: 12, weight: .semibold))
                    .tracking(0.6)
                    .foregroundStyle(Theme.textDim)
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textFaint)
            }
            Spacer(minLength: 0)
            if let chip {
                StatusChip(text: chip.text, color: chip.color)
            }
        }
    }

    /// Small group label inside a card ("LOADED" / "LIBRARY").
    private func groupLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .semibold))
            .tracking(0.5)
            .foregroundStyle(Theme.textFaint)
    }

    /// The bridge's last word on a section, inline until the next
    /// refresh or action replaces it.
    @ViewBuilder
    private func sectionResult(_ section: String) -> some View {
        if let result = vm.actionResults[section] {
            Text(result.message)
                .font(.system(size: 13))
                .foregroundStyle(result.ok ? Theme.good : Theme.bad)
        }
    }

    /// Progress row shown in the section whose action is in flight.
    @ViewBuilder
    private func busyIndicator(actions: [String]) -> some View {
        if let inFlight = vm.actionInFlight, actions.contains(inFlight) {
            HStack(spacing: 8) {
                ProgressView()
                Text(busyLabel(for: inFlight))
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textDim)
            }
        }
    }

    private func busyLabel(for action: String) -> String {
        switch action {
        case "lm_load": return "Loading…"
        case "lm_unload": return "Unloading…"
        case "miner_start": return "Starting…"
        case "miner_stop": return "Stopping…"
        case "darkbloom_restart", "bridge_restart": return "Restarting…"
        default: return "Working…"
        }
    }

    // MARK: - LM Studio

    private func lmStudioCard(_ data: ActionsResponse) -> some View {
        let loaded = data.lmstudio.loaded
        let available = data.lmstudio.available
        return DashCard {
            cardHeader(
                "LM Studio",
                "models on the Mac — a cold load can take minutes",
                chip: (text: "\(loaded.count) LOADED",
                       color: loaded.isEmpty ? Theme.textDim : Theme.good)
            )
            groupLabel("LOADED")
            if loaded.isEmpty {
                Text("No models loaded")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textDim)
            } else {
                ForEach(loaded, id: \.identifier) { model in
                    HStack(spacing: 10) {
                        Text(model.identifier)
                            .font(.system(size: 15))
                            .foregroundStyle(Theme.mist)
                            .lineLimit(1)
                        Spacer(minLength: 0)
                        Button("Unload") {
                            pending = PendingAction(
                                action: "lm_unload", target: model.identifier,
                                section: "lmstudio",
                                title: "Unload \(model.identifier)?",
                                body: "This frees the model's memory on the Mac. Anything using it — Hermes, local chat — loses access until it's loaded again.",
                                confirmLabel: "Unload"
                            )
                        }
                        .buttonStyle(.bordered)
                        .tint(Theme.blueSoft)
                        .disabled(anyBusy)
                    }
                    .padding(.vertical, 2)
                }
            }
            groupLabel("LIBRARY")
                .padding(.top, 6)
            if available.isEmpty {
                Text("Library unavailable")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textDim)
            } else {
                ForEach(available, id: \.key) { model in
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(model.key)
                                .font(.system(size: 15))
                                .foregroundStyle(Theme.mist)
                                .lineLimit(1)
                            if let size = model.size {
                                Text(size)
                                    .font(.system(size: 13))
                                    .foregroundStyle(Theme.textFaint)
                            }
                        }
                        Spacer(minLength: 0)
                        Button("Load") {
                            pending = PendingAction(
                                action: "lm_load", target: model.key,
                                section: "lmstudio",
                                title: "Load \(model.key)?",
                                body: "Loading a model can use a lot of memory on the Mac, and a cold load can take several minutes. The button stays busy until the bridge answers.",
                                confirmLabel: "Load"
                            )
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Theme.accent)
                        .disabled(anyBusy)
                    }
                    .padding(.vertical, 2)
                }
            }
            busyIndicator(actions: ["lm_load", "lm_unload"])
            sectionResult("lmstudio")
        }
    }

    // MARK: - Darkbloom

    private func darkbloomCard(_ data: ActionsResponse) -> some View {
        let running = data.darkbloom.running
        return DashCard {
            cardHeader(
                "Darkbloom",
                "provider service on the Mac",
                chip: (text: running ? "RUNNING" : "NOT RUNNING",
                       color: running ? Theme.good : Theme.bad)
            )
            KVRow("Provider", running ? "Running" : "Not running")
            Button("Restart Darkbloom") {
                pending = PendingAction(
                    action: "darkbloom_restart", target: nil,
                    section: "darkbloom",
                    title: "Restart Darkbloom?",
                    body: "Serving interrupts briefly while the provider restarts; it comes back on its own. Earnings pause only for the restart itself.",
                    confirmLabel: "Restart"
                )
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.accent)
            .disabled(anyBusy)
            busyIndicator(actions: ["darkbloom_restart"])
            sectionResult("darkbloom")
        }
    }

    // MARK: - Mining

    private func miningCard(_ data: ActionsResponse) -> some View {
        let running = data.mining.minerRunning
        return DashCard {
            cardHeader(
                "Mining — Batman-3",
                "the miner on the Windows PC",
                chip: (text: running ? "MINER RUNNING" : "MINER STOPPED",
                       color: running ? Theme.good : Theme.bad)
            )
            KVRow("Miner", running ? "Running" : "Stopped")
            HStack(spacing: 10) {
                Button("Start miner") {
                    pending = PendingAction(
                        action: "miner_start", target: nil,
                        section: "mining",
                        title: "Start the miner?",
                        body: "Starts the miner on Batman-3. The GPU draws full power while it runs.",
                        confirmLabel: "Start"
                    )
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.accent)
                .disabled(anyBusy || running)
                Button("Stop miner") {
                    pending = PendingAction(
                        action: "miner_stop", target: nil,
                        section: "mining",
                        title: "Stop the miner?",
                        body: "Mining earnings halt while the miner is off.",
                        confirmLabel: "Stop"
                    )
                }
                .buttonStyle(.bordered)
                .tint(Theme.blueSoft)
                .disabled(anyBusy || !running)
            }
            busyIndicator(actions: ["miner_start", "miner_stop"])
            sectionResult("mining")
        }
    }

    // MARK: - Bridge

    private func bridgeCard(_ data: ActionsResponse) -> some View {
        DashCard {
            cardHeader("Bridge", "the dashboard bridge itself")
            KVRow("Bridge version", data.bridge.version.isEmpty ? "—" : data.bridge.version)
            Button("Restart bridge") {
                pending = PendingAction(
                    action: "bridge_restart", target: nil,
                    section: "bridge",
                    title: "Restart the bridge?",
                    body: "The dashboard drops for a few seconds while the bridge restarts, then reconnects on its own.",
                    confirmLabel: "Restart"
                )
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.accent)
            .disabled(anyBusy)
            busyIndicator(actions: ["bridge_restart"])
            sectionResult("bridge")
        }
    }
}
