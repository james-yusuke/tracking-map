import MapKit
import SwiftUI
import UIKit

struct ContentView: View {
    @State private var accessToken: String?

    init() {
        if ProcessInfo.processInfo.arguments.contains("UITEST_RESET") {
            KeychainStore().remove("guardian_access_token")
            KeychainStore().remove("guardian_refresh_token")
        }
        _accessToken = State(initialValue: KeychainStore().string(for: "guardian_access_token"))
    }

    var body: some View {
        Group {
            if accessToken == nil {
                ParentAuthView { token in
                    try? KeychainStore().set(Data(token.utf8), for: "guardian_access_token")
                    accessToken = token
                }
            } else if let currentToken = accessToken {
                ParentDashboardView(
                    accessToken: currentToken,
                    onAccessTokenRefresh: { token in
                        try? KeychainStore().set(Data(token.utf8), for: "guardian_access_token")
                        accessToken = token
                    }
                ) {
                    KeychainStore().remove("guardian_access_token")
                    KeychainStore().remove("guardian_refresh_token")
                    self.accessToken = nil
                }
            }
        }
        .tint(OrbitTheme.lime)
    }
}

private enum ParentAuthMode {
    case login
    case register
}

private struct ParentAuthView: View {
    @State private var mode = ParentAuthMode.login
    @State private var displayName = ""
    @State private var familyName = ""
    @State private var email = ""
    @State private var password = ""
    @State private var passwordConfirmation = ""
    @State private var loading = false
    @State private var error: String?
    let onLogin: (String) -> Void

    private var canSubmit: Bool {
        guard !loading, !email.isEmpty, password.count >= 12 else { return false }
        if mode == .login { return true }
        return !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !familyName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && password == passwordConfirmation
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Spacer(minLength: 52)
                ZStack {
                    RoundedRectangle(cornerRadius: 18).fill(OrbitTheme.lime).frame(width: 58, height: 58)
                    Image(systemName: "circle.circle.fill").font(.system(size: 30, weight: .black)).foregroundStyle(OrbitTheme.navy)
                }
                Text("Family Orbit").font(.largeTitle.bold())
                Text(mode == .login ? "大切な家族と、安心でつながる。" : "家族との位置共有を始めましょう。")
                    .foregroundStyle(OrbitTheme.muted)

                Picker("認証方法", selection: $mode) {
                    Text("ログイン").tag(ParentAuthMode.login)
                    Text("新規登録").tag(ParentAuthMode.register)
                }
                .pickerStyle(.segmented)
                .onChange(of: mode) { _, _ in error = nil }

                if mode == .register {
                    TextField("保護者のお名前", text: $displayName)
                        .textContentType(.name)
                        .padding(16).background(OrbitTheme.surface, in: RoundedRectangle(cornerRadius: 15))
                    TextField("家族名", text: $familyName)
                        .padding(16).background(OrbitTheme.surface, in: RoundedRectangle(cornerRadius: 15))
                }
                TextField("メールアドレス", text: $email)
                    .textContentType(.emailAddress).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                    .padding(16).background(OrbitTheme.surface, in: RoundedRectangle(cornerRadius: 15))
                SecureField(mode == .login ? "パスワード" : "パスワード（12文字以上）", text: $password)
                    .textContentType(mode == .login ? .password : .newPassword)
                    .padding(16).background(OrbitTheme.surface, in: RoundedRectangle(cornerRadius: 15))
                if mode == .register {
                    SecureField("パスワードを再入力", text: $passwordConfirmation)
                        .textContentType(.newPassword)
                        .padding(16).background(OrbitTheme.surface, in: RoundedRectangle(cornerRadius: 15))
                    if !passwordConfirmation.isEmpty && password != passwordConfirmation {
                        Text("パスワードが一致しません").foregroundStyle(OrbitTheme.danger).font(.footnote)
                    }
                    Text("登録後、メールアドレス確認用のメールを送信します。")
                        .font(.footnote).foregroundStyle(OrbitTheme.muted)
                }
                if let error { Text(error).foregroundStyle(OrbitTheme.danger).font(.footnote) }
                Button {
                    loading = true
                    error = nil
                    Task {
                        do {
                            let token: String
                            if mode == .login {
                                token = try await OrbitAPI.shared.login(email: email, password: password)
                            } else {
                                token = try await OrbitAPI.shared.register(
                                    email: email,
                                    password: password,
                                    displayName: displayName,
                                    familyName: familyName
                                )
                            }
                            await MainActor.run { onLogin(token); loading = false }
                        } catch {
                            await MainActor.run { self.error = error.localizedDescription; loading = false }
                        }
                    }
                } label: {
                    Text(loading ? "確認中…" : mode == .login ? "ログイン" : "家族アカウントを作成")
                        .fontWeight(.bold).frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(OrbitPrimaryButtonStyle())
                .disabled(!canSubmit)
                Text("本サービスは緊急通報用ではありません。位置の精度や到達時間を保証するものではありません。")
                    .font(.caption).foregroundStyle(OrbitTheme.muted).padding(.top, 12)
            }
            .padding(26)
        }
        .background(OrbitTheme.navy.ignoresSafeArea())
    }
}

private struct ParentDashboardView: View {
    @Environment(\.scenePhase) private var scenePhase
    let accessToken: String
    let onAccessTokenRefresh: (String) -> Void
    let onExit: () -> Void
    @State private var refreshedAccessToken: String?
    @State private var dashboard: OrbitDashboard?
    @State private var selectedChildID: String?
    @State private var selectedTab = 0
    @State private var message: String?
    @State private var showFamilySheet = false

