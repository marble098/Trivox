# Trivox v24 — Stability, Performance & UI Audit

Base commit: `dda9f62d2bc9aa4d376b40ad5e511fee814f3dd8`

## Inventory

- Tracked files inventoried through the Git tree: **223**
- Kotlin/Kotlin Gradle files: **104**
- App/tool/workflow files in the audit surface: **182**

## Critical native crash finding — fixed

- The supplied Android tombstone identifies `libwg-go.so` in the SIGSEGV process.
- The last checkpoint completed `wireGuardSetStateDown` immediately before the crash.
- A native SIGSEGV cannot be caught by Kotlin `runCatching`.
- v24 removes the separate WireGuard Android Go backend and its `libwg-go.so` dependency.
- WireGuard profiles remain supported through pinned Xray/libXray WireGuard outbound.
- Legacy `nativeWireGuardVpn` JSON is retained only for backup compatibility and forced false.

## Performance fixes

- Home map does not perform a second exit-IP request while connected; it consumes refreshed profile exit data.
- Direct IP lookup runs only while disconnected and is keyed to connection-session state, not profile selection.
- Single-profile network tests no longer occupy the storage executor for the network timeout; the latency executor owns a cancellable Future.
- Existing bounded batch workers, DNS caches, batched result writes, Xray JNI lifecycle guards and mixed-port recovery are preserved.

## Map redesign

- Replaces seven hand-drawn continent blobs with simplified Natural Earth 1:110m public-domain country geometry.
- Geometry is normalized offline into FloatArrays: no Maps SDK, WebView, bitmap atlas or runtime parser.
- Country boundaries, latitude/longitude grid, equator, route arc, one-shot camera motion, flag marker and IP chips are Canvas-rendered.
- IP privacy setting is respected and no infinite animation is used.

## CI/static audit gates

- Fail if WireGuard Android tunnel dependency or `com.wireguard.android` imports return.
- Fail if `NativeWireGuardManager` returns to VPN service.
- Fail if legacy native-WireGuard preference can default/restore true.
- Fail if world geometry becomes coarse or single-test path stops being cancellable.
- Existing full repository secret/resource/API/Compose/network audit stays enabled.

## Large-file architecture warnings

- `app/src/main/java/com/trivox/client/ui/MainActivity.kt` — 147,755 bytes
- `app/src/main/java/com/trivox/client/ui/compose/MainComposeScreen.kt` — 66,119 bytes
- `app/src/main/java/com/trivox/client/network/NordVpnSubscriptionManager.kt` — 57,997 bytes
- `app/src/main/java/com/trivox/client/ui/compose/LegacyLayoutBridge.kt` — 43,953 bytes
- `app/src/main/java/com/trivox/client/service/TrivoxVpnService.kt` — 43,483 bytes
- `app/src/main/java/com/trivox/client/network/PingManager.kt` — 42,357 bytes
- `app/src/main/java/com/trivox/client/config/ConfigParser.kt` — 41,323 bytes

Large source files are maintainability debt, not automatically runtime lag. Splitting them inside a native-crash hotfix would increase regression risk without reducing runtime work.

## Runtime guarantee

This pass removes the confirmed `libwg-go.so` crash surface and fixes concrete static/runtime findings. A static audit cannot guarantee every future kernel/network/upstream-native crash is impossible; GitHub Actions plus device diagnostics remain the final gates.
