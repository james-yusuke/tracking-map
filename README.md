# Family Orbit

[Japanese](README-ja.md)

Family Orbit is a limited-beta MVP for Japan that lets families share a child's current location by mutual agreement. The Link app always shows when location sharing is active, and the child can pause sharing at any time. This is not an emergency response service, and it does not guarantee location accuracy or notification delivery times.

## Architecture

| Client / service | Technology | Purpose |
| --- | --- | --- |
| Android `app` | Kotlin, Jetpack Compose, Google Maps | Guardian app for viewing family information |
| Android `link` | Kotlin, Compose, Fused Location, Foreground Service | Child app for explicit location sharing |
| iOS `FamilyOrbit` | SwiftUI, MapKit | Guardian app for viewing family information |
| iOS `FamilyOrbitLink` | SwiftUI, Core Location | Child app for explicit location sharing |
| Web | Next.js, MapLibre, MapTiler | Read-only dashboard for authenticated users |
| API / worker | Go, MongoDB, WebSocket | Authentication, locations, zone evaluation, and notifications |
| VPS | Caddy, Docker Compose | TLS termination and five-service operation |

The server rejects write API calls made from the Web client. MongoDB TTL indexes delete location history after 30 days, and the MongoDB port is not exposed to the host.

## Local development

You need Docker Desktop, Go 1.26, Bun 1.3, Android Studio, and Xcode. Copy the example configuration first, then replace the JWT secrets and backup key with sufficiently long random values.

```sh
cp .env.example .env
docker compose --profile dev up --build
```

- Web: `http://localhost`
- API health check: `http://localhost/health`
- API for mobile debug builds: `http://localhost:4000/api/v1` (exposed only on the host loopback interface)
- OpenAPI specification: `http://localhost/api/docs/openapi.yaml`
- Mailpit: `http://localhost:8025`

Set `NEXT_PUBLIC_DEMO_MODE=true` in `.env` only when previewing the demo Web interface. Always set it to `false` in production, and change `APP_DOMAIN`, `WEB_ORIGIN`, and the `NEXT_PUBLIC_*` variables to the production HTTPS domain.

## Mobile configuration

For Android, add the following values to `android/local.properties`:

```properties
MAPS_API_KEY=your-google-maps-key
FAMILY_ORBIT_API_URL=https://family.example.jp/api/v1/
```

To test FCM on physical devices, add the following client identifiers to your personal `~/.gradle/gradle.properties`, which is not tracked by the repository. The guardian and Link apps must use separate Firebase Android app IDs.

```properties
FIREBASE_PARENT_APP_ID=1:...:android:...
FIREBASE_LINK_APP_ID=1:...:android:...
FIREBASE_API_KEY=...
FIREBASE_PROJECT_ID=...
FIREBASE_SENDER_ID=...
```

In Android Studio, `app` is the guardian app and `link` is the child app. Their application IDs are `com.tracking.familyorbit` and `com.tracking.familyorbit.link`. Both use minSdk 24 and targetSdk 37.

An Android Emulator uses `http://10.0.2.2:4000/api/v1` when no URL is configured. Physical devices cannot use `localhost` or `10.0.2.2`; set `FAMILY_ORBIT_API_URL` to a development HTTPS URL reachable on the same LAN or to the VPS HTTPS URL.

For iOS, open `ios/ios.xcodeproj` and select the `FamilyOrbit` or `FamilyOrbitLink` scheme. Both targets require iOS 17 or later. Configure the signing team, Background Modes, and Push Notifications for your developer organization, and provide each target with the production HTTPS API URL. Configure separate APNs topics in `.env`: `APNS_PARENT_BUNDLE_ID` for the guardian bundle `com.tracking.familyorbit`, and `APNS_LINK_BUNDLE_ID` for the Link bundle `com.tracking.familyorbit.link`. The child target uses the Background Modes location updates capability. Tracking is not guaranteed to continue after the user force-quits the app; the guardian app treats the device as offline after 15 minutes.

iOS Simulator debug builds use `http://127.0.0.1:4000/api/v1` because some Simulator configurations do not resolve `localhost`. When testing on a physical iPhone, set `FAMILY_ORBIT_API_URL` for both targets to a development HTTPS URL reachable from the device instead of the Mac's `localhost` or `127.0.0.1`.

## Testing

```sh
# Go unit tests
cd server && go test ./...

# API integration tests with a real MongoDB instance
docker compose --env-file .env.example --profile test run --rm api-test

# Web
cd web && bun run lint && bun run build -- --webpack
bun x playwright install chromium
bun run test:e2e

# Android: unit tests, guardian and child APKs, and UI test APKs
cd android
./gradlew :link:testDebugUnitTest :app:assembleDebug :link:assembleDebug \
  :app:assembleDebugAndroidTest :link:assembleDebugAndroidTest

# iOS: run FamilyOrbitTests and FamilyOrbitUITests with Xcode
xcodebuild -project ios/ios.xcodeproj -scheme FamilyOrbit \
  -destination 'platform=iOS Simulator,name=iPhone 17' test
```

Playwright checks login, multiple children, history, keyboard operation, read-only behavior, and accessible names at desktop and mobile widths. Use `ios/TestRoutes/TokyoSchoolRoute.gpx` to simulate movement on iOS.

## VPS operation

1. Place the repository and `.env` on the VPS, then point DNS to the VPS.
2. Configure domain, SMTP, MapTiler, FCM, and APNs secrets in `.env`.
3. Run `docker compose up -d --build`. Caddy obtains the TLS certificate automatically.
4. Verify all services with `docker compose ps` and `/health`.

Run the encrypted daily backup from cron or a systemd timer. Encrypted files older than seven days are deleted automatically.

```sh
docker compose --profile backup run --rm backup
```

Schedule downtime before restoring, specify the exact backup file, and run the dedicated Compose profile.

```sh
docker compose stop api worker
RESTORE_FILE=/backups/family-orbit-YYYYMMDDTHHMMSSZ.archive.gz.enc \
  docker compose --profile restore run --rm restore
docker compose start api worker
```

Before the first release, always rehearse a restore to a separate database and verify the restored record counts.

## Pre-release checklist

- Follow `docs/field-test-checklist.md` to test screen-off behavior, restarts, permission changes, network loss, simulated locations, and force-quitting.
- Run an eight-hour mixed movement and stationary test through Android Internal Testing and TestFlight.
- Verify sharing-paused, permission-disabled, 15-minute offline, low-accuracy, and stale-location states in both guardian and child apps.
- Prepare the Google Play background location declaration, Apple's background location explanation, privacy policy, and guardian consent for legal and store review.
- Replace the provisional bundle IDs, trademark, domain, Google Maps / MapTiler, FCM, APNs, and SMTP settings with production values.

See `docs/privacy-and-release.md` for details.
