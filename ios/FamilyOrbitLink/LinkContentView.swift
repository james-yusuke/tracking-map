import CoreLocation
import Network
import SwiftUI
import UIKit

struct LinkRootView: View {
    @State private var token = KeychainStore().string(for: "child_device_token")
    @State private var removedNotice = UserDefaults.standard.bool(forKey: "removed_from_family")

    var body: some View {
        Group {
            if let token {
                TrackingStatusView(
                    token: token,
                    onRemoved: { removedNotice = true; self.token = nil },
                    onUnpair: {
                        KeychainStore().remove("child_device_token")
                        EncryptedLocationQueue().clear()
                        UserDefaults.standard.set(false, forKey: "tracking_active")
                        UserDefaults.standard.set(false, forKey: "pause_restricted")
                        UserDefaults.standard.set(false, forKey: "removed_from_family")
                        removedNotice = false
                        self.token = nil
                    }
                )
            } else {
                PairingView(removedNotice: removedNotice) { token, pauseRestricted in
                    try? KeychainStore().set(Data(token.utf8), for: "child_device_token")
                    UserDefaults.standard.set(pauseRestricted, forKey: "pause_restricted")
                    UserDefaults.standard.set(pauseRestricted, forKey: "tracking_active")
                    UserDefaults.standard.set(false, forKey: "removed_from_family")
                    removedNotice = false
                    self.token = token
                }
            }
        }
        .task(id: token) {
            if let token { await LinkPushRegistration.request(deviceToken: token) }
        }
        .onReceive(NotificationCenter.default.publisher(for: .orbitFamilyRemoved)) { _ in
            removedNotice = true
            token = nil
        }
    }
}

private final class PermissionGuide: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var status: CLAuthorizationStatus
    private let manager = CLLocationManager()
    override init() { status = manager.authorizationStatus; super.init(); manager.delegate = self }
    func whenInUse() { manager.requestWhenInUseAuthorization() }
    func always() { manager.requestAlwaysAuthorization() }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { status = manager.authorizationStatus }
}

private struct PairingView: View {
    @StateObject private var permissions = PermissionGuide()
    @State private var code = ""
    @State private var loading = false
    @State private var error: String?
    let removedNotice: Bool
    let onPaired: (String, Bool) -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 15) {
                Spacer(minLength: 28)
                ZStack { RoundedRectangle(cornerRadius: 18).fill(OrbitTheme.lime).frame(width: 58, height: 58); Image(systemName: "circle.circle.fill").font(.system(size: 30, weight: .black)).foregroundStyle(OrbitTheme.navy) }
                Text("Family Orbit Link").font(.title2.bold())
                Text("位置共有の状態はいつでも確認できます。接続コードによっては、アプリ内の停止操作が保護者設定で制限されます。").foregroundStyle(OrbitTheme.muted).multilineTextAlignment(.center)

                if removedNotice {
                    VStack(alignment: .leading, spacing: 7) {
                        Text("家族から削除されました").fontWeight(.bold).foregroundStyle(OrbitTheme.danger)
                        Text("位置共有とメッセージ受信は停止しました。再び参加するには、保護者から新しい接続コードを受け取ってください。")
                            .font(.footnote).foregroundStyle(.white)
                    }.orbitCard()
                }

                VStack(alignment: .leading, spacing: 12) {
                    Text("1　位置情報を許可").foregroundStyle(OrbitTheme.lime).fontWeight(.bold)
                    Text("画面を閉じた時も、接続した家族へ現在地を共有するために使います。広告には利用しません。").foregroundStyle(OrbitTheme.muted)
                    Button(permissions.status == .authorizedAlways ? "常に許可：設定済み" : "位置情報を許可") {
                        if permissions.status == .authorizedWhenInUse { permissions.always() } else { permissions.whenInUse() }
                    }
                    .buttonStyle(OrbitPrimaryButtonStyle())
                    if permissions.status == .authorizedWhenInUse {
                        Button("バックグラウンド共有を許可") { permissions.always() }.buttonStyle(.bordered)
                    }
                }.orbitCard()

                VStack(alignment: .leading, spacing: 12) {
                    Text("2　保護者と接続").foregroundStyle(OrbitTheme.mint).fontWeight(.bold)
                    Text("保護者アプリの6桁コードを入力してください。コードは10分間・1回だけ有効です。").foregroundStyle(OrbitTheme.muted)
                    TextField("6桁の接続コード", text: $code)
                        .keyboardType(.numberPad).textContentType(.oneTimeCode)
                        .onChange(of: code) { _, value in code = String(value.filter(\.isNumber).prefix(6)) }
                        .padding(15).background(OrbitTheme.raised, in: RoundedRectangle(cornerRadius: 14))
                    if let error { Text(error).font(.footnote).foregroundStyle(OrbitTheme.danger) }
                    Button {
                        loading = true; error = nil
                        Task {
                            do {
                                let result = try await OrbitAPI.shared.pair(code: code, deviceName: UIDevice.current.name)
                                await MainActor.run { onPaired(result.token, result.pauseRestricted); loading = false }
                            } catch {
                                await MainActor.run { self.error = error.localizedDescription; loading = false }
                            }
                        }
                    } label: { Text(loading ? "接続中…" : "家族と接続する").fontWeight(.bold).frame(maxWidth: .infinity, minHeight: 50) }
                    .buttonStyle(OrbitPrimaryButtonStyle()).disabled(code.count != 6 || loading)
                }.orbitCard()
                Text("位置共有を開始すると、iOSの位置情報インジケータも表示されます。隠れた追跡は行いません。")
                    .font(.caption).foregroundStyle(OrbitTheme.muted).multilineTextAlignment(.center).padding(.horizontal, 12)
            }.padding(22)
        }.background(OrbitTheme.navy.ignoresSafeArea())
    }
}

