# Trivox P2 — Latency Integrity & Xray Compatibility

Base: `ffa4743b93ead45e0ca312e1137fd328271f94ce`

- Active TCP Connect Ping is removed from test engine and user-facing paths.
- Old tcp* persisted fields remain read-only for migration/backups only.
- Live ICMP uses multiple real Echo Requests, validates reply source identity,
  and never reports HTTP/TCP fallback as ICMP.
- Sub-millisecond ICMP samples are no longer forced to a fabricated 1 ms.
- Turbo Real All keeps its fast one-proof path and rescues only would-be failures.
- Hysteria2 Salamander share links map to Xray 26.7.28 FinalMask.
- Profile list/grid cards are denser and show protocol, security, and transport.
- Hysteria2/WireGuard/OpenSSH share schemes are retained by export paths.
- JVM tests and repository audit guards cover these regressions.

Fragment, Leak Guard V2 and broader theme/settings polish remain isolated for P3.
