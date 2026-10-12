import Foundation

// Talks to the Mac-side dashboard bridge (see bridge/dashboard_bridge.py).
// Foundation URLSession only, async/await, no dependencies.

enum BridgeError: Error {
    case http(Int)
    case emptyBody
    case unexpectedResponse
    case badURL
}

/// Trim, drop trailing slashes, and add http:// when no scheme is present
/// (mirrors the Android repository's normalizeBaseUrl).
func normalizeBridgeURL(_ raw: String) -> String {
    var url = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    while url.hasSuffix("/") { url.removeLast() }
    if !url.hasPrefix("http://") && !url.hasPrefix("https://") {
        url = "http://" + url
    }
    return url
}

final class BridgeClient {
    /// The bridge base URL; assigning a new value normalizes it.
    var baseURLString: String {
        didSet { baseURLString = normalizeBridgeURL(baseURLString) }
    }

    private let session: URLSession
    private let actionSession: URLSession
    private let decoder: JSONDecoder

    init(baseURLString: String) {
        self.baseURLString = normalizeBridgeURL(baseURLString)

        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        session = URLSession(configuration: config)

        // Actions can block for minutes server-side (a cold model load
        // takes as long as it takes, up to ~15 min), so the action POST
        // gets its own session with a long timeout instead of borrowing
        // the poll's 15 s one.
        let actionConfig = URLSessionConfiguration.default
        actionConfig.timeoutIntervalForRequest = 960
        actionSession = URLSession(configuration: actionConfig)

        decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
    }

    // MARK: - GET endpoints

    func fetchOverview() async throws -> OverviewResponse {
        let data = try await fetchData(path: "/api/overview")
        // A 200 with an error-shaped body must land in the error path —
        // never render as a plausible fake machine that reads "connected".
        try Self.requireTopLevelKeys(data, anyOf: ["system", "generated_at"])
        return try decoder.decode(OverviewResponse.self, from: data)
    }

    func fetchMining() async throws -> MiningResponse {
        try await get(MiningResponse.self, path: "/api/mining")
    }

    func fetchWindows() async throws -> WindowsResponse {
        try await get(WindowsResponse.self, path: "/api/windows")
    }

    func fetchActions() async throws -> ActionsResponse {
        let data = try await fetchData(path: "/api/actions")
        // Same rule as fetchOverview: no section keys, no control panel.
        try Self.requireTopLevelKeys(data, anyOf: ["lmstudio", "darkbloom", "mining", "bridge"])
        return try decoder.decode(ActionsResponse.self, from: data)
    }

    // MARK: - Actions

    /// POST /api/action. Deliberately parses the body on ANY HTTP status:
    /// the action contract answers 400 with a JSON {"ok": false,
    /// "error": ...} body whose words belong on screen, not folded into
    /// a generic HTTP error.
    func postAction(action: String, target: String?) async throws -> ActionResult {
        guard let url = URL(string: baseURLString + "/api/action") else {
            throw BridgeError.badURL
        }
        var payload: [String: Any] = ["action": action]
        if let target { payload["target"] = target }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let (data, response) = try await actionSession.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? -1
        if let result = try? decoder.decode(ActionResult.self, from: data) {
            return result
        }
        // A non-JSON body on an error status is a transport-level failure
        // (proxy page, captive portal); on a 200 it is a bridge bug.
        // Either way: an exception, never a fabricated result.
        if (200...299).contains(status) { throw BridgeError.unexpectedResponse }
        throw BridgeError.http(status)
    }

    // MARK: - Plumbing

    private func get<T: Decodable>(_ type: T.Type, path: String) async throws -> T {
        try decoder.decode(type, from: try await fetchData(path: path))
    }

    private func fetchData(path: String) async throws -> Data {
        guard let url = URL(string: baseURLString + path) else {
            throw BridgeError.badURL
        }
        let (data, response) = try await session.data(from: url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard status == 200 else { throw BridgeError.http(status) }
        guard !data.isEmpty else { throw BridgeError.emptyBody }
        return data
    }

    private static func requireTopLevelKeys(_ data: Data, anyOf keys: [String]) throws {
        guard let object = try? JSONSerialization.jsonObject(with: data),
              let dict = object as? [String: Any],
              keys.contains(where: { dict[$0] != nil })
        else { throw BridgeError.unexpectedResponse }
    }
}