private struct TrackingStatusView: View {
    @StateObject private var tracker: LocationTracker
    @State private var online = true
    @State private var serverReachable: Bool?
    @State private var messages: [OrbitMessage] = []
    @State private var messageError: String?
    @State private var pendingMessageID = UserDefaults.standard.string(forKey: "pending_link_message_id")
    @State private var openedMessage: OrbitMessage?
    @State private var unpairing = false
    private let monitor = NWPathMonitor()
    let onRemoved: () -> Void
    let onUnpair: () -> Void

    init(token: String, onRemoved: @escaping () -> Void, onUnpair: @escaping () -> Void) {
        _tracker = StateObject(wrappedValue: LocationTracker(token: token))
        self.onRemoved = onRemoved
        self.onUnpair = onUnpair
    }

    private var hasPermission: Bool { tracker.authorization == .authorizedAlways || tracker.authorization == .authorizedWhenInUse }
    private var status: String { !hasPermission ? "位置情報がオフ" : tracker.tracking ? "位置を共有中" : "共有を一時停止中" }
    private var accent: Color { !hasPermission ? OrbitTheme.danger : tracker.tracking ? OrbitTheme.lime : .orange }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                HStack { Image(systemName: "circle.circle.fill").foregroundStyle(OrbitTheme.lime); Text("Family Orbit Link").font(.headline); Spacer() }.padding(.top, 16)
                ZStack {
                    Circle().fill(accent.opacity(0.10)).frame(width: 220, height: 220)
                    Circle().fill(accent.opacity(0.15)).frame(width: 162, height: 162)
                    VStack(spacing: 8) { Image(systemName: tracker.tracking && hasPermission ? "location.fill" : "pause.fill").font(.system(size: 32, weight: .bold)); Text(status).font(.headline.bold()) }.foregroundStyle(accent)
                }
                Text(tracker.tracking ? "家族の保護者画面に現在地を送信しています" : "現在地は送信されていません")
                    .foregroundStyle(OrbitTheme.muted).multilineTextAlignment(.center)
                VStack(spacing: 15) {
                    LinkStatusRow(label: "位置情報", value: permissionLabel, good: hasPermission)
                    LinkStatusRow(label: "バックグラウンド", value: tracker.authorization == .authorizedAlways ? "常に許可" : "アプリ使用中のみ", good: tracker.authorization == .authorizedAlways)
                    LinkStatusRow(
                        label: "通信",
                        value: !online ? "オフライン・端末に保存中" : serverReachable == false ? "サーバーに接続できません" : serverReachable == true ? "オンライン" : "サーバーを確認中",
                        good: online && serverReachable == true
                    )
                    LinkStatusRow(label: "最終送信", value: tracker.lastSentAt?.formatted(date: .omitted, time: .shortened) ?? "まだありません", good: tracker.lastSentAt != nil)
                    if let accuracy = tracker.lastAccuracy { LinkStatusRow(label: "位置精度", value: "±\(Int(accuracy))m", good: accuracy <= 100) }
                }.orbitCard()
                if !hasPermission {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("位置情報の許可が必要です").font(.headline).foregroundStyle(OrbitTheme.danger)
                        Text("現在地の共有は停止しています。許可するまで家族へ位置情報は送信されません。")
                            .font(.footnote).foregroundStyle(OrbitTheme.muted)
                        Button("位置情報を許可") { requestLocationPermission() }.buttonStyle(OrbitPrimaryButtonStyle())
                        Button("端末設定を開く") { openSettings() }.frame(maxWidth: .infinity, minHeight: 44)
                    }.orbitCard()
                } else if tracker.authorization != .authorizedAlways {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("バックグラウンド共有が未設定です").font(.headline).foregroundStyle(.orange)
                        Text("画面を閉じた後も共有するには、位置情報を「常に許可」に変更してください。")
                            .font(.footnote).foregroundStyle(OrbitTheme.muted)
                        Button("バックグラウンド共有を設定") { tracker.requestAlways() }.buttonStyle(.bordered)
                    }.orbitCard()
                }
                VStack(alignment: .leading, spacing: 12) {
                    Text("保護者からのメッセージ").font(.headline)
                    if messages.isEmpty { Text("新しいメッセージはありません").foregroundStyle(OrbitTheme.muted) }
                    ForEach(messages) { message in
                        Button {
                            openedMessage = message
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(message.body).fontWeight(.semibold).foregroundStyle(.primary)
                                HStack {
                                    Text(relativeLinkTime(message.createdAt)).font(.caption).foregroundStyle(OrbitTheme.muted)
                                    Spacer()
                                    if message.readAt == nil { Text("未読").font(.caption.bold()).foregroundStyle(OrbitTheme.lime) }
                                }
                            }.padding(13).frame(maxWidth: .infinity, alignment: .leading).background(OrbitTheme.navy, in: RoundedRectangle(cornerRadius: 14))
                        }.buttonStyle(.plain)
                    }
                    if let messageError { Text(messageError).font(.footnote).foregroundStyle(OrbitTheme.danger) }
                    Text("受信履歴は30日後に自動削除されます。").font(.caption).foregroundStyle(OrbitTheme.muted)
                }.orbitCard()
                if let error = tracker.lastError { Text(error).font(.footnote).foregroundStyle(.orange) }
                if tracker.pauseRestricted {
                    VStack(alignment: .leading, spacing: 7) {
                        Label("共有停止は保護者設定で制限中", systemImage: "lock.fill")
                            .font(.headline).foregroundStyle(OrbitTheme.lime)
                        Text("このLinkアプリから位置共有の一時停止や家族との接続解除はできません。OSの位置情報設定は端末の利用者が確認できます。")
                            .font(.footnote).foregroundStyle(OrbitTheme.muted)
                    }.orbitCard()
                }
                Button("現在地を今すぐ送信") { tracker.refreshNow() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .disabled(!tracker.tracking || !hasPermission)
                if tracker.tracking {
                    if !tracker.pauseRestricted {
                        Button("位置共有を一時停止", role: .destructive) { tracker.pause() }
                            .buttonStyle(.bordered).frame(maxWidth: .infinity, minHeight: 52)
                    }
                } else {
                    Button("位置共有を開始") { tracker.start() }
                        .buttonStyle(OrbitPrimaryButtonStyle()).disabled(!hasPermission)
                }
                Text(tracker.pauseRestricted ? "共有中はOSの位置情報インジケータに表示されます。OS権限の変更や通信停止は保護者へ通知されます。アプリを強制終了した場合、iOSは共有の継続を保証しません。" : "共有中はOSの位置情報インジケータに表示されます。停止や権限変更は保護者へ通知されます。アプリを強制終了した場合、iOSは共有の継続を保証しません。")
                    .font(.caption).foregroundStyle(OrbitTheme.muted).multilineTextAlignment(.center)
                if !tracker.pauseRestricted {
                    Button(unpairing ? "解除中…" : "この端末の接続を解除", role: .destructive) {
                        guard !unpairing else { return }
                        tracker.pause()
                        unpairing = true
                        Task {
                            do {
                                try await OrbitAPI.shared.unpairDevice(token: tracker.deviceToken)
                                onUnpair()
                            } catch {
                                messageError = error.localizedDescription
                                unpairing = false
                            }
                        }
                    }
                    .disabled(unpairing)
                    .padding(.top, 8)
                }
            }.padding(.horizontal, 22).padding(.bottom, 30)
        }
        .background(OrbitTheme.navy.ignoresSafeArea())
        .onAppear {
            monitor.pathUpdateHandler = { path in DispatchQueue.main.async { online = path.status == .satisfied } }
            monitor.start(queue: DispatchQueue(label: "family-orbit-network"))
        }
        .onDisappear { monitor.cancel() }
        .onReceive(NotificationCenter.default.publisher(for: .orbitLinkMessageOpened)) { notification in
            pendingMessageID = notification.object as? String
            openPendingMessageIfAvailable()
        }
        .onChange(of: tracker.removedFromFamily) { _, removed in if removed { onRemoved() } }
        .sheet(item: $openedMessage) { message in
            LinkMessageDetail(message: message, deviceToken: tracker.deviceToken) { read in
                messages = messages.map { $0.id == read.id ? read : $0 }
            }
        }
        .task {
            while !Task.isCancelled {
                do {
                    let config = try await OrbitAPI.shared.deviceConfig(token: tracker.deviceToken)
                    messages = try await OrbitAPI.shared.deviceMessages(token: tracker.deviceToken)
                    tracker.updatePauseRestriction(config.pauseRestricted)
                    messageError = nil
                    serverReachable = true
                    openPendingMessageIfAvailable()
                } catch {
                    if let apiError = error as? OrbitAPIError, case .unauthorized = apiError {
                        LinkFamilyRemoval.mark()
                        onRemoved()
                        return
                    }
                    serverReachable = false
                    messageError = error.localizedDescription
                }
                try? await Task.sleep(for: .seconds(15))
            }
        }
    }

    private var permissionLabel: String {
        switch tracker.authorization { case .authorizedAlways: "許可済み"; case .authorizedWhenInUse: "使用中のみ"; case .denied: "許可が必要"; case .restricted: "制限中"; default: "未設定" }
    }

    private func openPendingMessageIfAvailable() {
        guard let pendingMessageID, let message = messages.first(where: { $0.id == pendingMessageID }) else { return }
        openedMessage = message
        self.pendingMessageID = nil
        UserDefaults.standard.removeObject(forKey: "pending_link_message_id")
    }

    private func requestLocationPermission() {
        if tracker.authorization == .denied || tracker.authorization == .restricted { openSettings() }
        else { tracker.requestWhenInUse() }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

private struct LinkMessageDetail: View {
    @Environment(\.dismiss) private var dismiss
    @State private var message: OrbitMessage
    let deviceToken: String
    let onRead: (OrbitMessage) -> Void

    init(message: OrbitMessage, deviceToken: String, onRead: @escaping (OrbitMessage) -> Void) {
        _message = State(initialValue: message)
        self.deviceToken = deviceToken
        self.onRead = onRead
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 18) {
                Text(message.body).font(.title3.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading)
                Text(relativeLinkTime(message.createdAt)).font(.footnote).foregroundStyle(OrbitTheme.muted)
                Spacer()
            }
            .padding(22)
            .background(OrbitTheme.navy.ignoresSafeArea())
            .navigationTitle("保護者からのメッセージ")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("閉じる") { dismiss() } } }
        }
        .task {
            guard message.readAt == nil, let read = try? await OrbitAPI.shared.markMessageRead(id: message.id, token: deviceToken) else { return }
            message = read
            onRead(read)
        }
    }
}

private func relativeLinkTime(_ value: String) -> String {
    guard let date = ISO8601DateFormatter().date(from: value) else { return value }
    return date.formatted(date: .abbreviated, time: .shortened)
}

private struct LinkStatusRow: View {
    let label: String; let value: String; let good: Bool
    var body: some View { HStack { Circle().fill(good ? OrbitTheme.mint : Color.orange).frame(width: 9, height: 9); Text(label).foregroundStyle(OrbitTheme.muted); Spacer(); Text(value).fontWeight(.semibold).multilineTextAlignment(.trailing) } }
}
