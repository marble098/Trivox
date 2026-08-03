# Trivox Full Technical Audit v3

Reviewed source baseline: `0c8e75c45edfabe779b467464db1aafbceeb7c7c`

This package is a focused corrective release for the current lightweight
single-module architecture. It deliberately avoids adding Retrofit, OkHttp,
coroutines, Room, dependency injection, or another UI framework.

## Executive assessment

Trivox has a sensible low-dependency foundation, strict Android component
export rules, a stable native-controller lifetime, bounded diagnostics, ABI
splits, and staged GitHub signing checks. Its largest risks were not dependency
quality; they were concentrated responsibilities and subtle edge cases in
latency measurement, DNS bootstrap, subscription parsing, and UI/background
handoffs.

The most important confirmed runtime defects were reproduced from diagnostics:

1. NordVPN API DNS was redirected to `10.10.34.35` and to a globally routable
   IPv6 address ending in the same poisoned IPv4 value.
2. Nord locations were parsed from the wrong JSON path. The current 3x-ui
   implementation reads `location.country.city`, while Trivox read
   `location.city`.
3. Real delay depended on one Google endpoint and produced repeated timeout,
   NXDOMAIN, aborted-connection and closed-pipe noise.
4. The ping quorum used `ceil(attempts * 0.67)`. For three attempts that equals
   three, so a valid two-of-three result was incorrectly rejected.
5. The final immediate ping-buffer flush was silently discarded whenever a
   delayed flush was already scheduled.
6. Android/system DNS lookup tasks can ignore interruption. Two stuck resolver
   threads could permanently stall every later TCP test.
7. File import read the entire selected file before enforcing a limit, and did
   so from an activity-result callback.
8. Update responses were allocated fully before the 512 KiB check, and a
   transient failure suppressed checks for the full daily interval.
9. External share input had no size guard.
10. Nord refreshes changed profile identity whenever the selected server or raw
    JSON changed, losing favorite and historical latency state.

## Corrective changes

### Ping pipeline

- Uses an exact integer two-thirds quorum.
- Keeps robust median latency and median absolute deviation jitter.
- Adds negative DNS caching and automatic resolver-pool rotation after timeout.
- Prefers IPv4 on networks where IPv6 is present but unreliable.
- Rejects private/local answers for public hostnames while retaining literal
  LAN addresses and `.local`, `.lan`, and `.home.arpa`.
- Uses a small verified connectivity-target pool with learned preferred target.
- Automatically falls back when a target is blocked, unavailable, or has DNS
  failure.
- Requires exact HTTP 204 for `gen_204` and `generate_204` endpoints.
- Separates latency work from general import/export work.
- Throttles persistent live-ping writes while keeping the live UI result fresh.
- Force-drains the last buffered batch result even if a delayed flush exists.
- Converts native real-delay errors to bounded categories instead of logging a
  full error for every expected failed sample.
- Migrates the default endpoint from cleartext Google to HTTPS Cloudflare.
- Adds unit coverage for quorum, median and jitter behavior.

### NordVPN

- Follows the same country-filtered v2 API approach used by current 3x-ui.
- Reads the actual nested schema `location.country.city`, with a legacy
  top-level fallback.
- Extracts every distinct location ID for every country.
- Chooses the lowest-load online NordLynx server independently per location.
- Preserves duplicate city locations instead of merging all blank/identical
  city names into one `Best` item.
- Generates deterministic profile IDs from country ID plus location ID.
- Uses four bounded country workers, a global four-minute catalog deadline,
  and per-country response limits instead of loading one global response up to
  24 MiB.
- Accepts partial success: one failed country no longer discards all successful
  countries.
- Resolves the Nord API only through certificate-verified Cloudflare and Google
  DoH connections made directly to pinned public resolver IP addresses.
- Accepts only literal public A answers; CNAME text is never handed to the
  poisoned system resolver.
- Rejects private, local, multicast, offline, keyless and malformed stations.
- Limits credentials, country, error and server payloads separately.
- Uses stable names containing country, city, and server suffix when multiple
  location IDs share a city label.
- Adds parser unit tests matching the 3x-ui nested-city schema.

### Parsing, storage and lifecycle

- Caps imported text, JSON, line count, line length, profile count and error
  count before expensive parsing.
- Stops unsupported-scheme errors from echoing credentials.
- Preserves literal `+` in RFC 3986 URI components instead of converting it to
  a space.
- Repairs WireGuard endpoint fallback parsing.
- Preserves subscription state by stable ID as well as raw JSON.
- Caps serialized profile storage.
- Moves selected-file reading off the UI thread and enforces a streaming limit.
- Rejects oversized external share input.
- Streams update responses into a bounded buffer.
- Retries update checks after one hour on failure while retaining a daily
  successful-check interval.
- Prevents overlapping update checks.
- Expands `.gitignore` so Gradle output, APKs, AARs, signing files and Python
  bytecode cannot enter commits.

## Repository audit guard

`tools/audit-trivox.py` validates:

- XML syntax.
- generated bytecode and secret-file exclusions.
- credential-shaped literals.
- required ping/Nord safety markers and tests.
- absence of system DNS in Nord bootstrap.
- parser/storage/import bounds.
- final ping flush, dedicated latency executor, and bounded update reads.
- Android cleartext and backup policies.
- unsafe coroutine and full RecyclerView invalidation patterns.

The script reports architecture warnings but fails CI only for correctness,
security, or repository-integrity errors.

## Critical review of remaining architecture

The following are architectural debts rather than defects silently claimed as
fixed:

- `MainActivity`, `PingManager`, `NordVpnSubscriptionManager`, and
  `ConfigParser` remain large. Splitting them is desirable, but doing so in the
  same corrective release would create a much larger regression surface.
- Profiles are still serialized as one JSON array in `SharedPreferences`.
  Batching and caps make this acceptable for the intended lightweight client,
  but a very large long-lived catalog would benefit from Room or a custom
  indexed store.
- A NordLynx private key is necessarily present in generated Xray outbound
  configuration. The app sandbox and `allowBackup=false` reduce exposure, but
  a future credential-reference layer should avoid persisting the expanded key
  inside every profile.
- Real Xray measurements must remain serialized because libXray exposes one
  process-wide native instance and Trivox correctly protects it with a native
  lock.
- No static or automated test suite can prove the absence of every vendor,
  network, kernel, or native-core runtime defect. The staged installer therefore
  runs unit tests, lint and full APK builds before advancing the commit to main.

## Validation strategy

The installer:

1. verifies the exact reviewed source baseline;
2. backs up and stashes local work;
3. applies complete files and exact patches;
4. runs the project resource validator and the new technical audit;
5. runs local Gradle tests/lint when an Android SDK is available;
6. commits on a unique temporary `audit/**` branch;
7. waits for full GitHub Actions validation and APK build;
8. advances the same validated commit to `main` only after success;
9. prints and saves commit, branch, workflow, release and `git status` state.

Main is not modified when staged validation fails.
