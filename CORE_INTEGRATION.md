# Core integration

## Why libXray

The official Xray-core release archives generally contain command-line executables. Android applications cannot safely treat an arbitrary executable as a JNI library, and modern Android execution/SELinux rules make that approach fragile. Trivox therefore uses the official [XTLS/libXray](https://github.com/XTLS/libXray) Android AAR matching the pinned Xray version.

For `v26.7.28`, the official asset is `libxray-android.zip`. Its release digest is checked when GitHub supplies one; the wizard also calculates the archive and final AAR SHA-256. The AAR contains `classes.jar` and JNI libraries. Only requested ABIs enter split APKs.

## Manual preparation

Place an official AAR or ZIP in `core-input/`, then run:

```bash
./tools/trivox-wizard.sh --local-core core-input/libxray-android.zip --abis arm64-v8a
```

The validator rejects ZIP traversal, symbolic links, excessive expanded size, multiple ambiguous AARs, missing `classes.jar`, missing JNI libraries, checksum mismatch, and unavailable ABIs. It writes:

- `app/libs/libXray.aar`
- `app/src/main/assets/core-manifest.json`

The AAR is ignored by Git so it cannot be accidentally redistributed or bloat source checkouts.

## Update modes

```bash
./tools/trivox-wizard.sh --core-version v26.7.28
./tools/trivox-wizard.sh --latest-stable
./tools/trivox-wizard.sh --latest-prerelease
./tools/trivox-wizard.sh --local-core core-input/libxray-android.zip
```

The prerelease mode first identifies the official Xray-core prerelease and then requires an exact matching official libXray Android release. It stops instead of substituting a mismatched wrapper.

## Runtime API

`XrayCoreAdapter` uses the structured `LibXray.invoke` entry point for `testXray`, `runXrayFromJson`, `stopXray`, `getXrayState`, `xrayVersion`, and `ping`. Reflection keeps Android Studio sync possible before the optional AAR is placed; runtime availability and manifest integrity are reported explicitly.

## VPN architecture

Xray `v26.7.28` has an Android-capable TUN inbound. `TrivoxVpnService`:

1. obtains user VPN permission;
2. creates a real Android TUN interface;
3. applies IPv4/IPv6 routes, MTU, DNS, and package allow/bypass rules using official APIs;
4. duplicates the TUN FD for configuration validation so validation cannot consume the live FD;
5. places the live FD in the root Xray config `env` as `xray.tun.fd`;
6. registers libXray's dialer controller so outbound sockets call `VpnService.protect(fd)` and avoid loops;
7. starts the core and monitors its state;
8. stops the core before closing the TUN FD.

An external tun2socks helper is not included because it would add native size and another attack surface without technical need for this Xray version. `--skip-tun-helper` remains accepted and explains this choice.

## Adding another core later

Implement `CoreAdapter`, return truthful `CoreCapabilities`, generate version-specific configuration in that adapter, and select it through `CoreManager`. Do not reuse Xray JSON for Mihomo or sing-box schemes that have different semantics. Their native artifacts, manifests, checksums, and licenses must remain separate.
