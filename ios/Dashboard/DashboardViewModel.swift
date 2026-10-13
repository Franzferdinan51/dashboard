import Combine
import Foundation

/// The outcome of one bridge action, shown inline in its section
/// ("lmstudio" / "darkbloom" / "mining" / "bridge") until the next
/// refresh or action replaces it.
struct ActionOutcome {
    let ok: Bool
    let message: String
}

/// The Settings "Test" probe's state (Android's TestState): idle until
/// the user tests a draft URL, then a verdict with latency + bridge
/// version, or the friendly failure.
enum BridgeTestState {
    case idle
    case testing
    case ok(latencyMs: Int, version: String?)
    case failed(String)
}

@MainActor
final class DashboardViewModel: ObservableObject {
    @Published var overview: OverviewResponse?
    @Published var mining: MiningData?
    @Published var windows: WindowsData?
    @Published var actions: ActionsResponse?
    @Published var decisions: [DecisionEvent] = []
    @Published var cannaai: CannaaiData?
    @Published var systemone: SystemOneData?
    @Published var connected: Bool = false
    @Published var lastError: String?
    @Published var isRefreshing: Bool = false
    /// When the last successful overview sample landed (this device's
    /// clock) — the Overview status line's "updated Xs ago".
    @Published var lastUpdatedAt: Date?
    /// The action string currently running, nil when idle. Actions run
    /// one at a time, app-wide.
    @Published var actionInFlight: String?
    /// Last outcome per section key.
    @Published var actionResults: [String: ActionOutcome] = [:]
    /// A plant-photo upload is on its way to the bridge (the POST
    /// itself, not the minutes-long analysis that follows it).
    @Published var analyzeSubmitting: Bool = false
    /// The bridge's verdict on the last photo upload (accepted /
    /// already-running / rejected), shown in the Grow tab until the
    /// next upload replaces it.
    @Published var analyzeOutcome: ActionOutcome?
    /// A vision-model change is on its way to the bridge (the POST
    /// plus the CannaAI restart it triggers — not instant).
    @Published var visionModelSubmitting: Bool = false
    /// The bridge's verdict on the last vision-model change, shown
    /// under the Grow tab's model picker until the next change.
    @Published var visionModelOutcome: ActionOutcome?
    /// Persisted in UserDefaults key "bridge_url".
    @Published var bridgeURLString: String
    /// Poll cadence in seconds, persisted in UserDefaults key
    /// "refresh_seconds" (default 5, range 3...30 — Android parity).
    @Published var refreshSeconds: Int
    @Published var testState: BridgeTestState = .idle
    /// Set by ActionsView while its tab is on screen: the side-fetch
    /// cycle then also refreshes action state, so JIT auto-unloads
    /// (600 s TTL) don't leave the Load/Unload buttons stale.
    var actionsTabVisible = false

    private let client: BridgeClient
    private var pollTask: Task<Void, Never>?
    private var consecutiveFailures = 0
    /// Bumped on every bridge-URL change. Fetches and actions capture it
    /// at launch and refuse to write state if it has moved on — an
    /// in-flight request to the OLD bridge must never land on the new
    /// one's screen.
    private var urlGeneration = 0
    private var lastSideFetch: Date = .distantPast
    /// A refresh requested while one was in flight: honored with one
    /// trailing fetch instead of being dropped (Android's pendingRefresh).
    private var pendingRefresh = false
    /// Manual-refresh callers parked until the trailing fetch lands —
    /// pull-to-refresh must not dismiss its spinner on a fetch it
    /// didn't cause.
    private var refreshWaiters: [CheckedContinuation<Void, Never>] = []

    init() {
        let saved = UserDefaults.standard.string(forKey: "bridge_url")
        let initial = saved ?? "http://100.68.208.113:8901"
        bridgeURLString = initial
        let savedSeconds = UserDefaults.standard.object(forKey: "refresh_seconds") as? Int
        refreshSeconds = min(max(savedSeconds ?? 5, 3), 30)
        client = BridgeClient(baseURLString: initial)
    }

    // MARK: - Poll loop

