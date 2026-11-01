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

For a server on the same local network, a URL might look like
`http://192.168.1.10:3080`.

## Conversation history loading

The conversation screen initially requests the latest **five user rounds**. A
round includes the prompt, thinking, tool calls/results and the assistant reply;
an unfinished latest round counts too. Scrolling near the top loads five earlier
rounds, with a retry button on failure. Replies exceeding 500 underlying records
are split across pages so one long round cannot expand into an unbounded fetch.

Deploy the accompanying Codeg server changes for `tailRounds` / `roundLimit`
support. Existing server clients remain compatible. An older server may return
the full transcript; Android accepts that response and disables history paging
when window metadata is absent. Servers supporting only record paging use a
30-record fallback for older pages.

History pages are verified against the loaded prefix fingerprint. Completion
and reconnect refreshes preserve the already-loaded window; incompatible
history is reset to a fresh recent window. Session-wide usage statistics remain
session-wide regardless of the loaded range.

Debug builds emit `CodegTranscript` timing logs for detail/history downloads,
decoded payload bytes (before HTTP compression), JSON decoding, and persisted
timeline conversion. On a device, compare the same long session and network
conditions before/after the change, including first entry, upward pagination,
and a completed live reply. Also verify reading position, retry after a network
failure, and a round spanning multiple history pages. Release builds omit these
logs, and neither build logs transcript content through this instrumentation.

## Local conversation cache

Saved server profiles cache the conversation list (with folder/grouping context)
and confirmed transcript pages in the app's private cache directory. On entry,
cached data appears first. Idle snapshots younger than 60 seconds avoid another
fetch; older snapshots refresh in the background. Live sessions always check the
server and reattach. Network failures keep cached content visible with a retry.

Use the list's refresh button/pull gesture or **Refresh** in a conversation's
overflow menu to bypass freshness. Conversation refreshes retain the loaded
history window and leave the composer and live stream intact. During a live
reply, transcript replacement waits for completion so the response is not shown
twice. Live/optimistic messages and interactive approval cards are not cached;
the last confirmed snapshot stays readable until a completed response replaces it.

Reopening a cached conversation decodes just its latest five-round/500-record
page. Loaded older pages are available offline while retained in the same
snapshot. An authoritative detail refresh replaces that snapshot: structural
prefix hashes do not prove message text equality, so unrefreshed older cached
pages are discarded. Old servers without window metadata retain their existing
full-history fallback.

Cache keys separate profiles, normalized server URLs and credentials; tokens
and URLs are not written into cache filenames. Changing an endpoint/token or
removing a profile clears its cache. Conversation mutations invalidate the
affected detail and list; starting a reply marks the last confirmed detail stale.
Clearing/deleting snapshots fences requests already in flight against writing
them back.

**Settings → Local cache** shows disk usage and clears cached data. The cache
has a 100 MiB global LRU budget and a 10 MiB payload limit per conversation;
oversized tails are not stored. It is disposable, excluded from app backups,
and can also be removed by Android. Clearing it leaves currently displayed
messages in memory. Unvisited or evicted history still requires the server.

## Conversation file downloads

Open **Files** in a conversation toolbar or **Settings** for the unified file manager.
**Conversation files** lists files written, modified, or explicitly linked in the
complete conversation, including history above the phone's loaded message window.
Files appear before any download. Paths are deduplicated, with the latest operation,
availability, size and source conversation. Search, switch conversations, or refresh
manually. The compact index is cached per server and credentials; failed refreshes
keep the previous list visible. Updated servers use `get_conversation_files`. If an
older server returns `501 / not_implemented` or an unstructured 404 for that endpoint,
the file manager reads complete history with the existing conversation API and builds
the index on the phone. This fallback runs only in the file manager and does not replace
the paged conversation cache. Large conversations take longer to index on older servers.
Authentication, permission, and other server errors are reported without triggering fallback.
Older servers cannot check current file availability in advance; those entries remain
openable and say that availability is checked on opening. Historical deletion alone does
not prove that a path is still missing today.

The index recognizes structured write/edit/patch tools and local Markdown
links, including files built by shell commands when the reply links them. It is not
a filesystem change monitor: arbitrary shell side effects, agent tools with no
recognizable paths, and images embedded without a local file URI cannot be indexed.
It records file paths rather than historical versions; previews show current files.

Tap **View** to preview a server file without saving it in Downloads. Tap **Save to
phone** to start a persistent download. Non-APK Markdown file links also open a remote
viewer directly; APK links retain the direct download/install flow. Absolute paths are server
paths, including files outside registered projects such as the server's Downloads
folder. The server validates access to the explicitly linked file through the
authenticated ticket endpoint. Relative paths stay within the conversation folder.
Paths containing spaces can use Markdown angle brackets,
for example `[Download APK](</workspace/Android Project/app-debug.apk>)`.

The app uses the server's authenticated `workspace_download_ticket` endpoint and
hands the one-use URL to Android DownloadManager. The server token is never sent
to the system downloader. No third-party hosting service or separate file server
is needed. The connected Codeg server must support workspace download tickets.

Select the **Download history** tab in the file manager for the independent
download list. It shows records for the selected saved server and
endpoint, with progress, refresh, retry, cancel, file viewing and a source
conversation shortcut. Records survive app restarts. Removing a history entry
does not delete the file. Files downloaded by older versions without persistent
history remain in the phone's system Downloads folder.

Android saves files under **Download/Codeg**, using unique names to preserve
existing files. Multiple transfers can continue through the system downloader
after leaving the conversation. Retry starts a new transfer with a fresh ticket;
interrupted one-use transfers cannot reliably resume. The list opens saved copies;
tapping **Save to phone**, an APK link, or the local viewer's **Download again** fetches the
current server file, so a new APK at the same path does not reopen an older build.
Earlier completed files remain intact. If a saved file is missing, retry downloads
a new copy.

Files open a separate viewer. Images support pinch zoom, pan and double tap
zoom/reset; text and code support selection and copy. Viewing is offline after
the download completes. Images are decoded off the main thread and limited to
2048 pixels per side; remote image transfer is limited to 12 MiB, with download
offered for larger images. Text preview is limited to 128 KiB or 2000 lines, with a
visible truncation notice; copying copies that preview. The complete download
remains unchanged. HTML is displayed as source; PDF, SVG, archives and other
unsupported formats use external apps. Returning to the source conversation
preserves its ViewModel, draft and scroll position.

APK installation from the viewer opens Android's installer. If needed, allow installs from Codeg
in system settings, then return and tap **Install** again. Ordinary web links open
the browser; direct HTTP(S) APK links use the same download flow without sending
Codeg credentials. Existing older Android builds need this version installed first
before their conversation links can use this feature.

## Hide folders on this device

Use a folder row's overflow menu in **Folders**, or a project group's menu in
**Chats**, to choose **Hide on this device**. The folder and its worktrees are
removed from the project list, ordinary conversation groups and Recent. Pinned
conversations, global search and pages already open remain accessible.

Both lists offer **Show hidden folders** in their toolbar menus, with a Restore
button for each hidden folder. Choices survive app restarts and are isolated by
saved server profile and URL. They do not close the server workspace, change
other clients, remove files or delete conversations. Clearing conversation cache
does not reset these preferences.

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
