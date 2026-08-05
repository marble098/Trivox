#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()

def read(path: str) -> str:
    target = ROOT / path
    return target.read_text(encoding="utf-8") if target.is_file() else ""

checks: list[tuple[str, bool]] = []

def check(label: str, condition: bool) -> None:
    checks.append((label, condition))
    print(("OK  " if condition else "BAD ") + label)

core_manager = read("app/src/main/java/com/trivox/client/core/CoreManager.kt")
adapter = read("app/src/main/java/com/trivox/client/core/ExecutableCoreAdapter.kt")
support = read("app/src/main/java/com/trivox/client/core/CoreSupportFiles.kt")
xray = read("app/src/main/java/com/trivox/client/config/XrayConfigBuilder.kt")
native_doc = read("app/src/main/java/com/trivox/client/config/NativeProfileDocument.kt")
workflow = read(".github/workflows/trivox-multicore-binaries.yml")
gradle = read("app/build.gradle.kts")
strings_en = read("app/src/main/res/values/strings.xml")
strings_fa = read("app/src/main/res/values-fa/strings.xml")
generator = read("tools/apply-native-subscription-smartcore-fix.py")
ui = read("app/src/main/java/com/trivox/client/ui/SubscriptionManagementActivity.kt")

raw_error = '"' + chr(10) + '" + summary.firstError'
raw_warning = '"' + chr(10) + '" + warning'
escaped_error = '"\\n" + summary.firstError'
escaped_warning = '"\\n" + warning'

bridge_segment = (
    core_manager.split("private fun isImmediateBridgeFailure", 1)[-1]
    .split("private fun isImmediateTransportFailure", 1)[0]
)

check("Native profiles force their required core before smart/manual fallback", "NativeProfileDocument.affinity(request.profile)?.let { return it }" in core_manager)
check("Smart selection respects complete native document affinity", "Smart core native affinity" in core_manager)
check("VPN self-bypass respects native affinity", "NativeProfileDocument.affinity(profile)" in core_manager and "VPN self-bypass decision" in core_manager)
check("Native outbound local listener is proven before Xray TUN bridge starts", "waitForLocalMixedProxy" in core_manager and "Native local mixed proxy is ready" in core_manager)
check("Native bridge verifier does not treat startup connection_refused as fatal", 'it == "invalid_xray_config"' in core_manager and '"connection_refused"' not in bridge_segment)
check("Core support files are installed into each native core work directory", "object CoreSupportFiles" in support and "V2RAY_LOCATION_ASSET" in support and "SING_BOX_DATA_DIR" in support)
check("Executable native adapters stage runtime data before validation/start", "CoreSupportFiles.install(context, work, home)" in adapter and "CoreSupportFiles.applyEnvironment" in adapter)
check(
    "Trivox default DNS uses local secure bootstrap instead of DNS over broken proxy",
    "DnsMode.TRIVOX_DEFAULT -> smartDns(localSecureDns(), settings)" in xray or
        ("DnsMode.TRIVOX_DEFAULT ->" in xray and "smartDns(localSecureDns(), settings)" in xray)
)
check("Native Mihomo config forces localhost mixed listener", "mixed-port: $mixedPort" in native_doc and "bind-address: '127.0.0.1'" in native_doc)
check("Native sing-box config gets a localhost mixed inbound", '"type", "mixed"' in native_doc and '"listen_port", mixedPort' in native_doc)
check("Workflow prepares core data assets", "Prepare core support data" in workflow and "prepare-core-support-files.py" in workflow)
check("Gradle keeps core data uncompressed", "noCompress" in gradle and "mmdb" in gradle and "dat" in gradle and "db" in gradle)
check("Conversion newline literals remain Kotlin-safe", escaped_error in ui and escaped_warning in ui and raw_error not in ui and raw_warning not in ui)
check("Conversion generator is double-escaped", '"\\\\n" + summary.firstError' in generator and '"\\\\n" + warning' in generator)
check("English UI wording is compacted to Sub/Config where possible", ">Sub" in strings_en or " Sub" in strings_en or "Subs" in strings_en)
check("Persian UI wording is compacted to ساب/کانفیگ where possible", "ساب" in strings_fa or "کانفیگ" in strings_fa)

failed = [label for label, ok in checks if not ok]
if failed:
    print("\nCritical ultimate-core checks failed:")
    for label in failed:
        print(" - " + label)
    raise SystemExit(1)

print("\nAll Trivox ultimate core runtime checks passed.")