    /// Starts the poll loop (interval = refreshSeconds). Idempotent.
    func start() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                // Anchor the cadence to the START of the fetch, so the
                // real period is the interval rather than fetch-time +
                // interval.
                let cycleStart = Date()
                await self.refresh()
                let elapsed = Date().timeIntervalSince(cycleStart)
                let remaining = max(0, Double(self.refreshSeconds) - elapsed)
                try? await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000))
            }
        }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
    }

    // MARK: - Refresh

    /// Poll cycles and pull-to-refresh share this entry. A caller that
    /// arrives while a fetch is in flight queues ONE trailing fetch
    /// and (unlike the old skip) waits for it — the in-flight fetch
    /// may have started before the user's pull, with older data.
    func refresh() async {
        if isRefreshing {
            pendingRefresh = true
            await withCheckedContinuation { refreshWaiters.append($0) }
            return
        }
        isRefreshing = true
        repeat {
            pendingRefresh = false
            await fetchOnce()
        } while pendingRefresh && !Task.isCancelled
        isRefreshing = false
        let waiters = refreshWaiters
        refreshWaiters = []
        waiters.forEach { $0.resume() }
    }

    private func fetchOnce() async {
        let gen = urlGeneration
        do {
            let ov = try await client.fetchOverview()
            guard gen == urlGeneration else { return }
            consecutiveFailures = 0
            overview = ov
            connected = true
            lastError = nil
            lastUpdatedAt = Date()

            // Mining + Windows ride a slower cadence (~25 s): both are
            // SWR-cached on the bridge, so this mostly costs a local
            // round-trip. Their failures never touch the main state —
            // the views just keep their last data. The decisions log,
            // CannaAI and SystemOne proxies ride the same cadence.
            let sideDue = Date().timeIntervalSince(lastSideFetch) > 25
                || mining == nil || windows == nil
            if sideDue {
                let newMining = try? await client.fetchMining()
                let newWindows = try? await client.fetchWindows()
                let newDecisions = try? await client.fetchDecisions()
                let newCannaai = try? await client.fetchCannaai()
                let newSystemone = try? await client.fetchSystemone()
                guard gen == urlGeneration else { return }
                if let newMining { mining = newMining.mining }
                if let newWindows { windows = newWindows.windows }
                if let newDecisions { decisions = newDecisions.decisions }
                if let newCannaai { cannaai = newCannaai }
                if let newSystemone { systemone = newSystemone }
                // Stamp the cadence ONLY when the core pair landed.
                // The old code stamped before fetching and swallowed
                // the errors, so one failed first fetch left the
                // Mining/Windows tabs on "Waiting…" for the full 25 s;
                // now a failure retries on the next main poll.
                if newMining != nil && newWindows != nil {
                    lastSideFetch = Date()
                }
                // While the Actions tab is on screen, its state rides
                // this cadence too — LM Studio's JIT TTL (600 s) can
                // unload a model under an open tab.
                if actionsTabVisible {
                    await refreshActions()
                }
            }
        } catch {
            guard gen == urlGeneration else { return }
            consecutiveFailures += 1
            // Two-strike debounce: a single flaky poll (slow bridge,
            // network hiccup) must not flap every chip to OFFLINE; the
            // second consecutive failure declares it for real. Last-good
            // data stays on screen either way.
            if consecutiveFailures >= 2 { connected = false }
            lastError = Self.friendlyError(error)
        }
    }

    func refreshActions() async {
        do {
            actions = try await client.fetchActions()
        } catch {
            // With data on screen a failed refresh stays silent — the
            // sections keep their last known state. With nothing yet,
            // surface it so the tab can show an error instead of a void.
            if actions == nil { lastError = Self.friendlyError(error) }
        }
    }

    // MARK: - Actions

    /// Fire one bridge action (already user-confirmed in the UI). One at
    /// a time: a second call while one runs is ignored, because actions
    /// like model loads take minutes and stacking them helps no one.
    /// The bridge's reply (or the failure) lands in the section's result
    /// slot, then actions state (and stats) refresh to match reality.
    func performAction(_ action: String, target: String?, section: String) async {
        guard actionInFlight == nil else { return }
        actionInFlight = action
        actionResults[section] = nil
        let gen = urlGeneration
        do {
            let result = try await client.postAction(action: action, target: target)
            if gen == urlGeneration {
                actionResults[section] = ActionOutcome(ok: result.ok, message: result.message)
            }
        } catch {
            if gen == urlGeneration {
                actionResults[section] = ActionOutcome(ok: false, message: Self.friendlyError(error))
            }
        }
        // On a URL change, setBridgeURL already reset this — don't clear
        // a newer action's claim.
        if gen == urlGeneration {
            actionInFlight = nil
            await refreshActions()
            await refresh()
        }
    }

    // MARK: - Plant photo analysis

    /// Hand one plant photo (base64 JPEG) to the bridge for CannaAI
    /// analysis. The POST only uploads — the vision model takes
    /// minutes, and the result arrives via the normal cannaai poll
    /// as a new history entry, with analyze.pending true meanwhile.
    func submitPlantPhoto(imageBase64: String, strain: String?) async {
        guard !analyzeSubmitting else { return }
        analyzeSubmitting = true
        analyzeOutcome = nil
        let gen = urlGeneration
        do {
            let ack = try await client.postAnalyze(
                imageBase64: imageBase64, strain: strain, notes: nil)
            if gen == urlGeneration {
                analyzeOutcome = ActionOutcome(ok: ack.ok, message: ack.message)
            }
        } catch {
            if gen == urlGeneration {
                analyzeOutcome = ActionOutcome(ok: false, message: Self.friendlyError(error))
            }
        }
        if gen == urlGeneration {
            analyzeSubmitting = false
            // Pull the fresh cannaai state right away: a 202 flips
            // analyze.pending on the bridge, and the card should
            // show "Analyzing…" without waiting out the side cadence.
            await refresh()
        }
    }

    // MARK: - Vision model selection

    /// Set which vision model CannaAI analyzes photos with (bridge
    /// 1.14+). One at a time, like every other write here; applying
    /// the change restarts CannaAI on the bridge side. The picker's
    /// current value is payload state, not local state — after the
    /// POST, a refresh pulls vision_model back so the menu shows
    /// what the bridge actually applied.
    func setVisionModel(_ model: String) async {
        guard !visionModelSubmitting else { return }
        visionModelSubmitting = true
        visionModelOutcome = nil
        let gen = urlGeneration
        do {
            let ack = try await client.postVisionModel(model)
            if gen == urlGeneration {
                visionModelOutcome = ActionOutcome(ok: ack.ok, message: ack.message)
            }
        } catch {
            if gen == urlGeneration {
                visionModelOutcome = ActionOutcome(ok: false, message: Self.friendlyError(error))
            }
        }
        if gen == urlGeneration {
            visionModelSubmitting = false
            await refresh()
        }
    }

    // MARK: - Settings

    func setBridgeURL(_ s: String) {
        let clean = normalizeBridgeURL(s)
        bridgeURLString = clean
        UserDefaults.standard.set(clean, forKey: "bridge_url")
        client.baseURLString = clean
        urlGeneration += 1
        consecutiveFailures = 0
        lastSideFetch = .distantPast
        testState = .idle
        // Full reset: the old bridge's snapshot must never linger on
        // screen as if it came from the new bridge.
        overview = nil
        mining = nil
        windows = nil
        actions = nil
        decisions = []
        cannaai = nil
        systemone = nil
        lastUpdatedAt = nil
        connected = false
        lastError = nil
        actionInFlight = nil
        actionResults = [:]
        analyzeSubmitting = false
        analyzeOutcome = nil
        visionModelSubmitting = false
        visionModelOutcome = nil
        stop()
        start()
        Task { await refresh() }
    }

    func setRefreshSeconds(_ seconds: Int) {
        let clamped = min(max(seconds, 3), 30)
        refreshSeconds = clamped
        UserDefaults.standard.set(clamped, forKey: "refresh_seconds")
    }

    /// Probe a candidate bridge address (the Settings draft, not the
    /// saved URL): GET /api/health, report latency + bridge version.
    func testConnection(_ urlString: String) async {
        testState = .testing
        do {
            let result = try await client.fetchHealth(baseURLString: urlString)
            testState = .ok(latencyMs: result.latencyMs, version: result.health.version)
        } catch {
            testState = .failed(Self.friendlyError(error))
        }
    }

    func clearTestState() {
        switch testState {
        case .testing: break
        default: testState = .idle
        }
    }

    // MARK: - Errors

    private static func friendlyError(_ error: Error) -> String {
        if let urlError = error as? URLError {
            switch urlError.code {
            case .cannotConnectToHost, .networkConnectionLost:
                return "Can't reach the bridge — is the Mac awake and the bridge running?"
            case .timedOut:
                return "Bridge timed out."
            case .cannotFindHost, .dnsLookupFailed:
                return "Unknown host — check the bridge address."
            default:
                return "Couldn't reach the bridge."
            }
        }
        if error is DecodingError {
            return "The bridge sent data the app couldn't read."
        }
        if let bridgeError = error as? BridgeError {
            switch bridgeError {
            case .http(let code):
                return "Bridge returned HTTP \(code)."
            case .unexpectedResponse, .emptyBody:
                return "The bridge sent data the app couldn't read."
            case .badURL:
                return "That bridge address doesn't look right."
            }
        }
        return "Couldn't reach the bridge."
    }
}
