import Foundation
import UIKit

struct APIErrorEnvelope: Decodable {
    let code: String
    let message: String
}

enum OrbitAPIError: LocalizedError {
    case invalidResponse
    case connection
    case unauthorized(String)
    case server(String)

    var errorDescription: String? {
        switch self {
        case .invalidResponse: "サーバーから正しい応答を受け取れませんでした"
        case .connection: "サーバーに接続できません。Family Orbitサーバーの起動と端末の通信を確認し、もう一度お試しください。"
        case let .unauthorized(message): message
        case let .server(message): message
        }
    }
}

final class OrbitAPI {
    static let shared = OrbitAPI()
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder
    private let baseURL: URL

    init(baseURL: URL? = nil) {
        let configured = Bundle.main.object(forInfoDictionaryKey: "FAMILY_ORBIT_API_URL") as? String
        let selectedURL = baseURL ?? URL(string: configured ?? "http://127.0.0.1:4000/api/v1")!
#if targetEnvironment(simulator)
        // Some Simulator runtimes do not resolve the localhost hostname even
        // though their loopback interface can reach services running on macOS.
        if selectedURL.host == "localhost", var components = URLComponents(url: selectedURL, resolvingAgainstBaseURL: false) {
            components.host = "127.0.0.1"
            self.baseURL = components.url ?? selectedURL
        } else {
            self.baseURL = selectedURL
        }
#else
        self.baseURL = selectedURL
#endif
        decoder = JSONDecoder()
        encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
    }

    func login(email: String, password: String) async throws -> String {
        struct Input: Encodable { let email: String; let password: String; let clientType = "parent_ios" }
        struct Output: Decodable { let accessToken: String; let refreshToken: String? }
        let output: Output = try await request("auth/login", method: "POST", body: Input(email: email, password: password))
        if let refresh = output.refreshToken { try? KeychainStore().set(Data(refresh.utf8), for: "guardian_refresh_token") }
        return output.accessToken
    }

    func register(email: String, password: String, displayName: String, familyName: String) async throws -> String {
        struct Input: Encodable {
            let email: String
            let password: String
            let displayName: String
            let familyName: String
            let clientType = "parent_ios"
        }
        struct Output: Decodable { let accessToken: String; let refreshToken: String? }
        let output: Output = try await request(
            "auth/register",
            method: "POST",
            body: Input(email: email, password: password, displayName: displayName, familyName: familyName)
        )
        if let refresh = output.refreshToken { try? KeychainStore().set(Data(refresh.utf8), for: "guardian_refresh_token") }
        return output.accessToken
    }

    func dashboard(accessToken: String) async throws -> OrbitDashboard {
        try await request("dashboard", authorization: "Bearer \(accessToken)")
    }

    func historyDays(childID: String, accessToken: String) async throws -> [OrbitHistoryDay] {
        let output: OrbitHistoryDayList = try await request("children/\(childID)/history-days", authorization: "Bearer \(accessToken)")
        return output.days
    }

    func history(childID: String, from: String, to: String, accessToken: String) async throws -> [OrbitHistoryPoint] {
        let fromValue = from.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? from
        let toValue = to.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? to
        return try await request("children/\(childID)/history?from=\(fromValue)&to=\(toValue)", authorization: "Bearer \(accessToken)")
    }

    func refreshAccessToken() async throws -> String {
        struct Input: Encodable { let token: String }
        struct Output: Decodable { let accessToken: String; let refreshToken: String? }
        guard let refreshToken = KeychainStore().string(for: "guardian_refresh_token") else {
            throw OrbitAPIError.unauthorized("セッションの有効期限が切れました。再ログインしてください。")
        }
        let output: Output = try await request("auth/refresh", method: "POST", body: Input(token: refreshToken))
        if let rotatedToken = output.refreshToken {
            try KeychainStore().set(Data(rotatedToken.utf8), for: "guardian_refresh_token")
        }
        return output.accessToken
    }

    func createChild(name: String, color: String = "#C9FF4A", accessToken: String) async throws -> OrbitCreatedChild {
        struct Input: Encodable { let name: String; let color: String }
        return try await request(
            "children",
            method: "POST",
            authorization: "Bearer \(accessToken)",
            body: Input(name: name, color: color)
        )
    }

    func createPairingCode(childID: String, pauseRestricted: Bool, accessToken: String) async throws -> OrbitPairingCode {
        struct Input: Encodable { let pauseRestricted: Bool }
        return try await request(
            "children/\(childID)/pairing-code",
            method: "POST",
            authorization: "Bearer \(accessToken)",
            body: Input(pauseRestricted: pauseRestricted)
        )
    }

    func deleteChild(childID: String, accessToken: String) async throws {
        try await requestWithoutResponse("children/\(childID)", method: "DELETE", authorization: "Bearer \(accessToken)")
    }

    func deleteAccount(accessToken: String) async throws {
        try await requestWithoutResponse("account", method: "DELETE", authorization: "Bearer \(accessToken)")
    }

    func saveZone(_ zone: OrbitZone?, name: String, latitude: Double, longitude: Double, radiusMeters: Double, childIDs: [String], accessToken: String) async throws {
        struct Input: Encodable {
            let name: String; let latitude: Double; let longitude: Double; let radiusMeters: Double
            let color = "#72E8C0"; let childIds: [String]; let enabled = true
        }
        let path = zone.map { "zones/\($0.id)" } ?? "zones"
        let method = zone == nil ? "POST" : "PATCH"
        _ = try await performRequest(path, method: method, authorization: "Bearer \(accessToken)", bodyData: try encoder.encode(Input(name: name, latitude: latitude, longitude: longitude, radiusMeters: radiusMeters, childIds: childIDs)))
    }

