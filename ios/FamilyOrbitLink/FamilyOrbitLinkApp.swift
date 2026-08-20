import SwiftUI
import UIKit
import UserNotifications

final class LinkAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let pushToken = deviceToken.map { String(format: "%02x", $0) }.joined()
        UserDefaults.standard.set(pushToken, forKey: "link_apns_token")
        guard let deviceToken = KeychainStore().string(for: "child_device_token") else { return }
        Task { try? await OrbitAPI.shared.registerDevicePush(pushToken: pushToken, token: deviceToken) }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        if notification.request.content.userInfo["type"] as? String == "family_removed" {
            await MainActor.run { LinkFamilyRemoval.mark() }
        }
        return [.banner, .list, .sound]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let userInfo = response.notification.request.content.userInfo
        if userInfo["type"] as? String == "family_removed" {
            await MainActor.run { LinkFamilyRemoval.mark() }
            return
        }
        guard let messageID = (userInfo["messageId"] ?? userInfo["itemId"]) as? String else { return }
        UserDefaults.standard.set(messageID, forKey: "pending_link_message_id")
        NotificationCenter.default.post(name: .orbitLinkMessageOpened, object: messageID)
    }
}

extension Notification.Name {
    static let orbitLinkMessageOpened = Notification.Name("orbitLinkMessageOpened")
    static let orbitFamilyRemoved = Notification.Name("orbitFamilyRemoved")
}

@MainActor
enum LinkFamilyRemoval {
    static func mark() {
        KeychainStore().remove("child_device_token")
        EncryptedLocationQueue().clear()
        UserDefaults.standard.set(false, forKey: "tracking_active")
        UserDefaults.standard.set(false, forKey: "pause_restricted")
        UserDefaults.standard.set(true, forKey: "removed_from_family")
        NotificationCenter.default.post(name: .orbitFamilyRemoved, object: nil)
    }
}

enum LinkPushRegistration {
    @MainActor
    static func request(deviceToken: String) async {
        guard (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound])) == true else { return }
        UIApplication.shared.registerForRemoteNotifications()
        if let pushToken = UserDefaults.standard.string(forKey: "link_apns_token") {
            try? await OrbitAPI.shared.registerDevicePush(pushToken: pushToken, token: deviceToken)
        }
    }
}

@main
struct FamilyOrbitLinkApp: App {
    @UIApplicationDelegateAdaptor(LinkAppDelegate.self) private var appDelegate
    var body: some Scene {
        WindowGroup {
            LinkRootView()
                .preferredColorScheme(.dark)
                .tint(OrbitTheme.lime)
        }
    }
}
