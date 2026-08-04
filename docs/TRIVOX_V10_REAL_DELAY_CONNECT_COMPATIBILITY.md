# Trivox v10 — Verified Real Delay, faster connect and Xray 26.7.28 compatibility

Base commit: `c0aa496e835278626c3de8af96a9852af9994e5c`

## Diagnostics-driven findings

The supplied diagnostic report contains 1,656 lines. The dominant failure is not
an Android crash: it is repeated DNS timeout and transport-handshake failure while
profiles are being tested. The report contains 1,279 Xray DNS retrieval failures,
116 WebSocket dial failures, 66 REALITY certificate mismatches, 17 live-route
verification failures, and no JVM crash or native panic marker.

## Real Delay All v10

The old implementation accepted libXray `ping` when it returned a positive delay.
That value was not a sufficient end-to-end proof and could survive a REALITY,
WebSocket, redirect, or arbitrary HTTP-response failure.

The v10 implementation no longer uses native MeasureDelay as the truth source.
For every profile it:

1. refuses to disturb an already-running user connection;
2. builds and validates the exact profile with a localhost mixed inbound;
3. starts the actual Xray profile in proxy mode;
4. sends bounded HTTPS requests through that running mixed proxy;
5. validates either a server-generated Cloudflare trace body or exact HTTP 204;
6. records latency only after the required verified samples succeed;
7. stops the temporary core and clears temporary configuration/log files.

A failed profile always returns `latencyMs = null`, therefore a previous stale
millisecond value is cleared by the existing profile result pipeline.

## Connect path

Connection verification now tries a DNS-free `1.1.1.1/cdn-cgi/trace` proof first
and validates the response body. Valid routes can become Connected after this
single strong proof. DNS-based exact-204 endpoints remain bounded fallbacks.
Known fatal REALITY, WebSocket, authentication, TLS and configuration errors can
abort the startup window early instead of waiting for every timeout.

## Supported Xray 26.7.28 input families

URI/subscription parsing covers:

- VLESS and VMess;
- Trojan;
- Shadowsocks, including supported Shadowsocks 2022 methods;
- SOCKS5 and HTTP/HTTPS upstream proxies;
- Hysteria2 aliases (`hy2`, `hysteria2`, `hysteria`);
- WireGuard URI aliases and standard wg-quick text;
- complete Xray JSON outbounds.

Transport conversion covers raw/TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP,
SplitHTTP aliases, mKCP aliases and Hysteria. TLS, REALITY and no-security modes
are preserved. Historical H2/H3/HTTP and QUIC transport links cannot honestly be advertised
as compatible with XHTTP because changing the client transport also requires a
matching server change. They are rejected with explicit migration messages.
Legacy XTLS is migrated to TLS only for Vision flow; other legacy XTLS profiles
are rejected. Modern certificate pinning (`pcs`/`pinnedPeerCertSha256`) and peer
name verification (`vcn`/`verifyPeerCertByName`) are preserved. The removed
`allowInsecure` switch is rejected unless the provider also supplies a modern
verification constraint.
This prevents creation of profiles that look imported but can never start.

## Files

The archive contains complete replacement/new files at their repository-relative
paths. It does not contain generated APKs, Gradle caches, credentials, or native
binaries.
