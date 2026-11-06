# Optional EasyTier native transport

The normal Android build remains a direct HTTP/WebSocket client. Build the
optional EasyTier variant with `-PeasyTier=true` after generating the native
libraries below. There is no bundled network identity, peer list, server URL,
auth token, or signing credential. Users configure their own server profiles.

## Reproducible native source

The JNI adapter and engine come from the public
[EasyTier repository](https://github.com/EasyTier/EasyTier), pinned to
`4a10d1c2b93beae60ff0d6d3329463c1ad6928b2`. Cargo uses the upstream lockfile.
The sole source patch disables the JNI Android logger (`Debug` to `Off`), so
runtime network configuration cannot leak through verbose library logging.
Native exception text can include parser inputs; application UI and telemetry
must report a generic error instead of logging the native exception or config.

Install Rust 1.95 or later, Android NDK r26 or later, and Protocol Buffers
`protoc`. Add each requested Rust target once, then build:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/version
python3 scripts/build-easytier.py --abi arm64-v8a --abi x86_64
./gradlew :app:assembleDebug -PeasyTier=true
```

The script also accepts `--abi armeabi-v7a` and `--abi x86`; install the
corresponding `armv7-linux-androideabi` or `i686-linux-android` target first.
No Android ABI is claimed unless its generated library is included. All
runtime native libraries must match the device ABI.

Sources and Cargo outputs are under `build/easytier/`. Verified libraries are
written to `build/easytier/jniLibs/<abi>/libeasytier_android_jni.so`, and the
source revision, patch description, and output SHA-256 values are recorded in
`build/easytier/build-info.json`. These generated files are not committed.
Run only one native build per checkout at a time. Optional
`EASYTIER_GIT_PROXY` applies to the public source checkout and Cargo Git
dependencies; `HTTPS_PROXY` configures registry downloads as usual.

Every library is linked with a 16 KiB maximum page size and checked with NDK
`llvm-readelf`: all ELF `LOAD` segments must have at least `0x4000` alignment.
The script also rejects an unexpected dependency on a separate FFI `.so`.
Gradle checks the exact ABI set and library SHA-256 values against that manifest;
leftover libraries from a different ABI selection cause a build failure. Rebuild
all intended ABIs together, or remove only the obsolete generated ABI output.
Native dependency notices are generated from Cargo's non-dev graph (including
build tools as a conservative superset). Reviewed supplements cover packages
whose published crates omit license text. The generator checks their recorded
source hashes and refuses a report with missing notices. The selected target set
must match the native manifest before Gradle packages the optional APK.

APK ZIP alignment and the final packaged ABI set still require normal Android
release validation.

## Scope and lifecycle

EasyTier runs in userspace (`no_tun = true`) and exposes a TCP forward on
loopback only. The existing Codeg HTTP client and event WebSocket use that
endpoint after an end-to-end HTTP health request succeeds. A listening local
socket is not sufficient readiness evidence. This does not create a system
VPN, replace another app's VPN, install system routes, or provide networking to
other apps.

Network identity and peer configuration belong to a manually configured
server profile. The app restores only the selected profile at startup; it
must cancel stale connection attempts when switching, deleting, or editing
profiles. A first mesh connection may take longer while peer discovery occurs.
Readiness timeout and cancellation must leave the server picker usable.

The selected native instance is process-scoped. Leaving the Activity does not
explicitly disconnect it; it may remain until the process exits. There is no
foreground service or guarantee of continuous background connectivity.

## License and replacement

EasyTier is LGPL-3.0. The optional APK must include the EasyTier attribution,
LGPL-3.0, and GPL-3.0 texts from `config/easytier/` in its open-source assets.
This is a dynamically loaded JNI shared library; the app source remains under
its existing license. Rust dependencies retain their own upstream licenses.

For a modified library, fetch the pinned source, apply the documented logger
patch (or your own compatible changes), build the same exported JNI interface,
and replace `build/easytier/jniLibs/<abi>/libeasytier_android_jni.so`. Build the
application again with `-PeasyTier=true`. A debug APK can be installed with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`; use a matching
debug signing key for replacement, or uninstall a differently signed copy
first (which removes its saved data). No vendor signing key is needed to build
and install a modified application under a distinct application ID.

The public engine revision, full app source, documented patch, lockfile, and
build instructions enable users to rebuild and recombine the application with
a modified library. Release publishers should distribute the corresponding
source and dependency notices alongside their optional binary release and
review the LGPL obligations for their distribution channel.

## Official release opt-in

The existing release workflow keeps direct builds by default. Maintainers can
set the repository variable `CODEG_EASYTIER=true` to build all four Android ABIs
from the pinned public source and include the transport in the official APK.
That path also uploads the modified corresponding engine source and dependency
notices beside the APK. Runtime configuration and signing credentials are never
inputs to the native engine build. Local acceptance currently covers ARM64 on
an API 34 emulator with ARM64 translation; the workflow defines builds for the
other ABIs, which have not been tested on physical devices here.

## End-to-end local fixture

The opt-in Android instrumentation test uses the actual app root, encrypted
profile storage, and production HTTP/WebSocket clients. Build the enabled debug
APK and test APK, then start `python3 scripts/mesh-app-fixture.py` in another
terminal. The fixture prints three synthetic connection arguments. Use those
values with:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PeasyTier=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class app.codeg.android.mesh.MeshAppIntegrationTest \
  -e meshPeer tcp://10.0.2.2:PEER_PORT \
  -e meshServer http://10.226.31.1:HTTP_PORT \
  -e meshNetwork codeg-app-fixture \
  app.codeg.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The fixture's mapped subnet exists only through EasyTier, preventing an accidental
direct HTTP pass. It checks a nonempty project response and authenticated event
stream pong, and leaves a clearly synthetic profile for a force-stop/cold-start
check. The test skips without explicit fixture arguments. Stop the fixture with
Ctrl-C, and remove its synthetic profile from the debug app after acceptance.
No live personal server or network is required.
