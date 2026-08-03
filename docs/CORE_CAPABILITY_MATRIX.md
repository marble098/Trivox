# Trivox capability implementation matrix

## Implemented by this feature pack

- Separate configurable Live Ping method.
- One-shot Real Delay beside Live Ping.
- The list-wide Test action is explicitly TCP Ping.
- Safe profile switching while connected.
- NordVPN Sub using the current 3x-ui NordLynx flow.
- Android Keystore encryption for the NordVPN token.
- Existing URL subscriptions remain compatible.

## Not falsely marked as implemented

The following require a real second core or additional native executables:

- Sing-box core, TUIC and ShadowTLS.
- FakeIP, Sing-box remote rule sets and urltest.
- dnstt, iodine and dns2tcp.
- Netfilter/system-lockdown enforcement beyond VpnService blocking.
- Automatic WARP account generation. Hiddify App delegates this to hiddify-core,
  which uses the Go warp-plus library; Trivox currently ships only libXray.
- uTLS randomization, REALITY multi-SNI rotation and native multi-core failover.

## Required next phase

1. Add a version-pinned Sing-box Android library and `SingBoxCoreAdapter`.
2. Add `CoreKind` to profiles and route lifecycle calls through `CoreManager`.
3. Add Sing-box-native TUIC, ShadowTLS, FakeIP, rule sets, DNS and urltest.
4. Add a process manager for dnstt/iodine/dns2tcp.
5. Validate all ABIs and signed APKs in CI.
