#!/usr/bin/env python3
from __future__ import annotations
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()

def text(path: str) -> str:
    target = ROOT / path
    return target.read_text(encoding="utf-8") if target.is_file() else ""

builder = text("app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt")
test = text("app/src/test/java/com/trivox/client/config/XrayConfigBuilderWireGuardTest.kt")
start = builder.find("val dnsRouteTag = when {")
end = builder.find("val rules = JSONArray()", start)
dns_route_block = builder[start:end] if start >= 0 and end >= 0 else ""

checks = [
    (
        "Trivox default DNS uses local secure endpoints",
        "DnsMode.TRIVOX_DEFAULT -> smartDns(localSecureDns(), settings)" in builder,
    ),
    (
        "Trivox default DNS route is direct",
        "DnsMode.TRIVOX_DEFAULT" in dns_route_block and '"direct"' in dns_route_block,
    ),
    (
        "WireGuard DNS test expects local secure DNS",
        'firstDnsServer.startsWith("https+local://")' in test,
    ),
    (
        "WireGuard DNS test verifies direct DNS engine route",
        'assertEquals("direct", dnsEngineRoute.getString("outboundTag"))' in test,
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

print("\nAll Trivox validation-fix checks passed.")
