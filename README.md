# Family Orbit

家族の合意に基づいて子どもの現在地を共有する、日本向け限定ベータのMVPです。追跡中であることは子ども用アプリに常時表示され、子ども本人がいつでも共有を一時停止できます。緊急通報サービスではなく、位置精度や通知の到達時間を保証するものではありません。

## 構成

| クライアント／サービス | 技術 | 役割 |
| --- | --- | --- |
| Android `app` | Kotlin、Jetpack Compose、Google Maps | 保護者による確認 |
| Android `link` | Kotlin、Compose、Fused Location、Foreground Service | 子どもの明示的な位置共有 |
| iOS `FamilyOrbit` | SwiftUI、MapKit | 保護者による確認 |
| iOS `FamilyOrbitLink` | SwiftUI、Core Location | 子どもの明示的な位置共有 |
| Web | Next.js、MapLibre、MapTiler | 認証後の閲覧専用ダッシュボード |
| API / worker | Go、MongoDB、WebSocket | 認証、位置、エリア判定、通知 |
| VPS | Caddy、Docker Compose | TLS終端と5サービスの運用 |

Webから更新系APIを呼ぶとサーバーが拒否します。位置履歴はMongoDB TTLインデックスで30日後に削除され、MongoDBのポートはホストへ公開しません。

## ローカル起動

必要なものはDocker Desktop、Go 1.26、Bun 1.3、Android Studio、Xcodeです。まず設定例をコピーし、JWT秘密鍵とバックアップ鍵を十分に長いランダム値へ変更します。

```sh
cp .env.example .env
docker compose --profile dev up --build
```

- Web: `http://localhost`
- APIヘルスチェック: `http://localhost/health`
- モバイルDebug用API: `http://localhost:4000/api/v1`（ホストのloopbackだけに公開）
- OpenAPI: `http://localhost/api/docs/openapi.yaml`
- Mailpit: `http://localhost:8025`

デモ表示だけを確認する場合は `.env` の `NEXT_PUBLIC_DEMO_MODE=true` を使えます。本番では必ず `false` にし、`APP_DOMAIN`、`WEB_ORIGIN`、`NEXT_PUBLIC_*` をHTTPSの本番ドメインへ変更してください。

## モバイル設定

Androidは `android/local.properties` に次を設定します。

```properties
MAPS_API_KEY=your-google-maps-key
FAMILY_ORBIT_API_URL=https://family.example.jp/api/v1/
```

FCMを実機で確認する場合は、個人の `~/.gradle/gradle.properties`（リポジトリ管理外）に次のクライアント識別子を設定します。保護者用とLink用は別のFirebase AndroidアプリIDを使用します。

```properties
FIREBASE_PARENT_APP_ID=1:...:android:...
FIREBASE_LINK_APP_ID=1:...:android:...
FIREBASE_API_KEY=...
FIREBASE_PROJECT_ID=...
FIREBASE_SENDER_ID=...
```

Android Studioで `app` が保護者用、`link` が子ども用です。アプリIDはそれぞれ `com.tracking.familyorbit` と `com.tracking.familyorbit.link`、minSdk 24、targetSdk 37です。
ローカルのAndroid Emulatorでは未設定時に `http://10.0.2.2:4000/api/v1` を使用します。実機では`localhost`や`10.0.2.2`を使用できないため、同じLANから到達できる開発用HTTPS URL、またはVPSのHTTPS URLを `FAMILY_ORBIT_API_URL` に設定してください。

iOSは `ios/ios.xcodeproj` を開き、`FamilyOrbit` または `FamilyOrbitLink` スキームを選びます。両ターゲットともiOS 17以降です。署名Team、Background Modes、Push Notificationsを開発者組織に合わせて設定し、API URLは各ターゲットの設定へ本番HTTPS URLを与えてください。APNs topicは保護者用 `com.tracking.familyorbit` とLink用 `com.tracking.familyorbit.link` を `.env` の `APNS_PARENT_BUNDLE_ID`／`APNS_LINK_BUNDLE_ID` に個別設定します。子ども用ターゲットはBackground ModesのLocation updatesを使用します。アプリを強制終了した場合に追跡継続は保証されず、保護者側では15分後にオフラインとして扱います。
iOS SimulatorのDebugビルドは `http://127.0.0.1:4000/api/v1` を使用します。Simulatorによっては`localhost`を名前解決できないため、loopback IPを明示しています。iPhone実機でローカル確認する場合はMacの`localhost`や`127.0.0.1`ではなく、実機から到達できる開発用HTTPS URLを両ターゲットの `FAMILY_ORBIT_API_URL` に設定してください。

## テスト

```sh
# Goの単体テスト
cd server && go test ./...

# 実MongoDBを使うAPI統合テスト
docker compose --env-file .env.example --profile test run --rm api-test

# Web
cd web && bun run lint && bun run build -- --webpack
bun x playwright install chromium
bun run test:e2e

# Android: Unit、親・子のAPK、UIテストAPK
cd android
./gradlew :link:testDebugUnitTest :app:assembleDebug :link:assembleDebug \
  :app:assembleDebugAndroidTest :link:assembleDebugAndroidTest

# iOS: XcodeのFamilyOrbitTests / FamilyOrbitUITestsを実行
xcodebuild -project ios/ios.xcodeproj -scheme FamilyOrbit \
  -destination 'platform=iOS Simulator,name=iPhone 17' test
```

Playwrightはデスクトップ／モバイル幅で、ログイン、複数の子ども、履歴、キーボード操作、閲覧専用表示、アクセシブル名を検査します。iOSの疑似移動には `ios/TestRoutes/TokyoSchoolRoute.gpx` を使用できます。

## VPS運用

1. VPSへリポジトリと `.env` を配置し、DNSをVPSへ向けます。
2. `.env` にドメイン、SMTP、MapTiler、FCM、APNsのシークレットを設定します。
3. `docker compose up -d --build` を実行します。Caddyが証明書を自動取得します。
4. `docker compose ps` と `/health` で全サービスを確認します。

毎日の暗号化バックアップはcronまたはsystemd timerから次を実行します。暗号化済みファイルは7日を超えると自動削除されます。

```sh
docker compose --profile backup run --rm backup
```

復元は停止時間を確保し、対象ファイルを明示して専用Compose profileから実行します。

```sh
docker compose stop api worker
RESTORE_FILE=/backups/family-orbit-YYYYMMDDTHHMMSSZ.archive.gz.enc \
  docker compose --profile restore run --rm restore
docker compose start api worker
```

初回リリース前に、別DBへの復元と件数確認まで必ずリハーサルします。

## リリース前チェック

- `docs/field-test-checklist.md` に沿って、画面OFF、再起動、権限変更、通信断、疑似位置、強制終了を検証する
- Android Internal TestingとTestFlightで8時間の移動・停止混在試験を行う
- 子どもの共有停止、権限無効、15分オフライン、低精度、古い位置を親子双方で確認する
- Google Playのバックグラウンド位置情報申告、Appleの背景位置説明、プライバシーポリシー、保護者同意を法務・ストア審査向けに準備する
- 正式なBundle ID、商標、ドメイン、Google Maps／MapTiler、FCM、APNs、SMTPを本番値へ置き換える

詳細は `docs/privacy-and-release.md` を参照してください。