    func deleteZone(id: String, accessToken: String) async throws {
        try await requestWithoutResponse("zones/\(id)", method: "DELETE", authorization: "Bearer \(accessToken)")
    }

    func sendMessage(childID: String, clientMessageID: String, body: String, accessToken: String) async throws -> OrbitMessage {
        struct Input: Encodable { let clientMessageId: String; let body: String }
        return try await request("children/\(childID)/messages", method: "POST", authorization: "Bearer \(accessToken)", body: Input(clientMessageId: clientMessageID, body: body))
    }

    func guardianMessages(childID: String, accessToken: String) async throws -> [OrbitMessage] {
        let output: OrbitMessageList = try await request("children/\(childID)/messages", authorization: "Bearer \(accessToken)")
        return output.messages
    }

    func registerPush(token: String, accessToken: String) async throws {
        struct Input: Encodable { let deviceName: String; let platform = "ios"; let pushToken: String }
        struct Output: Decodable { let id: String }
        let _: Output = try await request(
            "devices/push",
            method: "POST",
            authorization: "Bearer \(accessToken)",
            body: Input(deviceName: UIDevice.current.name, pushToken: token)
        )
    }

    func pair(code: String, deviceName: String) async throws -> (token: String, childID: String, pauseRestricted: Bool) {
        struct Input: Encodable { let code: String; let deviceName: String; let platform = "ios" }
        struct Output: Decodable { let deviceToken: String; let childId: String; let pauseRestricted: Bool }
        let output: Output = try await request("pairing", method: "POST", body: Input(code: code, deviceName: deviceName))
        return (output.deviceToken, output.childId, output.pauseRestricted)
    }

    func sendLocations(_ locations: [LocationUpload], token: String) async throws {
        struct Input: Encodable { let idempotencyKey: String; let trackingState: String; let samples: [LocationUpload] }
        struct Output: Decodable { let accepted: Int }
        guard let first = locations.first, let last = locations.last else { return }
        let idempotencyKey = "queue:\(first.id.uuidString):\(last.id.uuidString):\(locations.count)"
        let _: Output = try await request(
            "device/locations",
            method: "POST",
            authorization: "Device \(token)",
            body: Input(idempotencyKey: idempotencyKey, trackingState: "active", samples: locations)
        )
    }

    func sendTrackingState(_ state: String, reason: String, token: String) async throws {
        struct Input: Encodable { let state: String; let reason: String }
        struct Output: Decodable { let state: String }
        let _: Output = try await request("device/tracking-state", method: "POST", authorization: "Device \(token)", body: Input(state: state, reason: reason))
    }

    func deviceZones(token: String) async throws -> [DeviceZone] {
        struct Output: Decodable { let zones: [DeviceZone] }
        let output: Output = try await request("device/zones", authorization: "Device \(token)")
        return output.zones
    }

    func registerDevicePush(pushToken: String, token: String) async throws {
        struct Input: Encodable { let pushToken: String }
        struct Output: Decodable { let registered: Bool }
        let _: Output = try await request("device/push", method: "POST", authorization: "Device \(token)", body: Input(pushToken: pushToken))
    }

    func deviceMessages(token: String) async throws -> [OrbitMessage] {
        let output: OrbitMessageList = try await request("device/messages", authorization: "Device \(token)")
        return output.messages
    }

    func deviceConfig(token: String) async throws -> OrbitDeviceConfig {
        try await request("device/config", authorization: "Device \(token)")
    }

    func markMessageRead(id: String, token: String) async throws -> OrbitMessage {
        struct EmptyBody: Encodable {}
        return try await request("device/messages/\(id)/read", method: "POST", authorization: "Device \(token)", body: EmptyBody())
    }

    func unpairDevice(token: String) async throws {
        try await requestWithoutResponse("device", method: "DELETE", authorization: "Device \(token)")
    }

    private func request<Output: Decodable>(_ path: String, method: String = "GET", authorization: String? = nil) async throws -> Output {
        try await request(path, method: method, authorization: authorization, bodyData: nil)
    }

    private func request<Input: Encodable, Output: Decodable>(_ path: String, method: String = "GET", authorization: String? = nil, body: Input) async throws -> Output {
        try await request(path, method: method, authorization: authorization, bodyData: try encoder.encode(body))
    }

    private func request<Output: Decodable>(_ path: String, method: String, authorization: String?, bodyData: Data?) async throws -> Output {
        let data = try await performRequest(path, method: method, authorization: authorization, bodyData: bodyData)
        return try decoder.decode(Output.self, from: data)
    }

    private func requestWithoutResponse(_ path: String, method: String, authorization: String?) async throws {
        _ = try await performRequest(path, method: method, authorization: authorization, bodyData: nil)
    }

    private func performRequest(_ path: String, method: String, authorization: String?, bodyData: Data?) async throws -> Data {
        let pieces = path.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        var url = baseURL.appendingPathComponent(String(pieces[0]))
        if pieces.count == 2, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            components.percentEncodedQuery = String(pieces[1])
            url = components.url ?? url
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = bodyData
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        if let authorization { request.setValue(authorization, forHTTPHeaderField: "Authorization") }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch let error as URLError where [.cannotConnectToHost, .cannotFindHost, .networkConnectionLost, .notConnectedToInternet, .timedOut].contains(error.code) {
            throw OrbitAPIError.connection
        }
        guard let http = response as? HTTPURLResponse else { throw OrbitAPIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            let message = (try? decoder.decode(APIErrorEnvelope.self, from: data))?.message ?? "通信に失敗しました (\(http.statusCode))"
            if http.statusCode == 401 { throw OrbitAPIError.unauthorized(message) }
            throw OrbitAPIError.server(message)
        }
        return data
    }
}
