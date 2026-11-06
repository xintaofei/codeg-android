# Codeg for Android

A native Android client for the
[Codeg](https://github.com/xintaofei/codeg) multi-agent coding server, built
with Jetpack Compose and Material 3.

The Android application is an API client. Agent execution, conversations,
project operations, and provider configuration are managed by a Codeg server
over HTTP and WebSocket APIs.

## Project status

The main client flows are implemented and the project is under active
development:

- multiple server profiles with connection validation and encrypted token
  storage;
- conversation lists, live session streaming, markdown and diff rendering,
  tool timelines, attachments, permissions, questions, and plan updates;
- project browsing, file changes, commit history, branch selection, repository
  cloning, and Git commit/push/pull/fetch flows;
- activity and search screens;
- settings for agents, model providers, MCP servers, skills, experts, quick
  messages, chat channels, version control, language, and system options;
- English and Simplified Chinese resources, deep links, and adaptive navigation
  for wider displays.

Available screens depend on the capabilities exposed by the connected Codeg
server.

## Requirements

- Android Studio Narwhal (2025.1.1) or newer;
- Android SDK Platform 36;
- JDK 17 or newer;
- a running Codeg server. Its default port is `3080`.

The build currently pins Gradle 8.14.3, Android Gradle Plugin 8.11.0, Kotlin
2.0.21, Compose BOM 2024.12.01, Ktor 3.0.3, and Hilt 2.52.

The application supports Android 12 and later (`minSdk 31`) and targets API 36.

## Build and test

Android Studio normally creates `local.properties` with the local Android SDK
path. That file is machine-specific and ignored by Git.

```bash
# Debug APK
./gradlew :app:assembleDebug

# JVM unit tests
./gradlew :app:testDebugUnitTest

# Install on a connected emulator or device
./gradlew :app:installDebug
```

A release build can optionally use a root-level `keystore.properties` file and
an external keystore. Both are local-only inputs and must never be committed. A
fresh clone without signing configuration can still assemble an unsigned
release APK.

## Connect to a server

1. Start a Codeg server and note its URL and generated `CODEG_TOKEN`.
2. Open the Android app and add a server profile.
3. Enter the server URL and token, test the connection, and save the profile.

The selected server is restored automatically the next time the app starts.
An optional EasyTier-enabled build also supports a userspace mesh connection per
profile: enter your network name, password, peer URLs, and the Codeg server's mesh
HTTP address. The password is encrypted separately from the profile. A connection
screen offers retry and server selection if the mesh cannot reach Codeg.

The standard build stays a direct client; it does not display an unavailable mesh
option. See [the optional native build instructions](docs/easytier-native-build.md)
for `-PeasyTier=true`, supported ABIs, source pinning, and dependency notices.

For a server on the same local network, a URL might look like
`http://192.168.1.10:3080`.

## Security model

Server tokens are encrypted with AES-256-GCM using a key held by the Android
Keystore. The shared HTTP client has request logging disabled, so authorization
headers and request bodies are not written by the application's network logger.

Cleartext HTTP is allowed because Codeg servers are often used directly on a
trusted local network. Prefer HTTPS or a trusted tunnel when connecting across
untrusted networks.

HTTP requests authenticate with `Authorization: Bearer <token>`. The WebSocket
event stream transports the token in the
`codeg-token.<base64url-no-pad>` subprotocol alongside `codeg-events`.

Please report security issues according to [SECURITY.md](SECURITY.md).

## Architecture

The project uses a single `:app` module organized by responsibility:

```text
app/             application shell and navigation
core/common      shared utilities and locale/deep-link helpers
core/model       API and UI models plus wire-format adapters
core/network     HTTP/WebSocket clients and error handling
core/security    Android Keystore-backed secret storage
core/datastore   local profiles and preferences
core/data        repositories coordinating storage and networking
core/designsystem
                 theme, components, markdown, and diff rendering
di/              Hilt modules
feature/         conversations, projects, activity, search, servers, settings
```

Requests generally use camelCase, while server responses may use snake_case.
Most operations are `POST /api/<name>` calls. The event stream supports snapshot,
replay, and live event frames, and unknown event types are preserved rather than
terminating the stream.

## License and third-party material

Codeg for Android is licensed under the
[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for material provenance,
dependency licensing guidance, and trademark information.

The resolved release-runtime dependency inventory is checked in under
[`third-party-licenses/`](third-party-licenses/DEPENDENCIES.md). Regenerate it
after changing dependencies:

```bash
./gradlew :app:syncThirdPartyLicenseReport --no-build-cache --rerun-tasks
```

The generated notice bundle is packaged in every APK and is readable from
**Settings → Open-source licenses**.

Release builds and the Gradle `check` lifecycle verify that the checked-in
bundle exactly matches a fresh report. CI fails with regeneration instructions
when dependency or legal-notice changes make it stale.