    private var currentAccessToken: String { refreshedAccessToken ?? accessToken }
    var selectedChild: OrbitChild? { dashboard?.children.first { $0.id == selectedChildID } ?? dashboard?.children.first }

    var body: some View {
        Group {
            if let dashboard {
                TabView(selection: $selectedTab) {
                    NavigationStack { ParentMapView(dashboard: dashboard, selectedChildID: $selectedChildID, accessToken: currentAccessToken).toolbar { toolbar } }
                        .tabItem { Label("現在地", systemImage: "location.fill") }.tag(0)
                    NavigationStack { HistoryView(child: selectedChild, accessToken: currentAccessToken).toolbar { toolbar } }
                        .tabItem { Label("履歴", systemImage: "point.bottomleft.forward.to.point.topright.scurvepath") }.tag(1)
                    NavigationStack { ZonesView(dashboard: dashboard, accessToken: currentAccessToken, onChanged: { await refreshDashboard(accessToken: currentAccessToken) }).toolbar { toolbar } }
                        .tabItem { Label("安全エリア", systemImage: "shield.checkered") }.tag(2)
                    NavigationStack { AlertsView(alerts: dashboard.alerts).toolbar { toolbar } }
                        .tabItem { Label("通知", systemImage: "bell.fill") }.tag(3)
                }
            } else {
                VStack(spacing: 16) {
                    if message == nil { ProgressView().tint(OrbitTheme.lime); Text("家族データを読み込んでいます").foregroundStyle(OrbitTheme.muted) }
                    else { ContentUnavailableView("家族データを取得できません", systemImage: "wifi.exclamationmark", description: Text(message ?? "")); Button("再試行") { Task { await refreshDashboard(accessToken: currentAccessToken) } }.buttonStyle(.borderedProminent) }
                }.frame(maxWidth: .infinity, maxHeight: .infinity).background(OrbitTheme.navy)
            }
        }
        .tint(OrbitTheme.lime)
        .background(OrbitTheme.navy)
        .safeAreaInset(edge: .top, spacing: 0) {
            if let message {
                Text(message).font(.caption).foregroundStyle(Color.orange)
                    .frame(maxWidth: .infinity).padding(9).background(Color.orange.opacity(0.13))
            }
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            let initialAccessToken = currentAccessToken
            await ParentPushRegistration.request(accessToken: initialAccessToken)
            while !Task.isCancelled {
                await refreshDashboard(accessToken: currentAccessToken)
                try? await Task.sleep(for: .seconds(5))
            }
        }
        .sheet(isPresented: $showFamilySheet) { if let dashboard {
            FamilyManagementView(
                dashboard: dashboard,
                accessToken: currentAccessToken,
                onRefresh: {
                    let updated = try await OrbitAPI.shared.dashboard(accessToken: currentAccessToken)
                    self.dashboard = updated
                    if !updated.children.contains(where: { $0.id == selectedChildID }) {
                        selectedChildID = updated.children.first?.id
                    }
                },
                onExit: onExit
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        }
    }

    @MainActor
    private func refreshDashboard(accessToken: String) async {
        do {
            let updated = try await OrbitAPI.shared.dashboard(accessToken: accessToken)
            applyDashboard(updated)
            message = nil
        } catch OrbitAPIError.unauthorized(_) {
            do {
                let renewedToken = try await OrbitAPI.shared.refreshAccessToken()
                refreshedAccessToken = renewedToken
                onAccessTokenRefresh(renewedToken)
                let updated = try await OrbitAPI.shared.dashboard(accessToken: renewedToken)
                applyDashboard(updated)
                message = nil
            } catch {
                message = error.localizedDescription
            }
        } catch is CancellationError {
            return
        } catch {
            message = error.localizedDescription
        }
    }

    @MainActor
    private func applyDashboard(_ updated: OrbitDashboard) {
        dashboard = updated
        if !updated.children.contains(where: { $0.id == selectedChildID }) {
            selectedChildID = updated.children.first?.id
        }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            VStack(spacing: 1) {
                Text("Family Orbit").font(.subheadline.bold())
                Text(dashboard?.family.name ?? "読み込み中")
                    .font(.caption2)
                    .foregroundStyle(OrbitTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: 180)
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button { showFamilySheet = true } label: {
                Image(systemName: "person.2.fill")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(OrbitTheme.navy)
                    .frame(width: 38, height: 38)
                    .background(OrbitTheme.lime, in: Circle())
            }
            .accessibilityLabel("家族と端末を管理")
        }
    }
}

private enum FamilyDeletionTarget: Identifiable {
    case child(OrbitChild)
    case account

    var id: String {
        switch self {
        case let .child(child): "child:\(child.id)"
        case .account: "account"
        }
    }
}

private struct FamilyManagementView: View {
    let dashboard: OrbitDashboard
    let accessToken: String?
    let onRefresh: () async throws -> Void
    let onExit: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var newChildName = ""
    @State private var pairingCode: OrbitPairingCode?
    @State private var pairingChildName = ""
    @State private var pauseRestricted = false
    @State private var loadingID: String?
    @State private var error: String?
    @State private var deletionTarget: FamilyDeletionTarget?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack(spacing: 14) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(OrbitTheme.lime.opacity(0.14))
                                .frame(width: 58, height: 58)
                            Image(systemName: "person.3.fill").foregroundStyle(OrbitTheme.lime).font(.title3.bold())
                        }
                        VStack(alignment: .leading, spacing: 4) {
                            Text(dashboard.family.name).font(.title3.bold())
                            Text("保護者 · \(dashboard.children.count)人のプロフィール")
                                .font(.subheadline).foregroundStyle(OrbitTheme.muted)
                        }
                    }
                    .orbitCard()

                    if let pairingCode {
                        PairingCodeCard(code: pairingCode, childName: pairingChildName)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }

