# Trivox Performance, NordVPN Cities and Compact UI v2.2

Reviewed base: `df5b2d69498d41aab39d4aab1d8798de6c5c1cf3`

## Scope

This change set keeps the existing Trivox features and focuses on responsiveness, correct WireGuard/NordVPN handling, subscription access, privacy controls and a more compact main screen.

## Diagnosed problems

1. Batch TCP Ping changed every profile to `TESTING` with one full SharedPreferences read/write per profile.
2. Every completed ping reloaded, parsed and sorted all profiles and called a full RecyclerView refresh.
3. One Future was allocated for every profile even though the executor had a small fixed worker pool.
4. DNS resolution was repeated for every profile and attempt.
5. NordVPN selected only one server per country and discarded the remaining cities.
6. A poisoned/private DNS result could map `api.nordvpn.com` to a private address such as `10.10.34.35`.
7. NordVPN profiles stored only an outbound as their raw JSON, so Copy JSON returned an incomplete portable configuration.
8. Generic JSON parsing did not extract WireGuard `peers[0].endpoint`.
9. Shadowsocks 2022 URL-safe or unpadded keys could reach Xray in a form rejected by Base64 decoding.
10. `releases/latest` returns 404 when the repository has no GitHub Release, even when tags exist.
11. A stale Xray process could keep the mixed port bound after an Android process/session transition.
12. Several storage-heavy actions still performed serialization on the main thread.

## Performance changes

- Added a process-wide decoded profile cache with defensive copies.
- Added `updateMany` and `deleteMany` so batch changes are persisted once.
- Replaced one-task-per-profile batching with bounded worker loops.
- Added a short DNS cache for TCP probing.
- Buffered ping results and flushes them at most once per 220 ms.
- Moved JSON generation, backup generation, rename, duplicate, delete, clear-dead and single-ping persistence off the UI thread.
- Replaced `notifyDataSetChanged()` with `AsyncListDiffer` / `DiffUtil`.
- Disabled RecyclerView item animations during rapid ping updates.
- Removed layout-wide change animations from the main screen.

## NordVPN and WireGuard changes

- Selects the lowest-load usable NordLynx server for every distinct country/city pair.
- Naming format: `🇦🇪 NordVPN Emirates - Dubai`.
- Rejects private, loopback, link-local and otherwise unusable API DNS results.
- Uses a public DoH fallback and TLS SNI/hostname verification when connecting to a mapped public IP.
- Generates a complete portable Xray JSON containing log, SOCKS/HTTP inbounds, proxy/direct/block outbounds, DNS and routing.
- Stores a separate TCP probe endpoint. Nord TCP Ping uses the server hostname on 443 because NordLynx itself is UDP/51820.
- Real Delay All remains the end-to-end test for the actual Xray/WireGuard route.
- Generic imported WireGuard JSON now extracts the peer endpoint.
- WireGuard default DNS uses direct IPv4 bootstrap resolvers to avoid recursive DoH-through-tunnel startup deadlocks.

## UI and workflow changes

- Compact main layout with an expandable connection-tools panel.
- Lightweight text/vector-like symbols instead of image assets that require decoding.
- TCP Ping and Real Delay All are adjacent.
- Main Add menu contains URL subscription and NordVPN token options.
- Long-pressing a subscription tab opens that subscription's settings.
- Added Hide IP on main screen in Settings.
- Added Pause while connected: 15 minutes, 30 minutes or custom 1–1440 minutes.
- Pause persists across process death and device reboot; it resumes through a private BroadcastReceiver.
- Disconnect state uses a red primary action.

## Other repairs

- Shadowsocks 2022 keys are normalized and length-validated before Xray receives them.
- Update checking falls back from GitHub Releases to repository tags.
- Mixed proxy startup attempts one stale-core cleanup before reporting another-app port ownership.

## Safety and validation

The Termux installer:

- Requires the exact reviewed main commit.
- Verifies GitHub authentication, push access and existing signing secrets before modification.
- Stashes local changes and creates a file backup.
- Applies complete source replacements plus exact idempotent patches.
- Runs XML, Bash, Python, Android-resource and git-diff checks.
- Runs local Gradle test/lint when Android SDK is available.
- Otherwise pushes the exact commit and requires GitHub Actions to pass.
- Automatically reverts its own commit if GitHub Actions fails and main has not moved.
- Creates `~/storage/downloads/Trivox-performance-ui-v2-applied-files.zip` containing every final changed/added repository file.

## Deliberate limitations

- TCP Connect cannot directly measure a UDP WireGuard handshake. Nord profiles therefore probe the same server on TCP 443; use Real Delay All for tunnel truth.
- A pause can resume VPN only while Android's VPN permission remains granted. If Android revokes it, Trivox records the reason instead of bypassing the platform permission prompt.
- No additional native core or unsupported protocol is claimed or simulated.

## v2.1 installer correction

- Replaced the ambiguous repository companion-object anchor with a whole-class replacement scoped from `ConfigRepository` to `SettingsRepository`.
- Scoped Shadowsocks password replacement to the Shadowsocks server object so the Trojan password field is untouched.
- Scoped the connected exit-IP privacy replacement to the connected-info block so other `profileExitLine` calls remain intact.


## v2.2 staged-CI correction

- `ProfileAdapter.Holder.bind` now accepts public model values rather than the adapter-private `Row` type.
- Installer compatibility is checked with Git tree equality, so a clean apply/revert history with an identical source tree is accepted.
- The candidate is pushed to a temporary `develop` branch first. The workflow already monitors pushes to `develop`, while release publishing remains restricted to main/master.
- `main` is fast-forwarded only after the staged workflow succeeds.
- Python bytecode and executable-bit-only changes are rejected before commit.
