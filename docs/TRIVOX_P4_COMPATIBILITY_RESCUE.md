# Trivox P4 — Compatibility Rescue

This patch is compatibility-first and keeps Xray core pinned to v26.7.28.

## Runtime connection policy

A running VPN Xray process is no longer destroyed only because a public HTTPS
health endpoint timed out, reset, returned an endpoint-specific response, or had
a transient WebSocket/DNS probe failure. Deterministic configuration,
authentication, REALITY, and certificate failures remain hard failures.

## Real Delay

Single-profile Real Delay tries multiple independent verified HTTPS targets
inside each sample and prefers SOCKS before HTTP proxy fallback. The configured
verified URL and hostname-based 204 targets are tried before IP-only trace rescue.

## Imported config preservation

Network tuning no longer invents `sockopt.domainStrategy=UseIP` for imported
outbounds. Happy Eyeballs is only added when the imported config already declared
a compatible IP domain strategy. Fragment remains opt-in.

## DNS bootstrap

Hostname proxy endpoints first use the Android/system resolver, then direct
DNS-over-TCP fallbacks. Pre-start endpoint resolution can inject resolved
addresses into Xray `hosts`.

No subscription secrets or proxy credentials are added to logs.