                    VStack(alignment: .leading, spacing: 11) {
                        Text("子どもの端末を接続").font(.headline)
                        Text("Family Orbit Linkへ入力する、10分有効・1回限りのコードを発行します。")
                            .font(.subheadline).foregroundStyle(OrbitTheme.muted)

                        Toggle(isOn: $pauseRestricted) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("子どもによる共有停止を制限").fontWeight(.bold)
                                Text("Linkアプリ内の一時停止・接続解除を無効にします。OSの権限変更やアプリ削除は防げません。")
                                    .font(.caption).foregroundStyle(OrbitTheme.muted)
                            }
                        }
                        .tint(OrbitTheme.lime)
                        .padding(12)
                        .background(OrbitTheme.raised, in: RoundedRectangle(cornerRadius: 15, style: .continuous))

                        if dashboard.children.isEmpty {
                            Label("まず子どものプロフィールを追加してください", systemImage: "person.crop.circle.badge.plus")
                                .font(.subheadline.weight(.semibold)).foregroundStyle(OrbitTheme.mint)
                                .padding(.vertical, 6)
                        } else {
                            ForEach(dashboard.children) { child in
                                HStack(spacing: 12) {
                                    Text(String(child.name.prefix(1)))
                                        .font(.headline).foregroundStyle(OrbitTheme.navy)
                                        .frame(width: 42, height: 42).background(OrbitTheme.lime, in: Circle())
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(child.name).fontWeight(.bold)
                                        Text(child.connectivity == "online" ? "接続済み · 再発行できます" : "未接続またはオフライン")
                                            .font(.caption).foregroundStyle(OrbitTheme.muted)
                                    }
                                    Spacer()
                                    Button(loadingID == child.id ? "発行中…" : "コード発行") {
                                        issueCode(childID: child.id, childName: child.name)
                                    }
                                    .font(.caption.weight(.bold))
                                    .buttonStyle(.bordered)
                                    .tint(OrbitTheme.lime)
                                    .disabled(loadingID != nil || accessToken == nil)
                                    Button {
                                        deletionTarget = .child(child)
                                    } label: {
                                        Image(systemName: "trash")
                                            .frame(width: 30, height: 30)
                                    }
                                    .buttonStyle(.bordered)
                                    .tint(OrbitTheme.danger)
                                    .disabled(loadingID != nil || accessToken == nil)
                                    .accessibilityLabel("\(child.name)を削除")
                                }
                                .padding(14)
                                .background(OrbitTheme.raised, in: RoundedRectangle(cornerRadius: 17, style: .continuous))
                            }
                        }
                    }
                    .orbitCard()

                    VStack(alignment: .leading, spacing: 12) {
                        Label("子どもを追加", systemImage: "plus.circle.fill").font(.headline)
                        TextField("表示名（例：あおい）", text: $newChildName)
                            .textContentType(.name)
                            .padding(15)
                            .background(OrbitTheme.raised, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
                        Button {
                            addChildAndIssueCode()
                        } label: {
                            Label(loadingID == "new" ? "追加しています…" : "追加してコードを発行", systemImage: "link.badge.plus")
                        }
                        .buttonStyle(OrbitPrimaryButtonStyle())
                        .disabled(newChildName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || loadingID != nil || accessToken == nil)
                    }
                    .orbitCard()

                    if let error {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote).foregroundStyle(OrbitTheme.danger)
                            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                            .background(OrbitTheme.danger.opacity(0.10), in: RoundedRectangle(cornerRadius: 15))
                    }

                    VStack(alignment: .leading, spacing: 9) {
                        Text("家族アカウントの削除").font(.headline)
                        Text("誤って登録した場合は、位置履歴・端末・通知を含む家族データを完全に削除できます。")
                            .font(.footnote).foregroundStyle(OrbitTheme.muted)
                        Button("家族アカウントを削除", role: .destructive) {
                            deletionTarget = .account
                        }
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .buttonStyle(.bordered)
                        .disabled(loadingID != nil || accessToken == nil)
                    }
                    .orbitCard()

                    Button("ログアウト", role: .destructive) {
                        dismiss(); onExit()
                    }
                    .frame(maxWidth: .infinity, minHeight: 48)
                }
                .padding(18)
            }
            .background(OrbitTheme.navy)
            .navigationTitle("家族・端末管理")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }.foregroundStyle(OrbitTheme.lime)
                }
            }
        }
        .preferredColorScheme(.dark)
        .animation(.snappy, value: pairingCode?.code)
        .alert(item: $deletionTarget) { target in
            switch target {
            case let .child(child):
                Alert(
                    title: Text("\(child.name)を削除しますか？"),
                    message: Text("ペアリング済み端末を無効にし、位置履歴・通知・安全エリア状態も削除します。この操作は取り消せません。"),
                    primaryButton: .destructive(Text("完全に削除")) { deleteChild(child) },
                    secondaryButton: .cancel(Text("キャンセル"))
                )
            case .account:
                Alert(
                    title: Text("家族アカウントを削除しますか？"),
                    message: Text("すべての子ども、位置履歴、端末、安全エリア、通知が完全に削除されます。復元はできません。"),
                    primaryButton: .destructive(Text("家族データを削除")) { deleteAccount() },
                    secondaryButton: .cancel(Text("キャンセル"))
                )
            }
        }
    }

    private func issueCode(childID: String, childName: String) {
        guard let accessToken else { return }
        loadingID = childID
        error = nil
        Task {
            defer { loadingID = nil }
            do {
                pairingCode = try await OrbitAPI.shared.createPairingCode(childID: childID, pauseRestricted: pauseRestricted, accessToken: accessToken)
                pairingChildName = childName
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func addChildAndIssueCode() {
        guard let accessToken else { return }
        let name = newChildName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        loadingID = "new"
        error = nil
        Task {
            defer { loadingID = nil }
            do {
                let child = try await OrbitAPI.shared.createChild(name: name, accessToken: accessToken)
                pairingCode = try await OrbitAPI.shared.createPairingCode(childID: child.id, pauseRestricted: pauseRestricted, accessToken: accessToken)
                pairingChildName = child.name
                newChildName = ""
                try await onRefresh()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func deleteChild(_ child: OrbitChild) {
        guard let accessToken else { return }
        loadingID = "delete:\(child.id)"
        error = nil
        Task {
            defer { loadingID = nil }
            do {
                try await OrbitAPI.shared.deleteChild(childID: child.id, accessToken: accessToken)
                if pairingChildName == child.name {
                    pairingCode = nil
                    pairingChildName = ""
                }
                try await onRefresh()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func deleteAccount() {
        guard let accessToken else { return }
        loadingID = "delete-account"
        error = nil
        Task {
            do {
                try await OrbitAPI.shared.deleteAccount(accessToken: accessToken)
                dismiss()
                onExit()
            } catch {
                self.error = error.localizedDescription
                loadingID = nil
            }
        }
    }
}

private struct PairingCodeCard: View {
    let code: OrbitPairingCode
    let childName: String

    private var formattedCode: String {
        guard code.code.count == 6 else { return code.code }
        return "\(code.code.prefix(3))  \(code.code.suffix(3))"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("リンクコード", systemImage: "link.circle.fill").font(.headline)
                Spacer()
                Text("10分有効").font(.caption.bold()).foregroundStyle(OrbitTheme.navy)
                    .padding(.horizontal, 10).padding(.vertical, 6).background(OrbitTheme.lime, in: Capsule())
            }
            Text(childName).font(.subheadline).foregroundStyle(OrbitTheme.muted)
            Text(formattedCode)
                .font(.system(size: 38, weight: .black, design: .monospaced))
                .tracking(3).frame(maxWidth: .infinity).minimumScaleFactor(0.7)
                .accessibilityLabel("リンクコード \(code.code.map(String.init).joined(separator: " "))")
            HStack {
                Label("Family Orbit Linkに入力", systemImage: "iphone")
                    .font(.footnote).foregroundStyle(OrbitTheme.muted)
                Spacer()
                Button("コピー") { UIPasteboard.general.string = code.code }
                    .font(.footnote.bold()).foregroundStyle(OrbitTheme.lime)
            }
            if code.pauseRestricted {
                Label("このコードはLinkアプリ内の共有停止を制限します", systemImage: "lock.fill")
                    .font(.footnote.bold()).foregroundStyle(OrbitTheme.lime)
            }
        }
        .padding(20)
        .background(
            LinearGradient(colors: [OrbitTheme.raised, OrbitTheme.lime.opacity(0.12)], startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .overlay { RoundedRectangle(cornerRadius: 24).stroke(OrbitTheme.lime.opacity(0.35)) }
    }
}

private struct ParentMapView: View {
    let dashboard: OrbitDashboard
    @Binding var selectedChildID: String?
    let accessToken: String
    @State private var position: MapCameraPosition = .automatic
    @State private var showMessage = false
    private var selected: OrbitChild? { dashboard.children.first { $0.id == selectedChildID } ?? dashboard.children.first }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(dashboard.children) { child in
                            Button {
                                selectedChildID = child.id
                                if let location = child.latestLocation {
                                    position = .region(MKCoordinateRegion(center: .init(latitude: location.latitude, longitude: location.longitude), latitudinalMeters: 4_000, longitudinalMeters: 4_000))
                                }
                            } label: {
                                HStack(spacing: 8) {
                                    Circle().fill(child.connectivity == "online" ? OrbitTheme.mint : OrbitTheme.muted).frame(width: 8, height: 8)
                                    Text(child.name).fontWeight(.semibold)
                                }
                                .padding(.horizontal, 16).frame(minHeight: 48)
                                .background(selectedChildID == child.id ? OrbitTheme.lime : OrbitTheme.surface, in: Capsule())
                                .foregroundStyle(selectedChildID == child.id ? OrbitTheme.navy : Color.white)
                            }
                        }
                    }.padding(.horizontal, 18)
                }
                if let child = selected, let location = child.latestLocation {
                    Map(position: $position) {
                        ForEach(dashboard.zones) { zone in
                            MapCircle(center: CLLocationCoordinate2D(latitude: zone.latitude, longitude: zone.longitude), radius: zone.radiusMeters)
                                .foregroundStyle(OrbitTheme.mint.opacity(0.13)).stroke(OrbitTheme.mint, lineWidth: 2)
                        }
                        MapCircle(center: CLLocationCoordinate2D(latitude: location.latitude, longitude: location.longitude), radius: max(location.accuracy, 8))
                            .foregroundStyle(OrbitTheme.lime.opacity(0.18)).stroke(OrbitTheme.lime, lineWidth: 2)
                        Annotation(child.name, coordinate: CLLocationCoordinate2D(latitude: location.latitude, longitude: location.longitude)) {
                            ZStack {
                                Circle().fill(OrbitTheme.navy).frame(width: 54, height: 54).shadow(radius: 8)
                                Circle().fill(OrbitTheme.lime).frame(width: 42, height: 42)
                                Text(String(child.name.prefix(1))).font(.headline).foregroundStyle(OrbitTheme.navy)
                            }
                        }
                    }
                    .mapStyle(.standard(elevation: .realistic, pointsOfInterest: .excludingAll))
                    .frame(height: 420).clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous)).padding(.horizontal, 18)
                    ChildSummary(child: child, location: location) { showMessage = true }.padding(.horizontal, 18)
                } else {
                    VStack(spacing: 16) {
                        ContentUnavailableView("位置情報がまだありません", systemImage: "location.slash", description: Text("子ども用アプリの接続と権限を確認してください。"))
                            .frame(height: 360)
                        if selected != nil { Button("メッセージを送る") { showMessage = true }.buttonStyle(OrbitPrimaryButtonStyle()).padding(.horizontal, 18) }
                    }
                }
            }.padding(.vertical, 12)
        }
        .background(OrbitTheme.navy)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if let location = selected?.latestLocation {
                position = .region(MKCoordinateRegion(center: .init(latitude: location.latitude, longitude: location.longitude), latitudinalMeters: 4_000, longitudinalMeters: 4_000))
            }
        }
        .onChange(of: selected?.latestLocation?.recordedAt) { _, _ in
            if let location = selected?.latestLocation {
                position = .region(MKCoordinateRegion(center: .init(latitude: location.latitude, longitude: location.longitude), latitudinalMeters: 4_000, longitudinalMeters: 4_000))
            }
        }
        .sheet(isPresented: $showMessage) {
            if let selected { GuardianMessageView(child: selected, accessToken: accessToken).presentationDetents([.large]) }
        }
    }
}

