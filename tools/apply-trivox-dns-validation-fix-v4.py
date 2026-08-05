#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()

v8 = repo / "app/src/test/java/com/trivox/client/config/XrayConfigBuilderV8ReliabilityTest.kt"
wg = repo / "app/src/test/java/com/trivox/client/config/XrayConfigBuilderWireGuardTest.kt"
builder = repo / "app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt"

def require(path: Path) -> None:
    if not path.is_file():
        raise SystemExit(f"required file missing: {path}")

def write_if_changed(path: Path, text: str) -> None:
    old = path.read_text(encoding="utf-8")
    if old == text:
        print(f"unchanged {path.relative_to(repo)}")
        return
    path.write_text(text, encoding="utf-8")
    print(f"patched {path.relative_to(repo)}")

for path in (v8, wg, builder):
    require(path)

s = builder.read_text(encoding="utf-8")
old_dns_mode = '''        DnsMode.THROUGH_PROXY,
        DnsMode.TRIVOX_DEFAULT ->
            smartDns(remoteSecureDns(profile), settings)
'''
new_dns_mode = '''        DnsMode.THROUGH_PROXY ->
            smartDns(remoteSecureDns(profile), settings)

        DnsMode.TRIVOX_DEFAULT -> smartDns(localSecureDns(), settings)
'''
if old_dns_mode in s:
    s = s.replace(old_dns_mode, new_dns_mode, 1)
if "DnsMode.TRIVOX_DEFAULT -> smartDns(localSecureDns(), settings)" not in s:
    raise SystemExit("TRIVOX_DEFAULT local DNS branch is missing")

old_route = '''        val dnsRouteTag = when {
            settings.dnsMode == DnsMode.SYSTEM ||
                settings.dnsMode == DnsMode.DIRECT -> "direct"
            mode == ConnectionMode.PROXY &&
                settings.dnsMode != DnsMode.THROUGH_PROXY &&
                settings.dnsMode != DnsMode.IMPORTED -> "direct"
            else -> "proxy"
        }
'''
new_route = '''        val dnsRouteTag = when (settings.dnsMode) {
            DnsMode.SYSTEM,
            DnsMode.DIRECT,
            DnsMode.TRIVOX_DEFAULT -> "direct"
            DnsMode.THROUGH_PROXY,
            DnsMode.IMPORTED -> "proxy"
            DnsMode.CUSTOM ->
                if (mode == ConnectionMode.PROXY) "direct" else "proxy"
        }
'''
if old_route in s:
    s = s.replace(old_route, new_route, 1)
elif "DnsMode.TRIVOX_DEFAULT -> \"direct\"" not in s:
    raise SystemExit("dns route selection block changed and does not prove TRIVOX_DEFAULT is direct")
write_if_changed(builder, s)

s = v8.read_text(encoding="utf-8")
s = s.replace(
    "fun builtInDnsTrafficIsRoutedThroughSelectedOutbound()",
    "fun trivoxDefaultDnsTrafficUsesLocalBootstrapAndDirectRoute()",
)
s = s.replace(
    'it.optString("outboundTag") == "proxy"',
    'it.optString("outboundTag") == "direct"',
)
anchor = '        assertEquals("dns-in", root.getJSONObject("dns").getString("tag"))\n'
if anchor in s and 'startsWith("https+local://")' not in s:
    s = s.replace(
        anchor,
        anchor + '        assertTrue(root.getJSONObject("dns").getJSONArray("servers").getString(0).startsWith("https+local://"))\n',
        1,
    )
write_if_changed(v8, s)

s = wg.read_text(encoding="utf-8")
s = s.replace(
    'assertTrue(dns.getJSONArray("servers").getString(0).startsWith("https://"))',
    'assertTrue(dns.getJSONArray("servers").getString(0).startsWith("https+local://"))',
)
write_if_changed(wg, s)

print("Trivox DNS unit-test alignment patch applied")
