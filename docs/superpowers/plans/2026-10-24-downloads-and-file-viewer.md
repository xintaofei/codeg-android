# Downloads and File Viewer Implementation Plan

> **For agentic workers:** Execute inline, task by task, preserving the existing uncommitted work.

**Goal:** Add a persistent download list and independent local file viewer, with navigation back to the source conversation.

**Architecture:** A singleton repository owns transfer preparation and Android DownloadManager IDs. DataStore stores metadata, never server auth tokens or issued ticket URLs. Navigation pushes downloads/viewer destinations above conversations, keeping their ViewModels and saved UI state.

**Tech Stack:** Kotlin, Compose, Navigation Compose, Hilt, DataStore, Android DownloadManager and ImageDecoder; no new dependencies.

**Spec:** User-approved interaction described in this conversation: downloads list, local image/text/code viewer, return to source conversation, APK/system opening fallback.

## Global Constraints

- Keep server authentication in the existing download-ticket API.
- Scope the list and source navigation to the selected saved server and endpoint.
- Continue transfers after leaving the conversation; restore records after restart.
- Relative links remain within the conversation directory; retain the absolute-path download fix.
- Decode images off the main thread, capped at 2048 pixels per side; bound text preview to 128 KiB and 2000 lines.
- Never remove completed phone files when dismissing a banner or removing a history entry.

## Tasks

- [x] Persist download metadata and own transfers outside conversation ViewModels. Test persistence, identity filtering, duplicate detection and restart recovery.
- [x] Implement list with progress, refresh, retry, cancel, open and source-conversation actions.
- [x] Implement local image zoom and text/code copy in a separate viewer; keep APK installation and external-format fallback.
- [x] Wire session/settings navigation and preserve session draft/scroll state when viewing files.
- [x] Run unit tests, lint and APK build, review the connected flows, update README and the generated Downloads APK.

## Verification

Run `ANDROID_HOME=/Users/sofire/Library/Android/sdk ./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
Check `git diff --check`. If a device/emulator is available, exercise conversation → file → source conversation, restart history, image zoom, missing-file errors and APK installation.

## Completed verification

- Full unit test task, debug APK build and lint passed; 8 new tests cover durable metadata, server identity, download freshness, restart states and bounded text previews.
- Android 15 Small_Phone emulator, read-only session, with a loopback fixture server: downloaded and viewed Python/Unicode text and PNG, tested long text truncation, list opening, cancellation, retry, persisted records after restarting/updating the app, source navigation and preserved drafts.
- Confirmed the Android installation permission screen and APK update confirmation dialog. The system confirmation dialog was canceled after verification.
- Checked settings → downloads → source conversation → downloads → source conversation retains the input draft.
- Final APK copied to `~/Downloads/codeg-android-debug.apk`; SHA-256 matches the build output. No server change or third-party service required.
- Physical Android phone verification remains outstanding.