private struct ChildSummary: View {
    let child: OrbitChild
    let location: OrbitLocation
    let onMessage: () -> Void
    var body: some View {
        VStack(spacing: 16) {
            HStack {
                ZStack { Circle().fill(OrbitTheme.lime).frame(width: 48, height: 48); Text(String(child.name.prefix(1))).foregroundStyle(OrbitTheme.navy).font(.headline) }
                VStack(alignment: .leading) { Text(child.name).font(.title3.bold()); Text("最終更新 \(relativeTime(location.recordedAt))").font(.subheadline).foregroundStyle(OrbitTheme.muted) }
                Spacer()
                Text("\(Int(location.batteryLevel * 100))%").fontWeight(.bold).foregroundStyle(location.batteryLevel < 0.2 ? OrbitTheme.danger : OrbitTheme.mint)
            }
            Divider().overlay(Color.white.opacity(0.08))
            HStack {
                Metric(title: "位置精度", value: "±\(Int(location.accuracy))m")
                Spacer(); Metric(title: "通信", value: child.connectivity == "online" ? "オンライン" : "15分以上なし")
                Spacer(); Metric(title: "充電", value: location.isCharging ? "充電中" : "未接続")
            }
            Button("メッセージを送る", action: onMessage).buttonStyle(OrbitPrimaryButtonStyle())
        }.orbitCard()
    }
}

