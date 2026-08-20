import SwiftUI
import UIKit
import UserNotifications

final class ParentAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        UserDefaults.standard.set(token, forKey: "guardian_apns_token")
        guard let accessToken = KeychainStore().string(for: "guardian_access_token") else { return }
        Task { try? await OrbitAPI.shared.registerPush(token: token, accessToken: accessToken) }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}

enum ParentPushRegistration {
    @MainActor
    static func request(accessToken: String) async {
        let center = UNUserNotificationCenter.current()
        guard (try? await center.requestAuthorization(options: [.alert, .badge, .sound])) == true else { return }
        UIApplication.shared.registerForRemoteNotifications()
        if let token = UserDefaults.standard.string(forKey: "guardian_apns_token") {
            try? await OrbitAPI.shared.registerPush(token: token, accessToken: accessToken)
        }
    }
}

@main
struct FamilyOrbitApp: App {
    @UIApplicationDelegateAdaptor(ParentAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
        }
    }
}
