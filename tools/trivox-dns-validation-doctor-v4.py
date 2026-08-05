#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()

def read(path: str) -> str:
    target = root / path
    return target.read_text(encoding="utf-8") if target.is_file() else ""

builder = read("app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt")
v8 = read("app/src/test/java/com/trivox/client/config/XrayConfigBuilderV8ReliabilityTest.kt")
wg = read("app/src/test/java/com/trivox/client/config/XrayConfigBuilderWireGuardTest.kt")

checks = [
    (
        "TRIVOX_DEFAULT runtime DNS uses local secure bootstrap",
        "DnsMode.TRIVOX_DEFAULT -> smartDns(localSecureDns(), settings)" in builder,
    ),
    (
        "TRIVOX_DEFAULT built-in DNS route is direct",
        'DnsMode.TRIVOX_DEFAULT -> "direct"' in builder or ('settings.dnsMode == DnsMode.TRIVOX_DEFAULT' in builder and '"direct"' in builder),
    ),
    (
        "V8 reliability test expects local DNS bootstrap",
        'startsWith("https+local://")' in v8,
    ),
    (
        "V8 reliability test expects direct DNS route",
        'it.optString("outboundTag") == "direct"' in v8 and 'it.optString("outboundTag") == "proxy"' not in v8,
    ),
    (
        "WireGuard DNS test expects local DNS bootstrap",
        'startsWith("https+local://")' in wg and 'startsWith("https://")' not in wg,
    ),
]

failed = []
for label, ok in checks:
    print(("OK  " if ok else "BAD ") + label)
    if not ok:
        failed.append(label)

if failed:
    print("\nCritical validation-fix checks failed:")
    for label in failed:
        print(" - " + label)
    raise SystemExit(1)

print("\nAll Trivox DNS validation-fix checks passed.")