private struct GuardianMessageView: View {
    let child: OrbitChild
    let accessToken: String
    @Environment(\.dismiss) private var dismiss
    @State private var messageText = ""
    @State private var messages: [OrbitMessage] = []
    @State private var loading = false
    @State private var error: String?
    @State private var clientMessageID: UUID?

    var bodyView: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("本文は子どものロック画面にも表示されます。個人情報の入力にご注意ください。")
                        .font(.footnote).foregroundStyle(OrbitTheme.muted)
                    TextField("メッセージ（200文字まで）", text: $messageText, axis: .vertical)
                        .lineLimit(3...6).padding(15).background(OrbitTheme.raised, in: RoundedRectangle(cornerRadius: 15))
                        .onChange(of: messageText) { _, value in
                            if value.count > 200 { messageText = String(value.prefix(200)) }
                            clientMessageID = nil
                        }
                    HStack { Spacer(); Text("\(messageText.count)/200").font(.caption).foregroundStyle(OrbitTheme.muted) }
                    Button {
                        send()
                    } label: { Text(loading ? "送信中…" : "メッセージを送る").frame(maxWidth: .infinity, minHeight: 50) }
                    .buttonStyle(OrbitPrimaryButtonStyle()).disabled(messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || loading)
                    if let error { Text(error).font(.footnote).foregroundStyle(OrbitTheme.danger) }
                    Text("送信履歴").font(.headline).padding(.top, 8)
                    if messages.isEmpty { Text("まだメッセージはありません").foregroundStyle(OrbitTheme.muted).orbitCard() }
                    ForEach(messages) { message in
                        VStack(alignment: .leading, spacing: 7) {
                            Text(message.body)
                            Text(status(message)).font(.caption).foregroundStyle(message.readAt == nil ? OrbitTheme.muted : OrbitTheme.mint)
                        }.orbitCard()
                    }
                }.padding(18)
            }
            .background(OrbitTheme.navy)
            .navigationTitle("\(child.name)へメッセージ")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("閉じる") { dismiss() } } }
            .task {
                while !Task.isCancelled {
                    await load()
                    try? await Task.sleep(for: .seconds(5))
                }
            }
        }
    }

    var body: some View { bodyView }

    private func load() async {
        do { messages = try await OrbitAPI.shared.guardianMessages(childID: child.id, accessToken: accessToken); error = nil }
        catch { self.error = error.localizedDescription }
    }

    private func send() {
        let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !loading else { return }
        let requestID = clientMessageID ?? UUID()
        clientMessageID = requestID
        loading = true; error = nil
        Task {
            do {
                let sent = try await OrbitAPI.shared.sendMessage(childID: child.id, clientMessageID: requestID.uuidString, body: text, accessToken: accessToken)
                messages.removeAll { $0.id == sent.id }; messages.insert(sent, at: 0); messageText = ""; clientMessageID = nil; loading = false
            } catch { self.error = error.localizedDescription; loading = false }
        }
    }

    private func status(_ message: OrbitMessage) -> String {
        if let readAt = message.readAt { return "既読 \(relativeTime(readAt))" }
        if message.deliveryState == "pushed" { return "通知送信済み \(relativeTime(message.pushedAt ?? message.createdAt))" }
        if message.deliveryState == "failed" { return "送信失敗・再試行待ち \(relativeTime(message.createdAt))" }
        return "送信待ち \(relativeTime(message.createdAt))"
    }
}

