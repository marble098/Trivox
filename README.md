# Trivox

Trivox is a compact Android client for Xray-core. It uses classic XML Views, a single Android module, platform networking APIs, `org.json`, `SharedPreferences`, and three AndroidX libraries. It has no ads, analytics, telemetry, WebView networking, root requirement, or silent in-app executable updater.

The pinned core is Xray `v26.7.28`, integrated through the official `XTLS/libXray` Android AAR. The AAR is deliberately not committed: run the wizard to retrieve and verify it, or provide the official archive manually.

## Quick start

```bash
./tools/trivox-wizard.sh --core-version v26.7.28 --abis arm64-v8a --prepare
./build.sh all
```

Windows PowerShell:

```powershell
.\tools\trivox-wizard.ps1 -CoreVersion v26.7.28 -Abis arm64-v8a -Prepare
.\build.ps1 all
```

To use a local artifact, place it in `core-input/` and run:

```bash
./tools/trivox-wizard.sh --local-core core-input/libxray-android.zip --abis arm64-v8a
```

`Xray-android-arm64-v8a.zip` from the Xray-core release contains a standalone executable, not the Android wrapper API required by this application. The wizard inspects it and stops with a precise error. Use the official `libxray-android.zip` from [XTLS/libXray releases](https://github.com/XTLS/libXray/releases).

## Architecture

- `CoreAdapter` isolates the app from core-specific APIs.
- `XrayCoreAdapter` calls the official libXray structured API by reflection so the source project still syncs before the optional AAR is prepared.
- `CoreManager` owns validation, start, state, and stop.
- `ConnectionService` runs local SOCKS/HTTP proxy mode.
- `TrivoxVpnService` establishes Android's TUN interface and passes a duplicated FD through Xray's `xray.tun.fd` environment during validation, then the live FD during start.
- `ConfigParser` handles common Xray links, mixed/base64 subscriptions, and full Xray JSON while preserving every original input.
- `ConfigRepository`, `SubscriptionRepository`, and `SettingsRepository` keep private JSON in `SharedPreferences`.

Mihomo and sing-box are not included. A future adapter implements `CoreAdapter`, declares its own capabilities, and is selected in `CoreManager`; UI and repositories do not need replacement.

## Features in 0.1.0

- VLESS, VMess, Trojan, Shadowsocks, SOCKS, HTTP proxy links and Xray JSON.
- TCP, WebSocket, gRPC, HTTP Upgrade, XHTTP, KCP, QUIC, TLS, and REALITY fields used by these links.
- Clipboard, manual text, HTTPS subscription, file, Android share, base64 and mixed-text imports.
- Exact duplicate removal, raw input preservation, search, latency sort, favorites, enable/disable, rename, duplicate, delete, list/grid display, and groups.
- Bounded TCP testing and official libXray real-delay API support.
- Localhost-only SOCKS/HTTP listeners with port-conflict checks.
- Real full-device Android VPN; IPv4, optional IPv6, MTU, DNS, application allow/bypass lists, socket protection, network-change reconnect, optional boot reconnect, foreground state, duration, and unexpected core-exit detection.
- Sanitized bounded diagnostics and Storage Access Framework export.

See [BUILDING.md](BUILDING.md), [CORE_INTEGRATION.md](CORE_INTEGRATION.md), and [README_FA.md](README_FA.md).

## Known limits

- ICMP availability varies by Android vendor. Trivox never treats Xray TUN's locally generated ICMP echo response as proof that the remote host is alive.
- URI extensions not understood by the parser are retained in the raw value but are not silently converted. Import full Xray JSON for uncommon core-specific fields.
- Android's VPN application rules operate at package level. System apps are hidden by default.
- Domain-based DNS endpoints are valid in Xray JSON but Android `VpnService.Builder` itself accepts only IP DNS servers. Trivox installs safe IP bootstrap DNS on the TUN interface and sends Xray DNS according to the selected mode.
- The official libXray AAR is large because it includes the core. ABI-split APKs avoid packaging unrelated ABIs; the universal APK is necessarily much larger.

## Versions

- App version: generated automatically from Git history by CI
- Xray/libXray pin: `v26.7.28`
- AGP: `8.13.2`
- Kotlin: `2.4.10`
- Gradle: `8.13`
- JDK: `17`
- compile/target SDK: `36`; min SDK: `26`


## Technical audit

The ping, DNS bootstrap, NordVPN location import, parser bounds, storage
preservation, update checker and CI guards were reviewed in the v3 corrective
audit. See `docs/TRIVOX_FULL_TECHNICAL_AUDIT_V3.md`. Run:

```bash
python3 tools/audit-trivox.py --ci
```


## Subscription and diagnostics hardening v4.2

The v4.2 corrective pass adds tolerant HTTPS subscription normalization,
bounded gzip/deflate downloads, redirect-loop and retry handling, cancellation
semantics, partial NordVPN catalogue preservation, optimized batch real-delay,
instant first-tap connection feedback, actionable-only diagnostics and refreshed
subscription/main layouts. Run `python3 tools/audit-trivox.py --ci` to validate
the guards.


## Android 11 diagnostics guard v4.3

The v4.3 follow-up replaces the brittle text-only CI guard with a semantic
validator and places `ApplicationExitInfo` access behind an explicit Android 11
API boundary. No lint baseline or suppression is used.


## Localized progress formatting v4.4

The v4.4 follow-up gives subscription progress strings a single explicit
string-argument contract in every locale, adds a repository-level formatter
validator, upgrades failed-Lint artifact upload to the Node.js 24 action, and
removes the remaining memory-trim compiler deprecation. No Lint baseline or
suppression is used.

## License

Trivox source is Apache-2.0. Xray-core is MPL-2.0 and libXray is MIT. The core binary is downloaded separately from its official release and remains under its upstream license. See [LICENSES.md](LICENSES.md).


## WireGuard, DNS and grid reliability v5

The v5 reliability pass no longer pins the active profile to the first row. It
adds a dedicated compact grid card, a vector expand control, and bounded live
health probes so the UI cannot remain in a misleading measuring state.

WireGuard native-process startup is no longer reported as a successful
connection by itself. Trivox keeps a localhost-only mixed listener for
end-to-end verification, applies a conservative MTU and persistent keepalive,
and stops the core when real HTTP traffic cannot cross the tunnel. TCP endpoint
reachability is never used as an Alive result for WireGuard profiles.

DNS traffic arriving from Android's VPN interface is hijacked into Xray's DNS
outbound. Smart/default DNS uses encrypted IP-based DoH through the selected
route with cache, parallel fallback, and no dependency on Android's system
resolver. Direct and System remain explicit opt-in bypass modes.
