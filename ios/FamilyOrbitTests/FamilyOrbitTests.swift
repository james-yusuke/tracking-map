import XCTest
@testable import FamilyOrbit

@MainActor
final class FamilyOrbitTests: XCTestCase {
    func testRealMessageContractDecodesDeliveryAndReadState() throws {
        let data = #"{"id":"m1","childId":"c1","clientMessageId":"client-1","body":"帰宅したら連絡してね","deliveryState":"read","createdAt":"2026-08-20T00:00:00Z","pushedAt":"2026-08-20T00:00:01Z","readAt":"2026-08-20T00:01:00Z"}"#.data(using: .utf8)!
        let message = try JSONDecoder().decode(OrbitMessage.self, from: data)
        XCTAssertEqual(message.deliveryState, "read")
        XCTAssertNotNil(message.readAt)
    }

    func testHistoryDayContractUsesServerValues() throws {
        let data = #"{"date":"2026-08-20","pointCount":42,"firstRecordedAt":"2026-08-19T23:00:00Z","lastRecordedAt":"2026-08-20T08:00:00Z"}"#.data(using: .utf8)!
        let day = try JSONDecoder().decode(OrbitHistoryDay.self, from: data)
        XCTAssertEqual(day.pointCount, 42)
    }

    func testPairingCodeCarriesPauseRestriction() throws {
        let data = #"{"code":"123456","expiresAt":"2026-08-20T00:10:00Z","qrPayload":"familyorbit://pair?code=123456","pauseRestricted":true}"#.data(using: .utf8)!
        let pairing = try JSONDecoder().decode(OrbitPairingCode.self, from: data)
        XCTAssertTrue(pairing.pauseRestricted)
    }
}