private struct Metric: View {
    let title: String; let value: String
    var body: some View { VStack(alignment: .leading, spacing: 3) { Text(title).font(.caption).foregroundStyle(OrbitTheme.muted); Text(value).font(.subheadline.bold()) } }
}

private struct HistoryView: View {
    let child: OrbitChild?
    let accessToken: String
    @State private var days: [OrbitHistoryDay] = []
    @State private var points: [OrbitHistoryPoint] = []
    @State private var selectedDay: OrbitHistoryDay?
    @State private var loading = false
    @State private var error: String?
    @State private var position: MapCameraPosition = .automatic
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ScreenHeading(title: "30日間の履歴", subtitle: "\(child?.name ?? "家族")の実際の位置記録です")
                if loading { ProgressView().tint(OrbitTheme.lime).frame(maxWidth: .infinity).padding(28) }
                if let error { Text(error).font(.footnote).foregroundStyle(OrbitTheme.danger).orbitCard() }
                if !loading && error == nil && days.isEmpty {
                    ContentUnavailableView("履歴はまだありません", systemImage: "point.bottomleft.forward.to.point.topright.scurvepath", description: Text("Linkから位置が届くと日付ごとに表示されます。"))
                        .frame(maxWidth: .infinity, minHeight: 260)
                }
                if let selectedDay, !points.isEmpty {
                    let coordinates = points.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
                    VStack(alignment: .leading, spacing: 9) {
                        Text("\(selectedDay.date) · \(points.count)地点").font(.headline)
                        Map(position: $position) {
                            MapPolyline(coordinates: coordinates).stroke(OrbitTheme.lime, lineWidth: 5)
                            if let first = coordinates.first { Marker("開始", coordinate: first).tint(OrbitTheme.mint) }
                            if let last = coordinates.last, coordinates.count > 1 { Marker("終了", coordinate: last).tint(OrbitTheme.lime) }
                        }
                        .mapStyle(.standard(pointsOfInterest: .excludingAll)).frame(height: 320).clipShape(RoundedRectangle(cornerRadius: 22))
                    }.orbitCard()
                }
                ForEach(days) { day in
                    Button { Task { await select(day) } } label: {
                        HStack {
                            Image(systemName: "point.bottomleft.forward.to.point.topright.scurvepath").foregroundStyle(OrbitTheme.lime).frame(width: 44, height: 44).background(OrbitTheme.lime.opacity(0.12), in: Circle())
                            VStack(alignment: .leading, spacing: 4) { Text(day.date).fontWeight(.bold).foregroundStyle(.white); Text("\(day.pointCount)地点 · \(relativeTime(day.firstRecordedAt))〜\(relativeTime(day.lastRecordedAt))").font(.caption).foregroundStyle(OrbitTheme.muted).lineLimit(2) }
                            Spacer(); Text("表示").foregroundStyle(OrbitTheme.mint)
                        }.orbitCard()
                    }.buttonStyle(.plain)
                }
                Text("履歴は30日後にサーバーから自動削除されます。").font(.caption).foregroundStyle(OrbitTheme.muted)
            }.padding(18)
        }.background(OrbitTheme.navy).navigationBarTitleDisplayMode(.inline)
        .task(id: child?.id) { await loadDays() }
    }

    private func loadDays() async {
        guard let child else { days = []; return }
        loading = true; error = nil; selectedDay = nil; points = []
        do { days = try await OrbitAPI.shared.historyDays(childID: child.id, accessToken: accessToken) }
        catch { self.error = error.localizedDescription }
        loading = false
    }

    private func select(_ day: OrbitHistoryDay) async {
        guard let child, let bounds = historyBounds(day.date) else { return }
        selectedDay = day; points = []; error = nil
        do {
            points = try await OrbitAPI.shared.history(childID: child.id, from: bounds.0, to: bounds.1, accessToken: accessToken)
            if let first = points.first {
                position = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: first.latitude, longitude: first.longitude), latitudinalMeters: 5_000, longitudinalMeters: 5_000))
            }
        } catch { self.error = error.localizedDescription }
    }

    private func historyBounds(_ value: String) -> (String, String)? {
        let zone = TimeZone(identifier: "Asia/Tokyo")!
        let formatter = DateFormatter(); formatter.calendar = Calendar(identifier: .gregorian); formatter.timeZone = zone; formatter.dateFormat = "yyyy-MM-dd"
        guard let start = formatter.date(from: value) else { return nil }
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        guard let end = calendar.date(byAdding: .day, value: 1, to: start) else { return nil }
        let iso = ISO8601DateFormatter(); iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return (iso.string(from: start), iso.string(from: end))
    }
}

