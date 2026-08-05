#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

REPO = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()

TEST_PATH = "app/src/test/java/com/trivox/client/config/XrayConfigBuilderWireGuardTest.kt"
BUILDER_PATH = "app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt"
DOCTOR_PATH = "tools/trivox-ultimate-validation-doctor.py"

def fail(message: str) -> None:
    raise SystemExit(message)

def read(path: str) -> str:
    target = REPO / path
    if not target.is_file():
        fail(f"required file missing: {path}")
    return target.read_text(encoding="utf-8")

def write(path: str, content: str) -> None:
    target = REPO / path
    old = target.read_text(encoding="utf-8") if target.exists() else None
    if old == content:
        print(f"unchanged {path}")
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    print(f"patched {path}")

def patch_wireguard_dns_test() -> None:
    s = read(TEST_PATH)

    old = "        assertTrue(dns.getJSONArray(\"servers\").getString(0).startsWith(\"https://\"))\n"
    new = """        val dnsServers = dns.getJSONArray("servers")
        val firstDnsServer = dnsServers.getString(0)
        assertTrue(firstDnsServer.startsWith("https+local://"))
        assertTrue((0 until dnsServers.length()).any {
            dnsServers.getString(it).startsWith("https+local://")
        })
"""
    if old in s:
        s = s.replace(old, new, 1)
    elif "firstDnsServer.startsWith(\"https+local://\")" not in s:
        fail("WireGuard DNS assertion changed; refusing unsafe patch")

    route_old = """        assertEquals("dns-out", firstRule.getString("outboundTag"))
    }
}"""
    route_new = """        assertEquals("dns-out", firstRule.getString("outboundTag"))

        val dnsEngineRoute = root.getJSONObject("routing")
            .getJSONArray("rules")
            .getJSONObject(1)
        assertEquals("direct", dnsEngineRoute.getString("outboundTag"))
    }
}"""
    if "val dnsEngineRoute = root.getJSONObject(\"routing\")" not in s:
        if route_old not in s:
            fail("WireGuard test routing anchor changed; refusing unsafe patch")
        s = s.replace(route_old, route_new, 1)

    write(TEST_PATH, s)

def patch_dns_routing_direct() -> None:
    s = read(BUILDER_PATH)

    old = """        val dnsRouteTag = when {
            settings.dnsMode == DnsMode.SYSTEM ||
                settings.dnsMode == DnsMode.DIRECT -> "direct\""""
    new = """        val dnsRouteTag = when {
            settings.dnsMode == DnsMode.SYSTEM ||
                settings.dnsMode == DnsMode.DIRECT ||
                settings.dnsMode == DnsMode.TRIVOX_DEFAULT -> "direct\""""
    if old in s:
        s = s.replace(old, new, 1)

    old_default = """        DnsMode.THROUGH_PROXY,
        DnsMode.TRIVOX_DEFAULT ->
            smartDns(remoteSecureDns(profile), settings)"""
    new_default = """        DnsMode.THROUGH_PROXY ->
            smartDns(remoteSecureDns(profile), settings)

        DnsMode.TRIVOX_DEFAULT -> smartDns(localSecureDns(), settings)"""
    if old_default in s:
        s = s.replace(old_default, new_default, 1)

    if "DnsMode.TRIVOX_DEFAULT -> smartDns(localSecureDns(), settings)" not in s:
        fail("TRIVOX_DEFAULT is not using local secure DNS")

    start = s.find("val dnsRouteTag = when {")
    end = s.find("val rules = JSONArray()", start)
    if start < 0 or end < 0:
        fail("DNS route block not found")
    direct_block = s[start:end]
    if "DnsMode.TRIVOX_DEFAULT" not in direct_block or "\"direct\"" not in direct_block:
        fail("TRIVOX_DEFAULT DNS route is not direct")

    write(BUILDER_PATH, s)

def install_doctor() -> None:
    path = REPO / DOCTOR_PATH
    content = """#!/usr/bin/env python3
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
    print("\\nCritical validation-fix checks failed:")
    for label in failed:
        print(" - " + label)
    raise SystemExit(1)

print("\\nAll Trivox validation-fix checks passed.")
"""
    old = path.read_text(encoding="utf-8") if path.exists() else None
    if old == content:
        print(f"unchanged {DOCTOR_PATH}")
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        path.chmod(0o755)
        print(f"patched {DOCTOR_PATH}")

patch_dns_routing_direct()
patch_wireguard_dns_test()
install_doctor()
print("Trivox ultimate validation fix applied")
