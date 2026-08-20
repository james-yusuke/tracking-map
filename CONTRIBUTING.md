# Contributing to Family Orbit

Thank you for helping improve Family Orbit. Contributions should preserve the project's core principles: family consent, visible location sharing, strict family isolation, data minimization, and accessible user experiences.

## Before you start

- Read the [project overview](README.md), [security policy](SECURITY.md), and relevant files in `docs/`.
- Search existing issues and pull requests before starting duplicate work.
- Open an issue before making a large product, API, schema, tracking-policy, or infrastructure change.
- Do not use a public issue to report a vulnerability. Follow [SECURITY.md](SECURITY.md) instead.
- Keep changes focused. Unrelated refactors should be submitted separately.

## Safety and privacy

Family Orbit processes sensitive child location data. Development and testing must follow these rules:

- Use only accounts, families, devices, servers, and data that you own or are explicitly authorized to use.
- Never commit real names, email addresses, credentials, tokens, pairing codes, device identifiers, notification contents, or precise coordinates.
- Do not use real child location history in fixtures, screenshots, logs, or bug reports.
- Use synthetic routes such as `ios/TestRoutes/TokyoSchoolRoute.gpx` and clearly fictional test profiles.
- Redact secrets, personal data, and coordinates from logs before sharing them.
- Preserve the child's visible tracking indicator and the product's consent and disclosure requirements.
- Do not introduce covert tracking, hidden permission prompts, or claims that location delivery is guaranteed.

## Development setup

The main tools are Docker Desktop, Go 1.26, Bun 1.3.14, Android Studio, and Xcode.

```sh
cp .env.example .env
docker compose --profile dev up --build
```

Replace example secrets with long random development values. Keep `.env`, signing files, Firebase configuration files, APNs keys, and local SDK configuration out of Git.

See [README.md](README.md) for Android, iOS, FCM, APNs, maps, and local API configuration.

## Repository layout

| Path | Contents |
| --- | --- |
| `android/app` | Android guardian app |
| `android/link` | Android child Link app |
| `android/core` | Shared Android models, API client, theme, and secure storage |
| `ios/ios` | iOS guardian app |
| `ios/FamilyOrbitLink` | iOS child Link app |
| `ios/Shared` | Shared iOS models, API client, theme, and Keychain support |
| `web` | Read-only Next.js dashboard |
| `server` | Go API, worker, MongoDB access, and OpenAPI specification |
| `ops` | Backup and restore containers and scripts |
| `docs` | Privacy, release, and field-test guidance |

## Branches and commits

Create a short-lived branch from the latest `main` branch. Use a descriptive name such as `fix/message-delivery` or `feat/zone-editor`.

Write concise, imperative commit messages. Conventional prefixes are encouraged:

- `feat:` for user-visible functionality
- `fix:` for bug fixes
- `docs:` for documentation
- `test:` for test-only changes
- `refactor:` for behavior-preserving code changes
- `chore:` for maintenance and tooling

Do not commit generated build directories, IDE user state, local configuration, or secrets. Commit dependency lock files when dependencies change.

## Implementation expectations

### API and data

- Enforce authentication, client type, family ownership, and device membership on the server.
- Never rely on a mobile or Web client to enforce authorization.
- Preserve idempotency for location batches and parent-to-child messages.
- Keep retention and deletion behavior explicit, including MongoDB TTL indexes.
- Avoid personal names and coordinates in push data payloads and server logs.
- Update `server/internal/app/openapi.yaml` whenever an API field, enum, endpoint, or error changes.
- Keep Android, iOS, and Web models consistent with OpenAPI.
- Add or update Go tests for authorization boundaries, validation, retention, and duplicate prevention.

### Android and iOS

- Keep guardian and Link apps as distinct application targets.
- Make active, paused, permission-denied, stale, and offline states unambiguous.
- Keep Android's foreground-service notification visible while tracking.
- Do not imply that iOS tracking continues after a force-quit.
- Preserve encrypted offline queues and chronological retry behavior.
- Test permission changes, background transitions, restarts, and network loss.
- Maintain Dynamic Type, VoiceOver, TalkBack, and minimum touch-target support.

### Web

- Keep the Web dashboard read-only in both the UI and API authorization layer.
- Use TypeScript 7 for Next.js type checking.
- Keep `web/scripts/typescript-eslint-compat.cjs` until `typescript-eslint` supports the TypeScript 7 compiler API.
- Update `web/bun.lock` with Bun 1.3.14 when dependencies change.
- Preserve keyboard operation, visible focus, accessible names, responsive layouts, and WCAG AA contrast.

## Tests

Run the checks relevant to your change. Cross-cutting changes should run all applicable checks.

### Go

```sh
cd server
go test ./...
```

Use the Compose test profile for integration tests against MongoDB:

```sh
docker compose --env-file .env.example --profile test run --rm api-test
```

### Web

```sh
cd web
bun install --frozen-lockfile
bun run lint
bun run build -- --webpack
bun x playwright install chromium
bun run test:e2e
```

### Android

Install Android SDK Platform `37.0`, then run:

```sh
cd android
./gradlew :app:assembleDebug :link:assembleDebug \
  :app:testDebugUnitTest :link:testDebugUnitTest \
  :app:assembleDebugAndroidTest :link:assembleDebugAndroidTest
```

For tracking changes, also test on a physical device with the screen off, after a restart, after permission changes, and with simulated movement.

### iOS

```sh
xcodebuild -project ios/ios.xcodeproj -scheme FamilyOrbit \
  -destination 'platform=iOS Simulator,name=iPhone 17' test

xcodebuild -project ios/ios.xcodeproj -scheme FamilyOrbitLink \
  -sdk iphonesimulator -configuration Debug build
```

Use GPX routes for simulated movement and verify background, permission-change, and force-quit states on physical devices where applicable.

## Pull requests

In the pull request description:

- Explain the problem and the user-visible outcome.
- Identify affected clients, API endpoints, collections, and environment variables.
- Describe privacy, security, retention, battery, and background-execution implications.
- List the exact tests run and their results.
- Include screenshots or recordings for UI changes using fictional data only.
- Document follow-up work and known limitations.

Before requesting review, confirm that:

- [ ] The change is focused and contains no unrelated files.
- [ ] No secrets, personal data, or real location data are included.
- [ ] Authorization and family isolation remain server-enforced.
- [ ] OpenAPI and client models are synchronized when applicable.
- [ ] Empty, loading, error, stale, offline, and permission states are handled when applicable.
- [ ] Accessibility and localization have been considered.
- [ ] Relevant automated and physical-device tests have passed.
- [ ] Documentation and `.env.example` are updated when configuration changes.

Maintainers may request changes, additional tests, or a narrower scope before merging.
