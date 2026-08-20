import CoreLocation
import SwiftUI

@MainActor
final class LocationTracker: NSObject, ObservableObject, @preconcurrency CLLocationManagerDelegate {
    @Published private(set) var tracking = false
    @Published private(set) var authorization: CLAuthorizationStatus
    @Published private(set) var lastSentAt: Date?
    @Published private(set) var lastAccuracy: CLLocationAccuracy?
    @Published private(set) var sending = false
    @Published private(set) var lastError: String?
    @Published private(set) var removedFromFamily = false
    @Published private(set) var pauseRestricted = false

    private let manager = CLLocationManager()
    private let queue = EncryptedLocationQueue()
    private let token: String
    var deviceToken: String { token }
    private var heartbeat: Task<Void, Never>?

    init(token: String) {
        self.token = token
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.activityType = .fitness
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 100
        manager.pausesLocationUpdatesAutomatically = true
        UIDevice.current.isBatteryMonitoringEnabled = true
        pauseRestricted = UserDefaults.standard.bool(forKey: "pause_restricted")
        tracking = UserDefaults.standard.bool(forKey: "tracking_active") || pauseRestricted
        lastSentAt = UserDefaults.standard.object(forKey: "last_sent_at") as? Date
        if tracking { start() }
    }

    deinit { heartbeat?.cancel() }

    func requestWhenInUse() { manager.requestWhenInUseAuthorization() }
    func requestAlways() { manager.requestAlwaysAuthorization() }
    func refreshNow() {
        guard tracking, authorization == .authorizedAlways || authorization == .authorizedWhenInUse else { return }
        manager.requestLocation()
    }

    func start() {
        guard authorization == .authorizedAlways || authorization == .authorizedWhenInUse else {
            requestWhenInUse(); return
        }
        tracking = true
        UserDefaults.standard.set(true, forKey: "tracking_active")
        manager.allowsBackgroundLocationUpdates = authorization == .authorizedAlways
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
        manager.startMonitoringSignificantLocationChanges()
        Task { await sendState("active", reason: "started_by_child"); await registerZones(); await flush() }
        startHeartbeat()
    }

    func pause() {
        guard !pauseRestricted else {
            tracking = true
            UserDefaults.standard.set(true, forKey: "tracking_active")
            start()
            return
        }
        tracking = false
        UserDefaults.standard.set(false, forKey: "tracking_active")
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        heartbeat?.cancel()
        Task { await sendState("paused", reason: "paused_by_child") }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorization = manager.authorizationStatus
        if authorization == .denied || authorization == .restricted {
            tracking = false
            UserDefaults.standard.set(false, forKey: "tracking_active")
            Task { await sendState("permission_denied", reason: "location_permission_revoked") }
        } else if pauseRestricted {
            start()
        } else if tracking {
            manager.allowsBackgroundLocationUpdates = authorization == .authorizedAlways
            manager.startUpdatingLocation()
        }
    }

    func updatePauseRestriction(_ restricted: Bool) {
        if pauseRestricted == restricted && (!restricted || tracking) { return }
        pauseRestricted = restricted
        UserDefaults.standard.set(restricted, forKey: "pause_restricted")
        if restricted {
            UserDefaults.standard.set(true, forKey: "tracking_active")
            start()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard tracking, let location = locations.last, location.horizontalAccuracy >= 0 else { return }
        lastAccuracy = location.horizontalAccuracy
        let level = UIDevice.current.batteryLevel
        let upload = LocationUpload(
            id: UUID(), recordedAt: location.timestamp,
            latitude: location.coordinate.latitude, longitude: location.coordinate.longitude,
            accuracy: location.horizontalAccuracy,
            speed: location.speed >= 0 ? location.speed : nil,
            batteryLevel: level >= 0 ? Double(level) : 0,
            isCharging: UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
        )
        queue.append(upload)
        Task { await flush() }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        guard (error as? CLError)?.code != .locationUnknown else { return }
        lastError = error.localizedDescription
    }

    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) { if tracking { manager.requestLocation() } }
    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) { if tracking { manager.requestLocation() } }

    private func flush() async {
        guard !sending else { return }
        let pending = queue.prefix(100)
        guard !pending.isEmpty else { return }
        sending = true
        defer { sending = false }
        do {
            try await OrbitAPI.shared.sendLocations(pending, token: token)
            queue.remove(ids: Set(pending.map(\.id)))
            lastSentAt = .now
            UserDefaults.standard.set(lastSentAt, forKey: "last_sent_at")
            lastError = nil
            if !queue.prefix(1).isEmpty { await flush() }
        } catch {
            if !handleDeviceFailure(error) {
                lastError = "オフラインのため端末に安全に保存しています"
            }
        }
    }

    private func sendState(_ state: String, reason: String) async {
        do { try await OrbitAPI.shared.sendTrackingState(state, reason: reason, token: token) }
        catch { _ = handleDeviceFailure(error) }
    }

    private func registerZones() async {
        guard authorization == .authorizedAlways, CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) else { return }
        let zones: [DeviceZone]
        do { zones = try await OrbitAPI.shared.deviceZones(token: token) }
        catch { _ = handleDeviceFailure(error); return }
        for region in manager.monitoredRegions { manager.stopMonitoring(for: region) }
        for zone in zones.prefix(20) {
            let region = CLCircularRegion(
                center: CLLocationCoordinate2D(latitude: zone.latitude, longitude: zone.longitude),
                radius: min(zone.radiusMeters, manager.maximumRegionMonitoringDistance),
                identifier: zone.id
            )
            region.notifyOnEntry = true; region.notifyOnExit = true
            manager.startMonitoring(for: region)
        }
    }

    private func startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(300))
                guard let self, self.tracking else { return }
                await self.sendState("active", reason: "heartbeat")
                await self.flush()
            }
        }
    }

    @discardableResult
    private func handleDeviceFailure(_ error: Error) -> Bool {
        guard let apiError = error as? OrbitAPIError, case .unauthorized = apiError else { return false }
        tracking = false
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        heartbeat?.cancel()
        queue.clear()
        LinkFamilyRemoval.mark()
        removedFromFamily = true
        lastError = nil
        return true
    }
}
