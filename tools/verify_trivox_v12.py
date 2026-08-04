#!/usr/bin/env python3
from pathlib import Path
import json
import sys
import xml.etree.ElementTree as ET

root = Path.cwd()
checks = {
    "real delay model": ("app/src/main/java/com/trivox/client/data/Models.kt", "enum class RealDelayProfile"),
    "real delay runtime": ("app/src/main/java/com/trivox/client/network/BatchRealDelayRunner.kt", "RealDelayPolicy.from(settings)"),
    "proxy DNS routing": ("app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt", "val dnsRouteTag = when"),
    "batch DNS direct route": ("app/src/main/java/com/trivox/client/network/BatchRealDelayRunner.kt", "batch-direct"),
    "OpenSSH parser": ("app/src/main/java/com/trivox/client/config/ConfigParser.kt", "OpenSshProfileCodec.parse(raw)"),
    "OpenSSH secure import": ("app/src/main/java/com/trivox/client/ui/MainActivity.kt", "OpenSshProfileCodec.secureForStorage"),
    "OpenSSH proxy service": ("app/src/main/java/com/trivox/client/service/ConnectionService.kt", "OpenSshTunnelBridge(this).start(profile)"),
    "OpenSSH VPN service": ("app/src/main/java/com/trivox/client/service/TrivoxVpnService.kt", "OpenSshTunnelBridge(this).start(profile)"),
    "OpenSSH VPN reconnect": ("app/src/main/java/com/trivox/client/service/TrivoxVpnService.kt", "var reconnectRequest = request"),
}
failed = False
for name, (path, marker) in checks.items():
    file = root / path
    ok = file.is_file() and marker in file.read_text(encoding="utf-8")
    print(("[ok] " if ok else "[missing] ") + name)
    failed |= not ok

for path in [
    "app/src/main/res/values/strings_v12.xml",
    "app/src/main/res/values-fa/strings_v12.xml",
    "app/src/main/res/layout/activity_settings.xml",
]:
    try:
        ET.parse(root / path)
        print(f"[ok] XML {path}")
    except Exception as exc:
        print(f"[bad] XML {path}: {exc}")
        failed = True

manifest = root / "app/src/main/assets/openssh/manifest.json"
if manifest.is_file():
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if data.get("version") == "BUILD_REQUIRED":
        print("[warning] OpenSSH ABI assets still need the GitHub Actions workflow.")
    else:
        print(f"[ok] OpenSSH assets version {data.get('version')}")
else:
    print("[warning] OpenSSH manifest not found")

sys.exit(1 if failed else 0)
