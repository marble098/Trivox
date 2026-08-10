# Trivox P3 — Smart Connection & Leak Guard V2

Base: `51d143121dd6e8b251e4bebd5f368176f582b3cf`

- ICMP and Automatic Live Ping are removed from runtime, UI, settings, tests and CI guards.
- Real Delay remains an explicit Xray-route ranking test; startup route verification remains a safety gate.
- Adaptive Connection Intelligence is local-only: transport/security features plus short-lived per-profile outcome memory tune the next handshake plan without cloud AI or telemetry.
- Proxy endpoint hostnames are pre-resolved before VPN capture and injected as session-only Xray DNS hosts.
- Scoped DNS bootstrap uses multiple local TCP/DoH paths and stale-cache support.
- WebSocket reset/timeout/DNS failures use controlled transient recovery instead of being misclassified as an immediate generic handshake failure.
- Advanced Fragment uses an Xray Freedom outbound with `settings.fragment` and `sockopt.dialerProxy`; Happy Eyeballs is removed when dialerProxy is active.
- Fragment skips WireGuard, Hysteria/mKCP and proxy chains.
- Leak Guard V2 scores DNS/IP/IPv6 risk, uses multiple exit probes, applies hardened DNS/routing/IPv6 settings and reconnects through the existing coordinator.
- Compose choice controls wrap responsively and use visual glyphs; list-mode Real Test is compact.