private struct ZonesView: View {
    let dashboard: OrbitDashboard
    let accessToken: String
    let onChanged: () async -> Void
    @State private var showEditor = false
    @State private var editingZone: OrbitZone?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ScreenHeading(title: "安全エリア", subtitle: "出入りを検知して保護者へ通知します")
                if dashboard.zones.isEmpty { Text("安全エリアはまだありません。地図から追加できます。").foregroundStyle(OrbitTheme.muted).orbitCard() }
                ForEach(dashboard.zones) { zone in
                    Button { editingZone = zone; showEditor = true } label: {
                        HStack { Image(systemName: "house.fill").foregroundStyle(OrbitTheme.mint).frame(width: 46, height: 46).background(OrbitTheme.mint.opacity(0.12), in: RoundedRectangle(cornerRadius: 14)); VStack(alignment: .leading) { Text(zone.name).fontWeight(.bold).foregroundStyle(.white); Text("半径 \(Int(zone.radiusMeters))m · 出入りを通知").foregroundStyle(OrbitTheme.muted) }; Spacer(); Text("編集").foregroundStyle(OrbitTheme.lime).fontWeight(.bold) }.orbitCard()
                    }.buttonStyle(.plain)
                }
                Button { editingZone = nil; showEditor = true } label: { Label("安全エリアを追加", systemImage: "plus").fontWeight(.bold).frame(maxWidth: .infinity, minHeight: 50) }.buttonStyle(OrbitPrimaryButtonStyle())
                Text("設定できる半径は100〜5,000mです。端末とサーバーの両方で判定します。").font(.caption).foregroundStyle(OrbitTheme.muted)
            }.padding(18)
        }.background(OrbitTheme.navy).navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showEditor) {
            ZoneEditorView(dashboard: dashboard, zone: editingZone, accessToken: accessToken, onSaved: { await onChanged(); showEditor = false })
                .presentationDetents([.large])
        }
    }
}

private struct ZoneEditorView: View {
    let dashboard: OrbitDashboard
    let zone: OrbitZone?
    let accessToken: String
    let onSaved: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var center: CLLocationCoordinate2D
    @State private var radius: Double
    @State private var childIDs: Set<String>
    @State private var position: MapCameraPosition
    @State private var loading = false
    @State private var error: String?

    init(dashboard: OrbitDashboard, zone: OrbitZone?, accessToken: String, onSaved: @escaping () async -> Void) {
        self.dashboard = dashboard; self.zone = zone; self.accessToken = accessToken; self.onSaved = onSaved
        let fallback = dashboard.children.compactMap(\.latestLocation).first.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) } ?? CLLocationCoordinate2D(latitude: 35.6812, longitude: 139.7671)
        let initial = zone.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) } ?? fallback
        _name = State(initialValue: zone?.name ?? "")
        _center = State(initialValue: initial)
        _radius = State(initialValue: zone?.radiusMeters ?? 300)
        _childIDs = State(initialValue: Set(zone?.childIds ?? dashboard.children.map(\.id)))
        _position = State(initialValue: .region(MKCoordinateRegion(center: initial, latitudinalMeters: 4_000, longitudinalMeters: 4_000)))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    TextField("安全エリア名", text: $name).padding(15).background(OrbitTheme.raised, in: RoundedRectangle(cornerRadius: 15))
                    Text("地図を動かして中心を指定").font(.footnote).foregroundStyle(OrbitTheme.muted)
                    ZStack {
                        Map(position: $position) {
                            MapCircle(center: center, radius: radius).foregroundStyle(OrbitTheme.mint.opacity(0.15)).stroke(OrbitTheme.mint, lineWidth: 2)
                        }
                        .onMapCameraChange(frequency: .onEnd) { context in center = context.region.center }
                        Image(systemName: "plus.circle.fill").font(.title).foregroundStyle(OrbitTheme.lime).allowsHitTesting(false)
                    }.frame(height: 300).clipShape(RoundedRectangle(cornerRadius: 22))
                    Text("半径 \(Int(radius))m").font(.headline)
                    Slider(value: $radius, in: 100...5_000, step: 50).tint(OrbitTheme.lime)
                    Text("対象の子ども").font(.headline)
                    ForEach(dashboard.children) { child in
                        Button {
                            if childIDs.contains(child.id) { childIDs.remove(child.id) } else { childIDs.insert(child.id) }
                        } label: {
                            HStack { Image(systemName: childIDs.contains(child.id) ? "checkmark.circle.fill" : "circle"); Text(child.name); Spacer() }
                                .frame(maxWidth: .infinity, minHeight: 46).padding(.horizontal, 12)
                                .background(OrbitTheme.raised, in: RoundedRectangle(cornerRadius: 14))
                        }.buttonStyle(.plain).foregroundStyle(childIDs.contains(child.id) ? OrbitTheme.lime : OrbitTheme.muted)
                    }
                    if let error { Text(error).font(.footnote).foregroundStyle(OrbitTheme.danger) }
                    if zone != nil { Button("この安全エリアを削除", role: .destructive) { delete() }.frame(maxWidth: .infinity, minHeight: 48).buttonStyle(.bordered) }
                }.padding(18)
            }.background(OrbitTheme.navy)
            .navigationTitle(zone == nil ? "安全エリアを追加" : "安全エリアを編集")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() }.disabled(loading) }
                ToolbarItem(placement: .confirmationAction) { Button(loading ? "保存中…" : "保存") { save() }.disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || childIDs.isEmpty || loading) }
            }
        }
    }

    private func save() {
        loading = true; error = nil
        Task {
            do {
                try await OrbitAPI.shared.saveZone(zone, name: name.trimmingCharacters(in: .whitespacesAndNewlines), latitude: center.latitude, longitude: center.longitude, radiusMeters: radius, childIDs: Array(childIDs), accessToken: accessToken)
                await onSaved(); loading = false
            } catch { self.error = error.localizedDescription; loading = false }
        }
    }

    private func delete() {
        guard let zone else { return }
        loading = true; error = nil
        Task {
            do { try await OrbitAPI.shared.deleteZone(id: zone.id, accessToken: accessToken); await onSaved(); loading = false }
            catch { self.error = error.localizedDescription; loading = false }
        }
    }
}

