import Combine
import Foundation

/// The outcome of one bridge action, shown inline in its section
/// ("lmstudio" / "darkbloom" / "mining" / "bridge") until the next
/// refresh or action replaces it.
struct ActionOutcome {
    let ok: Bool
    let message: String
}

@MainActor
final class DashboardViewModel: ObservableObject {
    @Published var overview: OverviewResponse?
    @Published var mining: MiningData?
    @Published var windows: WindowsData?
    @Published var actions: ActionsResponse?
    @Published var connected: Bool = false
    @Published var lastError: String?
    @Published var isRefreshing: Bool = false
    /// The action string currently running, nil when idle. Actions run
    /// one at a time, app-wide.
    @Published var actionInFlight: String?
    /// Last outcome per section key.
    @Published var actionResults: [String: ActionOutcome] = [:]
    /// Persisted in UserDefaults key "bridge_url".
    @Published var bridgeURLString: String

    private let client: BridgeClient
    private var pollTask: Task<Void, Never>?
    private var consecutiveFailures = 0
    /// Bumped on every bridge-URL change. Fetches and actions capture it
    /// at launch and refuse to write state if it has moved on — an
    /// in-flight request to the OLD bridge must never land on the new
    /// one's screen.
    private var urlGeneration = 0
    private var lastSideFetch: Date = .distantPast

    init() {
        let saved = UserDefaults.standard.string(forKey: "bridge_url")
        let initial = saved ?? "http://100.68.208.113:8901"
        bridgeURLString = initial
        client = BridgeClient(baseURLString: initial)
    }

    // MARK: - Poll loop

    /// Starts the 5 s poll loop. Idempotent.
    func start() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                // Anchor the cadence to the START of the fetch, so the
                // real period is 5 s rather than fetch-time + 5 s.
                let cycleStart = Date()
                await self.refresh()
                let elapsed = Date().timeIntervalSince(cycleStart)
                let remaining = max(0, 5 - elapsed)
                try? await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000))
            }
        }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
    }

    // MARK: - Refresh

    func refresh() async {
        // A second caller while one is in flight just skips: the poll
        // loop's next cycle (or the in-flight fetch itself) covers it.
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        let gen = urlGeneration
        do {
            let ov = try await client.fetchOverview()
            guard gen == urlGeneration else { return }
            consecutiveFailures = 0
            overview = ov
            connected = true
            lastError = nil

            // Mining + Windows ride a slower cadence (~25 s): both are
            // SWR-cached on the bridge, so this mostly costs a local
            // round-trip. Their failures never touch the main state —
            // the views just keep their last data.
            if Date().timeIntervalSince(lastSideFetch) > 25 {
                lastSideFetch = Date()
                let newMining = try? await client.fetchMining()
                let newWindows = try? await client.fetchWindows()
                guard gen == urlGeneration else { return }
                if let newMining { mining = newMining.mining }
                if let newWindows { windows = newWindows.windows }
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

    // MARK: - Settings

    func setBridgeURL(_ s: String) {
        let clean = normalizeBridgeURL(s)
        bridgeURLString = clean
        UserDefaults.standard.set(clean, forKey: "bridge_url")
        client.baseURLString = clean
        urlGeneration += 1
        consecutiveFailures = 0
        lastSideFetch = .distantPast
        // Full reset: the old bridge's snapshot must never linger on
        // screen as if it came from the new bridge.
        overview = nil
        mining = nil
        windows = nil
        actions = nil
        connected = false
        lastError = nil
        actionInFlight = nil
        actionResults = [:]
        stop()
        start()
        Task { await refresh() }
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
