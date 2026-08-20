import Foundation

struct OrbitFamily: Codable {
    let id: String
    let name: String
    let role: String
}

struct OrbitLocation: Codable {
    let latitude: Double
    let longitude: Double
    let accuracy: Double
    let recordedAt: String
    let batteryLevel: Double
    let isCharging: Bool
}

struct OrbitChild: Codable, Identifiable {
    let id: String
    let name: String
    let color: String
    let trackingState: String
    let connectivity: String
    let latestLocation: OrbitLocation?
}

struct OrbitZone: Codable, Identifiable {
    let id: String
    let name: String
    let latitude: Double
    let longitude: Double
    let radiusMeters: Double
    let color: String?
    let childIds: [String]?
}

struct OrbitAlert: Codable, Identifiable {
    let id: String
    let childId: String?
    let type: String
    let title: String
    let message: String
    let occurredAt: String
}

struct OrbitCreatedChild: Codable, Identifiable {
    let id: String
    let name: String
    let color: String
}

struct OrbitPairingCode: Codable {
    let code: String
    let expiresAt: String
    let qrPayload: String
    let pauseRestricted: Bool
}

struct OrbitDeviceConfig: Codable {
    let pauseRestricted: Bool
    let trackingState: String
}

struct OrbitDashboard: Codable {
    let family: OrbitFamily
    let children: [OrbitChild]
    let zones: [OrbitZone]
    let alerts: [OrbitAlert]
    let generatedAt: String
}

struct OrbitHistoryDay: Codable, Identifiable {
    var id: String { date }
    let date: String
    let pointCount: Int
    let firstRecordedAt: String
    let lastRecordedAt: String
}

struct OrbitHistoryPoint: Codable, Identifiable {
    var id: String { "\(recordedAt):\(latitude):\(longitude)" }
    let latitude: Double
    let longitude: Double
    let accuracy: Double
    let recordedAt: String
    let batteryLevel: Double
}

struct OrbitMessage: Codable, Identifiable {
    let id: String
    let childId: String
    let clientMessageId: String
    let body: String
    let deliveryState: String
    let createdAt: String
    let pushedAt: String?
    let readAt: String?
}

struct OrbitMessageList: Codable { let messages: [OrbitMessage] }
struct OrbitHistoryDayList: Codable { let days: [OrbitHistoryDay] }

struct DeviceZone: Codable, Identifiable {
    let id: String
    let name: String
    let latitude: Double
    let longitude: Double
    let radiusMeters: Double
}

struct LocationUpload: Codable, Identifiable {
    let id: UUID
    let recordedAt: Date
    let latitude: Double
    let longitude: Double
    let accuracy: Double
    let speed: Double?
    let batteryLevel: Double
    let isCharging: Bool
}