private struct AlertsView: View {
    let alerts: [OrbitAlert]
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .center, spacing: 14) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .fill(OrbitTheme.lime.opacity(0.14))
                            .frame(width: 58, height: 58)
                        Image(systemName: "bell.badge.fill").font(.title3.bold()).foregroundStyle(OrbitTheme.lime)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("通知").font(.title2.bold())
                        Text("家族の変化を、見逃さない。")
                            .font(.subheadline).foregroundStyle(OrbitTheme.muted)
                    }
                    Spacer()
                    Text("\(alerts.count)件")
                        .font(.caption.bold()).foregroundStyle(OrbitTheme.navy)
                        .padding(.horizontal, 11).padding(.vertical, 7)
                        .background(OrbitTheme.lime, in: Capsule())
                }

                if alerts.isEmpty {
                    VStack(spacing: 18) {
                        ZStack {
                            Circle().fill(OrbitTheme.mint.opacity(0.10)).frame(width: 92, height: 92)
                            Circle().stroke(OrbitTheme.mint.opacity(0.24), lineWidth: 1).frame(width: 72, height: 72)
                            Image(systemName: "checkmark.seal.fill").font(.system(size: 34)).foregroundStyle(OrbitTheme.mint)
                        }
                        VStack(spacing: 7) {
                            Text("新しい通知はありません").font(.title3.bold())
                            Text("位置共有の停止や安全エリアへの出入り、\n端末のオフラインをここでお知らせします。")
                                .font(.subheadline).foregroundStyle(OrbitTheme.muted)
                                .multilineTextAlignment(.center).lineSpacing(3)
                        }
                        HStack(spacing: 8) {
                            AlertCapability(label: "安全エリア", icon: "mappin.and.ellipse")
                            AlertCapability(label: "共有状態", icon: "location.fill")
                            AlertCapability(label: "端末状態", icon: "iphone")
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 34).padding(.horizontal, 18)
                    .background(
                        LinearGradient(colors: [OrbitTheme.surface, OrbitTheme.raised.opacity(0.75)], startPoint: .topLeading, endPoint: .bottomTrailing),
                        in: RoundedRectangle(cornerRadius: 26, style: .continuous)
                    )
                    .overlay { RoundedRectangle(cornerRadius: 26).stroke(Color.white.opacity(0.07)) }
                } else {
                    Text("最近の通知").font(.headline).padding(.top, 2)
                    ForEach(alerts) { alert in
                        HStack(alignment: .top, spacing: 14) {
                            ZStack {
                                RoundedRectangle(cornerRadius: 15, style: .continuous)
                                    .fill(alertColor(alert.type).opacity(0.13))
                                    .frame(width: 48, height: 48)
                                Image(systemName: alertIcon(alert.type))
                                    .font(.system(size: 17, weight: .bold)).foregroundStyle(alertColor(alert.type))
                            }
                            VStack(alignment: .leading, spacing: 7) {
                                HStack(alignment: .firstTextBaseline) {
                                    Text(alertCategory(alert.type).uppercased())
                                        .font(.caption2.bold()).tracking(0.7).foregroundStyle(alertColor(alert.type))
                                    Spacer()
                                    Text(relativeTime(alert.occurredAt)).font(.caption).foregroundStyle(OrbitTheme.muted)
                                }
                                Text(alert.title).font(.body.bold())
                                Text(alert.message).font(.subheadline).foregroundStyle(OrbitTheme.muted).lineSpacing(2)
                            }
                        }
                        .orbitCard()
                    }
                }
                Text("通知には氏名や座標を含めず、アプリを開いた後に安全に詳細を取得します。")
                    .font(.caption).foregroundStyle(OrbitTheme.muted).padding(.horizontal, 2)
            }
            .padding(18)
        }.background(OrbitTheme.navy).navigationBarTitleDisplayMode(.inline)
    }

    private func alertIcon(_ type: String) -> String {
        if type.contains("zone") { return type.contains("exited") ? "figure.walk.departure" : "mappin.and.ellipse" }
        if type.contains("battery") { return "battery.25percent" }
        if type.contains("tracking") { return "location.slash.fill" }
        if type.contains("offline") || type.contains("device") { return "wifi.slash" }
        return "bell.fill"
    }

    private func alertColor(_ type: String) -> Color {
        if type.contains("zone") { return OrbitTheme.mint }
        if type.contains("battery") || type.contains("offline") || type.contains("tracking") { return OrbitTheme.danger }
        return OrbitTheme.lime
    }

    private func alertCategory(_ type: String) -> String {
        if type.contains("zone") { return "安全エリア" }
        if type.contains("battery") { return "バッテリー" }
        if type.contains("tracking") { return "位置共有" }
        if type.contains("offline") || type.contains("device") { return "端末" }
        return "お知らせ"
    }
}

private struct AlertCapability: View {
    let label: String
    let icon: String
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: icon).foregroundStyle(OrbitTheme.mint)
            Text(label).font(.caption2).foregroundStyle(OrbitTheme.muted).lineLimit(1)
        }
        .frame(maxWidth: .infinity, minHeight: 62)
        .background(OrbitTheme.navy.opacity(0.52), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct ScreenHeading: View {
    let title: String; let subtitle: String
    var body: some View { VStack(alignment: .leading, spacing: 5) { Text(title).font(.title2.bold()); Text(subtitle).foregroundStyle(OrbitTheme.muted) }.padding(.bottom, 10) }
}

private func relativeTime(_ value: String) -> String {
    guard let date = ISO8601DateFormatter().date(from: value) else { return value }
    let formatter = RelativeDateTimeFormatter(); formatter.locale = Locale(identifier: "ja_JP"); formatter.unitsStyle = .full
    return formatter.localizedString(for: date, relativeTo: .now)
}
